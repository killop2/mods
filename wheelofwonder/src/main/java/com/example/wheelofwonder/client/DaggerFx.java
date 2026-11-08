package com.example.wheelofwonder.client;

import com.example.wheelofwonder.ImpalementArtsItem;
import com.example.wheelofwonder.WheelEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Impalement Arts visuals on the client: the daggers themselves (DaggerModel), their glow, hellfire and arcane
 * lightning sparks around every blade, MOBA-style glowing trails behind them while they circle and fly, and bursts
 * when they appear, are thrown and hit. Trails and glows are additive (translucent under a shader pack).
 */
public final class DaggerFx {
    static final int FIRE = 0xFF3A22, ARCANE = 0xB04BFF, HOT = 0xFFB060;
    private static final int N = DaggerController.N;

    private static final class Trail {
        final List<Vec3> pts = new ArrayList<>();
        final List<Float> times = new ArrayList<>();
        final int color;
        float life;

        Trail(int color) {
            this.color = color;
        }
    }

    private static final Trail[] TRAILS = new Trail[N];
    /** Trails of a finished throw, fading out. */
    private static final List<Trail> FADING = new ArrayList<>();
    /** Where each dagger's blade was drawn last frame (for the particles). */
    private static final Vec3[] BLADE = new Vec3[N], TIP = new Vec3[N];
    private static float clock;

    private DaggerFx() {
    }

    private static int colorOf(int i) {
        return (i & 1) == 0 ? FIRE : ARCANE;
    }

    // ------------------------------------------------------------------ events from the controller

    static void begin(Vec3 chest) {
        for (int i = 0; i < N; i++) {
            if (TRAILS[i] != null) FADING.add(TRAILS[i]);
            TRAILS[i] = new Trail(colorOf(i));
            BLADE[i] = TIP[i] = null;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        // the daggers appear in a puff of soul fire and violet sparks
        ring(level, chest, new Vec3(0.0D, 1.0D, 0.0D), ParticleTypes.SOUL_FIRE_FLAME, 24, 0.12D);
        ring(level, chest, new Vec3(0.0D, 1.0D, 0.0D), WheelEntity.dust(ARCANE, 1.2F), 20, 0.25D);
        ring(level, chest.add(0.0D, -1.1D, 0.0D), new Vec3(0.0D, 1.0D, 0.0D), ParticleTypes.FLAME, 28, 0.18D);
    }

    static void thrown(Vec3 chest, Vec3 dir) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        ring(level, chest.add(dir.scale(0.6D)), dir, ParticleTypes.ELECTRIC_SPARK, 26, 0.45D);
        ring(level, chest.add(dir.scale(0.6D)), dir, WheelEntity.dust(FIRE, 1.1F), 22, 0.35D);
        for (Trail t : TRAILS) if (t != null) t.life = 9.0F;
    }

