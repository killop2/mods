package com.example.wheelofwonder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
 * Server side of the ballista: the "load" key (numpad 7) puts an NPC into the nearest free ballista, and after the
 * shot the flying ragdoll is tracked for the shooter's camera (TRACK every tick) until the camera is done (DONE).
 */
public final class BallistaServer {
    /** Safety net: tracking never runs longer than this (the camera itself lasts until the player presses Ctrl). */
    private static final int MAX_AGE = 20 * 60 * 20;
    private static final int TRAIL_TICKS = 160;
    private static final double MAX_STEP = 8.0D;

    private static final class Shot {
        final ServerPlayer player;
        final LivingEntity mob;
        final boolean ragdoll;
        final Vec3 vel, spin;
        boolean rigidDone, landed, clientDone, free = true;
        int tailA = 0xC84BFF, tailB = 0xFF8A1F;
        Vec3 last;
        double lastVy;
        int age;

        Shot(ServerPlayer player, LivingEntity mob, boolean ragdoll, Vec3 vel, Vec3 spin, Vec3 from) {
            this.player = player;
            this.mob = mob;
            this.ragdoll = ragdoll;
            this.vel = vel;
            this.spin = spin;
            this.last = from;
        }
    }

    private static final Map<UUID, Shot> SHOTS = new HashMap<>();

    private BallistaServer() {
    }

    static boolean loadable(Entity e) {
        return e instanceof LivingEntity le && le.isAlive() && !(e instanceof Player) && !(e instanceof ArmorStand)
                && !e.isSpectator() && !e.getType().is(Tags.EntityTypes.BOSSES) && !(e.getVehicle() instanceof NpcLauncher)
                && !WheelRagdolls.isRagdolled(le);
    }

    public static void handleRequest(BallistaRequest req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (req.action() == BallistaRequest.LOAD) {
            load(player, req.entityId());
        } else if (req.action() == BallistaRequest.METEOR) {
            MeteorEntity.cast(player);
        } else if (req.action() == BallistaRequest.DONE) {
            for (Shot s : SHOTS.values()) if (s.player == player) s.clientDone = true;
        }
    }

    private static void load(ServerPlayer player, int entityId) {
        ServerLevel level = player.serverLevel();
        Entity e = level.getEntity(entityId);
        if (!loadable(e) || e.distanceTo(player) > 10.0D) {
            player.displayClientMessage(Component.translatable(e instanceof LivingEntity le && WheelRagdolls.isRagdolled(le)
                    ? "wheelofwonder.msg.npc_ragdoll" : "wheelofwonder.msg.npc_busy"), true);
            send(player, BallistaState.REJECTED, entityId, player.position());
            return;
        }
        LivingEntity mob = (LivingEntity) e;
        NpcLauncher best = nearestLauncher(level, mob);
        if (best == null) {
            player.displayClientMessage(Component.translatable("wheelofwonder.msg.no_ballista",
                    (int) Math.round(WheelConfig.BALLISTA_RADIUS.get()), (int) Math.round(WheelConfig.ROCKET_RADIUS.get())), true);
            send(player, BallistaState.REJECTED, entityId, mob.position());
            return;
        }
        best.load(player, mob);
        Vec3 c = mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
        send(player, BallistaState.SEATED, mob.getId(), c);
    }

