package com.example.wheelofwonder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the Walrus PUNCH! combo:
 * START  - critical uppercut: extra damage, the NPC becomes a ragdoll and flies straight up,
 * RISING - the body is tracked until it stops climbing,
 * HOLD   - it hangs at the top (a short anime "hang time") while the puncher leaps up to it,
 * STRIKE - the punch at the top: the body is smashed down as one rigid object,
 * SMASHED- tracked until it hits the ground (shockwave) and the puncher has landed (no fall damage until then).
 * The client drives the puncher's leap and the camera; the server owns the ragdoll and the damage.
 */
public final class PunchServer {
    private static final int RISING = 0, HOLD = 1, SMASHED = 2, INTRO = 3;
    private static final int HOLD_MAX = 160;
    private static final int RISE_MAX = 260;
    /** Safety net only: the client's free camera can stay on the body as long as the player likes (until Ctrl). */
    private static final int SMASHED_MAX = 20 * 60 * 20;
    private static final double MAX_STEP = 8.0D;

    private static final class Combo {
        final ServerPlayer player;
        final LivingEntity mob;
        boolean ragdoll;
        Vec3 origin;
        int phase = RISING;
        int age, phaseAge;
        Vec3 last, apex;
        double lastVy;
        boolean landed;
        Vec3 spin = Vec3.ZERO;
        Vec3 dir = new Vec3(1.0D, 0.0D, 0.0D);
        double height;
        int riseAge;
        Vec3 riseStart;

        // slow steered fall after the smash
        boolean diving;
        int diveAge;
        Vec3 diveStart, diveEnd, diveSpin = Vec3.ZERO, diveDir = new Vec3(1.0D, 0.0D, 0.0D);
        // the top punch landed; the body waits frozen for the end of the hit-stop
        boolean struck;
        int strikeAge;
        // the client's cinematic is over (or skipped): tracking may stop
        boolean clientDone;

        Combo(ServerPlayer player, LivingEntity mob, boolean ragdoll, Vec3 origin) {
            this.player = player;
            this.mob = mob;
            this.ragdoll = ragdoll;
            this.origin = origin;
            this.last = origin;
        }
    }

    private static final Map<UUID, Combo> COMBOS = new HashMap<>();

    private PunchServer() {
    }

    public static boolean protectsFall(ServerPlayer p) {
        return COMBOS.containsKey(p.getUUID());
    }

    static boolean isPunchable(Entity e) {
        return e instanceof LivingEntity le && le.isAlive() && !(e instanceof Player) && !(e instanceof ArmorStand)
                && !e.isSpectator() && !e.isInvisible() && !e.getType().is(Tags.EntityTypes.BOSSES);
    }

    public static void handleRequest(PunchRequest req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (req.action() == PunchRequest.START) start(player, req.entityId());
        else if (req.action() == PunchRequest.STRIKE) strike(player);
        else if (req.action() == PunchRequest.RELEASE) {
            Combo c = COMBOS.get(player.getUUID());
            if (c != null && c.phase == HOLD && c.struck) beginDive(c);
        }
        else if (req.action() == PunchRequest.DONE) {
            Combo c = COMBOS.get(player.getUUID());
            if (c != null) c.clientDone = true;
        } else if (req.action() == PunchRequest.CANCEL) {
            Combo c = COMBOS.get(player.getUUID());
            if (c != null) c.clientDone = true;
            if (c != null && c.phase != SMASHED) {
                c.phase = SMASHED;
                c.phaseAge = 0;
                if (!c.ragdoll) c.mob.setNoGravity(false);
            }
        }
    }

    // ------------------------------------------------------------------ uppercut

