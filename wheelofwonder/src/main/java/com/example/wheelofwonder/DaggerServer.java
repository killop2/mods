package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of Impalement Arts:
 * SPIN   - the daggers circle the thrower; the NPC is held still (it is the target),
 * FLIGHT - they fly (the client draws them; the server only waits the flight time),
 * STUCK  - they are in the NPC: a short hit-stop, the NPC still frozen,
 * FLY    - damage, the NPC becomes a ragdoll thrown along the daggers' flight (one rigid piece, like the smashed
 *          body of Walrus PUNCH!), tracked every tick for the thrower's camera until the client says DONE (Ctrl).
 */
public final class DaggerServer {
    private static final int SPIN = 0, FLIGHT = 1, STUCK = 2, FLY = 3;
    private static final int MAX_AGE = 20 * 60 * 20;
    private static final double MAX_STEP = 8.0D;

    private static final class Throw {
        final ServerPlayer player;
        final LivingEntity mob;
        int phase = SPIN, phaseAge, age;
        int spinTicks, flightTicks, stopTicks;
        Vec3 dir = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 last;
        boolean clientDone, slowed;

        Throw(ServerPlayer player, LivingEntity mob) {
            this.player = player;
            this.mob = mob;
            this.last = centre(mob);
        }
    }

    private static final Map<UUID, Throw> THROWS = new HashMap<>();

    private DaggerServer() {
    }

    static Vec3 centre(LivingEntity e) {
        return e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
    }

    public static void handleRequest(DaggerRequest req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        Throw t = THROWS.get(player.getUUID());
        switch (req.action()) {
            case DaggerRequest.START -> start(player, req.entityId());
            case DaggerRequest.CANCEL -> {
                if (t == null) return;
                if (t.phase == FLY) {
                    t.clientDone = true;
                } else {
                    // aborted during the wind-up: nothing happens to the NPC
                    THROWS.remove(player.getUUID());
                    endCooldown(player);
                }
            }
            case DaggerRequest.DONE -> {
                if (t != null) t.clientDone = true;
            }
            default -> {
            }
        }
    }

    private static void start(ServerPlayer player, int entityId) {
        Entity e = player.serverLevel().getEntity(entityId);
        var item = player.getMainHandItem();
        double r = WheelConfig.DAGGER_RADIUS.get() + 3.0D;
        if (THROWS.containsKey(player.getUUID()) || !ImpalementArtsItem.isDaggers(item) || player.getCooldowns().isOnCooldown(item.getItem())
                || !PunchServer.isPunchable(e) || WheelRagdolls.isRagdolled((LivingEntity) e) || e.distanceTo(player) > r) {
            send(player, DaggerState.END, entityId, player.position(), 0);
            return;
        }
        LivingEntity mob = (LivingEntity) e;
        Throw t = new Throw(player, mob);
        t.spinTicks = Math.max(6, (int) Math.round(WheelConfig.DAGGER_SPIN.get() * 20.0D));
        THROWS.put(player.getUUID(), t);
        // long cooldown while it runs (replaced by the normal one at the end)
        player.getCooldowns().addCooldown(item.getItem(), 20 * 90);
        if (mob instanceof Mob m) m.getNavigation().stop();
        ServerLevel level = player.serverLevel();
        Vec3 c = player.position().add(0.0D, 1.1D, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.PLAYERS, 0.9F, 1.4F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.8F, 1.6F);
        send(player, DaggerState.SPIN, mob.getId(), centre(mob), t.spinTicks);
    }

    // ------------------------------------------------------------------ slow motion (the server's tick rate)

    /** Tick rate before the slow motion (-1 = none running), and when it ends (System.nanoTime). */
    private static float savedRate = -1.0F;
    private static long slowStart, slowEnd;

    private static void slowMotion(net.minecraft.server.MinecraftServer server) {
        if (!WheelConfig.DAGGER_SLOWMO.get()) return;
        var trm = server.tickRateManager();
        if (savedRate < 0.0F) savedRate = trm.tickrate();
        slowStart = System.nanoTime();
        slowEnd = slowStart + (long) (WheelConfig.DAGGER_SLOWMO_TIME.get() * 1.0E9D);
        trm.setTickRate(Math.max(1.0F, savedRate * WheelConfig.DAGGER_SLOWMO_SPEED.get().floatValue()));
    }

    /** Real time drives it (game ticks are slow meanwhile): hold, then speed back up over the last half second. */
    private static void slowMotionTick(net.minecraft.server.MinecraftServer server) {
        if (savedRate < 0.0F) return;
        var trm = server.tickRateManager();
        long now = System.nanoTime();
        if (now >= slowEnd) {
            trm.setTickRate(savedRate);
            savedRate = -1.0F;
            return;
        }
        double left = (slowEnd - now) / 1.0E9D;
        double slow = WheelConfig.DAGGER_SLOWMO_SPEED.get();
        double k = left < 0.5D ? 1.0D - left / 0.5D : 0.0D;
        double f = slow + (1.0D - slow) * k * k;
        trm.setTickRate(Math.max(1.0F, (float) (savedRate * f)));
    }

