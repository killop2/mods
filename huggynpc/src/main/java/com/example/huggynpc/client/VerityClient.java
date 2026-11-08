package com.example.huggynpc.client;

import com.example.huggynpc.HuggyEntity;
import com.example.huggynpc.VerityPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verity on the client:
 * - crumbled Veritys are not drawn (their ragdoll body stays invisible), their cubes fly and bounce about instead,
 *   lie on the ground a while and shrink away;
 * - NPCs wearing Verity as their head: a skinned NPC (Huggy family) leaves its head out and hands over where it was
 *   (HuggyModel -> putHead); a Custom NPC with the player model gets its head hidden here. Either way the ball is
 *   drawn after the NPC (RenderLivingEvent.Post), with Verity's own texture.
 */
public final class VerityClient {
    private static final Set<Integer> HIDDEN = new HashSet<>(), MERGED = new HashSet<>();
    private static final List<Debris> DEBRIS = new ArrayList<>();
    private static final int MAX_DEBRIS = 4000;

    private VerityClient() {
    }

    public static boolean hidden(int id) {
        return HIDDEN.contains(id);
    }

    public static boolean merged(int id) {
        return MERGED.contains(id);
    }

    // ------------------------------------------------------------------ size: big on the ground, head size on a head

    private static final float BIG = com.example.huggynpc.VerityBrain.BIG;
    private static final Map<Integer, Float> SIZE_WANT = new HashMap<>(), SIZE_NOW = new HashMap<>();
    private static final Map<Integer, Long> SIZE_AT = new HashMap<>();

    /** How big Verity id is drawn right now (1 = head size); it grows and shrinks smoothly over about 0.3 s. */
    static float scale(int id) {
        float want = SIZE_WANT.getOrDefault(id, BIG);
        float now = SIZE_NOW.getOrDefault(id, want);
        long t = net.minecraft.Util.getNanos();
        Long last = SIZE_AT.put(id, t);
        if (last != null && now != want) {
            float dt = Math.min(0.1F, (t - last) / 1.0E9F);
            float k = 1.0F - (float) Math.exp(-dt * 12.0F);
            now += (want - now) * k;
            if (Math.abs(want - now) < 0.01F) now = want;
        }
        SIZE_NOW.put(id, now);
        return now;
    }

    public static void onPayload(VerityPayload p) {
        switch (p.action()) {
            case VerityPayload.CRUMBLE -> {
                HIDDEN.add(p.entityId());
                burst(p, p.scale());
            }
            case VerityPayload.RESTORE -> HIDDEN.remove(p.entityId());
            case VerityPayload.HIDE -> HIDDEN.add(p.entityId());
            case VerityPayload.MERGED -> MERGED.add(p.entityId());
            case VerityPayload.HEAD_BURST -> {
                MERGED.remove(p.entityId());
                burst(p, p.scale());
            }
            case VerityPayload.SIZE -> SIZE_WANT.put(p.entityId(), p.scale());
            default -> {
            }
        }
    }