    private static void start(ServerPlayer player, int entityId) {
        ServerLevel level = player.serverLevel();
        Entity e = level.getEntity(entityId);
        if (COMBOS.containsKey(player.getUUID()) || !WalrusGlovesItem.isGloves(player.getMainHandItem())
                || player.getCooldowns().isOnCooldown(player.getMainHandItem().getItem()) || !isPunchable(e)
                || WheelRagdolls.isRagdolled((LivingEntity) e)) {
            send(player, PunchState.END, entityId, Vec3.ZERO);
            return;
        }
        LivingEntity mob = (LivingEntity) e;
        double reach = WheelConfig.PUNCH_REACH.get() + 1.5D;
        if (mob.getBoundingBox().distanceToSqr(player.getEyePosition()) > reach * reach) {
            send(player, PunchState.END, entityId, Vec3.ZERO);
            return;
        }
        // INTRO: the NPC is held still for a moment while the camera shows the scene from the side
        Combo intro = new Combo(player, mob, false, mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D));
        intro.phase = INTRO;
        player.getCooldowns().addCooldown(player.getMainHandItem().getItem(), 20 * 90);
        COMBOS.put(player.getUUID(), intro);
        if (mob instanceof Mob m) m.getNavigation().stop();
    }

    /** End of the intro: the critical uppercut. */
    private static void uppercut(Combo c) {
        ServerPlayer player = c.player;
        LivingEntity mob = c.mob;
        ServerLevel level = player.serverLevel();
        int entityId = mob.getId();
        RandomSource r = player.getRandom();
        Vec3 center = mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);

        // critical hit with extra damage
        mob.invulnerableTime = 0;
        mob.hurt(player.damageSources().playerAttack(player), WheelConfig.PUNCH_DAMAGE.get().floatValue());
        level.playSound(null, center.x, center.y, center.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.6F, 0.8F);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.4F, 0.7F);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 1.6F);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.7F, 1.6F);
        uppercutFx(level, center, r);

        if (!mob.isAlive()) {
            // knocked out by the hit itself: no combo
            COMBOS.remove(player.getUUID());
            send(player, PunchState.END, entityId, center);
            endCooldown(player);
            return;
        }
        if (mob instanceof Mob m) m.getNavigation().stop();
        Vec3 dir = mob.position().subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
        dir = dir.lengthSqr() < 1.0E-4D ? player.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize() : dir.normalize();
        // never fly into a ceiling: stop 1.3 blocks under the first block above the NPC
        double room = headroom(level, mob);
        double height = Math.max(1.2D, Math.min(WheelConfig.PUNCH_HEIGHT.get(), room - 1.3D));
        // a gentle start; the rise itself is steered (RISING) so it is slow and ends exactly at the top
        Vec3 vel = new Vec3(0.0D, 6.0D, 0.0D);
        Vec3 axis = new Vec3(0.0D, 1.0D, 0.0D).cross(dir).normalize();
        Vec3 spin = axis.scale(0.9D).add(0.0D, (r.nextDouble() - 0.5D) * 0.8D, 0.0D);

        boolean ragdoll = false;
        if (WheelRagdolls.available()) {
            mob.addTag(WheelMod.RAGDOLL_TAG);
            if (WheelConfig.LIMP.get()) mob.addTag(WheelMod.KICKMOD_LIMP_TAG);
            ragdoll = WheelRagdolls.launch(level, mob, vel, spin, WheelConfig.RAGDOLL_SECONDS.get());
            if (!ragdoll) {
                mob.removeTag(WheelMod.RAGDOLL_TAG);
                mob.removeTag(WheelMod.KICKMOD_LIMP_TAG);
            }
        }
        if (!ragdoll) {
            // no Sable: plain knock-up of the entity (blocks per tick)
            mob.setDeltaMovement(vel.scale(1.0D / 20.0D).multiply(1.0D, 0.85D, 1.0D));
            mob.hurtMarked = true;
        }
        c.ragdoll = ragdoll;
        c.origin = center;
        c.last = center;
        c.phase = RISING;
        c.phaseAge = 0;
        c.spin = spin;
        c.height = height;
        c.dir = dir;
        if (!ragdoll) mob.setNoGravity(true);
        send(player, PunchState.HIT, mob.getId(), center);
    }

    // ------------------------------------------------------------------ smash at the top

    private static void strike(ServerPlayer player) {
        Combo c = COMBOS.get(player.getUUID());
        if (c == null || c.phase != HOLD) return;
        ServerLevel level = player.serverLevel();
        RandomSource r = player.getRandom();
        Vec3 body = c.last;
        Vec3 dir = body.subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
        dir = dir.lengthSqr() < 1.0E-4D ? player.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize() : dir.normalize();
        // forward flip: the body tumbles over in the direction of the punch
        Vec3 spin = new Vec3(0.0D, 1.0D, 0.0D).cross(dir).normalize().scale(-3.0D);
        // the fall is steered (DIVE in the tick): it speeds up all the way down and lands where it should,
        // slow enough to watch, instead of hitting the ground a fraction of a second after the punch
        Vec3 end = body.add(dir.scale(1.8D));
        double groundY = groundBelow(level, end, body.y);
        c.diveStart = body;
        c.diveEnd = new Vec3(end.x, groundY + 0.55D, end.z);
        c.diveSpin = spin;
        c.diveDir = dir;
        // The hit-stop: the body stays frozen at the top (HOLD keeps holding it) until the client's pause is over
        // and it sends RELEASE; only then the fall starts, so the camera can follow it from the first moment.
        c.struck = true;
        c.strikeAge = 0;
        level.playSound(null, body.x, body.y, body.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 2.0F, 0.6F);
        level.playSound(null, body.x, body.y, body.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.8F, 1.7F);
        level.playSound(null, body.x, body.y, body.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2F, 1.5F);
        level.playSound(null, body.x, body.y, body.z, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1.5F, 0.7F);
        level.sendParticles(ParticleTypes.SONIC_BOOM, body.x, body.y, body.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.FLASH, body.x, body.y, body.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION, body.x, body.y, body.z, 2, 0.3D, 0.3D, 0.3D, 0.0D);
        burst(level, ParticleTypes.ELECTRIC_SPARK, body, 60, 0.9D, r);
        burst(level, ParticleTypes.SNOWFLAKE, body, 50, 0.45D, r);
        burst(level, ParticleTypes.CRIT, body, 40, 0.9D, r);
        send(player, PunchState.SMASH, c.mob.getId(), body);
    }

    // ------------------------------------------------------------------ tick

    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Combo> it = COMBOS.values().iterator();
        while (it.hasNext()) {
            Combo c = it.next();
            ServerPlayer p = c.player;
            if (p.isRemoved() || !p.isAlive() || p.level() != c.mob.level()) {
                if (!c.ragdoll && !c.mob.isRemoved()) c.mob.setNoGravity(false);
                endCooldown(p);
                it.remove();
                continue;
            }
            p.fallDistance = 0.0F;
            c.age++;
            c.phaseAge++;
            ServerLevel level = p.serverLevel();
            Vec3 pos = bodyPos(c);
            if (pos.distanceTo(c.last) > MAX_STEP) pos = c.last;
            double vy = pos.y - c.last.y;
            c.last = pos;
            send(p, PunchState.TRACK, c.mob.getId(), pos);

            switch (c.phase) {
                case INTRO -> {
                    // hold the NPC still, facing the puncher, while the camera shows the scene
                    LivingEntity mob = c.mob;
                    if (!mob.isAlive() || mob.isRemoved()) {
                        send(p, PunchState.END, mob.getId(), pos);
                        endCooldown(p);
                        it.remove();
                        continue;
                    }
                    if (mob instanceof Mob m) {
                        m.getNavigation().stop();
                        m.getLookControl().setLookAt(p, 30.0F, 30.0F);
                    }
                    Vec3 dm = mob.getDeltaMovement();
                    mob.setDeltaMovement(0.0D, Math.min(0.0D, dm.y), 0.0D);
                    if (c.phaseAge >= Math.round(WheelConfig.PUNCH_INTRO.get() * 20.0D)) uppercut(c);
                }
                case RISING -> {
                    boolean bodyReady = !c.ragdoll || WheelRagdolls.isRagdolled(c.mob);
                    if (!bodyReady) {
                        // Sable is still building the body (waiting for the client's model)
                        if (c.phaseAge > 60) {
                            c.phase = SMASHED;
                            c.phaseAge = 0;
                            send(p, PunchState.END, c.mob.getId(), pos);
                        }
                        break;
                    }
                    if (c.riseStart == null) c.riseStart = pos;
                    int total = Math.max(10, (int) Math.round(WheelConfig.PUNCH_RISE.get() * 20.0D));
                    c.riseAge++;
                    double t = Math.min(1.0D, c.riseAge / (double) total);
                    // ease-out: fast off the ground, slowing down to a stop at the top
                    double e = 1.0D - (1.0D - t) * (1.0D - t) * (1.0D - t);
                    double topY = c.origin.y + c.height;
                    Vec3 target = new Vec3(c.riseStart.x + c.dir.x * 0.4D * e, Mth.lerp(e, c.riseStart.y, topY), c.riseStart.z + c.dir.z * 0.4D * e);
                    double de = 3.0D * (1.0D - t) * (1.0D - t) / total * 20.0D; // d(ease)/ds
                    Vec3 pathVel = new Vec3(c.dir.x * 0.4D * de, (topY - c.riseStart.y) * de, c.dir.z * 0.4D * de);
                    if (c.ragdoll) {
                        WheelRagdolls.steerRoot(c.mob, target, pathVel, c.spin);
                    } else if (c.mob.isAlive()) {
                        c.mob.setPos(target.x, target.y - c.mob.getBbHeight() * 0.5D, target.z);
                        c.mob.setDeltaMovement(Vec3.ZERO);
                        c.mob.hurtMarked = true;
                    }
                    if (c.riseAge % 4 == 0) level.sendParticles(ParticleTypes.SNOWFLAKE, pos.x, pos.y - 0.5D, pos.z, 2, 0.2D, 0.1D, 0.2D, 0.01D);
                    if (t >= 1.0D || c.phaseAge > RISE_MAX) {
                        c.phase = HOLD;
                        c.phaseAge = 0;
                        c.apex = target;
                        send(p, PunchState.APEX, c.mob.getId(), target);
                        level.playSound(null, target.x, target.y, target.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 0.6F);
                    }
                }
                case HOLD -> {
                    hold(c);
                    if (c.phaseAge % 3 == 0) level.sendParticles(ParticleTypes.SNOWFLAKE, pos.x, pos.y, pos.z, 3, 0.4D, 0.4D, 0.4D, 0.02D);
                    if (c.struck) {
                        // safety: the client never sent RELEASE
                        if (++c.strikeAge > 100) beginDive(c);
                    } else if (c.phaseAge > HOLD_MAX) {
                        // the puncher never came: let it fall
                        c.phase = SMASHED;
                        c.phaseAge = 0;
                        if (!c.ragdoll) c.mob.setNoGravity(false);
                    }
                }
                default -> {
                    if (c.diving) dive(c);
                    if (!c.landed && !c.diving && c.phaseAge > 2 && c.lastVy < -0.3D && vy > -0.08D) {
                        c.landed = true;
                        landingFx(level, pos, p.getRandom());
                        send(p, PunchState.LANDED, c.mob.getId(), pos);
                    }
                    // The body is tracked (TRACK every tick) until the client's camera is done with it and says DONE,
                    // so the follow camera never loses it while it still rolls; SMASHED_MAX is only a safety net.
                    if (c.phaseAge > SMASHED_MAX || (c.clientDone && p.onGround())) {
                        endCooldown(p);
                        it.remove();
                        continue;
                    }
                }
            }
            c.lastVy = vy;
        }
    }

    /** End of the hit-stop: the smashed body starts its fall. */
    private static void beginDive(Combo c) {
        // start the dive from where the body hangs now
        c.diveStart = c.last;
        c.diving = true;
        c.diveAge = 0;
        if (c.ragdoll && WheelRagdolls.isRagdolled(c.mob)) {
            WheelRagdolls.throwRigid(c.mob, c.diveDir.scale(2.0D).add(0.0D, -3.0D, 0.0D), c.diveSpin);
        }
        c.phase = SMASHED;
        c.phaseAge = 0;
    }

    /**
     * Steered fall after the smash: accelerating (t^2) from the top down to just above the ground, the body
     * tumbling forward all the way; then it is let go with the speed it has, so it slams into the ground.
     */
    private static void dive(Combo c) {
        int total = Math.max(8, (int) Math.round(WheelConfig.PUNCH_DIVE.get() * 20.0D));
        c.diveAge++;
        double t = Math.min(1.0D, c.diveAge / (double) total);
        Vec3 span = c.diveEnd.subtract(c.diveStart);
        Vec3 target = c.diveStart.add(span.scale(t * t));
        Vec3 pathVel = span.scale(2.0D * t / total * 20.0D);
        if (c.ragdoll) {
            if (WheelRagdolls.isRagdolled(c.mob)) WheelRagdolls.steerRoot(c.mob, target, pathVel, c.diveSpin);
        } else if (c.mob.isAlive()) {
            c.mob.setNoGravity(true);
            c.mob.setPos(target.x, target.y - c.mob.getBbHeight() * 0.5D, target.z);
            c.mob.setDeltaMovement(Vec3.ZERO);
            c.mob.hurtMarked = true;
        }
        if (t >= 1.0D) {
            c.diving = false;
            if (c.ragdoll && WheelRagdolls.isRagdolled(c.mob)) {
                WheelRagdolls.throwRigid(c.mob, pathVel.add(0.0D, -2.0D, 0.0D), c.diveSpin);
            } else if (c.mob.isAlive()) {
                c.mob.setNoGravity(false);
                c.mob.setDeltaMovement(pathVel.scale(1.0D / 20.0D));
                c.mob.hurtMarked = true;
            }
            // the impact itself (the body is half a block above the ground and fast)
            if (!c.landed) {
                c.landed = true;
                landingFx(c.player.serverLevel(), c.diveEnd, c.player.getRandom());
                send(c.player, PunchState.LANDED, c.mob.getId(), c.diveEnd);
            }
        }
    }

    /** Top of the first solid block under {@code at} (scanning down from fromY), or fromY - 12 if none. */
    private static double groundBelow(ServerLevel level, Vec3 at, double fromY) {
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int y = Mth.floor(fromY); y > Mth.floor(fromY) - 48; y--) {
            bp.set(Mth.floor(at.x), y, Mth.floor(at.z));
            var shape = level.getBlockState(bp).getCollisionShape(level, bp);
            if (!shape.isEmpty()) return y + shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        return fromY - 12.0D;
    }

    /** Free blocks above the NPC's head (checked on its centre column and the 4 corners of its hitbox), up to 40. */
    private static double headroom(ServerLevel level, LivingEntity mob) {
        var bb = mob.getBoundingBox();
        double[][] cols = {{(bb.minX + bb.maxX) * 0.5D, (bb.minZ + bb.maxZ) * 0.5D}, {bb.minX + 0.05D, bb.minZ + 0.05D},
                {bb.maxX - 0.05D, bb.minZ + 0.05D}, {bb.minX + 0.05D, bb.maxZ - 0.05D}, {bb.maxX - 0.05D, bb.maxZ - 0.05D}};
        double best = 40.0D;
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (double[] col : cols) {
            for (int i = 0; i < 40; i++) {
                bp.set(Mth.floor(col[0]), Mth.floor(bb.maxY) + i, Mth.floor(col[1]));
                if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) {
                    best = Math.min(best, bp.getY() - bb.maxY);
                    break;
                }
            }
        }
        return best + mob.getBbHeight() * 0.5D;
    }

    private static void hold(Combo c) {
        if (c.ragdoll) {
            if (WheelRagdolls.isRagdolled(c.mob)) WheelRagdolls.holdRoot(c.mob, c.apex, c.spin.scale(0.3D));
        } else if (c.mob.isAlive()) {
            c.mob.setNoGravity(true);
            c.mob.setPos(c.apex.x, c.apex.y - c.mob.getBbHeight() * 0.5D, c.apex.z);
            c.mob.setDeltaMovement(Vec3.ZERO);
            c.mob.hurtMarked = true;
        }
    }

    private static Vec3 bodyPos(Combo c) {
        if (c.ragdoll) {
            Vec3 root = WheelRagdolls.rootPosition(c.mob);
            if (root != null) return root;
            return c.last;
        }
        return c.mob.position().add(0.0D, c.mob.getBbHeight() * 0.5D, 0.0D);
    }

    private static void endCooldown(ServerPlayer p) {
        var item = WheelMod.GLOVES_ITEM.get();
        p.getCooldowns().removeCooldown(item);
        int cd = WheelConfig.PUNCH_COOLDOWN.get();
        if (cd > 0) p.getCooldowns().addCooldown(item, cd);
    }

    private static void send(ServerPlayer p, int phase, int id, Vec3 pos) {
        PacketDistributor.sendToPlayer(p, new PunchState(phase, id, pos.x, pos.y, pos.z));
    }

    // ------------------------------------------------------------------ effects (seen by everybody)

    private static void burst(ServerLevel level, ParticleOptions type, Vec3 at, int n, double speed, RandomSource r) {
        for (int i = 0; i < n; i++) {
            Vec3 v = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().scale(speed * (0.4D + r.nextDouble() * 0.6D));
            // count 0 = the offset is the particle's velocity
            level.sendParticles(type, at.x, at.y, at.z, 0, v.x, v.y, v.z, 1.0D);
        }
    }

    private static void uppercutFx(ServerLevel level, Vec3 c, RandomSource r) {
        level.sendParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        burst(level, ParticleTypes.CRIT, c, 30, 0.8D, r);
        burst(level, ParticleTypes.ELECTRIC_SPARK, c, 35, 0.7D, r);
        burst(level, ParticleTypes.SNOWFLAKE, c, 30, 0.35D, r);
        // an icy pillar shooting up where the NPC is launched
        for (int i = 0; i < 14; i++) {
            level.sendParticles(ParticleTypes.SNOWFLAKE, c.x, c.y + i * 0.45D, c.z, 2, 0.12D, 0.1D, 0.12D, 0.02D);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + i * 0.45D, c.z, 1, 0.1D, 0.1D, 0.1D, 0.05D);
        }
    }

    private static void landingFx(ServerLevel level, Vec3 at, RandomSource r) {
        BlockPos below = BlockPos.containing(at.x, at.y - 0.6D, at.z);
        BlockState ground = level.getBlockState(below);
        if (ground.isAir()) ground = level.getBlockState(below.below());
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.4F, 1.3F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.6F, 0.6F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.2F, 0.8F);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 3, 0.6D, 0.2D, 0.6D, 0.0D);
        if (!ground.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y - 0.3D, at.z, 70, 0.8D, 0.2D, 0.8D, 0.3D);
        }
        // shockwave ring of frost and dust
        for (int i = 0; i < 36; i++) {
            double a = i * Math.PI * 2.0D / 36.0D;
            double dx = Math.cos(a), dz = Math.sin(a);
            level.sendParticles(ParticleTypes.CLOUD, at.x, at.y - 0.4D, at.z, 0, dx, 0.02D, dz, 0.45D);
            level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y - 0.3D, at.z, 0, dx, 0.08D, dz, 0.55D);
            if (i % 2 == 0) level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y - 0.2D, at.z, 0, dx, 0.1D, dz, 0.7D);
        }
    }
}
