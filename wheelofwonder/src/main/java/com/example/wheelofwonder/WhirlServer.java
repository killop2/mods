package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whirlwind Whip (Sinister Chain, Alt + right click): the hero yanks every NPC on his chains to him and swings them
 * round himself on the chains, faster and faster: the ring widens and rises, the bodies stream out horizontally.
 * After the whirl he lets go: the chains break and every NPC flies off along its own tangent as a ragdoll. The
 * caster's camera then follows one of them at random until Ctrl.
 *
 * The motion is a pure function of the time since the start (shared with the client, which draws the NPCs on the
 * ring itself every frame, so they never lag behind at speed).
 */
public final class WhirlServer {
    /** Ticks: pulling the NPCs onto the ring, then whirling. */
    public static final int GATHER = 14, SPIN = 100;
    private static final int MAX_AGE = 20 * 60 * 20;
    private static final double MAX_STEP = 8.0D;

    // ------------------------------------------------------------------ the motion (server and client)

    /** How far the ring has turned t ticks after the start (radians): slow while gathering, then speeding up. */
    public static double angle(double t) {
        // slower than before: from 0.08 up to 0.33 rad/tick (about one turn per second at the end)
        if (t <= GATHER) return 0.08D * t;
        double u = Math.min(t - GATHER, SPIN);
        double a = 0.08D * GATHER + 0.08D * u + 0.25D * SPIN * Math.pow(u / SPIN, 2.5D) / 2.5D;
        if (t > GATHER + SPIN) a += 0.33D * (t - GATHER - SPIN);
        return a;
    }

    /** 0..1 over the whirl itself (after the gathering). */
    public static double progress(double t) {
        return Mth.clamp((t - GATHER) / SPIN, 0.0D, 1.0D);
    }

    private static double ease(double x) {
        x = Mth.clamp(x, 0.0D, 1.0D);
        return x * x * (3.0D - 2.0D * x);
    }

    /** Radius of the ring of necks round the hero: drawn in close, then flung out wider and wider. */
    public static double radius(double t) {
        return 3.0D + 2.6D * ease(progress(t));
    }

    /**
     * Where NPC i of n has its neck t ticks after the start, relative to the hero's feet: on the ring, rising with
     * the speed, each one bobbing a little on its own.
     */
    public static Vec3 neckOffset(int i, int n, double t) {
        double a = angle(t) + i * Math.PI * 2.0D / Math.max(1, n);
        double r = radius(t);
        double h = 1.1D + 1.1D * ease(progress(t)) + 0.25D * Math.sin(t * 0.25D + i * 1.9D) * ease(progress(t) * 3.0D);
        return new Vec3(Math.cos(a) * r, h, Math.sin(a) * r);
    }

    /** During the gathering the NPCs are pulled from where they stood onto their place on the ring. */
    public static double gather(double t) {
        return ease(t / GATHER);
    }

    /** How far the bodies lean out (radians): standing at first, streaming out flat at full speed. */
    public static double lean(double t) {
        return Math.toRadians(88.0D) * ease(Math.min(1.0D, progress(t) * 1.6D));
    }

    /** Neck height of an upright NPC (where the chain is fastened). */
    public static double neckHeight(LivingEntity e) {
        return e.getEyeHeight() * 0.86D;
    }

    // ------------------------------------------------------------------ server

    private static final class Whirl {
        final ServerPlayer player;
        final List<LivingEntity> mobs;
        final List<Vec3> from = new ArrayList<>();
        final List<ChainServer.Chain> chains;
        int age;
        boolean released, clientDone;
        LivingEntity focus;
        Vec3 last;

        Whirl(ServerPlayer player, List<LivingEntity> mobs, List<ChainServer.Chain> chains) {
            this.player = player;
            this.mobs = mobs;
            this.chains = chains;
            for (LivingEntity m : mobs) this.from.add(m.position().add(0.0D, neckHeight(m), 0.0D));
        }
    }

    private static final Map<UUID, Whirl> WHIRLS = new HashMap<>();

    private WhirlServer() {
    }

