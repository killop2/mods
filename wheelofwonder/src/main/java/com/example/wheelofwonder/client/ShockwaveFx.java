package com.example.wheelofwonder.client;

import com.example.wheelofwonder.ShockState;
import com.example.wheelofwonder.ShockwaveItem;
import com.example.wheelofwonder.WheelEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Shockwave! visuals, for everybody who sees it (driven by ShockState):
 *  - the rift: a jagged fissure of light on the ground (violet haze, ice-blue body, white-hot core) with side cracks
 *    branching off, burning brightest just behind the front and fading out after a few seconds,
 *  - a curtain of energy standing up over the last blocks behind the front, and a glowing head,
 *  - crystal spikes bursting out of the ground along the rift and sinking back,
 *  - shock rings on the ground: the slam, a ring collapsing into every vortex, a wide ring when it bursts.
 * All additive (translucent under a shader pack), like the other MOBA effects of the mod.
 */
public final class ShockwaveFx {
    static final int ICE = 0x8FE9FF, CYAN = 0x3FB8FF, VIOLET = 0xA45BFF, WHITE = 0xEFFFFF;
    private static final float CRACK_LIFE = 110.0F, SHARD_LIFE = 18.0F;
    private static final double SEG = 0.4D, CURTAIN = 3.2D;

    private record Pt(Vec3 p, float t, double jit) {
    }

    private record Branch(List<Vec3> pts, float t) {
    }

    private static final class Wave {
        final List<Pt> pts = new ArrayList<>();
        final List<Branch> branches = new ArrayList<>();
        Vec3 dir = new Vec3(0.0D, 0.0D, 1.0D), perp = new Vec3(1.0D, 0.0D, 0.0D);
        boolean ended;
        float lastT;
        double sinceShard, sinceBranch;
        int count;
    }

    private record Shard(Vec3 p, float t, float h, float w, float rot, Vec3 lean) {
    }

    private record Ring(Vec3 p, float t, float r0, float r1, float life, int col) {
    }

    private record Glow(Vec3 p, float t, float life, float size, int col) {
    }

    /** A vortex: p = its centre (one block over the ground), t = start, life = ticks until it bursts. */
    private record Vortex(Vec3 p, float t, float life, float seed) {
    }

    /** A lightning arc inside a vortex: a jagged line, shown for a few ticks. */
    private record Arc(List<Vec3> pts, float t) {
    }

    private static final List<Vortex> VORTICES = new ArrayList<>();
    private static final List<Arc> ARCS = new ArrayList<>();

    private static final Map<Integer, Wave> WAVES = new HashMap<>();
    private static final List<Wave> OLD = new ArrayList<>();
    private static final List<Shard> SHARDS = new ArrayList<>();
    private static final List<Ring> RINGS = new ArrayList<>();
    private static final List<Glow> GLOWS = new ArrayList<>();
    private static float clock;
    private static boolean shaderMode;

    private ShockwaveFx() {
    }

    static void clear() {
        WAVES.clear();
        OLD.clear();
        SHARDS.clear();
        RINGS.clear();
        GLOWS.clear();
        VORTICES.clear();
        ARCS.clear();
    }

    /** How far the vortex has turned after age ticks (the same wind-up as on the server). */
    private static double spinAt(float age, float life) {
        double a = Math.max(0.0D, Math.min(age, life));
        return 0.05D * a + 0.42D * a * a * a / (3.0D * life * life);
    }

    // ------------------------------------------------------------------ events

