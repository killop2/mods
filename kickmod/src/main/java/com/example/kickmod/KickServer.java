package com.example.kickmod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Server side: validates the kick, launches the ragdoll on impact, streams the body position, /unragdoll. */
public final class KickServer {
    /** How long the wind-up may take before the impact packet arrives. */
    private static final int PENDING_TIMEOUT = 200;
    /** Free camera: the body position is sent until the client says DONE (Ctrl); safety limit 10 minutes. */
    private static final int TRACK_TICKS = 20 * 60 * 10;

    private record Pending(int entityId, long startTick) {
    }

    private static final class Track {
        final ServerPlayer player;
        final LivingEntity mob;
        final Vec3 origin;
        Vec3 last;
        int ticksLeft = TRACK_TICKS;
        /** launch velocity (m/s), used if Sable accepts the launch but never builds the body */
        Vec3 velocity;
        boolean ragdollChecked;

        /** @param origin body centre at the moment of the hit, taken BEFORE the ragdoll is launched
         *  (Sable may move the hidden entity into its storage area afterwards). */
        Track(ServerPlayer player, LivingEntity mob, Vec3 origin) {
            this.player = player;
            this.mob = mob;
            this.origin = origin;
            this.last = origin;
        }
    }

    /** A real body never moves more than this in one tick (160 m/s); bigger jumps are Sable's storage coordinates. */
    private static final double MAX_TRACK_STEP = 8.0D;

    /** Entity tag of mobs kicked by this mod: their ragdoll joints are made limp (RagdollJointMixin). Saved with the entity. */
    public static final String RAGDOLL_TAG = "kickmod_ragdoll";

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private KickServer() {
    }

    public static boolean isKickable(Entity e) {
        // a mob that is already a ragdoll is hidden (invisible) by Sable: it cannot be kicked again
        return e instanceof LivingEntity le && le.isAlive() && !(e instanceof Player) && !(e instanceof ArmorStand)
                && !e.isSpectator() && !e.isInvisible();
    }

    public static void handleKick(KickPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (p.phase() == KickPayload.DONE) {
            TRACKS.remove(player.getUUID());
            return;
        }
        ServerLevel level = player.serverLevel();
        Entity e = level.getEntity(p.entityId());
        if (!isKickable(e)) return;
        LivingEntity mob = (LivingEntity) e;
        if (RagdollBridge.isRagdolled(mob)) return;
        double reach = KickConfig.MAX_REACH.get();
        // measured to the edge of the hitbox: wide NPCs are reachable from their side, not only from their centre
        if (mob.getBoundingBox().distanceToSqr(player.getEyePosition()) > (reach + 2.0D) * (reach + 2.0D)) return;

        if (p.phase() == KickPayload.START) {
            PENDING.put(player.getUUID(), new Pending(p.entityId(), level.getGameTime()));
            return;
        }
        Pending pend = PENDING.remove(player.getUUID());
        if (pend == null || pend.entityId() != p.entityId() || level.getGameTime() - pend.startTick() > PENDING_TIMEOUT) return;
        impact(level, player, mob, clampMul(p.launchForward()), clampMul(p.launchUp()));
    }

    private static double clampMul(float v) {
        return Float.isFinite(v) ? Math.max(0.3D, Math.min(2.5D, v)) : 1.0D;
    }

    private static void impact(ServerLevel level, ServerPlayer player, LivingEntity mob, double mulForward, double mulUp) {
        Vec3 dir = mob.position().subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
        if (dir.lengthSqr() < 1.0E-4D) dir = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        dir = dir.normalize();
        var rnd = level.getRandom();
        double hMin = KickConfig.HORIZONTAL_MIN.get(), hMax = Math.max(hMin, KickConfig.HORIZONTAL_MAX.get());
        double h = (hMin + (hMax - hMin) * rnd.nextDouble()) * mulForward;
        double v = KickConfig.VERTICAL_SPEED.get() * (0.94D + 0.12D * rnd.nextDouble()) * mulUp;
        Vec3 velocity = dir.scale(h).add(0.0D, v, 0.0D);
        // tumble backwards: the top of the body rotates along the flight direction (axis = up x dir)
        Vec3 axis = new Vec3(0.0D, 1.0D, 0.0D).cross(dir).normalize();
        Vec3 spin = axis.scale(KickConfig.SPIN.get())
                .add((rnd.nextDouble() - 0.5D) * 1.5D, (rnd.nextDouble() - 0.5D) * 1.5D, (rnd.nextDouble() - 0.5D) * 1.5D);

        level.playSound(null, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5D, mob.getZ(),
                SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.0F, 0.9F + rnd.nextFloat() * 0.2F);

        if (mob instanceof Mob m) m.getNavigation().stop();
        Vec3 origin = mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
        mob.addTag(RAGDOLL_TAG);
        boolean ragdoll = RagdollBridge.launchForever(level, mob, velocity, spin);
        if (!ragdoll) {
            mob.removeTag(RAGDOLL_TAG);
            // Sable: Ragdolls missing or refused this entity: plain knockback (blocks per tick = m/s / 20)
            mob.setDeltaMovement(velocity.scale(1.0D / 20.0D));
            mob.hurtMarked = true;
        }
        Track track = new Track(player, mob, origin);
        track.velocity = velocity;
        track.ragdollChecked = !ragdoll;
        TRACKS.put(player.getUUID(), track);
    }