    static void start(ServerPlayer p) {
        var item = p.getMainHandItem();
        if (WHIRLS.containsKey(p.getUUID()) || p.getCooldowns().isOnCooldown(item.getItem())) return;
        List<ChainServer.Chain> chains = ChainServer.chainsOf(p.getUUID());
        List<LivingEntity> mobs = new ArrayList<>();
        List<ChainServer.Chain> used = new ArrayList<>();
        for (ChainServer.Chain c : chains) {
            boolean any = false;
            for (LivingEntity e : c.row()) {
                if (e.level() != p.level() || e.distanceTo(p) > 32.0D || WheelRagdolls.isRagdolled(e) || WheelMod.held(e) || e.isPassenger()) continue;
                if (mobs.size() >= 24) break;
                mobs.add(e);
                any = true;
            }
            if (any) used.add(c);
        }
        if (mobs.isEmpty()) {
            p.displayClientMessage(Component.translatable("wheelofwonder.msg.whirl_none"), true);
            PacketDistributor.sendToPlayer(p, new WhirlState(WhirlState.END, p.getId(), -1, p.getX(), p.getY(), p.getZ(), new int[0]));
            return;
        }
        Whirl w = new Whirl(p, mobs, used);
        WHIRLS.put(p.getUUID(), w);
        p.getCooldowns().addCooldown(item.getItem(), 20 * 60);
        for (LivingEntity m : mobs) {
            WheelMod.hold(m);
            if (m instanceof Mob mob) mob.getNavigation().stop();
        }
        int[] ids = mobs.stream().mapToInt(LivingEntity::getId).toArray();
        ServerLevel level = p.serverLevel();
        PacketDistributor.sendToPlayersNear(level, null, p.getX(), p.getY(), p.getZ(), 96.0D,
                new WhirlState(WhirlState.START, p.getId(), -1, p.getX(), p.getY(), p.getZ(), ids));
        level.playSound(null, p.getX(), p.getY() + 1.0D, p.getZ(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.4F, 0.6F);
        level.playSound(null, p.getX(), p.getY() + 1.0D, p.getZ(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.PLAYERS, 0.9F, 0.8F);
        level.playSound(null, p.getX(), p.getY() + 1.0D, p.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.7F, 0.6F);
    }

    static void done(ServerPlayer p) {
        Whirl w = WHIRLS.get(p.getUUID());
        if (w != null) w.clientDone = true;
    }

    public static void onServerStopping() {
        WHIRLS.clear();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Whirl> it = WHIRLS.values().iterator();
        while (it.hasNext()) {
            Whirl w = it.next();
            ServerPlayer p = w.player;
            if (p.isRemoved() || !p.isAlive() || w.age > MAX_AGE) {
                if (!w.released) for (LivingEntity m : w.mobs) WheelMod.unhold(m);
                endCooldown(p);
                it.remove();
                continue;
            }
            w.age++;
            ServerLevel level = p.serverLevel();
            if (!w.released) {
                whirlTick(w, level);
                if (w.age >= GATHER + SPIN) release(w, level);
                continue;
            }
            // the camera's NPC: its body every tick
            LivingEntity f = w.focus;
            if (f == null || f.isRemoved() || w.clientDone) {
                if (!w.clientDone) send(p, WhirlState.END, -1, p.position());
                endCooldown(p);
                it.remove();
                continue;
            }
            Vec3 pos = WheelRagdolls.rootPosition(f);
            if (pos == null) pos = WheelRagdolls.isRagdolled(f) ? w.last : f.position().add(0.0D, f.getBbHeight() * 0.5D, 0.0D);
            if (pos.distanceTo(w.last) > MAX_STEP) pos = w.last;
            w.last = pos;
            send(p, WhirlState.TRACK, f.getId(), pos);
            if (w.age % 5 == 0) WheelMod.keepLoaded(level, pos);
        }
    }

    private static void whirlTick(Whirl w, ServerLevel level) {
        ServerPlayer p = w.player;
        int n = w.mobs.size();
        double t = w.age;
        double g = gather(t);
        RandomSource r = level.getRandom();
        for (int i = 0; i < n; i++) {
            LivingEntity m = w.mobs.get(i);
            if (m.isRemoved() || !m.isAlive()) continue;
            Vec3 neck = p.position().add(neckOffset(i, n, t));
            Vec3 at = w.from.get(i).lerp(neck, g);
            m.setPos(at.x, at.y - neckHeight(m), at.z);
            m.setDeltaMovement(Vec3.ZERO);
            m.fallDistance = 0.0F;
            // facing along the swing
            double a = angle(t) + i * Math.PI * 2.0D / n;
            float yaw = (float) Math.toDegrees(a);
            m.setYRot(yaw);
            m.setYHeadRot(yaw);
            m.yBodyRot = yaw;
            if (m instanceof Mob mob) mob.getNavigation().stop();
            // sparks and hellfire stream off the whirling bodies
            if ((w.age + i) % 3 == 0) level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 1, 0.2D, 0.2D, 0.2D, 0.01D);
            if (progress(t) > 0.4D && (w.age + i) % 2 == 0) {
                level.sendParticles(WheelEntity.dust(r.nextBoolean() ? 0xFF5A14 : 0xB04BFF, 1.2F), at.x, at.y, at.z, 1, 0.15D, 0.15D, 0.15D, 0.0D);
            }
        }
        // the whoosh of the chains, faster and higher as it speeds up
        double sp = progress(t);
        int period = Math.max(3, (int) Math.round(12 - 9 * sp));
        if (w.age % period == 0) {
            level.playSound(null, p.getX(), p.getY() + 1.5D, p.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.7F + 0.6F * (float) sp, 0.7F + 0.6F * (float) sp);
        }
        if (w.age % 9 == 0) level.playSound(null, p.getX(), p.getY() + 1.5D, p.getZ(), SoundEvents.CHAIN_STEP, SoundSource.PLAYERS, 1.0F, 0.6F + (float) sp);
        if (w.age == GATHER) level.playSound(null, p.getX(), p.getY() + 1.0D, p.getZ(), SoundEvents.CHAIN_HIT, SoundSource.PLAYERS, 1.4F, 0.6F);
        if (w.age == GATHER + SPIN - 20) level.playSound(null, p.getX(), p.getY() + 1.0D, p.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 0.9F, 1.4F);
    }

    /** He lets go: the chains snap, every NPC flies off along its own tangent, up and out, as a ragdoll. */
    private static void release(Whirl w, ServerLevel level) {
        w.released = true;
        ServerPlayer p = w.player;
        RandomSource r = level.getRandom();
        int n = w.mobs.size();
        double t = w.age;
        List<LivingEntity> flying = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LivingEntity m = w.mobs.get(i);
            WheelMod.unhold(m);
            if (m.isRemoved() || !m.isAlive()) continue;
            double a = angle(t) + i * Math.PI * 2.0D / n;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            Vec3 tan = new Vec3(-Math.sin(a), 0.0D, Math.cos(a));
            Vec3 vel = tan.scale(20.0D + r.nextDouble() * 6.0D).add(out.scale(7.0D)).add(0.0D, 9.0D + r.nextDouble() * 5.0D, 0.0D);
            Vec3 axis = new Vec3(0.0D, 1.0D, 0.0D).cross(tan);
            Vec3 spin = axis.normalize().scale(-2.0D).add(r.nextGaussian() * 0.6D, r.nextGaussian() * 0.6D, r.nextGaussian() * 0.6D);
            Vec3 c = m.position().add(0.0D, neckHeight(m), 0.0D);
            BallistaServer.launch(level, null, m, vel, spin, true);
            flying.add(m);
            // the link at the collar bursts
            level.sendParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 4, 0.1D, 0.1D, 0.1D, 0.0D);
            for (int k = 0; k < 10; k++) {
                Vec3 v = tan.scale(0.4D).add(r.nextGaussian() * 0.25D, r.nextGaussian() * 0.25D, r.nextGaussian() * 0.25D);
                level.sendParticles(k % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y, c.z, 0, v.x, v.y, v.z, 1.0D);
            }
        }
        ChainServer.breakChains(level, w.chains);
        Vec3 h = p.position().add(0.0D, 2.0D, 0.0D);
        level.playSound(null, h.x, h.y, h.z, SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 1.6F, 0.5F);
        level.playSound(null, h.x, h.y, h.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.7F, 0.6F);
        level.playSound(null, h.x, h.y, h.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.2F, 0.6F);
        level.playSound(null, h.x, h.y, h.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.6F, 1.5F);
        level.sendParticles(ParticleTypes.FLASH, h.x, h.y, h.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        PacketDistributor.sendToPlayersNear(level, null, p.getX(), p.getY(), p.getZ(), 96.0D,
                new WhirlState(WhirlState.RELEASE, p.getId(), -1, p.getX(), p.getY(), p.getZ(), new int[0]));
        if (flying.isEmpty()) return;
        // the camera follows one of them, picked at random
        w.focus = flying.get(r.nextInt(flying.size()));
        w.last = w.focus.position().add(0.0D, w.focus.getBbHeight() * 0.5D, 0.0D);
        send(p, WhirlState.LAUNCH, w.focus.getId(), w.last);
    }

    private static void endCooldown(ServerPlayer p) {
        var item = WheelMod.CHAIN_ITEM.get();
        p.getCooldowns().removeCooldown(item);
        p.getCooldowns().addCooldown(item, 40);
    }

    private static void send(ServerPlayer p, int phase, int id, Vec3 pos) {
        PacketDistributor.sendToPlayer(p, new WhirlState(phase, p.getId(), id, pos.x, pos.y, pos.z, new int[0]));
    }
}
