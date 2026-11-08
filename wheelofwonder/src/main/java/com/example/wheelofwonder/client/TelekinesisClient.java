package com.example.wheelofwonder.client;

import com.example.wheelofwonder.TeleRequest;
import com.example.wheelofwonder.TeleState;
import com.example.wheelofwonder.TelekinesisOrbItem;
import com.example.wheelofwonder.TelekinesisServer;
import com.example.wheelofwonder.WheelEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Telekinesis Orb on the client. A right click on an NPC: the camera goes onto it at once (BallistaCamera, the same
 * free camera as the chains': mouse turns, wheel zooms, Ctrl back) and the server's act begins - a giant dark green
 * hand comes down out of the sky over the NPC, lifts it on glowing strings tied to its hands and feet, stretches it,
 * swings it round under itself and flings it far away (TelekinesisServer). Everyone around sees the hand.
 * The effects are drawn with plain see-through blending (not added light), so the green stays dark and clear against
 * a bright sky or snow; with a shader pack they go through the beacon-beam path, which the pack treats as pure glow.
 */
public final class TelekinesisClient {
    static final int DARK = 0x0B7A26, GREEN = 0x16A838, LIME = 0xB8FF5A, STRING = 0xD4FF86, WHITE = 0xF0FFE8;
    private static final float HAND_SCALE = 1.9F;
    private static final double HAND_UP = 2.5D;
    private static final int FADE_OUT = 14;

    /** One act of the hand: the NPC, the point it is lifted to (the hand hangs above it), the body's track. */
    private static final class Act {
        final int id;
        final Vec3 top;
        final boolean mine;
        Vec3 body, prevBody;
        int age;
        int flungAt = -1;
        Vec3 flungPos;

        Act(int id, Vec3 top, boolean mine) {
            this.id = id;
            this.top = top;
            this.mine = mine;
            this.body = this.prevBody = top.subtract(0.0D, TelekinesisServer.LIFT_H, 0.0D);
        }
    }

    private static final Map<Integer, Act> ACTS = new HashMap<>();
    private static int pendingId = -1;
    private static int ticks;

    // ---- the held body's parts as Sable drew them (RagdollRenderMixin): the strings hang on its hands and feet
    private record Part(int id, String role, Vec3 pos, long seen) {
    }

    private static final Map<Object, Part> PARTS = new HashMap<>();

    private TelekinesisClient() {
    }

    /** No manual control any more: nothing to hold. */
    public static boolean isHolding() {
        return false;
    }

    public static boolean wantsParts(int entityId) {
        Act a = ACTS.get(entityId);
        return a != null && a.flungAt < 0;
    }

    public static void recordPart(Object key, String role, Vec3 pos) {
        // with a shader pack the body is drawn a second time for the shadows, seen from the sun: those positions
        // are nowhere near the body (a string to them ran across the whole sky). Only points at the body count.
        for (Act a : ACTS.values()) {
            if (a.flungAt >= 0 || !finite(pos) || pos.distanceTo(a.body) > 3.5D) continue;
            PARTS.put(key, new Part(a.id, role, pos, System.nanoTime()));
            return;
        }
    }

    // ------------------------------------------------------------------ input

    /** Right click with the orb. @return true if the click is used up */
    public static boolean onUse() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !TelekinesisOrbItem.isOrb(p.getMainHandItem())) return false;
        if (ClientSetup.cinematic() || p.getCooldowns().isOnCooldown(p.getMainHandItem().getItem())) return true;
        Entity t = pick(mc, p);
        if (t == null) {
            p.displayClientMessage(Component.translatable("wheelofwonder.msg.no_target", (int) TelekinesisServer.GRAB_RANGE), true);
            return true;
        }
        pendingId = t.getId();
        PacketDistributor.sendToServer(new TeleRequest(TeleRequest.GRAB, t.getId(), 0, 0, 0, 0, 0, 0));
        // the camera goes onto the NPC right away
        BallistaCamera.followNow(t.getId(), t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D));
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return true;
    }

    static void toggleOrbit() {
    }

    public static boolean onScroll(double delta) {
        return false;
    }

    /** The NPC closest to the crosshair within 25 degrees (near ones preferred), with nothing solid in between. */
    private static Entity pick(Minecraft mc, LocalPlayer p) {
        double r = TelekinesisServer.GRAB_RANGE;
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1.0F);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(r), x -> x instanceof LivingEntity le && le.isAlive()
                && !(x instanceof Player) && !(x instanceof ArmorStand) && !x.isSpectator())) {
            Vec3 c = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            Vec3 d = c.subtract(eye);
            double len = d.length();
            if (len > r || len < 0.5D) continue;
            double ang = Math.toDegrees(Math.acos(Mth.clamp(look.dot(d.scale(1.0D / len)), -1.0D, 1.0D)));
            if (ang > 25.0D || CamUtil.freeLength(mc, eye, c, 0.0D) < len - 0.8D) continue;
            double score = ang + len * 0.15D;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ server events

    public static void onState(TeleState s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 pos = new Vec3(s.x(), s.y(), s.z());
        switch (s.phase()) {
            case TeleState.GRABBED -> {
                boolean mine = s.entityId() == pendingId;
                ACTS.put(s.entityId(), new Act(s.entityId(), pos, mine));
                if (mine) pendingId = -1;
            }
            case TeleState.TRACK -> {
                Act a = ACTS.get(s.entityId());
                if (a != null && a.flungAt < 0) a.body = pos;
            }
            case TeleState.FLING -> {
                Act a = ACTS.get(s.entityId());
                if (a != null) {
                    a.flungAt = a.age;
                    a.flungPos = pos;
                    burstFx(mc, pos);
                }
            }
            case TeleState.END -> {
                ACTS.remove(s.entityId());
                if (s.entityId() == pendingId) pendingId = -1;
            }
            default -> {
            }
        }
    }

    static void clear() {
        ACTS.clear();
        PARTS.clear();
        pendingId = -1;
    }

    private static void burstFx(Minecraft mc, Vec3 at) {
        RandomSource r = mc.level.getRandom();
        for (int i = 0; i < 40; i++) {
            Vec3 d = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().scale(0.3D + r.nextDouble() * 0.4D);
            mc.level.addParticle(i % 3 == 0 ? ParticleTypes.HAPPY_VILLAGER : WheelEntity.dust(i % 2 == 0 ? GREEN : DARK, 1.6F), at.x, at.y, at.z, d.x, d.y, d.z);
        }
        mc.level.addParticle(ParticleTypes.FLASH, at.x, at.y, at.z, 0.0D, 0.0D, 0.0D);
    }

    // ------------------------------------------------------------------ tick

    static void tick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!ACTS.isEmpty()) clear();
            return;
        }
        RandomSource r = mc.level.getRandom();
        Iterator<Act> it = ACTS.values().iterator();
        while (it.hasNext()) {
            Act a = it.next();
            a.age++;
            a.prevBody = a.body;
            if (a.age > TelekinesisServer.FLING_AT + 60 || (a.flungAt >= 0 && a.age - a.flungAt > FADE_OUT)) {
                it.remove();
                continue;
            }
            if (a.flungAt >= 0) continue;
            Vec3 t = a.body;
            if (r.nextFloat() < 0.8F) {
                mc.level.addParticle(WheelEntity.dust(r.nextBoolean() ? GREEN : DARK, 1.2F), t.x + r.nextGaussian() * 0.6D, t.y + r.nextGaussian() * 0.7D, t.z + r.nextGaussian() * 0.6D, 0.0D, 0.02D, 0.0D);
            }
            if (r.nextFloat() < 0.3F) mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, t.x + r.nextGaussian() * 0.5D, t.y + r.nextGaussian() * 0.6D, t.z + r.nextGaussian() * 0.5D, 0.0D, 0.0D, 0.0D);
            // green light dripping down the strings from the hand
            if (r.nextFloat() < 0.6F) {
                Vec3 q = a.top.add(r.nextGaussian() * 0.8D, HAND_UP * r.nextDouble(), r.nextGaussian() * 0.8D);
                mc.level.addParticle(WheelEntity.dust(LIME, 0.8F), q.x, q.y, q.z, 0.0D, -0.04D, 0.0D);
            }
        }
        PARTS.values().removeIf(p -> !ACTS.containsKey(p.id()));
    }

    /** Pose hook: the caster raises his hand towards the hand in the sky while it works. */
    public static void applyPose(HumanoidModel<?> m, LivingEntity e, float ageInTicks) {
        if (e != Minecraft.getInstance().player) return;
        for (Act a : ACTS.values()) {
            if (!a.mine || a.flungAt >= 0) continue;
            m.rightArm.xRot = -2.6F + 0.1F * Mth.sin(ageInTicks * 0.3F);
            m.rightArm.yRot = -0.2F;
            m.rightArm.zRot = 0.0F;
            return;
        }
    }

    // ------------------------------------------------------------------ the puppet

    /** Hands and feet of this act's body, as drawn this frame; where a limb is not known, a guess round the body. */
    private static Vec3[] limbs(Act a, Vec3 centre) {
        long now = System.nanoTime();
        Vec3 torso = null;
        List<Vec3> arms = new ArrayList<>(), legs = new ArrayList<>();
        for (Part pt : PARTS.values()) {
            if (pt.id() != a.id || now - pt.seen() > 300_000_000L) continue;
            switch (pt.role()) {
                case "TORSO" -> torso = pt.pos();
                case "ARM" -> arms.add(pt.pos());
                case "LEG" -> legs.add(pt.pos());
                default -> {
                }
            }
        }
        Vec3 c = torso != null ? torso : centre;
        Vec3[] ar = pickTwo(arms, c), lg = pickTwo(legs, c);
        return new Vec3[]{
                ar[0] != null ? ar[0] : c.add(0.6D, 0.35D, 0.0D), ar[1] != null ? ar[1] : c.add(-0.6D, 0.35D, 0.0D),
                lg[0] != null ? lg[0] : c.add(0.3D, -1.0D, 0.0D), lg[1] != null ? lg[1] : c.add(-0.3D, -1.0D, 0.0D)};
    }

    private static Vec3[] pickTwo(List<Vec3> parts, Vec3 c) {
        Vec3 first = null, second = null;
        double best = -1.0D;
        for (Vec3 v : parts) if (v.distanceTo(c) > best) { best = v.distanceTo(c); first = v; }
        best = -1.0D;
        if (first != null) {
            for (Vec3 v : parts) {
                if (v.distanceTo(first) < 0.35D) continue;
                double s = v.distanceTo(c) + 0.8D * v.distanceTo(first);
                if (s > best) { best = s; second = v; }
            }
        }
        return new Vec3[]{first, second};
    }

    /**
     * The puppeteer's hand: big, dark green, see-through, palm down, the forearm rising into the sky behind it, four
     * curled fingers working the strings and the thumb to one side. @return the tips of the four fingers
     */
    private static Vec3[] skyHand(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, Vec3 f, float time, float k, float alpha) {
        Vec3 s = f.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 wrist = c.subtract(f.scale(0.62D * k)).add(0.0D, 0.12D * k, 0.0D);
        prism(vc, pose, wrist.subtract(f.scale(1.3D * k)).add(0.0D, 1.8D * k, 0.0D), wrist, 0.3D * k, 0.22D * k, f, DARK, 0.75F * alpha);
        prism(vc, pose, wrist.subtract(f.scale(0.05D * k)), c.add(f.scale(0.48D * k)), 0.56D * k, 0.14D * k, f, DARK, 0.85F * alpha);
        Vec3[] tips = new Vec3[4];
        double[] side = {0.42D, 0.14D, -0.14D, -0.42D};
        double[] len = {0.5D, 0.56D, 0.52D, 0.42D};
        for (int i = 0; i < 4; i++) {
            double curl = 0.6D + 0.3D * Math.sin(time * 0.18D + i * 1.3D);
            Vec3 base = c.add(f.scale(0.48D * k)).add(s.scale(side[i] * k));
            double l = len[i] * k;
            Vec3 mid = base.add(f.scale(l * Math.cos(curl * 0.4D))).subtract(0.0D, l * Math.sin(curl * 0.4D), 0.0D);
            Vec3 dir2 = f.scale(Math.cos(curl * 1.6D)).subtract(0.0D, Math.sin(curl * 1.6D), 0.0D);
            Vec3 tip = mid.add(dir2.scale(l * 0.85D));
            prism(vc, pose, base, mid, 0.1D * k, 0.09D * k, f, DARK, 0.85F * alpha);
            prism(vc, pose, mid, tip, 0.085D * k, 0.08D * k, f, GREEN, 0.85F * alpha);
            // a glowing nail at the tip, where the string is tied
            prism(vc, pose, tip.subtract(dir2.scale(0.12D * k)), tip, 0.09D * k, 0.085D * k, f, LIME, 0.95F * alpha);
            tips[i] = tip;
        }
        Vec3 tb = c.add(s.scale(0.55D * k)).add(f.scale(0.1D * k));
        Vec3 tm = tb.add(s.scale(0.3D * k)).add(f.scale(0.25D * k)).subtract(0.0D, 0.12D * k, 0.0D);
        prism(vc, pose, tb, tm, 0.11D * k, 0.09D * k, f, DARK, 0.85F * alpha);
        prism(vc, pose, tm, tm.add(f.scale(0.28D * k)).subtract(0.0D, 0.18D * k, 0.0D), 0.09D * k, 0.08D * k, f, GREEN, 0.85F * alpha);
        return tips;
    }

    /** A box from a to b (half width w, half thickness h), every face shown from both sides. */
    private static void prism(VertexConsumer vc, PoseStack.Pose pose, Vec3 a, Vec3 b, double w, double h, Vec3 fwd, int rgb, float alpha) {
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1.0E-6D) return;
        Vec3 dir = d.normalize();
        Vec3 p1 = dir.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (p1.lengthSqr() < 1.0E-4D) p1 = dir.cross(fwd);
        p1 = p1.normalize().scale(w);
        Vec3 p2 = p1.cross(dir).normalize().scale(h);
        float r = ((rgb >> 16) & 255) / 255.0F, g = ((rgb >> 8) & 255) / 255.0F, bl = (rgb & 255) / 255.0F;
        // the top faces a little lighter, the bottom darker: it reads as a solid shape
        Vec3 a00 = a.subtract(p1).subtract(p2), a10 = a.add(p1).subtract(p2), a11 = a.add(p1).add(p2), a01 = a.subtract(p1).add(p2);
        Vec3 b00 = b.subtract(p1).subtract(p2), b10 = b.add(p1).subtract(p2), b11 = b.add(p1).add(p2), b01 = b.subtract(p1).add(p2);
        quad2(vc, pose, a00, a10, b10, b00, r * 0.7F, g * 0.7F, bl * 0.7F, alpha, alpha, alpha, alpha);
        quad2(vc, pose, a10, a11, b11, b10, r, g, bl, alpha, alpha, alpha, alpha);
        quad2(vc, pose, a11, a01, b01, b11, Math.min(1.0F, r * 1.3F), Math.min(1.0F, g * 1.3F), Math.min(1.0F, bl * 1.3F), alpha, alpha, alpha, alpha);
        quad2(vc, pose, a01, a00, b00, b01, r, g, bl, alpha, alpha, alpha, alpha);
        quad2(vc, pose, a00, a10, a11, a01, r, g, bl, alpha, alpha, alpha, alpha);
        quad2(vc, pose, b00, b10, b11, b01, r, g, bl, alpha, alpha, alpha, alpha);
    }

    /** A string from a fingertip down to a hand or a foot: a bright core with a dark green edge, trembling. */
    private static void string(VertexConsumer vc, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 cam, float time, int k, float alpha) {
        Vec3 d = b.subtract(a);
        if (d.length() > 12.0D) return;
        int n = 10;
        Vec3[] pts = new Vec3[n + 1];
        Vec3 side = d.cross(new Vec3(0.0D, 1.0D, 0.0D));
        side = side.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : side.normalize();
        for (int i = 0; i <= n; i++) {
            double f = i / (double) n;
            double wob = Math.sin(time * 0.35D + k * 1.7D + f * 6.0D) * 0.04D * Math.sin(Math.PI * f);
            pts[i] = a.add(d.scale(f)).add(side.scale(wob));
        }
        ribbon(vc, pose, pts, cam, 0.075F, DARK, 0.7F * alpha, false);
        ribbon(vc, pose, pts, cam, 0.028F, STRING, alpha, true);
    }

    // ------------------------------------------------------------------ rendering

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float time = ticks + partial;
        Vec3 cam = event.getCamera().getPosition();
        Quaternionf camRot = event.getCamera().rotation();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        // plain see-through blending: dark green stays visible on a bright sky (with or without a shader pack)
        RenderType rt = RenderType.beaconBeam(WheelRenderer.WHITE, true);
        VertexConsumer vc = buf.getBuffer(rt);
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = ps.last();
        List<Vec3[]> glows = new ArrayList<>();
        try {
            for (Act a : ACTS.values()) {
                float age = a.age + partial;
                boolean flung = a.flungAt >= 0;
                float out = flung ? Mth.clamp((age - a.flungAt) / FADE_OUT, 0.0F, 1.0F) : 0.0F;
                float in = Mth.clamp(age / 10.0F, 0.0F, 1.0F);
                float alpha = in * (1.0F - out);
                if (alpha <= 0.01F) continue;
                // the hand comes down out of the sky, and rises back after the throw
                double drop = (1.0D - CamUtil.ease(Math.min(1.0F, age / 14.0F))) * 7.0D + out * 6.0D;
                Vec3 c = a.top.add(0.0D, HAND_UP + drop + 0.12D * Math.sin(time * 0.12D), 0.0D);
                Vec3 f = a.top.subtract(p.getPosition(partial)).multiply(1.0D, 0.0D, 1.0D);
                f = f.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
                Vec3[] tips = skyHand(vc, pose, c, f, time, HAND_SCALE, alpha);
                glows.add(tips);
                if (flung) continue;
                Vec3 t = a.prevBody.lerp(a.body, partial);
                Vec3[] lim = limbs(a, t);
                boolean swapA = lim[0].distanceTo(tips[0]) + lim[1].distanceTo(tips[3]) > lim[0].distanceTo(tips[3]) + lim[1].distanceTo(tips[0]);
                boolean swapL = lim[2].distanceTo(tips[1]) + lim[3].distanceTo(tips[2]) > lim[2].distanceTo(tips[2]) + lim[3].distanceTo(tips[1]);
                string(vc, pose, tips[swapA ? 3 : 0], lim[0], cam, time, 0, alpha);
                string(vc, pose, tips[swapA ? 0 : 3], lim[1], cam, time, 1, alpha);
                string(vc, pose, tips[swapL ? 2 : 1], lim[2], cam, time, 2, alpha);
                string(vc, pose, tips[swapL ? 1 : 2], lim[3], cam, time, 3, alpha);
                // a dark green ring round the body and its shadow-mark on the ground
                ring(vc, pose, t, 1.1D, time, cam, GREEN, 0.6F * alpha);
                double g = groundBelow(mc, t);
                if (!Double.isNaN(g) && t.y - g < 14.0D) {
                    float pulse = 0.85F + 0.15F * Mth.sin(time * 0.3F);
                    flatRing(vc, pose, new Vec3(t.x, g + 0.06D, t.z), 1.0F * pulse, 0.18F, DARK, 0.6F * alpha);
                }
            }
            // the throw: dark and bright green rings running out
            for (Act a : ACTS.values()) {
                if (a.flungAt < 0 || a.flungPos == null) continue;
                float u = Mth.clamp((a.age + partial - a.flungAt) / FADE_OUT, 0.0F, 1.0F);
                flatRing(vc, pose, a.flungPos, 0.5F + 3.8F * CamUtil.ease(u), 0.3F * (1.0F - u) + 0.05F, DARK, 0.85F * (1.0F - u));
                flatRing(vc, pose, a.flungPos, 0.3F + 2.4F * CamUtil.ease(u), 0.15F * (1.0F - u) + 0.04F, LIME, 0.9F * (1.0F - u));
            }
        } catch (RuntimeException ignored) {
            // never take the game down over an effect
        }
        buf.endBatch(rt);
        ps.popPose();
        // little glows on the fingertips
        boolean shaders = ShaderCompat.shadersOn();
        RenderType gt = shaders ? RenderType.beaconBeam(ShaderCompat.GLOW_SOFT, true) : ShaderCompat.glow(WheelRenderer.GLOW);
        VertexConsumer glow = buf.getBuffer(gt);
        float fl = 0.85F + 0.15F * Mth.sin(time * 1.9F);
        for (Vec3[] tips : glows) for (Vec3 tip : tips) halo(ps, glow, camRot, tip.subtract(cam), 0.3F * fl, Draw.shade(0xFF000000 | LIME, 0.7F));
        buf.endBatch(gt);
    }

    private static void halo(PoseStack ps, VertexConsumer vc, Quaternionf camRot, Vec3 rel, float size, int argb) {
        ps.pushPose();
        ps.translate(rel.x, rel.y, rel.z);
        ps.mulPose(camRot);
        Draw.halo(vc, ps.last(), size, argb);
        ps.popPose();
    }

    private static double groundBelow(Minecraft mc, Vec3 at) {
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int by = Mth.floor(at.y); by >= Mth.floor(at.y) - 14; by--) {
            bp.set(Mth.floor(at.x), by, Mth.floor(at.z));
            VoxelShape s = mc.level.getBlockState(bp).getCollisionShape(mc.level, bp);
            if (!s.isEmpty()) return by + s.max(Direction.Axis.Y);
        }
        return Double.NaN;
    }

    /** A tilted ring round c, slowly turning, drawn as a camera-facing ribbon. */
    private static void ring(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, double r, float time, Vec3 cam, int rgb, float alpha) {
        int n = 36;
        Vec3[] pts = new Vec3[n + 1];
        double tilt = 0.35D, spin = time * 0.08D;
        for (int i = 0; i <= n; i++) {
            double a = i * Math.PI * 2.0D / n + spin;
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            pts[i] = c.add(x, z * Math.sin(tilt), z * Math.cos(tilt));
        }
        ribbon(vc, pose, pts, cam, 0.06F, rgb, alpha, true);
    }

    /** Camera-facing strip along the points; solid across (core) or fading to its edges (glow). */
    private static void ribbon(VertexConsumer vc, PoseStack.Pose pose, Vec3[] pts, Vec3 cam, float width, int rgb, float alpha, boolean solid) {
        float r = ((rgb >> 16) & 255) / 255.0F, g = ((rgb >> 8) & 255) / 255.0F, b = (rgb & 255) / 255.0F;
        float edge = solid ? alpha : 0.0F;
        int n = pts.length;
        Vec3[] side = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 d = pts[Math.min(n - 1, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 s = cam.subtract(pts[i]).cross(d);
            side[i] = s.lengthSqr() < 1.0E-9D ? new Vec3(0.0D, 1.0D, 0.0D) : s.normalize();
        }
        for (int i = 0; i < n - 1; i++) {
            Vec3 p0 = pts[i], p1 = pts[i + 1], e0 = side[i].scale(width), e1 = side[i + 1].scale(width);
            quad2(vc, pose, p0.add(e0), p0, p1, p1.add(e1), r, g, b, edge, alpha, alpha, edge);
            quad2(vc, pose, p0, p0.subtract(e0), p1.subtract(e1), p1, r, g, b, alpha, edge, edge, alpha);
        }
    }

    /** A flat ring on a horizontal plane: strongest along its middle line. */
    private static void flatRing(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, float rad, float band, int rgb, float alpha) {
        float r = ((rgb >> 16) & 255) / 255.0F, g = ((rgb >> 8) & 255) / 255.0F, b = (rgb & 255) / 255.0F;
        int segs = 40;
        float rin = Math.max(0.0F, rad - band), rout = rad + band;
        for (int i = 0; i < segs; i++) {
            double a0 = i * Math.PI * 2.0D / segs, a1 = (i + 1) * Math.PI * 2.0D / segs;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            Vec3 m0 = c.add(c0 * rad, 0.0D, s0 * rad), m1 = c.add(c1 * rad, 0.0D, s1 * rad);
            quad2(vc, pose, c.add(c0 * rin, 0.0D, s0 * rin), m0, m1, c.add(c1 * rin, 0.0D, s1 * rin), r, g, b, 0.0F, alpha, alpha, 0.0F);
            quad2(vc, pose, m0, c.add(c0 * rout, 0.0D, s0 * rout), c.add(c1 * rout, 0.0D, s1 * rout), m1, r, g, b, alpha, 0.0F, 0.0F, alpha);
        }
    }

    private static void quad2(VertexConsumer vc, PoseStack.Pose pose, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3,
                              float r, float g, float b, float a0, float a1, float a2, float a3) {
        // a broken point (not a number) would be drawn as a huge sliver across the sky
        if (!finite(p0) || !finite(p1) || !finite(p2) || !finite(p3)) return;
        vert(vc, pose, p0, r, g, b, a0);
        vert(vc, pose, p1, r, g, b, a1);
        vert(vc, pose, p2, r, g, b, a2);
        vert(vc, pose, p3, r, g, b, a3);
        vert(vc, pose, p3, r, g, b, a3);
        vert(vc, pose, p2, r, g, b, a2);
        vert(vc, pose, p1, r, g, b, a1);
        vert(vc, pose, p0, r, g, b, a0);
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z) && Math.abs(v.x) < 3.0E7D && Math.abs(v.z) < 3.0E7D;
    }

    private static void vert(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float r, float g, float b, float a) {
        vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, Math.max(0.0F, Math.min(1.0F, a)))
                .setUv(0.5F, 0.5F).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