    /** Ticks to wait for Sable to build the body (the client has to send the model first). */
    private static final int RAGDOLL_WAIT = 10;
    private static final int RAGDOLL_WAIT_MAX = 40;

    /**
     * Sable accepts a launch request first and builds the body when the client sends the mob's model parts.
     * Mobs whose model has no parts (custom renderers) never get a body: then fly off with plain knockback.
     */
    private static void checkRagdoll(Track t) {
        int age = TRACK_TICKS - t.ticksLeft;
        if (t.ragdollChecked || age < RAGDOLL_WAIT) return;
        if (RagdollBridge.isRagdolled(t.mob)) {
            t.ragdollChecked = true;
            return;
        }
        if (RagdollBridge.isPending(t.mob) && age < RAGDOLL_WAIT_MAX) return;
        t.ragdollChecked = true;
        if (t.mob.isRemoved() || !t.mob.isAlive()) return;
        com.mojang.logging.LogUtils.getLogger().info("[kickmod] no ragdoll body for {}, using knockback", t.mob.getName().getString());
        t.mob.removeTag(RAGDOLL_TAG);
        t.mob.setDeltaMovement(t.velocity.scale(1.0D / 20.0D));
        t.mob.hurtMarked = true;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        long now = event.getServer().overworld().getGameTime();
        PENDING.values().removeIf(p -> now - p.startTick() > PENDING_TIMEOUT * 4L);
        Iterator<Track> it = TRACKS.values().iterator();
        while (it.hasNext()) {
            Track t = it.next();
            if (--t.ticksLeft < 0 || t.player.isRemoved() || t.player.level() != t.mob.level()) {
                it.remove();
                continue;
            }
            checkRagdoll(t);
            Vec3 pos = RagdollBridge.bodyPosition(t.mob);
            // Right after the launch Sable may still report the body in its storage area far away
            // (not yet placed in the world): ignore such positions and keep the last good one.
            boolean bad = pos.distanceTo(t.last) > MAX_TRACK_STEP;
            if (t.ticksLeft >= TRACK_TICKS - 3 || bad) {
                com.mojang.logging.LogUtils.getLogger().info("[kickmod] track {}: body {} origin {} entity {}{}",
                        TRACK_TICKS - t.ticksLeft, pos, t.origin, t.mob.position(), bad ? " (ignored)" : "");
            }
            if (bad) {
                pos = t.last;
            }
            t.last = pos;
            PacketDistributor.sendToPlayer(t.player, new TrackPayload(t.mob.getId(), pos.x, pos.y, pos.z));
        }
    }

    /**
     * Sable: Ragdolls hurts ragdolled mobs on hard landings ("fly into wall"). A kicked NPC that dies loses its
     * ragdoll, and CustomNPCs then respawns it at its home. Kicked mobs lying as a ragdoll take no damage.
     */
    public static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        LivingEntity e = event.getEntity();
        if (KickConfig.PROTECT.get() && e.getTags().contains(RAGDOLL_TAG) && !e.level().isClientSide()
                && RagdollBridge.isRagdolled(e)) {
            event.setCanceled(true);
        }
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("unragdoll")
                .executes(c -> release(c, 150))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 1000))
                        .executes(c -> release(c, IntegerArgumentType.getInteger(c, "radius")))));
    }

    private static int release(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        Vec3 center = src.getPosition();
        AABB box = new AABB(center, center).inflate(radius);
        double r2 = (double) radius * radius;
        int count = 0;
        for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class, box, m -> !(m instanceof Player))) {
            if (mob.distanceToSqr(center) <= r2 && RagdollBridge.isRagdolled(mob) && RagdollBridge.release(mob)) {
                mob.removeTag(RAGDOLL_TAG);
                count++;
            }
        }
        final int n = count;
        src.sendSuccess(() -> Component.translatable("kickmod.msg.released", n), false);
        return n;
    }
}