    static void init() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(VerityClient::render);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW, VerityClient::pre);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW, VerityClient::post);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            HIDDEN.clear();
            MERGED.clear();
            DEBRIS.clear();
            HEADS.clear();
            SIZE_WANT.clear();
            SIZE_NOW.clear();
            SIZE_AT.clear();
        });
    }

    // ------------------------------------------------------------------ the cubes

    private static final class Debris {
        double x, y, z, vx, vy, vz;
        float ax, ay, spinX, spinY, size;
        int color, age, life, light;
        boolean resting;
    }

    private static void burst(VerityPayload p, float scale) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RandomSource r = mc.level.random;
        double yaw = Math.toRadians(p.yaw());
        double cs = Math.cos(yaw), sn = Math.sin(yaw);
        Vec3 base = new Vec3(p.vx(), p.vy(), p.vz()).scale(1.0D / 20.0D);
        float size = VerityMesh.DIAMETER / 8.0F * scale;
        for (VerityMesh.Cube c : VerityMesh.debris()) {
            if (DEBRIS.size() >= MAX_DEBRIS) break;
            // the cube's place in the ball, turned the way Verity faced (+Z of the ball = its face)
            double lx = c.x() * scale, ly = c.y() * scale, lz = c.z() * scale;
            double wx = lx * cs - lz * sn, wz = lx * sn + lz * cs;
            Debris d = new Debris();
            d.x = p.x() + wx;
            d.y = p.y() + ly;
            d.z = p.z() + wz;
            Vec3 out = new Vec3(wx, ly, wz);
            out = out.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 1.0D, 0.0D) : out.normalize();
            double kick = (0.05D + r.nextDouble() * 0.12D) * Math.sqrt(scale);
            d.vx = base.x + out.x * kick + r.nextGaussian() * 0.02D;
            d.vy = base.y + out.y * kick + 0.08D + r.nextDouble() * 0.08D;
            d.vz = base.z + out.z * kick + r.nextGaussian() * 0.02D;
            d.spinX = (float) r.nextGaussian() * 0.35F;
            d.spinY = (float) r.nextGaussian() * 0.35F;
            d.size = size * (0.85F + r.nextFloat() * 0.3F);
            d.color = c.color();
            d.life = 260 + r.nextInt(120);
            DEBRIS.add(d);
        }
        mc.level.playLocalSound(p.x(), p.y(), p.z(), net.minecraft.sounds.SoundEvents.SLIME_SQUISH_SMALL, net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 1.8F, false);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || DEBRIS.isEmpty()) return;
        Iterator<Debris> it = DEBRIS.iterator();
        while (it.hasNext()) {
            Debris d = it.next();
            if (++d.age > d.life) {
                it.remove();
                continue;
            }
            if (d.age % 10 == 1) d.light = LevelRenderer.getLightColor(level, BlockPos.containing(d.x, d.y, d.z));
            if (d.resting) continue;
            d.vy -= 0.04D;
            d.vx *= 0.98D;
            d.vy *= 0.98D;
            d.vz *= 0.98D;
            double h = d.size * 0.5D;
            // down / up
            double ny = d.y + d.vy;
            double top = floor(level, d.x, ny - h, d.z);
            if (d.vy < 0.0D && !Double.isNaN(top) && ny - h < top) {
                d.y = top + h;
                d.vy = -d.vy * 0.35D;
                d.vx *= 0.65D;
                d.vz *= 0.65D;
                d.spinX *= 0.6F;
                d.spinY *= 0.6F;
                if (Math.abs(d.vy) < 0.04D && d.vx * d.vx + d.vz * d.vz < 0.0004D) {
                    d.resting = true;
                    d.vy = 0.0D;
                    // lie flat
                    d.ax = Math.round(d.ax / 90.0F) * 90.0F;
                    d.ay = Math.round(d.ay / 90.0F) * 90.0F;
                }
            } else {
                d.y = ny;
            }
            // sideways into a wall: bounce back
            if (solid(level, d.x + d.vx, d.y, d.z)) d.vx = -d.vx * 0.3D;
            else d.x += d.vx;
            if (solid(level, d.x, d.y, d.z + d.vz)) d.vz = -d.vz * 0.3D;
            else d.z += d.vz;
            d.ax += d.spinX * 57.3F * 0.25F;
            d.ay += d.spinY * 57.3F * 0.25F;
            if (d.y < level.getMinBuildHeight() - 8) d.age = d.life;
        }
    }

    /** Top of the block the point is in, or NaN if it is air. */
    private static double floor(Level level, double x, double y, double z) {
        BlockPos bp = BlockPos.containing(x, y, z);
        VoxelShape s = level.getBlockState(bp).getCollisionShape(level, bp);
        if (s.isEmpty()) return Double.NaN;
        return bp.getY() + s.max(Direction.Axis.Y);
    }

    private static boolean solid(Level level, double x, double y, double z) {
        BlockPos bp = BlockPos.containing(x, y, z);
        VoxelShape s = level.getBlockState(bp).getCollisionShape(level, bp);
        return !s.isEmpty() && y < bp.getY() + s.max(Direction.Axis.Y) - 0.01D;
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || DEBRIS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        RenderType rt = RenderType.entityCutoutNoCull(VerityMesh.TEXTURE);
        VertexConsumer vc = buf.getBuffer(rt);
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        try {
            for (Debris d : DEBRIS) {
                if (d.light == 0) d.light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(d.x, d.y, d.z));
                double px = d.x + (d.resting ? 0.0D : d.vx * partial), py = d.y + (d.resting ? 0.0D : d.vy * partial), pz = d.z + (d.resting ? 0.0D : d.vz * partial);
                if (px - cam.x > 96 || cam.x - px > 96 || pz - cam.z > 96 || cam.z - pz > 96) continue;
                float s = d.size;
                int left = d.life - d.age;
                if (left < 30) s *= Math.max(0.0F, (left - partial) / 30.0F);
                if (s <= 0.002F) continue;
                ps.pushPose();
                ps.translate(px, py, pz);
                ps.mulPose(Axis.XP.rotationDegrees(d.ax));
                ps.mulPose(Axis.YP.rotationDegrees(d.ay));
                cube(vc, ps.last(), s * 0.5F, 0xFF000000 | d.color, d.light);
                ps.popPose();
            }
        } catch (RuntimeException ignored) {
            // never take the game down over an effect
        }
        ps.popPose();
        buf.endBatch(rt);
    }

    private static void cube(VertexConsumer c, PoseStack.Pose p, float h, int argb, int light) {
        int top = argb, side = shade(argb, 0.82F), bot = shade(argb, 0.62F), front = shade(argb, 0.92F);
        float a = -h, b = h;
        quad(c, p, a, a, b, b, a, b, b, b, b, a, b, b, front, light, 0, 0, 1);
        quad(c, p, b, a, a, a, a, a, a, b, a, b, b, a, front, light, 0, 0, -1);
        quad(c, p, b, a, b, b, a, a, b, b, a, b, b, b, side, light, 1, 0, 0);
        quad(c, p, a, a, a, a, a, b, a, b, b, a, b, a, side, light, -1, 0, 0);
        quad(c, p, a, b, b, b, b, b, b, b, a, a, b, a, top, light, 0, 1, 0);
        quad(c, p, a, a, a, b, a, a, b, a, b, a, a, b, bot, light, 0, -1, 0);
    }

    private static void quad(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, int argb, int light, float nx, float ny, float nz) {
        c.addVertex(p, x0, y0, z0).setColor(argb).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
        c.addVertex(p, x1, y1, z1).setColor(argb).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
        c.addVertex(p, x2, y2, z2).setColor(argb).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
        c.addVertex(p, x3, y3, z3).setColor(argb).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
    }

    private static int shade(int argb, float f) {
        int a = argb >>> 24, r = (int) (((argb >> 16) & 255) * f), g = (int) (((argb >> 8) & 255) * f), b = (int) ((argb & 255) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ Verity as another NPC's head

    /** Where a skinned NPC's head was drawn this frame (from HuggyModel): the ball goes there. */
    private record Head(long frame, Matrix4f pose, Matrix3f normal, float size, int light) {
    }

    private static final Map<Integer, Head> HEADS = new HashMap<>();
    private static final Map<Integer, boolean[]> HIDDEN_PARTS = new HashMap<>();

    static void putHead(LivingEntity e, PoseStack.Pose at, float size, int light) {
        HEADS.put(e.getId(), new Head(RagdollFrame.currentFrame(), new Matrix4f(at.pose()), new Matrix3f(at.normal()), size, light));
    }

    private static void pre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity e = event.getEntity();
        if (!MERGED.contains(e.getId())) return;
        if (NpcModelLookup.shownEntity(e) instanceof HuggyEntity) return;   // a skinned look: HuggyModel leaves the head out
        EntityModel<?> m = event.getRenderer().getModel();
        if (m instanceof HumanoidModel<?> hm) {
            HIDDEN_PARTS.put(e.getId(), new boolean[]{hm.head.visible, hm.hat.visible});
            hm.head.visible = false;
            hm.hat.visible = false;
        }
    }

    private static void post(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity e = event.getEntity();
        boolean[] was = HIDDEN_PARTS.remove(e.getId());
        if (was != null && event.getRenderer().getModel() instanceof HumanoidModel<?> hm) {
            hm.head.visible = was[0];
            hm.hat.visible = was[1];
        }
        if (!MERGED.contains(e.getId())) return;
        float partial = event.getPartialTick();
        float time = e.tickCount + partial;
        VertexConsumer vc = event.getMultiBufferSource().getBuffer(RenderType.entityCutoutNoCull(VerityMesh.TEXTURE));
        VerityMesh.Mesh mesh = VerityMesh.animated(time, e.getId(), e.swinging || !e.onGround());
        Head h = HEADS.get(e.getId());
        if (h != null && h.frame() == RagdollFrame.currentFrame()) {
            // where the skinned head was
            PoseStack ps = new PoseStack();
            ps.last().pose().set(h.pose());
            ps.last().normal().set(h.normal());
            float k = h.size() / VerityMesh.DIAMETER;
            ps.scale(k, k, k);
            VerityMesh.emit(ps.last(), vc, mesh, h.light(), OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            return;
        }
        if (was == null) return;   // not a model we know how to fit a head on
        // the player-model look: on the neck, turned with the head
        PoseStack ps = event.getPoseStack();
        float hgt = e.getBbHeight();
        float size = hgt / 1.8F * VerityMesh.DIAMETER * 1.15F;
        float headYaw = Mth.rotLerp(partial, e.yHeadRotO, e.yHeadRot);
        ps.pushPose();
        ps.translate(0.0D, hgt * (1.5D / 1.8D) + size * 0.42D, 0.0D);
        ps.mulPose(Axis.YP.rotationDegrees(-headYaw));
        float k = size / VerityMesh.DIAMETER;
        ps.scale(k, k, k);
        VerityMesh.emit(ps.last(), vc, mesh, event.getPackedLight(), OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        ps.popPose();
    }
}