    /** The hero starts the swing: light gathers on the spear. */
    static void windup(Player p) {
        Level level = p.level();
        Vec3 c = p.position().add(0.0D, 1.2D, 0.0D);
        RandomSource r = level.getRandom();
        for (int i = 0; i < 18; i++) {
            Vec3 o = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.6D, r.nextGaussian()).normalize().scale(1.6D);
            level.addParticle(WheelEntity.dust(i % 2 == 0 ? ICE : VIOLET, 1.1F), c.x + o.x, c.y + o.y, c.z + o.z, -o.x * 0.08D, -o.y * 0.08D, -o.z * 0.08D);
        }
    }

    public static void onState(ShockState s) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;
        float t = level.getGameTime();
        Vec3 pos = new Vec3(s.x(), s.y(), s.z());
        RandomSource r = level.getRandom();
        switch (s.phase()) {
            case ShockState.SLAM -> {
                Wave w = newWave(s);
                addPoint(w, pos, t, r);
                RINGS.add(new Ring(pos, t, 0.3F, 5.0F, 13.0F, ICE));
                RINGS.add(new Ring(pos, t, 0.2F, 3.0F, 8.0F, WHITE));
                GLOWS.add(new Glow(pos.add(0.0D, 0.5D, 0.0D), t, 7.0F, 2.6F, ICE));
                shardCircle(pos, t, 9, 0.7D, 1.6D, 1.0F, 1.9F, r);
                for (int i = 0; i < 30; i++) {
                    double a = i * Math.PI * 2.0D / 30.0D;
                    level.addParticle(i % 3 == 0 ? ParticleTypes.END_ROD : ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 0.2D, pos.z,
                            Math.cos(a) * 0.45D, 0.08D + r.nextDouble() * 0.15D, Math.sin(a) * 0.45D);
                }
                for (int i = 0; i < 10; i++) {
                    level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, pos.x + r.nextGaussian() * 0.6D, pos.y + 0.1D, pos.z + r.nextGaussian() * 0.6D, 0.0D, 0.08D, 0.0D);
                }
            }
            case ShockState.FRONT -> {
                Wave w = WAVES.get(s.casterId());
                if (w == null) w = newWave(s);
                addPoint(w, pos, t, r);
            }
            case ShockState.WAVE_END -> {
                Wave w = WAVES.remove(s.casterId());
                if (w != null) {
                    w.ended = true;
                    OLD.add(w);
                }
                RINGS.add(new Ring(pos, t, 0.2F, 2.6F, 10.0F, VIOLET));
                shardCircle(pos, t, 6, 0.4D, 1.1D, 0.8F, 1.5F, r);
                burst(level, pos.add(0.0D, 0.3D, 0.0D), ParticleTypes.ELECTRIC_SPARK, 16, 0.3D, r);
            }
            case ShockState.VORTEX -> {
                Vec3 g = pos.add(0.0D, -0.95D, 0.0D);
                RINGS.add(new Ring(g, t, 3.4F, 0.3F, 14.0F, VIOLET));
                RINGS.add(new Ring(g, t + 3.0F, 2.6F, 0.2F, 11.0F, ICE));
                GLOWS.add(new Glow(pos, t, 16.0F, 1.6F, VIOLET));
                VORTICES.add(new Vortex(pos, t, Math.max(10.0F, s.dx()), r.nextFloat() * Mth.TWO_PI));
            }
            case ShockState.BURST -> {
                VORTICES.removeIf(v -> v.p().distanceTo(pos) < 1.5D);
                Vec3 g = pos.add(0.0D, -0.95D, 0.0D);
                RINGS.add(new Ring(g, t, 0.4F, 6.5F, 15.0F, ICE));
                RINGS.add(new Ring(g, t, 0.3F, 4.2F, 10.0F, WHITE));
                RINGS.add(new Ring(g, t + 2.0F, 0.3F, 5.0F, 13.0F, VIOLET));
                GLOWS.add(new Glow(pos, t, 6.0F, 3.4F, WHITE));
                shardCircle(g, t, 12, 1.0D, 2.4D, 1.2F, 2.3F, r);
                burst(level, pos, ParticleTypes.END_ROD, 30, 0.45D, r);
                burst(level, pos, WheelEntity.dust(ICE, 1.6F), 24, 0.6D, r);
                burst(level, pos, WheelEntity.dust(VIOLET, 1.6F), 18, 0.5D, r);
            }
            default -> {
            }
        }
    }

    private static Wave newWave(ShockState s) {
        Wave old = WAVES.remove(s.casterId());
        if (old != null) {
            old.ended = true;
            OLD.add(old);
        }
        Wave w = new Wave();
        Vec3 d = new Vec3(s.dx(), 0.0D, s.dz());
        if (d.lengthSqr() > 1.0E-4D) w.dir = d.normalize();
        w.perp = new Vec3(-w.dir.z, 0.0D, w.dir.x);
        WAVES.put(s.casterId(), w);
        return w;
    }

    /** New stretch of rift up to p: points every SEG blocks (zigzag), spikes and side cracks on the way. */
    private static void addPoint(Wave w, Vec3 p, float t, RandomSource r) {
        Level level = Minecraft.getInstance().level;
        w.lastT = t;
        if (w.pts.isEmpty()) {
            w.pts.add(new Pt(p, t, 0.0D));
            return;
        }
        Vec3 last = w.pts.get(w.pts.size() - 1).p;
        double len = p.distanceTo(last);
        int n = (int) Math.floor(len / SEG);
        for (int i = 1; i <= n; i++) {
            Vec3 q = CamUtil.lerp(last, p, i * SEG / len);
            w.count++;
            double jit = ((w.count & 1) == 0 ? 1.0D : -1.0D) * (0.08D + r.nextDouble() * 0.22D);
            w.pts.add(new Pt(q, t, jit));
            w.sinceShard += SEG;
            w.sinceBranch += SEG;
            if (w.sinceShard > 0.7D) {
                w.sinceShard = 0.0D;
                int k = 1 + r.nextInt(2);
                for (int j = 0; j < k; j++) {
                    double sd = (r.nextBoolean() ? 1.0D : -1.0D) * (0.25D + r.nextDouble() * 0.7D);
                    Vec3 b = q.add(w.perp.scale(sd)).add(w.dir.scale((r.nextDouble() - 0.5D) * 0.4D));
                    Vec3 lean = w.perp.scale(Math.signum(sd) * (0.15D + r.nextDouble() * 0.25D)).add(w.dir.scale(-0.15D));
                    SHARDS.add(new Shard(b, t, 0.55F + r.nextFloat() * 0.95F, 0.12F + r.nextFloat() * 0.1F, r.nextFloat() * Mth.PI, lean));
                }
            }
            if (w.sinceBranch > 1.0D && r.nextFloat() < 0.75F) {
                w.sinceBranch = 0.0D;
                double sd = r.nextBoolean() ? 1.0D : -1.0D;
                Vec3 bd = w.perp.scale(sd).add(w.dir.scale(0.3D + r.nextDouble() * 0.5D)).normalize();
                double bl = 0.8D + r.nextDouble() * 1.2D;
                List<Vec3> bp = new ArrayList<>();
                bp.add(q);
                for (int j = 1; j <= 3; j++) {
                    Vec3 bperp = new Vec3(-bd.z, 0.0D, bd.x);
                    bp.add(q.add(bd.scale(bl * j / 3.0D)).add(bperp.scale((r.nextDouble() - 0.5D) * 0.3D)));
                }
                w.branches.add(new Branch(bp, t));
            }
            if (level != null && (w.count & 1) == 0) {
                level.addParticle(WheelEntity.dust(r.nextBoolean() ? ICE : VIOLET, 1.0F), q.x + r.nextGaussian() * 0.3D, q.y + 0.15D, q.z + r.nextGaussian() * 0.3D, 0.0D, 0.04D, 0.0D);
            }
        }
    }

    private static void shardCircle(Vec3 c, float t, int n, double r0, double r1, float h0, float h1, RandomSource r) {
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2.0D / n + r.nextDouble() * 0.4D;
            double rad = r0 + r.nextDouble() * (r1 - r0);
            Vec3 d = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            SHARDS.add(new Shard(c.add(d.scale(rad)), t + r.nextFloat() * 2.0F, h0 + r.nextFloat() * (h1 - h0),
                    0.14F + r.nextFloat() * 0.12F, r.nextFloat() * Mth.PI, d.scale(0.3D + r.nextDouble() * 0.3D)));
        }
    }

    private static void burst(Level level, Vec3 at, ParticleOptions type, int n, double speed, RandomSource r) {
        for (int i = 0; i < n; i++) {
            Vec3 v = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.6D + 0.3D, r.nextGaussian()).normalize().scale(speed * (0.4D + r.nextDouble() * 0.6D));
            level.addParticle(type, at.x, at.y, at.z, v.x, v.y, v.z);
        }
    }

    // ------------------------------------------------------------------ tick

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            clear();
            return;
        }
        float t = level.getGameTime();
        RandomSource r = level.getRandom();
        // the head of every running rift spits sparks and light
        for (Wave w : WAVES.values()) {
            if (w.pts.isEmpty() || t - w.lastT > 10.0F) continue;
            Vec3 h = w.pts.get(w.pts.size() - 1).p;
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, h.x + r.nextGaussian() * 0.3D, h.y + 0.2D, h.z + r.nextGaussian() * 0.3D,
                    r.nextGaussian() * 0.1D, 0.15D + r.nextDouble() * 0.15D, r.nextGaussian() * 0.1D);
            level.addParticle(ParticleTypes.END_ROD, h.x + r.nextGaussian() * 0.4D, h.y + 0.2D, h.z + r.nextGaussian() * 0.4D, 0.0D, 0.08D + r.nextDouble() * 0.08D, 0.0D);
            level.addParticle(WheelEntity.dust(r.nextBoolean() ? ICE : VIOLET, 1.5F), h.x + r.nextGaussian() * 0.5D, h.y + 0.3D + r.nextDouble() * 0.6D, h.z + r.nextGaussian() * 0.5D, 0.0D, 0.0D, 0.0D);
            if (r.nextFloat() < 0.5F) level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, h.x + r.nextGaussian() * 0.3D, h.y + 0.1D, h.z + r.nextGaussian() * 0.3D, 0.0D, 0.06D, 0.0D);
        }
        // a runaway rift (no WAVE_END came): let it fade
        Iterator<Map.Entry<Integer, Wave>> wi = WAVES.entrySet().iterator();
        while (wi.hasNext()) {
            Wave w = wi.next().getValue();
            if (t - w.lastT > 40.0F) {
                w.ended = true;
                OLD.add(w);
                wi.remove();
            }
        }
        // vortices: light spiralling up the funnel, arcs of lightning, ground pulses
        for (Vortex v : VORTICES) {
            float age = t - v.t();
            float u = Math.min(1.0F, age / v.life());
            double spin = spinAt(age, v.life());
            Vec3 g = v.p().add(0.0D, -1.0D, 0.0D);
            for (int k = 0; k < 2; k++) {
                double h = r.nextDouble() * 3.2D;
                double a = spin * 1.3D + r.nextDouble() * Mth.TWO_PI;
                double rad = funnelRadius(h, u);
                level.addParticle(WheelEntity.dust(r.nextBoolean() ? ICE : VIOLET, 0.9F), g.x + Math.cos(a) * rad, g.y + h, g.z + Math.sin(a) * rad, 0.0D, 0.05D, 0.0D);
            }
            if (r.nextFloat() < 0.25F + 0.5F * u) {
                // an arc from the core out to the funnel wall
                List<Vec3> pts = new ArrayList<>();
                double a = r.nextDouble() * Mth.TWO_PI, h = 0.4D + r.nextDouble() * 2.4D;
                Vec3 from = g.add(0.0D, 0.3D + r.nextDouble() * 2.0D, 0.0D);
                double rad = funnelRadius(h, u);
                Vec3 to = g.add(Math.cos(a) * rad, h, Math.sin(a) * rad);
                int segs = 5;
                for (int i = 0; i <= segs; i++) {
                    Vec3 q = CamUtil.lerp(from, to, i / (double) segs);
                    if (i > 0 && i < segs) q = q.add(r.nextGaussian() * 0.18D, r.nextGaussian() * 0.18D, r.nextGaussian() * 0.18D);
                    pts.add(q);
                }
                ARCS.add(new Arc(pts, t));
            }
            if (((int) age) % 14 == 0) RINGS.add(new Ring(g, t, 0.5F, 3.6F, 12.0F, (((int) age) / 14 & 1) == 0 ? ICE : VIOLET));
            if (age > v.life() - 12.0F && r.nextFloat() < 0.7F) {
                Vec3 o = v.p();
                Vec3 d = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.5D, r.nextGaussian()).normalize().scale(2.5D);
                level.addParticle(ParticleTypes.END_ROD, o.x + d.x, o.y + d.y, o.z + d.z, -d.x * 0.12D, -d.y * 0.12D, -d.z * 0.12D);
            }
        }
        VORTICES.removeIf(v -> t - v.t() > v.life() + 4.0F);
        ARCS.removeIf(a -> t - a.t() > 3.0F);
        OLD.removeIf(w -> t - w.lastT > CRACK_LIFE + 5.0F);
        SHARDS.removeIf(s -> t - s.t > SHARD_LIFE);
        RINGS.removeIf(g -> t - g.t > g.life);
        GLOWS.removeIf(g -> t - g.t > g.life);
        tickHeld(mc, r);
    }

    /** Frost sparks around the spear head of everybody holding it. */
    private static void tickHeld(Minecraft mc, RandomSource r) {
        if (mc.player == null || mc.level == null) return;
        for (Player pl : mc.level.players()) {
            if (pl.distanceToSqr(mc.player) > 48 * 48 || !ShockwaveItem.isShockwave(pl.getMainHandItem()) || pl.isInvisible()) continue;
            if (r.nextFloat() > 0.3F) continue;
            if (pl == mc.player && mc.options.getCameraType() == CameraType.FIRST_PERSON && !ClientSetup.cinematic()) continue;
            double yaw = Math.toRadians(pl.yBodyRot);
            Vec3 fwd = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw)), right = new Vec3(-Math.cos(yaw), 0.0D, -Math.sin(yaw));
            Vec3 p = pl.position().add(0.0D, 1.9D, 0.0D).add(right.scale(0.4D)).add(fwd.scale(0.5D));
            mc.level.addParticle(r.nextBoolean() ? ParticleTypes.ELECTRIC_SPARK : WheelEntity.dust(r.nextBoolean() ? ICE : VIOLET, 0.7F),
                    p.x + r.nextGaussian() * 0.15D, p.y + r.nextGaussian() * 0.25D, p.z + r.nextGaussian() * 0.15D, 0.0D, 0.02D, 0.0D);
        }
    }

    // ------------------------------------------------------------------ rendering

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (WAVES.isEmpty() && OLD.isEmpty() && SHARDS.isEmpty() && RINGS.isEmpty() && GLOWS.isEmpty() && VORTICES.isEmpty() && ARCS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        clock = mc.level.getGameTime() + partial;
        Vec3 cam = event.getCamera().getPosition();
        Quaternionf camRot = event.getCamera().rotation();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        shaderMode = ShaderCompat.shadersOn();
        RenderType rt = shaderMode ? RenderType.entityTranslucent(WheelRenderer.WHITE) : RenderType.lightning();
        VertexConsumer vc = buf.getBuffer(rt);
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = ps.last();
        try {
            for (Wave w : WAVES.values()) drawWave(vc, pose, w, false);
            for (Wave w : OLD) drawWave(vc, pose, w, true);
            for (Shard s : SHARDS) drawShard(vc, pose, s);
            for (Ring g : RINGS) drawRing(vc, pose, g);
            for (Vortex v : VORTICES) drawVortex(vc, pose, v);
            for (Arc a : ARCS) drawArc(vc, pose, a, cam);
        } catch (RuntimeException ignored) {
            // never take the game down over an effect
        }
        buf.endBatch(rt);
        ps.popPose();

        // glowing heads, vortex cores and flashes
        RenderType gt = ShaderCompat.glow(WheelRenderer.GLOW);
        VertexConsumer glow = buf.getBuffer(gt);
        for (Wave w : WAVES.values()) {
            if (w.pts.isEmpty() || clock - w.lastT > 10.0F) continue;
            Vec3 h = w.pts.get(w.pts.size() - 1).p.add(0.0D, 0.45D, 0.0D);
            float fl = 0.85F + 0.15F * Mth.sin(clock * 2.1F);
            halo(ps, glow, camRot, h.subtract(cam), 1.5F * fl, Draw.shade(0xFF000000 | ICE, 0.55F));
            halo(ps, glow, camRot, h.subtract(cam), 0.7F * fl, Draw.shade(0xFF000000 | WHITE, 0.75F));
        }
        for (Vortex v : VORTICES) {
            // the core: a pulsing light that swells up before the burst
            float u = Math.min(1.0F, (clock - v.t()) / v.life());
            float fl = 0.8F + 0.2F * Mth.sin(clock * 1.3F);
            float charge = u > 0.8F ? (u - 0.8F) / 0.2F : 0.0F;
            Vec3 c = v.p().add(0.0D, 0.6D, 0.0D).subtract(cam);
            halo(ps, glow, camRot, c, (1.4F + 1.8F * charge) * fl, Draw.shade(0xFF000000 | VIOLET, 0.45F + 0.3F * charge));
            halo(ps, glow, camRot, c, (0.7F + 1.2F * charge) * fl, Draw.shade(0xFF000000 | WHITE, 0.5F + 0.4F * charge));
        }
        for (Glow g : GLOWS) {
            float u = (clock - g.t) / g.life;
            if (u < 0.0F || u > 1.0F) continue;
            float a = (1.0F - u) * Math.min(1.0F, u * 6.0F + 0.3F);
            halo(ps, glow, camRot, g.p.subtract(cam), g.size * (0.7F + 0.5F * u), Draw.shade(0xFF000000 | g.col, 0.7F * a));
        }
        buf.endBatch(gt);
    }

    private static void halo(PoseStack ps, VertexConsumer vc, Quaternionf camRot, Vec3 rel, float size, int argb) {
        ps.pushPose();
        ps.translate(rel.x, rel.y, rel.z);
        ps.mulPose(camRot);
        Draw.halo(vc, ps.last(), size, argb);
        ps.popPose();
    }

    /** Brightness of a stretch of rift made at time t: a flash as it opens, then a slow fade. */
    private static float crackAlpha(float t, boolean old) {
        float age = clock - t;
        if (age < 0.0F) age = 0.0F;
        float fade = Math.max(0.0F, 1.0F - age / CRACK_LIFE);
        fade *= fade;
        float flash = age < 6.0F ? 1.0F + 0.8F * (1.0F - age / 6.0F) : 1.0F;
        float pulse = 0.85F + 0.15F * Mth.sin(clock * 0.6F + t * 0.3F);
        return Math.min(1.6F, fade * flash * pulse);
    }

    private static void drawWave(VertexConsumer vc, PoseStack.Pose pose, Wave w, boolean old) {
        int n = w.pts.size();
        if (n < 2) return;
        Vec3[] q = new Vec3[n], s = new Vec3[n];
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            Pt p = w.pts.get(i);
            q[i] = p.p.add(w.perp.scale(p.jit)).add(0.0D, 0.06D, 0.0D);
            a[i] = crackAlpha(p.t, old);
        }
        for (int i = 0; i < n; i++) {
            Vec3 d = q[Math.min(n - 1, i + 1)].subtract(q[Math.max(0, i - 1)]);
            Vec3 h = new Vec3(-d.z, 0.0D, d.x);
            s[i] = h.lengthSqr() < 1.0E-8D ? w.perp : h.normalize();
        }
        // the fissure: violet haze, ice-blue body, white-hot core; it tapers in at the front
        flatStrip(vc, pose, q, s, a, 1.05D, VIOLET, 0.32F);
        flatStrip(vc, pose, q, s, a, 0.42D, CYAN, 0.7F);
        flatStrip(vc, pose, q, s, a, 0.2D, ICE, 0.8F);
        flatStrip(vc, pose, q, s, a, 0.07D, WHITE, 1.0F);
        // side cracks
        for (Branch b : w.branches) {
            int m = b.pts().size();
            Vec3[] bq = new Vec3[m], bs = new Vec3[m];
            float[] ba = new float[m];
            float al = crackAlpha(b.t(), old);
            for (int i = 0; i < m; i++) {
                bq[i] = b.pts().get(i).add(0.0D, 0.07D, 0.0D);
                ba[i] = al * (1.0F - i / (float) m * 0.85F);
            }
            for (int i = 0; i < m; i++) {
                Vec3 d = bq[Math.min(m - 1, i + 1)].subtract(bq[Math.max(0, i - 1)]);
                Vec3 h = new Vec3(-d.z, 0.0D, d.x);
                bs[i] = h.lengthSqr() < 1.0E-8D ? w.perp : h.normalize();
            }
            flatStrip(vc, pose, bq, bs, ba, 0.3D, VIOLET, 0.3F);
            flatStrip(vc, pose, bq, bs, ba, 0.08D, ICE, 0.85F);
        }
        // the energy curtain standing over the last blocks behind a running front
        if (!old && clock - w.lastT < 4.0F) {
            Vec3 head = q[n - 1];
            for (int i = n - 2; i >= 0; i--) {
                double dist = q[i].distanceTo(head);
                if (dist > CURTAIN) break;
                double f0 = 1.0D - dist / CURTAIN, f1 = 1.0D - q[i + 1].distanceTo(head) / CURTAIN;
                float h0 = (float) (1.5D * f0), h1 = (float) (1.5D * f1);
                float a0 = (float) (0.55D * f0), a1 = (float) (0.55D * f1);
                curtain(vc, pose, q[i], q[i + 1], h0, h1, a0, a1, ICE);
                curtain(vc, pose, q[i], q[i + 1], h0 * 0.55F, h1 * 0.55F, a0, a1, WHITE);
            }
        }
    }

    /** A ribbon lying on the ground: brightest along the middle, nothing at its edges; seen from above and below. */
    private static void flatStrip(VertexConsumer vc, PoseStack.Pose pose, Vec3[] q, Vec3[] s, float[] a, double half, int rgb, float alpha) {
        float r = ((rgb >> 16) & 255) / 255.0F, g = ((rgb >> 8) & 255) / 255.0F, b = (rgb & 255) / 255.0F;
        int n = q.length;
        for (int i = 0; i < n - 1; i++) {
            float a0 = Math.min(1.0F, alpha * a[i]), a1 = Math.min(1.0F, alpha * a[i + 1]);
            if (a0 <= 0.003F && a1 <= 0.003F) continue;
            // tapered to a point at the very front
            double t0 = i == 0 ? 0.6D : 1.0D, t1 = i + 1 == n - 1 ? 0.25D : 1.0D;
            Vec3 e0 = s[i].scale(half * t0), e1 = s[i + 1].scale(half * t1);
            quad2(vc, pose, q[i], q[i].add(e0), q[i + 1].add(e1), q[i + 1], r, g, b, a0, 0.0F, 0.0F, a1);
            quad2(vc, pose, q[i].subtract(e0), q[i], q[i + 1], q[i + 1].subtract(e1), r, g, b, 0.0F, a0, a1, 0.0F);
        }
    }

    private static void curtain(VertexConsumer vc, PoseStack.Pose pose, Vec3 p0, Vec3 p1, float h0, float h1, float a0, float a1, int rgb) {
        float r = ((rgb >> 16) & 255) / 255.0F, g = ((rgb >> 8) & 255) / 255.0F, b = (rgb & 255) / 255.0F;
        quad2(vc, pose, p0, p1, p1.add(0.0D, h1, 0.0D), p0.add(0.0D, h0, 0.0D), r, g, b, a0, a1, 0.0F, 0.0F);
    }

    /** Crystal spike: two crossed tapered blades, rising out of the ground fast and sinking back slowly. */
    private static void drawShard(VertexConsumer vc, PoseStack.Pose pose, Shard s) {
        float age = clock - s.t();
        if (age < 0.0F) return;
        float grow = CamUtil.ease(age / 3.0F);
        float sink = age < 8.0F ? 0.0F : CamUtil.ease((age - 8.0F) / (SHARD_LIFE - 8.0F));
        float h = s.h() * grow * (1.0F - sink);
        if (h < 0.02F) return;
        float alpha = Math.min(1.0F, 1.3F - sink) * 0.9F;
        Vec3 base = s.p().add(0.0D, -0.05D - s.h() * 0.4D * sink, 0.0D);
        Vec3 top = base.add(0.0D, h, 0.0D).add(s.lean().scale(h));
        float r = ((ICE >> 16) & 255) / 255.0F, g = ((ICE >> 8) & 255) / 255.0F, b = (ICE & 255) / 255.0F;
        for (int k = 0; k < 2; k++) {
            double ang = s.rot() + k * Math.PI * 0.5D;
            Vec3 u = new Vec3(Math.cos(ang), 0.0D, Math.sin(ang)).scale(s.w());
            Vec3 mid = CamUtil.lerp(base, top, 0.35D);
            // lower part: wide, ice blue; upper part: narrowing to a white point
            quad2(vc, pose, base.subtract(u), base.add(u), mid.add(u.scale(1.15D)), mid.subtract(u.scale(1.15D)), r, g, b, alpha * 0.6F, alpha * 0.6F, alpha, alpha);
            quad2(vc, pose, mid.subtract(u.scale(1.15D)), mid.add(u.scale(1.15D)), top, top, r, g, b, alpha, alpha, alpha, alpha);
            Vec3 cu = u.scale(0.35D);
            quad2(vc, pose, base.subtract(cu), base.add(cu), top, top, 1.0F, 1.0F, 1.0F, alpha * 0.5F, alpha * 0.5F, alpha, alpha);
        }
    }

    /** Radius of the light funnel at height h over the ground; it tightens as the vortex winds up (u 0..1). */
    private static double funnelRadius(double h, float u) {
        return (0.9D + 0.55D * h) * (1.0D - 0.25D * u);
    }

    /**
     * The vortex: three arms of light spiralling on the ground, a funnel of whirling arcs above them (faster and
     * tighter as it winds up) and a pillar of light in the middle.
     */
    private static void drawVortex(VertexConsumer vc, PoseStack.Pose pose, Vortex v) {
        float age = clock - v.t();
        if (age < 0.0F) return;
        float u = Math.min(1.0F, age / v.life());
        float in = Math.min(1.0F, age / 8.0F);
        double spin = spinAt(age, v.life()) + v.seed();
        Vec3 g = v.p().add(0.0D, -0.93D, 0.0D);
        // spiral arms on the ground
        int pts = 25;
        for (int arm = 0; arm < 3; arm++) {
            Vec3[] q = new Vec3[pts], s = new Vec3[pts];
            float[] a = new float[pts];
            for (int j = 0; j < pts; j++) {
                double f = j / (double) (pts - 1);
                double rad = 0.25D + 3.0D * f * (1.0D - 0.2D * u);
                double ang = spin * 1.3D + arm * Mth.TWO_PI / 3.0D + f * 2.8D;
                q[j] = g.add(Math.cos(ang) * rad, 0.0D, Math.sin(ang) * rad);
                a[j] = in * (float) (0.25D + 0.75D * (1.0D - f));
            }
            for (int j = 0; j < pts; j++) {
                Vec3 d = q[Math.min(pts - 1, j + 1)].subtract(q[Math.max(0, j - 1)]);
                Vec3 h = new Vec3(-d.z, 0.0D, d.x);
                s[j] = h.lengthSqr() < 1.0E-8D ? new Vec3(1.0D, 0.0D, 0.0D) : h.normalize();
            }
            flatStrip(vc, pose, q, s, a, 0.34D, VIOLET, 0.45F);
            flatStrip(vc, pose, q, s, a, 0.11D, ICE, 0.9F);
        }
        // the funnel: rings of arcs, each a little wider and faster than the one below
        float alpha = in * (0.35F + 0.35F * u);
        for (int k = 0; k < 7; k++) {
            double h = 0.25D + k * 0.5D;
            double rad = funnelRadius(h, u);
            double rot = spin * (1.0D + 0.2D * k) + k * 0.7D;
            int col = (k & 1) == 0 ? ICE : WHITE;
            float r = ((col >> 16) & 255) / 255.0F, gg = ((col >> 8) & 255) / 255.0F, b = (col & 255) / 255.0F;
            for (int arc = 0; arc < 3; arc++) {
                double start = rot + arc * Mth.TWO_PI / 3.0D, span = Math.toRadians(85.0D);
                int segs = 8;
                for (int i = 0; i < segs; i++) {
                    double a0 = start + span * i / segs, a1 = start + span * (i + 1) / segs;
                    float e0 = (float) Math.sin(Math.PI * i / segs), e1 = (float) Math.sin(Math.PI * (i + 1) / segs);
                    Vec3 p0 = g.add(Math.cos(a0) * rad, h, Math.sin(a0) * rad), p1 = g.add(Math.cos(a1) * rad, h, Math.sin(a1) * rad);
                    quad2(vc, pose, p0, p1, p1.add(0.0D, 0.16D, 0.0D), p0.add(0.0D, 0.16D, 0.0D), r, gg, b, alpha * e0, alpha * e1, 0.0F, 0.0F);
                }
            }
        }
        // the pillar of light in the middle
        float pa = in * (0.22F + 0.3F * u) * (0.85F + 0.15F * Mth.sin(clock * 0.9F));
        float ir = ((ICE >> 16) & 255) / 255.0F, ig = ((ICE >> 8) & 255) / 255.0F, ib = (ICE & 255) / 255.0F;
        for (int k = 0; k < 2; k++) {
            double ang = spin * 0.5D + k * Math.PI * 0.5D;
            Vec3 w = new Vec3(Math.cos(ang), 0.0D, Math.sin(ang)).scale(0.32D);
            Vec3 top = g.add(0.0D, 3.8D, 0.0D);
            quad2(vc, pose, g.subtract(w), g.add(w), top.add(w), top.subtract(w), ir, ig, ib, pa, pa, 0.0F, 0.0F);
        }
    }

    /** A lightning arc: a white thread in an ice-blue glow, always facing the camera. */
    private static void drawArc(VertexConsumer vc, PoseStack.Pose pose, Arc arc, Vec3 cam) {
        float age = clock - arc.t();
        if (age < 0.0F) return;
        float a = Math.max(0.0F, 1.0F - age / 3.0F);
        List<Vec3> p = arc.pts();
        float ir = ((ICE >> 16) & 255) / 255.0F, ig = ((ICE >> 8) & 255) / 255.0F, ib = (ICE & 255) / 255.0F;
        for (int i = 0; i < p.size() - 1; i++) {
            Vec3 p0 = p.get(i), p1 = p.get(i + 1);
            Vec3 sd = cam.subtract(p0).cross(p1.subtract(p0));
            if (sd.lengthSqr() < 1.0E-8D) continue;
            sd = sd.normalize();
            Vec3 w = sd.scale(0.13D), c = sd.scale(0.035D);
            quad2(vc, pose, p0.subtract(w), p0.add(w), p1.add(w), p1.subtract(w), ir, ig, ib, 0.45F * a, 0.45F * a, 0.45F * a, 0.45F * a);
            quad2(vc, pose, p0.subtract(c), p0.add(c), p1.add(c), p1.subtract(c), 1.0F, 1.0F, 1.0F, a, a, a, a);
        }
    }

    private static void drawRing(VertexConsumer vc, PoseStack.Pose pose, Ring g) {
        float u = (clock - g.t()) / g.life();
        if (u < 0.0F || u > 1.0F) return;
        float rad = Mth.lerp(CamUtil.ease(u), g.r0(), g.r1());
        float band = 0.12F + 0.45F * (1.0F - u);
        float alpha = (1.0F - u) * 0.9F;
        int col = g.col();
        float r = ((col >> 16) & 255) / 255.0F, gg = ((col >> 8) & 255) / 255.0F, b = (col & 255) / 255.0F;
        Vec3 c = g.p().add(0.0D, 0.08D, 0.0D);
        int segs = 48;
        float rin = Math.max(0.0F, rad - band), rout = rad + band;
        for (int i = 0; i < segs; i++) {
            double a0 = i * Math.PI * 2.0D / segs, a1 = (i + 1) * Math.PI * 2.0D / segs;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            Vec3 m0 = c.add(c0 * rad, 0.0D, s0 * rad), m1 = c.add(c1 * rad, 0.0D, s1 * rad);
            quad2(vc, pose, c.add(c0 * rin, 0.0D, s0 * rin), m0, m1, c.add(c1 * rin, 0.0D, s1 * rin), r, gg, b, 0.0F, alpha, alpha, 0.0F);
            quad2(vc, pose, m0, c.add(c0 * rout, 0.0D, s0 * rout), c.add(c1 * rout, 0.0D, s1 * rout), m1, r, gg, b, alpha, 0.0F, 0.0F, alpha);
        }
    }

    /** One quad, drawn with both windings so it shows from either side. */
    private static void quad2(VertexConsumer vc, PoseStack.Pose pose, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3,
                              float r, float g, float b, float a0, float a1, float a2, float a3) {
        vert(vc, pose, p0, r, g, b, a0);
        vert(vc, pose, p1, r, g, b, a1);
        vert(vc, pose, p2, r, g, b, a2);
        vert(vc, pose, p3, r, g, b, a3);
        vert(vc, pose, p3, r, g, b, a3);
        vert(vc, pose, p2, r, g, b, a2);
        vert(vc, pose, p1, r, g, b, a1);
        vert(vc, pose, p0, r, g, b, a0);
    }

    private static void vert(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float r, float g, float b, float a) {
        VertexConsumer v = vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, Math.max(0.0F, Math.min(1.0F, a)));
        if (shaderMode) v.setUv(0.5F, 0.5F).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
