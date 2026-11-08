package com.example.wheelofwonder.client;

import com.example.wheelofwonder.ChainRequest;
import com.example.wheelofwonder.ChainSync;
import com.example.wheelofwonder.SinisterChainItem;
import com.example.wheelofwonder.WheelEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sinister Chain on the client: the clicks, and the chains themselves.
 *
 * Every chain is drawn as real links (dark iron with glowing ember ends, every other link turned 90 degrees) hanging
 * between the shackles with the sag of its fixed length, inside a pulsing hellfire glow (orange core, violet rim),
 * with a glowing shackle ring at every NPC. Flames rise from the links, embers drip, and a taut chain throws
 * sparks. Ragdolled NPCs are tracked by the position of their body the server sends.
 */
public final class ChainClient {
    private static List<List<ChainSync.Member>> chains = List.of();
    private static float length = 4.0F;
    private static final Map<Integer, Vec3> PREV = new HashMap<>(), CUR = new HashMap<>();
    private static int sinceSync;

    private ChainClient() {
    }

    // ------------------------------------------------------------------ clicks

    /** Right click with the chain. @return true if the click was used */
    public static boolean onUse(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !SinisterChainItem.isChain(mc.player.getMainHandItem())) return false;
        boolean sneak = mc.player.isShiftKeyDown();
        if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity le && !(le instanceof Player)) {
            // fastened by a leg if aimed at the lower half, else by a hand
            double rel = (ehr.getLocation().y - le.getY()) / Math.max(0.1D, le.getBbHeight());
            int part = rel < 0.45D ? 1 : 0;
            PacketDistributor.sendToServer(new ChainRequest(sneak ? ChainRequest.RELEASE : ChainRequest.ATTACH, le.getId(), part));
            return true;
        }
        if (mc.hitResult instanceof EntityHitResult) return false;
        PacketDistributor.sendToServer(new ChainRequest(sneak ? ChainRequest.RELEASE_ALL : ChainRequest.CLOSE, -1, 0));
        return true;
    }

    // ------------------------------------------------------------------ state

    public static void onSync(ChainSync s) {
        chains = s.chains();
        length = s.length();
        sinceSync = 0;
        for (List<ChainSync.Member> c : chains) for (ChainSync.Member m : c) {
            if (!m.ragdoll()) continue;
            Vec3 p = new Vec3(m.x(), m.y(), m.z());
            Vec3 old = CUR.get(m.id());
            PREV.put(m.id(), old != null && old.distanceTo(p) < 6.0D ? old : p);
            CUR.put(m.id(), p);
        }
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            chains = List.of();
            return;
        }
        if (++sinceSync > 60) chains = List.of();
        if (chains.isEmpty()) return;
        RandomSource r = mc.level.getRandom();
        for (List<Vec3[]> links : linksOf(mc, 1.0F)) {
            for (Vec3[] l : links) {
                Vec3 a = l[0], b = l[1];
                double d = a.distanceTo(b);
                boolean taut = d > length * 0.96;
                int n = Math.max(1, (int) (d * 1.2));
                for (int i = 0; i < n; i++) {
                    if (r.nextFloat() > 0.5F) continue;
                    double t = r.nextDouble();
                    Vec3 p = curve(a, b, t);
                    switch (r.nextInt(5)) {
                        case 0, 1 -> mc.level.addParticle(ParticleTypes.SMALL_FLAME, p.x, p.y, p.z, 0.0D, 0.02D, 0.0D);
                        case 2 -> mc.level.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, 0.0D, 0.015D, 0.0D);
                        case 3 -> mc.level.addParticle(WheelEntity.dust(0xFF5A14, 0.6F), p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
                        default -> {
                            if (r.nextFloat() < 0.15F) mc.level.addParticle(ParticleTypes.FALLING_LAVA, p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
                        }
                    }
                    // a taut chain glows hotter: embers fly off it
                    if (taut && r.nextFloat() < 0.25F) {
                        mc.level.addParticle(ParticleTypes.LAVA, p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
                    }
                }
            }
        }
        // the shackles smoulder
        for (List<ChainSync.Member> c : chains) for (ChainSync.Member m : c) {
            if (r.nextFloat() > 0.3F) continue;
            Vec3 p = anchor(mc, m, 1.0F);
            if (p != null) mc.level.addParticle(ParticleTypes.SMALL_FLAME, p.x + r.nextGaussian() * 0.06D, p.y, p.z + r.nextGaussian() * 0.06D, 0.0D, 0.02D, 0.0D);
        }
    }

    /** Where a member's shackle is (same rule as the server's), smoothly in between ticks. */
    static Vec3 anchor(Minecraft mc, ChainSync.Member m, float partial) {
        if (WhirlClient.whirling(m.id())) {
            // on a Whirlwind Whip ring: the collar is where the ring draws the body
            Entity w = mc.level.getEntity(m.id());
            Vec3 n = w != null ? WhirlClient.neck(w, partial) : null;
            if (n != null) return n;
        }
        if (m.ragdoll()) {
            Vec3 a = PREV.get(m.id()), b = CUR.get(m.id());
            if (a == null || b == null) return null;
            return a.lerp(b, partial);
        }
        Entity e = mc.level.getEntity(m.id());
        if (!(e instanceof LivingEntity le)) return null;
        // the lock in front of the collar round the neck (same rule as ChainServer.neck)
        return com.example.wheelofwonder.ChainServer.neck(le.getPosition(partial), Mth.rotLerp(partial, le.yBodyRotO, le.yBodyRot),
                le.getEyeHeight(), le.getBbWidth());
    }

    /** The collar: its centre (round the neck), its radius and the body's turn; null for a ragdoll's own ring. */
    private static double[] collar(Minecraft mc, ChainSync.Member m, float partial) {
        if (m.ragdoll() || WhirlClient.whirling(m.id())) return null;
        Entity e = mc.level.getEntity(m.id());
        if (!(e instanceof LivingEntity le)) return null;
        Vec3 pos = le.getPosition(partial);
        return new double[]{pos.x, pos.y + le.getEyeHeight() * 0.86D, pos.z, le.getBbWidth() * 0.3D,
                Math.toRadians(Mth.rotLerp(partial, le.yBodyRotO, le.yBodyRot))};
    }

    /** Pairs of shackle points, per chain. */
    private static List<List<Vec3[]>> linksOf(Minecraft mc, float partial) {
        List<List<Vec3[]>> out = new ArrayList<>();
        for (List<ChainSync.Member> c : chains) {
            List<Vec3[]> l = new ArrayList<>();
            Vec3 prev = null;
            boolean prevWhirl = false;
            for (ChainSync.Member m : c) {
                Vec3 p = anchor(mc, m, partial);
                boolean whirl = WhirlClient.whirling(m.id());
                // two NPCs both on a whirling ring: each hangs on its own chain from the hero's hand instead
                if (p != null && prev != null && !(whirl && prevWhirl)) l.add(new Vec3[]{prev, p});
                if (p != null) {
                    prev = p;
                    prevWhirl = whirl;
                }
            }
            out.add(l);
        }
        List<Vec3[]> hand = WhirlClient.handLinks(mc, partial);
        if (!hand.isEmpty()) out.add(hand);
        return out;
    }

    /** A point of the chain between a and b: it sags by the slack of its fixed length (a parabola). */
    static Vec3 curve(Vec3 a, Vec3 b, double t) {
        double d = a.distanceTo(b);
        double slack = Math.max(0.0D, length - d);
        double sag = Math.sqrt(slack * (d + length)) * 0.5D;   // about where a chain of that length hangs
        return a.lerp(b, t).subtract(0.0D, sag * 4.0D * t * (1.0D - t), 0.0D);
    }

    // ------------------------------------------------------------------ rendering

    private static final int IRON = 0xFF2E2329, IRON_EDGE = 0xFF4A3842, EMBER = 0xFFFF7A1E, RUNE = 0xFFC04BFF;
    private static final int FULL = 0xF000F0;

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || chains.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float time = mc.level.getGameTime() + partial;
        Vec3 cam = event.getCamera().getPosition();
        Quaternionf camRot = event.getCamera().rotation();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        List<List<Vec3[]>> all = linksOf(mc, partial);

        // the links
        RenderType solidType = RenderType.entityCutoutNoCull(WheelRenderer.WHITE);
        VertexConsumer solid = buf.getBuffer(solidType);
        List<Vec3[]> segments = new ArrayList<>();
        for (List<Vec3[]> links : all) {
            for (Vec3[] l : links) {
                Vec3 a = l[0], b = l[1];
                int steps = 16;
                Vec3[] pts = new Vec3[steps + 1];
                for (int i = 0; i <= steps; i++) pts[i] = curve(a, b, i / (double) steps);
                segments.add(pts);
                double total = 0;
                for (int i = 0; i < steps; i++) total += pts[i].distanceTo(pts[i + 1]);
                int n = Math.max(2, (int) Math.round(total / 0.15D));
                for (int k = 0; k < n; k++) {
                    double t = (k + 0.5D) / n;
                    Vec3 p = curve(a, b, t), q = curve(a, b, Math.min(1, t + 0.01D)), o = curve(a, b, Math.max(0, t - 0.01D));
                    Vec3 tan = q.subtract(o);
                    if (tan.lengthSqr() < 1.0E-9D) tan = new Vec3(0, 1, 0);
                    int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(p));
                    light = LightTexture.pack(Math.max(LightTexture.block(light), 9), LightTexture.sky(light));
                    // a slow wave of heat running along the chain
                    float heat = 0.5F + 0.5F * (float) Math.sin(time * 0.35F - k * 0.6F);
                    ps.pushPose();
                    ps.mulPose(frame(p.subtract(cam), tan.normalize(), (k & 1) * Math.PI / 2));
                    link(solid, ps.last(), light, heat);
                    ps.popPose();
                }
            }
        }
        // collars round the necks, with the lock in front where the chain hangs
        for (List<ChainSync.Member> c : chains) for (ChainSync.Member m : c) {
            Vec3 p = anchor(mc, m, partial);
            if (p == null) continue;
            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(p));
            light = LightTexture.pack(Math.max(LightTexture.block(light), 9), LightTexture.sky(light));
            double[] col = collar(mc, m, partial);
            if (col != null) {
                Vec3 centre = new Vec3(col[0], col[1], col[2]);
                ps.pushPose();
                ps.translate(centre.x - cam.x, centre.y - cam.y, centre.z - cam.z);
                ps.mulPose(com.mojang.math.Axis.YP.rotation((float) -col[4]));
                collarRing(solid, ps.last(), light, (float) col[3], time);
                ps.popPose();
            }
            // the padlock stands upright with its keyhole to the front (+Z turned with the body)
            ps.pushPose();
            ps.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
            if (col != null) ps.mulPose(com.mojang.math.Axis.YP.rotation((float) -col[4]));
            lock(solid, ps.last(), light);
            ps.popPose();
        }
        buf.endBatch(solidType);

        // hellfire glow along the chains and round the shackles
        boolean shaders = ShaderCompat.shadersOn();
        RenderType rt = shaders ? RenderType.entityTranslucent(WheelRenderer.WHITE) : RenderType.lightning();
        VertexConsumer vc = buf.getBuffer(rt);
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        float pulse = 0.75F + 0.25F * (float) Math.sin(time * 0.25F);
        for (Vec3[] pts : segments) {
            strip(vc, ps.last(), pts, cam, 0.18F, 0xC4280E, 0.4F * pulse, shaders, time);
            strip(vc, ps.last(), pts, cam, 0.09F, 0xFF7A1E, 0.8F * pulse, shaders, time);
            strip(vc, ps.last(), pts, cam, 0.03F, 0xFFE0A0, 0.9F, shaders, time);
        }
        buf.endBatch(rt);
        ps.popPose();
        RenderType gt = ShaderCompat.glow(WheelRenderer.GLOW);
        VertexConsumer glow = buf.getBuffer(gt);
        for (List<ChainSync.Member> c : chains) for (ChainSync.Member m : c) {
            Vec3 p = anchor(mc, m, partial);
            if (p == null) continue;
            ps.pushPose();
            ps.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
            ps.mulPose(camRot);
            Draw.halo(glow, ps.last(), 0.3F * pulse, Draw.shade(0xFFFF6A1A, 0.55F));
            Draw.halo(glow, ps.last(), 0.48F, Draw.shade(0xFFC4280E, 0.28F));
            ps.popPose();
        }
        buf.endBatch(gt);
    }

    /** Camera-relative frame at p with +Z along dir, turned by roll about it. */
    private static Matrix4f frame(Vec3 p, Vec3 dir, double roll) {
        Vec3 z = dir;
        Vec3 up = Math.abs(z.y) > 0.95D ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 x = up.cross(z).normalize();
        Vec3 y = z.cross(x);
        double c = Math.cos(roll), s = Math.sin(roll);
        Vec3 xr = x.scale(c).add(y.scale(s)), yr = y.scale(c).subtract(x.scale(s));
        return new Matrix4f((float) xr.x, (float) xr.y, (float) xr.z, 0, (float) yr.x, (float) yr.y, (float) yr.z, 0,
                (float) z.x, (float) z.y, (float) z.z, 0, (float) p.x, (float) p.y, (float) p.z, 1);
    }

    /** One oval link along +Z: two iron sides and two ends glowing with heat. */
    private static void link(VertexConsumer c, PoseStack.Pose p, int light, float heat) {
        float L = 0.085F, W = 0.04F, R = 0.012F;
        Draw.box(c, p, -W - R, -R, -L + R, -W + R, R, L - R, IRON, light);
        Draw.box(c, p, W - R, -R, -L + R, W + R, R, L - R, IRON_EDGE, light);
        int hot = mixColor(0xFF7A2A14, EMBER, heat);
        Draw.box(c, p, -W - R, -R, L - 2 * R, W + R, R, L, hot, heat > 0.4F ? FULL : light);
        Draw.box(c, p, -W - R, -R, -L, W + R, R, -L + 2 * R, hot, heat > 0.4F ? FULL : light);
    }

    /**
     * The collar round the neck (horizontal ring, +Z = the body's front): a band of dark iron, a thin glowing ember
     * seam along its middle (it smoulders) - all one colour each, no chequered pattern.
     */
    private static void collarRing(VertexConsumer c, PoseStack.Pose p, int light, float radius, float time) {
        int n = 20;
        float hb = 0.035F, th = 0.022F;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            ringSegment(c, p, radius, radius + th, -hb, hb, a0, a1, IRON, light);
            // the ember seam, a little proud of the band, brightening and dimming
            float heat = 0.6F + 0.4F * (float) Math.sin(time * 0.3F + i * 0.9F);
            ringSegment(c, p, radius + th, radius + th + 0.006F, -0.008F, 0.008F, a0, a1, mixColor(0xFF7A2A14, EMBER, heat), FULL);
        }
    }

    /** One piece of a flat ring between radii r0..r1 and heights y0..y1, from angle a0 to a1 (outer face lit). */
    private static void ringSegment(VertexConsumer c, PoseStack.Pose p, float r0, float r1, float y0, float y1, double a0, double a1, int argb, int light) {
        float s0 = (float) Math.sin(a0), c0 = (float) Math.cos(a0), s1 = (float) Math.sin(a1), c1 = (float) Math.cos(a1);
        int side = Draw.shade(argb, 0.85F), top = argb, bottom = Draw.shade(argb, 0.6F);
        // outer
        Draw.v(c, p, s0 * r1, y0, c0 * r1, side, 0, 1, light, s0, 0, c0); Draw.v(c, p, s1 * r1, y0, c1 * r1, side, 1, 1, light, s1, 0, c1);
        Draw.v(c, p, s1 * r1, y1, c1 * r1, side, 1, 0, light, s1, 0, c1); Draw.v(c, p, s0 * r1, y1, c0 * r1, side, 0, 0, light, s0, 0, c0);
        // inner
        Draw.v(c, p, s0 * r0, y1, c0 * r0, bottom, 0, 0, light, -s0, 0, -c0); Draw.v(c, p, s1 * r0, y1, c1 * r0, bottom, 1, 0, light, -s1, 0, -c1);
        Draw.v(c, p, s1 * r0, y0, c1 * r0, bottom, 1, 1, light, -s1, 0, -c1); Draw.v(c, p, s0 * r0, y0, c0 * r0, bottom, 0, 1, light, -s0, 0, -c0);
        // top and bottom
        Draw.v(c, p, s0 * r0, y1, c0 * r0, top, 0, 0, light, 0, 1, 0); Draw.v(c, p, s0 * r1, y1, c0 * r1, top, 0, 1, light, 0, 1, 0);
        Draw.v(c, p, s1 * r1, y1, c1 * r1, top, 1, 1, light, 0, 1, 0); Draw.v(c, p, s1 * r0, y1, c1 * r0, top, 1, 0, light, 0, 1, 0);
        Draw.v(c, p, s1 * r0, y0, c1 * r0, bottom, 1, 0, light, 0, -1, 0); Draw.v(c, p, s1 * r1, y0, c1 * r1, bottom, 1, 1, light, 0, -1, 0);
        Draw.v(c, p, s0 * r1, y0, c0 * r1, bottom, 0, 1, light, 0, -1, 0); Draw.v(c, p, s0 * r0, y0, c0 * r0, bottom, 0, 0, light, 0, -1, 0);
    }

    /** The padlock in front of the collar the chain hangs from: a dark body with a glowing keyhole, a shackle on top. */
    private static void lock(VertexConsumer c, PoseStack.Pose p, int light) {
        Draw.box(c, p, -0.045F, -0.075F, -0.02F, 0.045F, -0.015F, 0.02F, IRON_EDGE, light);
        Draw.box(c, p, -0.012F, -0.06F, 0.02F, 0.012F, -0.03F, 0.026F, EMBER, FULL);
        Draw.box(c, p, -0.035F, -0.015F, -0.008F, -0.022F, 0.03F, 0.008F, IRON, light);
        Draw.box(c, p, 0.022F, -0.015F, -0.008F, 0.035F, 0.03F, 0.008F, IRON, light);
        Draw.box(c, p, -0.035F, 0.03F, -0.008F, 0.035F, 0.042F, 0.008F, IRON, light);
    }

    private static int mixColor(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Camera-facing glowing strip along the points, bright in the middle and fading to the edges, flickering. */
    private static void strip(VertexConsumer vc, PoseStack.Pose pose, Vec3[] pts, Vec3 cam, float width, int rgb, float alpha, boolean shaders, float time) {
        float r = ((rgb >> 16) & 255) / 255F, g = ((rgb >> 8) & 255) / 255F, b = (rgb & 255) / 255F;
        int n = pts.length;
        Vec3[] side = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 d = pts[Math.min(n - 1, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 s = cam.subtract(pts[i]).cross(d);
            side[i] = s.lengthSqr() < 1.0E-9D ? new Vec3(0, 1, 0) : s.normalize();
        }
        for (int i = 0; i < n - 1; i++) {
            float f0 = 0.7F + 0.3F * (float) Math.sin(time * 0.9F + i * 1.7F), f1 = 0.7F + 0.3F * (float) Math.sin(time * 0.9F + (i + 1) * 1.7F);
            Vec3 p0 = pts[i], p1 = pts[i + 1], e0 = side[i].scale(width * f0), e1 = side[i + 1].scale(width * f1);
            float a0 = alpha * f0, a1 = alpha * f1;
            v(vc, pose, p0.add(e0), r, g, b, 0, shaders); v(vc, pose, p0, r, g, b, a0, shaders);
            v(vc, pose, p1, r, g, b, a1, shaders); v(vc, pose, p1.add(e1), r, g, b, 0, shaders);
            v(vc, pose, p0, r, g, b, a0, shaders); v(vc, pose, p0.subtract(e0), r, g, b, 0, shaders);
            v(vc, pose, p1.subtract(e1), r, g, b, 0, shaders); v(vc, pose, p1, r, g, b, a1, shaders);
        }
    }

    private static void v(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float r, float g, float b, float a, boolean shaders) {
        VertexConsumer x = vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
        if (shaders) x.setUv(0.5F, 0.5F).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(FULL).setNormal(pose, 0, 1, 0);
    }

    static boolean hasChains() {
        return !chains.isEmpty();
    }

    static Level level() {
        return Minecraft.getInstance().level;
    }
}
