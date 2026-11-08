package com.example.wheelofwonder.client;

import com.example.wheelofwonder.WalrusGlovesItem;
import com.example.wheelofwonder.WheelEntity;
import com.example.wheelofwonder.WheelMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Walrus PUNCH! visuals on the client:
 * - glowing ribbon trails (MOBA-style: coloured glow with a white-hot core) behind every punch and the leap,
 *   plus a comet tail on the smashed ragdoll,
 * - lightning sparks and frost around the gloves of every player who holds them,
 * - shockwave rings. Only vanilla particles; the ribbons are drawn additively after the particles.
 */
public final class PunchFx {
    private static final ResourceLocation TRAIL = ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "textures/entity/punch_trail.png");
    private static final int ICE = 0x3FD8FF, VOLT = 0x9A6BFF, GOLD = 0xFFD45A;

    private static final class Trail {
        final List<Vec3> pts = new ArrayList<>();
        final int color;
        final float width;
        final int life;
        int age, liveTicks;
        boolean live;

        Trail(int color, float width, int life) {
            this.color = color;
            this.width = width;
            this.life = life;
        }
    }

    private static final List<Trail> TRAILS = new ArrayList<>();
    private static Trail leap, comet;

    private PunchFx() {
    }

    // ------------------------------------------------------------------ trails

    private static Trail arc(Vec3 a, Vec3 b, Vec3 bulge, int color, float width, int life) {
        Trail t = new Trail(color, width, life);
        Vec3 ctrl = a.add(b).scale(0.5D).add(bulge);
        for (int i = 0; i <= 18; i++) {
            double s = i / 18.0D;
            Vec3 p = a.scale((1 - s) * (1 - s)).add(ctrl.scale(2 * (1 - s) * s)).add(b.scale(s * s));
            t.pts.add(p);
        }
        TRAILS.add(t);
        return t;
    }

    static void uppercut(Vec3 from, Vec3 to, Vec3 dir) {
        arc(from, to.add(0.0D, 1.2D, 0.0D), dir.scale(0.6D).add(0.0D, -0.3D, 0.0D), ICE, 0.45F, 24);
        arc(from.add(0.0D, -0.1D, 0.0D), to.add(0.0D, 1.6D, 0.0D), dir.scale(0.3D), VOLT, 0.25F, 20);
        ring(to, new Vec3(0.0D, 1.0D, 0.0D), ParticleTypes.ELECTRIC_SPARK, 22, 0.45D);
    }

    static void takeoff(Vec3 feet) {
        ring(feet.add(0.0D, 0.1D, 0.0D), new Vec3(0.0D, 1.0D, 0.0D), ParticleTypes.CLOUD, 26, 0.35D);
        ring(feet.add(0.0D, 0.1D, 0.0D), new Vec3(0.0D, 1.0D, 0.0D), ParticleTypes.SNOWFLAKE, 20, 0.45D);
        leap = new Trail(VOLT, 0.5F, 28);
        leap.live = true;
        TRAILS.add(leap);
    }

    static void leapPoint(Vec3 fist) {
        if (leap != null) leap.pts.add(fist);
    }

    static void strike(Vec3 fist, Vec3 body, Vec3 dir) {
        if (leap != null) leap.live = false;
        leap = null;
        // the big hit: a wide downward slash through the body and a shockwave ring around the punch
        arc(fist.subtract(dir.scale(0.6D)).add(0.0D, 0.5D, 0.0D), body.add(dir.scale(0.8D)).add(0.0D, -0.9D, 0.0D),
                dir.scale(0.5D).add(0.0D, 0.4D, 0.0D), ICE, 0.75F, 30);
        arc(fist.add(0.0D, 0.3D, 0.0D), body.add(0.0D, -0.6D, 0.0D), dir.scale(0.2D), GOLD, 0.35F, 22);
        ring(body, dir, ParticleTypes.ELECTRIC_SPARK, 32, 0.6D);
        ring(body, dir, ParticleTypes.SNOWFLAKE, 24, 0.35D);
        ring(body, dir, ParticleTypes.END_ROD, 16, 0.25D);
    }

    static void cometStart() {
        comet = new Trail(ICE, 0.6F, 30);
        comet.live = true;
        TRAILS.add(comet);
    }

    /** Stops the comet tail: from now on it fades out and disappears. */
    static void cometEnd() {
        if (comet != null) comet.live = false;
        comet = null;
    }

    /** Ends every trail that is still being drawn (combo over / aborted): they fade out instead of staying forever. */
    static void stopAll() {
        for (Trail t : TRAILS) t.live = false;
        leap = null;
        comet = null;
    }

    static void cometPoint(Vec3 body) {
        if (comet == null) return;
        // a body lying still adds no more points
        if (!comet.pts.isEmpty() && comet.pts.get(comet.pts.size() - 1).distanceTo(body) < 0.05D) return;
        comet.pts.add(body);
        // keep only the last part of the path: a tail, not a line across the map
        while (comet.pts.size() > 14) comet.pts.remove(0);
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            RandomSource r = level.getRandom();
            level.addParticle(ParticleTypes.SNOWFLAKE, body.x + r.nextGaussian() * 0.2D, body.y, body.z + r.nextGaussian() * 0.2D, 0.0D, 0.05D, 0.0D);
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, body.x, body.y, body.z, r.nextGaussian() * 0.1D, 0.1D, r.nextGaussian() * 0.1D);
        }
    }

    /** Particles flying outwards in the plane perpendicular to {@code normal}. */
    private static void ring(Vec3 at, Vec3 normal, net.minecraft.core.particles.ParticleOptions type, int n, double speed) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        Vec3 nrm = normal.normalize();
        Vec3 u = Math.abs(nrm.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : nrm.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 v = nrm.cross(u).normalize();
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2.0D / n;
            Vec3 d = u.scale(Math.cos(a)).add(v.scale(Math.sin(a))).scale(speed);
            level.addParticle(type, at.x, at.y, at.z, d.x, d.y, d.z);
        }
    }

    // ------------------------------------------------------------------ tick

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Iterator<Trail> it = TRAILS.iterator();
        while (it.hasNext()) {
            Trail t = it.next();
            // safety net: a trail is never drawn longer than 5 s, whatever happens to the combo
            if (t.live && ++t.liveTicks > 100) t.live = false;
            if (!t.live) t.age++;
            if (t.age > t.life || mc.level == null) {
                if (t == leap) leap = null;
                if (t == comet) comet = null;
                it.remove();
            }
        }
        if (mc.level == null || mc.player == null) return;
        // sparks and frost around the gloves
        for (Player pl : mc.level.players()) {
            if (pl.distanceToSqr(mc.player) > 48 * 48 || !WalrusGlovesItem.isGloves(pl.getMainHandItem()) || pl.isInvisible()) continue;
            boolean firstPerson = pl == mc.player && mc.options.getCameraType() == CameraType.FIRST_PERSON && !PunchController.isActive()
                    && !WheelCamera.isActive();
            for (int hand = -1; hand <= 1; hand += 2) {
                if (mc.level.getRandom().nextFloat() > 0.45F) continue;
                Vec3 p = firstPerson ? firstPersonHand(mc, hand) : thirdPersonHand(pl, hand);
                sparkle(mc.level, p);
            }
        }
    }

    private static Vec3 firstPersonHand(Minecraft mc, int hand) {
        var cam = mc.gameRenderer.getMainCamera();
        Vec3 look = new Vec3(cam.getLookVector());
        Vec3 up = new Vec3(cam.getUpVector());
        Vec3 left = new Vec3(cam.getLeftVector());
        return cam.getPosition().add(look.scale(0.75D)).add(up.scale(-0.42D)).add(left.scale(-0.38D * hand));
    }

    private static Vec3 thirdPersonHand(Player pl, int hand) {
        double yaw = Math.toRadians(pl.yBodyRot);
        Vec3 fwd = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0.0D, -Math.sin(yaw));
        return pl.position().add(0.0D, pl.isCrouching() ? 0.55D : 0.72D, 0.0D).add(right.scale(0.38D * hand)).add(fwd.scale(0.12D));
    }

    private static void sparkle(Level level, Vec3 p) {
        RandomSource r = level.getRandom();
        int k = r.nextInt(3);
        if (k == 0) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, r.nextGaussian() * 0.04D, r.nextGaussian() * 0.04D, r.nextGaussian() * 0.04D);
        } else if (k == 1) {
            level.addParticle(ParticleTypes.SNOWFLAKE, p.x + r.nextGaussian() * 0.06D, p.y, p.z + r.nextGaussian() * 0.06D, 0.0D, -0.01D, 0.0D);
        } else {
            level.addParticle(WheelEntity.dust(r.nextBoolean() ? ICE : VOLT, 0.45F), p.x + r.nextGaussian() * 0.07D,
                    p.y + r.nextGaussian() * 0.07D, p.z + r.nextGaussian() * 0.07D, 0.0D, 0.0D, 0.0D);
        }
    }

    // ------------------------------------------------------------------ rendering

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || TRAILS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose last = ps.last();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        // untextured, additive (src-alpha, one), no culling: soft glowing ribbons; with a shader pack a plain
        // translucent entity type instead (shader packs turn the lightning type into black-and-white noise)
        shaderMode = ShaderCompat.shadersOn();
        RenderType rt = shaderMode ? RenderType.entityTranslucent(WheelRenderer.WHITE) : RenderType.lightning();
        VertexConsumer vc = buf.getBuffer(rt);
        for (Trail t : TRAILS) {
            if (t.pts.size() < 2) continue;
            float fade = t.live ? 1.0F : Math.max(0.0F, 1.0F - (t.age + partial) / t.life);
            ribbon(vc, last, t, cam, t.width * (0.6F + 0.4F * fade), t.color, fade * 0.85F);
            ribbon(vc, last, t, cam, t.width * 0.3F, 0xFFFFFF, fade);
        }
        buf.endBatch(rt);
        ps.popPose();
    }

    /**
     * Camera-facing strip along the trail points: thin tail, thick head; bright along the middle line and fading to
     * nothing at both edges (vertex alpha, additive), so there are no hard borders. The side vector is computed once
     * per point from the averaged direction, so neighbouring segments share their corners (no gaps or spikes).
     */
    private static void ribbon(VertexConsumer vc, PoseStack.Pose pose, Trail t, Vec3 cam, float width, int rgb, float alpha) {
        int n = t.pts.size();
        if (n < 2 || alpha <= 0.01F) return;
        float r = ((rgb >> 16) & 255) / 255.0F, g = ((rgb >> 8) & 255) / 255.0F, b = (rgb & 255) / 255.0F;
        Vec3[] side = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 a = t.pts.get(Math.max(0, i - 1)), c = t.pts.get(Math.min(n - 1, i + 1));
            Vec3 dir = c.subtract(a);
            if (dir.lengthSqr() < 1.0E-8D) dir = new Vec3(0.0D, 1.0D, 0.0D);
            Vec3 s = cam.subtract(t.pts.get(i)).cross(dir);
            side[i] = s.lengthSqr() < 1.0E-8D ? (i > 0 ? side[i - 1] : new Vec3(0.0D, 1.0D, 0.0D)) : s.normalize();
        }
        for (int i = 0; i < n - 1; i++) {
            Vec3 p0 = t.pts.get(i), p1 = t.pts.get(i + 1);
            if (p1.subtract(p0).lengthSqr() < 1.0E-8D) continue;
            float u0 = i / (float) (n - 1), u1 = (i + 1) / (float) (n - 1);
            double w0 = width * (0.12D + 0.88D * u0), w1 = width * (0.12D + 0.88D * u1);
            float a0 = alpha * (0.35F + 0.65F * u0), a1 = alpha * (0.35F + 0.65F * u1);
            Vec3 e0 = side[i].scale(w0), e1 = side[i + 1].scale(w1);
            // upper half: edge (transparent) -> centre (bright)
            vert(vc, pose, p0.add(e0), r, g, b, 0.0F);
            vert(vc, pose, p0, r, g, b, a0);
            vert(vc, pose, p1, r, g, b, a1);
            vert(vc, pose, p1.add(e1), r, g, b, 0.0F);
            // lower half: centre (bright) -> edge (transparent)
            vert(vc, pose, p0, r, g, b, a0);
            vert(vc, pose, p0.subtract(e0), r, g, b, 0.0F);
            vert(vc, pose, p1.subtract(e1), r, g, b, 0.0F);
            vert(vc, pose, p1, r, g, b, a1);
        }
    }

    private static boolean shaderMode;

    private static void vert(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float r, float g, float b, float a) {
        VertexConsumer v = vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
        if (shaderMode) v.setUv(0.5F, 0.5F).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