    static void impact(Vec3 at, Vec3 dir) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        ring(level, at.subtract(dir.scale(0.4D)), dir, ParticleTypes.ELECTRIC_SPARK, 30, 0.5D);
        ring(level, at.subtract(dir.scale(0.4D)), dir, WheelEntity.dust(ARCANE, 1.4F), 26, 0.4D);
        ring(level, at.subtract(dir.scale(0.4D)), dir, ParticleTypes.FLAME, 20, 0.25D);
        // the flight trails die out now
        for (int i = 0; i < N; i++) {
            if (TRAILS[i] != null) FADING.add(TRAILS[i]);
            TRAILS[i] = null;
        }
    }

    /** The scene is over: the daggers vanish in a puff where they are. */
    static void end() {
        Level level = Minecraft.getInstance().level;
        for (int i = 0; i < N; i++) {
            if (TRAILS[i] != null) FADING.add(TRAILS[i]);
            TRAILS[i] = null;
            if (level != null && BLADE[i] != null) {
                Vec3 b = BLADE[i];
                level.addParticle(ParticleTypes.LARGE_SMOKE, b.x, b.y, b.z, 0.0D, 0.03D, 0.0D);
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, b.x, b.y, b.z, 0.0D, 0.05D, 0.0D);
            }
            BLADE[i] = TIP[i] = null;
        }
    }

    /** mode 0 = circling, 1 = flying, 2 = stuck. */
    static void tickDaggers(int mode) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        RandomSource r = level.getRandom();
        for (int i = 0; i < N; i++) {
            Vec3 b = BLADE[i], tip = TIP[i];
            if (b == null) continue;
            float chance = mode == 1 ? 0.9F : mode == 0 ? 0.55F : 0.25F;
            if (r.nextFloat() < chance) sparkle(level, b, tip, colorOf(i), r);
        }
    }

    private static void sparkle(Level level, Vec3 b, Vec3 tip, int col, RandomSource r) {
        Vec3 p = tip != null && r.nextBoolean() ? tip : b;
        switch (r.nextInt(4)) {
            case 0 -> level.addParticle(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, r.nextGaussian() * 0.05D, r.nextGaussian() * 0.05D, r.nextGaussian() * 0.05D);
            case 1 -> level.addParticle(col == FIRE ? ParticleTypes.SMALL_FLAME : ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 0.0D, 0.015D, 0.0D);
            case 2 -> level.addParticle(WheelEntity.dust(col, 0.55F), p.x + r.nextGaussian() * 0.05D, p.y + r.nextGaussian() * 0.05D, p.z + r.nextGaussian() * 0.05D, 0.0D, 0.0D, 0.0D);
            default -> level.addParticle(WheelEntity.dust(HOT, 0.4F), p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
        }
    }

    private static void ring(Level level, Vec3 at, Vec3 normal, ParticleOptions type, int n, double speed) {
        Vec3 nrm = normal.normalize();
        Vec3 u = Math.abs(nrm.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : nrm.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 v = nrm.cross(u).normalize();
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2.0D / n;
            Vec3 d = u.scale(Math.cos(a)).add(v.scale(Math.sin(a))).scale(speed);
            level.addParticle(type, at.x, at.y, at.z, d.x, d.y, d.z);
        }
    }

    /** Embers around the daggers in the hand of every player who holds them. */
    static void tickHeld() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        RandomSource r = mc.level.getRandom();
        for (Player pl : mc.level.players()) {
            if (pl.distanceToSqr(mc.player) > 48 * 48 || !ImpalementArtsItem.isDaggers(pl.getMainHandItem()) || pl.isInvisible()) continue;
            if (pl == mc.player && DaggerController.isActive()) continue;
            if (r.nextFloat() > 0.35F) continue;
            Vec3 p;
            if (pl == mc.player && mc.options.getCameraType() == CameraType.FIRST_PERSON && !ClientSetup.cinematic()) {
                var cam = mc.gameRenderer.getMainCamera();
                p = cam.getPosition().add(new Vec3(cam.getLookVector()).scale(0.8D)).add(new Vec3(cam.getUpVector()).scale(-0.3D))
                        .add(new Vec3(cam.getLeftVector()).scale(-0.42D));
            } else {
                double yaw = Math.toRadians(pl.yBodyRot);
                Vec3 fwd = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw)), right = new Vec3(-Math.cos(yaw), 0.0D, -Math.sin(yaw));
                p = pl.position().add(0.0D, 0.8D, 0.0D).add(right.scale(0.4D)).add(fwd.scale(0.25D));
            }
            sparkle(mc.level, p, null, r.nextBoolean() ? FIRE : ARCANE, r);
        }
    }

    // ------------------------------------------------------------------ rendering

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        clock = mc.level.getGameTime() + partial;
        Vec3 cam = event.getCamera().getPosition();
        Quaternionf camRot = event.getCamera().rotation();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        boolean live = DaggerController.isActive();

        if (live) {
            DaggerController.bindToParts(cam);
            int ph = DaggerController.phase();
            VertexConsumer solid = buf.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
            Matrix4f[] ms = new Matrix4f[N];
            for (int i = 0; i < N; i++) {
                Matrix4f m;
                try {
                    m = DaggerController.matrix(i, partial, cam);
                } catch (RuntimeException ex) {
                    m = null;   // never take the game down over one dagger
                }
                ms[i] = m;
                if (m == null) continue;
                Vector3f mid = m.transformPosition(0.0F, 0.0F, DaggerModel.BLADE * 0.45F, new Vector3f());
                Vector3f tip = m.transformPosition(0.0F, 0.0F, DaggerModel.BLADE, new Vector3f());
                BLADE[i] = cam.add(mid.x, mid.y, mid.z);
                TIP[i] = cam.add(tip.x, tip.y, tip.z);
                BlockPos bp = BlockPos.containing(BLADE[i]);
                int light = LevelRenderer.getLightColor(mc.level, bp);
                light = LightTexture.pack(Math.max(LightTexture.block(light), 9), LightTexture.sky(light));
                ps.pushPose();
                ps.mulPose(m);
                DaggerModel.draw(solid, ps, light);
                ps.popPose();
                // trail behind the guard while circling and flying
                if (TRAILS[i] != null && ph <= 1) {
                    Vector3f g = m.transformPosition(0.0F, 0.0F, 0.05F, new Vector3f());
                    addPoint(TRAILS[i], cam.add(g.x, g.y, g.z), ph == 0 ? 4.0F : 9.0F);
                }
            }
            buf.endBatch(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));

            // glow: a hot core along the blade and a coloured halo, flickering
            RenderType gt = ShaderCompat.glow(WheelRenderer.GLOW);
            VertexConsumer glow = buf.getBuffer(gt);
            for (int i = 0; i < N; i++) {
                if (ms[i] == null) continue;
                float fl = 0.85F + 0.15F * (float) Math.sin(clock * 1.7F + i * 1.3F);
                float strength = ph == 3 ? 0.55F : 1.0F;
                halo(ps, glow, camRot, BLADE[i].subtract(cam), 0.42F * fl * strength, Draw.shade(colorOf(i) | 0xFF000000, 0.55F * strength));
                halo(ps, glow, camRot, TIP[i].subtract(cam), 0.2F * fl, Draw.shade(HOT | 0xFF000000, 0.75F * strength));
            }
            buf.endBatch(gt);
        }
        drawTrails(ps, buf, cam);
    }

    private static void halo(PoseStack ps, VertexConsumer vc, Quaternionf camRot, Vec3 rel, float size, int argb) {
        ps.pushPose();
        ps.translate(rel.x, rel.y, rel.z);
        ps.mulPose(camRot);
        Draw.halo(vc, ps.last(), size, argb);
        ps.popPose();
    }

    private static void addPoint(Trail t, Vec3 p, float life) {
        t.life = life;
        if (!t.pts.isEmpty() && t.pts.get(t.pts.size() - 1).distanceTo(p) < 0.06D) return;
        t.pts.add(p);
        t.times.add(clock);
    }

    private static boolean shaderMode;

    private static void drawTrails(PoseStack ps, MultiBufferSource.BufferSource buf, Vec3 cam) {
        // drop old points; finished trails go away when empty
        for (Trail t : TRAILS) if (t != null) prune(t);
        Iterator<Trail> it = FADING.iterator();
        while (it.hasNext()) {
            Trail t = it.next();
            prune(t);
            if (t.pts.size() < 2) it.remove();
        }
        boolean any = FADING.size() > 0;
        for (Trail t : TRAILS) any |= t != null && t.pts.size() > 1;
        if (!any) return;
        shaderMode = ShaderCompat.shadersOn();
        RenderType rt = shaderMode ? RenderType.entityTranslucent(WheelRenderer.WHITE) : RenderType.lightning();
        VertexConsumer vc = buf.getBuffer(rt);
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose last = ps.last();
        for (Trail t : TRAILS) if (t != null) ribbon(vc, last, t, cam);
        for (Trail t : FADING) ribbon(vc, last, t, cam);
        buf.endBatch(rt);
        ps.popPose();
    }

    private static void prune(Trail t) {
        while (!t.times.isEmpty() && clock - t.times.get(0) > t.life) {
            t.times.remove(0);
            t.pts.remove(0);
        }
    }

    /** Coloured glowing ribbon with a white-hot core: thin and faint at the old end, wide and bright at the dagger. */
    private static void ribbon(VertexConsumer vc, PoseStack.Pose pose, Trail t, Vec3 cam) {
        int n = t.pts.size();
        if (n < 2) return;
        strip(vc, pose, t, cam, 0.16F, t.color, 0.8F);
        strip(vc, pose, t, cam, 0.05F, 0xFFF0E0, 0.9F);
    }

    private static void strip(VertexConsumer vc, PoseStack.Pose pose, Trail t, Vec3 cam, float width, int rgb, float alpha) {
        int n = t.pts.size();
        float r = ((rgb >> 16) & 255) / 255.0F, g = ((rgb >> 8) & 255) / 255.0F, b = (rgb & 255) / 255.0F;
        Vec3[] side = new Vec3[n];
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            Vec3 p0 = t.pts.get(Math.max(0, i - 1)), p1 = t.pts.get(Math.min(n - 1, i + 1));
            Vec3 d = p1.subtract(p0);
            if (d.lengthSqr() < 1.0E-8D) d = new Vec3(0.0D, 1.0D, 0.0D);
            Vec3 s = cam.subtract(t.pts.get(i)).cross(d);
            side[i] = s.lengthSqr() < 1.0E-8D ? (i > 0 ? side[i - 1] : new Vec3(0.0D, 1.0D, 0.0D)) : s.normalize();
            float age = (clock - t.times.get(i)) / t.life;
            a[i] = Math.max(0.0F, 1.0F - age);
        }
        for (int i = 0; i < n - 1; i++) {
            Vec3 p0 = t.pts.get(i), p1 = t.pts.get(i + 1);
            if (p1.subtract(p0).lengthSqr() < 1.0E-8D) continue;
            double w0 = width * (0.15D + 0.85D * a[i]), w1 = width * (0.15D + 0.85D * a[i + 1]);
            float a0 = alpha * a[i] * a[i], a1 = alpha * a[i + 1] * a[i + 1];
            Vec3 e0 = side[i].scale(w0), e1 = side[i + 1].scale(w1);
            vert(vc, pose, p0.add(e0), r, g, b, 0.0F);
            vert(vc, pose, p0, r, g, b, a0);
            vert(vc, pose, p1, r, g, b, a1);
            vert(vc, pose, p1.add(e1), r, g, b, 0.0F);
            vert(vc, pose, p0, r, g, b, a0);
            vert(vc, pose, p0.subtract(e0), r, g, b, 0.0F);
            vert(vc, pose, p1.subtract(e1), r, g, b, 0.0F);
            vert(vc, pose, p1, r, g, b, a1);
        }
    }

    private static void vert(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float r, float g, float b, float a) {
        VertexConsumer v = vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
        if (shaderMode) v.setUv(0.5F, 0.5F).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