    /** Server stopping in the middle of it: leave the normal speed behind. */
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        if (savedRate >= 0.0F) event.getServer().tickRateManager().setTickRate(savedRate);
        savedRate = -1.0F;
        THROWS.clear();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        slowMotionTick(event.getServer());
        Iterator<Throw> it = THROWS.values().iterator();
        while (it.hasNext()) {
            Throw t = it.next();
            ServerPlayer p = t.player;
            LivingEntity mob = t.mob;
            if (p.isRemoved() || !p.isAlive() || p.level() != mob.level() || t.age > MAX_AGE) {
                if (t.phase == FLY) WheelMod.unhold(mob);
                endCooldown(p);
                it.remove();
                continue;
            }
            t.age++;
            t.phaseAge++;
            ServerLevel level = p.serverLevel();
            if (t.phase != FLY && (!mob.isAlive() || mob.isRemoved() || WheelRagdolls.isRagdolled(mob))) {
                // the target died or was knocked over by something else before the daggers arrived
                send(p, DaggerState.END, mob.getId(), centre(mob), 0);
                endCooldown(p);
                it.remove();
                continue;
            }
            switch (t.phase) {
                case SPIN -> {
                    freeze(mob, p);
                    if (t.phaseAge % 10 == 0) {
                        Vec3 c = p.position().add(0.0D, 1.1D, 0.0D);
                        level.playSound(null, c.x, c.y, c.z, SoundEvents.TRIDENT_RIPTIDE_2.value(), SoundSource.PLAYERS, 0.35F, 1.5F + t.phaseAge * 0.01F);
                    }
                    if (t.phaseAge >= t.spinTicks) {
                        Vec3 from = p.position().add(0.0D, 1.25D, 0.0D);
                        Vec3 to = centre(mob);
                        Vec3 d = to.subtract(from);
                        t.dir = d.lengthSqr() < 1.0E-4D ? p.getLookAngle() : d.normalize();
                        double speed = WheelConfig.DAGGER_FLIGHT_SPEED.get();
                        t.flightTicks = Mth.clamp((int) Math.round(d.length() / speed * 20.0D), 8, 60);
                        t.phase = FLIGHT;
                        t.phaseAge = 0;
                        level.playSound(null, from.x, from.y, from.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.4F, 0.8F);
                        level.playSound(null, from.x, from.y, from.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.8F, 1.4F);
                        level.playSound(null, from.x, from.y, from.z, SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 0.8F, 1.2F);
                        send(p, DaggerState.THROW, mob.getId(), to, t.flightTicks);
                    }
                }
                case FLIGHT -> {
                    freeze(mob, p);
                    // the last moments of the flight, the hit and the first part of the ragdoll's flight in slow motion
                    if (!t.slowed && t.phaseAge >= t.flightTicks - 4) {
                        t.slowed = true;
                        slowMotion(p.server);
                    }
                    if (t.phaseAge >= t.flightTicks) {
                        t.phase = STUCK;
                        t.phaseAge = 0;
                        t.stopTicks = (int) Math.round(WheelConfig.DAGGER_HITSTOP.get() * 20.0D);
                        Vec3 c = centre(mob);
                        impactFx(level, c, t.dir, mob.getRandom());
                        send(p, DaggerState.HIT, mob.getId(), c, t.stopTicks);
                    }
                }
                case STUCK -> {
                    freeze(mob, p);
                    if (t.phaseAge >= t.stopTicks) launch(t, level);
                }
                default -> {
                    Vec3 pos = WheelRagdolls.rootPosition(mob);
                    if (pos == null) pos = WheelRagdolls.isRagdolled(mob) ? t.last : centre(mob);
                    if (pos.distanceTo(t.last) > MAX_STEP) pos = t.last;
                    t.last = pos;
                    if (t.clientDone || mob.isRemoved()) {
                        endCooldown(p);
                        it.remove();
                        continue;
                    }
                    send(p, DaggerState.TRACK, mob.getId(), pos, 0);
                    if (t.phaseAge % 5 == 0) WheelMod.keepLoaded(level, pos);
                    // the daggers keep burning in the flying body
                    if (t.phaseAge < 80 && t.phaseAge % 2 == 0) {
                        level.sendParticles(ParticleTypes.SMALL_FLAME, pos.x, pos.y, pos.z, 1, 0.2D, 0.2D, 0.2D, 0.01D);
                        level.sendParticles(WheelEntity.dust(0xB04BFF, 1.0F), pos.x, pos.y, pos.z, 1, 0.25D, 0.25D, 0.25D, 0.0D);
                    }
                }
            }
        }
    }

    /** The target waits where it is, facing the thrower. */
    private static void freeze(LivingEntity mob, ServerPlayer p) {
        if (mob instanceof Mob m) {
            m.getNavigation().stop();
            m.getLookControl().setLookAt(p, 30.0F, 30.0F);
        }
        Vec3 dm = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0D, Math.min(0.0D, dm.y), 0.0D);
    }

    /** End of the hit-stop: the damage, and the NPC is carried away by the daggers. */
    private static void launch(Throw t, ServerLevel level) {
        LivingEntity mob = t.mob;
        ServerPlayer p = t.player;
        Vec3 c = centre(mob);
        mob.invulnerableTime = 0;
        mob.hurt(p.damageSources().playerAttack(p), WheelConfig.DAGGER_DAMAGE.get().floatValue());
        if (!mob.isAlive()) {
            send(p, DaggerState.END, mob.getId(), c, 0);
            THROWS.remove(p.getUUID());
            endCooldown(p);
            return;
        }
        Vec3 vel = t.dir.scale(WheelConfig.DAGGER_LAUNCH.get()).add(0.0D, WheelConfig.DAGGER_LIFT.get(), 0.0D);
        // a slow tumble over the flight direction (head back, feet forward), like a body hit in the chest
        Vec3 axis = new Vec3(0.0D, 1.0D, 0.0D).cross(t.dir);
        axis = axis.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : axis.normalize();
        Vec3 spin = axis.scale(-1.6D).add(0.0D, (mob.getRandom().nextDouble() - 0.5D) * 0.6D, 0.0D);
        BallistaServer.launch(level, null, mob, vel, spin, true);
        t.phase = FLY;
        t.phaseAge = 0;
        t.last = c;
        level.playSound(null, c.x, c.y, c.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.6F, 0.7F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8F, 1.6F);
        level.sendParticles(ParticleTypes.SONIC_BOOM, c.x, c.y, c.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        burst(level, ParticleTypes.FLAME, c, 30, 0.5D, mob.getRandom());
        send(p, DaggerState.LAUNCH, mob.getId(), c, 0);
    }

    private static void endCooldown(ServerPlayer p) {
        var item = WheelMod.DAGGERS_ITEM.get();
        p.getCooldowns().removeCooldown(item);
        int cd = WheelConfig.DAGGER_COOLDOWN.get();
        if (cd > 0) p.getCooldowns().addCooldown(item, cd);
    }

    private static void send(ServerPlayer p, int phase, int id, Vec3 pos, int ticks) {
        PacketDistributor.sendToPlayer(p, new DaggerState(phase, id, pos.x, pos.y, pos.z, ticks));
    }

    // ------------------------------------------------------------------ effects (seen by everybody)

    private static void burst(ServerLevel level, ParticleOptions type, Vec3 at, int n, double speed, RandomSource r) {
        for (int i = 0; i < n; i++) {
            Vec3 v = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().scale(speed * (0.4D + r.nextDouble() * 0.6D));
            level.sendParticles(type, at.x, at.y, at.z, 0, v.x, v.y, v.z, 1.0D);
        }
    }

    /** Six blades arriving at once: a crimson flash, sparks thrown out of the back of the body, a ring of hellfire. */
    private static void impactFx(ServerLevel level, Vec3 c, Vec3 dir, RandomSource r) {
        level.playSound(null, c.x, c.y, c.z, SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, 1.6F, 0.8F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, 1.2F, 1.1F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.4F, 0.6F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1.4F, 0.6F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0F, 0.8F);
        level.sendParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 30, 0.3D, 0.4D, 0.3D, 0.6D);
        // sparks bursting out of the back, along the flight
        for (int i = 0; i < 40; i++) {
            Vec3 v = dir.add(r.nextGaussian() * 0.35D, r.nextGaussian() * 0.35D, r.nextGaussian() * 0.35D).normalize().scale(0.5D + r.nextDouble() * 0.8D);
            level.sendParticles(i % 3 == 0 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.FLAME, c.x, c.y, c.z, 0, v.x, v.y, v.z, 1.0D);
        }
        for (int i = 0; i < 24; i++) {
            Vec3 v = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().scale(0.25D + r.nextDouble() * 0.3D);
            level.sendParticles(WheelEntity.dust(i % 2 == 0 ? 0xFF2A3A : 0xB04BFF, 1.3F), c.x + v.x, c.y + v.y, c.z + v.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        burst(level, ParticleTypes.SOUL_FIRE_FLAME, c, 18, 0.25D, r);
    }
}