    /** The nearest free ride (ballista or rocket) within its own load radius of the NPC; works on both sides. */
    public static NpcLauncher nearestLauncher(net.minecraft.world.level.Level level, Entity npc) {
        double reach = Math.max(Math.max(Math.max(WheelConfig.BALLISTA_RADIUS.get(), WheelConfig.ROCKET_RADIUS.get()), WheelConfig.RANGER_RADIUS.get()),
                WheelConfig.PRISM_RADIUS.get()) + 2.0D;
        NpcLauncher best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity c : level.getEntities(npc, npc.getBoundingBox().inflate(reach), x -> x instanceof NpcLauncher l && l.isIdle())) {
            double d = c.distanceTo(npc);
            if (d <= ((NpcLauncher) c).loadRadius() && d < bestD) {
                bestD = d;
                best = (NpcLauncher) c;
            }
        }
        return best;
    }

    /** Called by the ballista at the moment of release. */
    static void launch(ServerLevel level, ServerPlayer player, LivingEntity mob, Vec3 vel, Vec3 spin) {
        launch(level, player, mob, vel, spin, true);
    }

    /**
     * The NPC becomes a ragdoll flying with {@code vel} and is tracked for the camera.
     * @param free true = a free throw (thrown as one rigid piece, comet tail); false = something else steers it (the rocket)
     */
    /** A free throw with its own comet-tail colours (Telekinesis Orb: green). */
    static void launch(ServerLevel level, ServerPlayer player, LivingEntity mob, Vec3 vel, Vec3 spin, int tailA, int tailB) {
        launch(level, player, mob, vel, spin, true);
        Shot s = SHOTS.get(mob.getUUID());
        if (s != null) {
            s.tailA = tailA;
            s.tailB = tailB;
        }
    }

    static void launch(ServerLevel level, ServerPlayer player, LivingEntity mob, Vec3 vel, Vec3 spin, boolean free) {
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
            // no Sable: a plain throw of the entity (blocks per tick)
            mob.setDeltaMovement(vel.scale(1.0D / 20.0D));
            mob.hurtMarked = true;
        }
        // no fall damage on the way and on landing (removed when the shot ends)
        WheelMod.hold(mob);
        Vec3 from = mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
        Shot shot = new Shot(player, mob, ragdoll, vel, spin, from);
        shot.free = free;
        shot.rigidDone = !free;
        Shot old = SHOTS.put(mob.getUUID(), shot);
        if (old != null && old.player != null && old.player != player) send(old.player, BallistaState.LANDED, mob.getId(), from);
        if (player != null) send(player, BallistaState.LAUNCH, mob.getId(), from);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 == 0) WheelRagdolls.sweepStale(tick);
        Iterator<Shot> it = SHOTS.values().iterator();
        while (it.hasNext()) {
            Shot s = it.next();
            s.age++;
            LivingEntity mob = s.mob;
            boolean viewer = s.player != null && !s.player.isRemoved() && s.player.level() == mob.level() && !s.clientDone;
            if (mob.isRemoved() || s.age > MAX_AGE || (!viewer && (s.landed || s.age > TRAIL_TICKS))) {
                WheelMod.unhold(mob);
                it.remove();
                continue;
            }
            ServerLevel level = (ServerLevel) mob.level();
            if (s.ragdoll && !s.rigidDone && WheelRagdolls.isRagdolled(mob)) {
                // the body exists now: throw it as one rigid piece (no joints fighting, no shaking in flight)
                s.rigidDone = WheelRagdolls.throwRigid(mob, s.vel, s.spin);
            }
            Vec3 pos = null;
            if (s.ragdoll) pos = WheelRagdolls.rootPosition(mob);
            if (pos == null) pos = s.ragdoll && WheelRagdolls.isRagdolled(mob) ? s.last : mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
            if (pos.distanceTo(s.last) > MAX_STEP) pos = s.last;
            double vy = pos.y - s.last.y;
            s.last = pos;
            if (viewer) send(s.player, BallistaState.TRACK, mob.getId(), pos);
            // while the camera watches it, the ragdoll's chunks stay simulated even far from the player
            if (viewer && s.age % 5 == 0) WheelMod.keepLoaded(level, pos);

            if (s.free && !s.landed && s.age < TRAIL_TICKS) {
                // comet tail of the Dark Carnival: purple and orange sparks with soul fire
                level.sendParticles(WheelEntity.dust(s.age % 2 == 0 ? s.tailA : s.tailB, 1.6F), pos.x, pos.y, pos.z, 2, 0.15D, 0.15D, 0.15D, 0.0D);
                if (s.age % 2 == 0) level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.x, pos.y, pos.z, 1, 0.1D, 0.1D, 0.1D, 0.01D);
                if (s.age % 4 == 0) level.sendParticles(ParticleTypes.FIREWORK, pos.x, pos.y, pos.z, 1, 0.1D, 0.1D, 0.1D, 0.02D);
            }
            if (!s.landed && s.age > 6 && s.lastVy < -0.25D && vy > -0.05D) {
                s.landed = true;
                if (s.free) landingFx(level, pos, mob.getRandom());
                if (viewer) send(s.player, BallistaState.LANDED, mob.getId(), pos);
            }
            s.lastVy = vy;
        }
    }

    private static void landingFx(ServerLevel level, Vec3 at, RandomSource r) {
        BlockPos below = BlockPos.containing(at.x, at.y - 0.6D, at.z);
        BlockState ground = level.getBlockState(below);
        if (ground.isAir()) ground = level.getBlockState(below.below());
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 0.9F, 1.4F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.NEUTRAL, 1.2F, 0.9F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.NEUTRAL, 1.0F, 0.5F);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        if (!ground.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y - 0.3D, at.z, 40, 0.7D, 0.2D, 0.7D, 0.25D);
        }
        for (int i = 0; i < 30; i++) {
            double a = i * Math.PI * 2.0D / 30.0D;
            int col = WheelEntity.BULB_COLORS[i % WheelEntity.BULB_COLORS.length];
            level.sendParticles(WheelEntity.dust(col, 1.4F), at.x, at.y, at.z, 0, Math.cos(a), 0.25D + r.nextDouble() * 0.4D, Math.sin(a), 0.5D);
            if (i % 3 == 0) level.sendParticles(ParticleTypes.SOUL, at.x, at.y - 0.2D, at.z, 0, Math.cos(a), 0.05D, Math.sin(a), 0.12D);
        }
    }

    private static void send(ServerPlayer p, int phase, int id, Vec3 pos) {
        PacketDistributor.sendToPlayer(p, new BallistaState(phase, id, pos.x, pos.y, pos.z));
    }
}
