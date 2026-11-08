package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Trident ("Тризуб!"), server side: the right click summons the ghost ship (GhostShipEntity plays the act); once the
 * target is captured its body is reported to the caster every tick for the camera - on the ship, over the stern and
 * all the way down - until the camera is back (Ctrl).
 */
public final class TrizubServer {
    public static final double RANGE = 48.0D;
    private static final int MAX_TRACK = 20 * 60;
    private static final double MAX_STEP = 8.0D;

    private static final class Track {
        final ServerPlayer player;
        final LivingEntity mob;
        Vec3 last;
        int age;
        boolean done, falling;

        Track(ServerPlayer player, LivingEntity mob, Vec3 at) {
            this.player = player;
            this.mob = mob;
            this.last = at;
        }
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    /** Players with a ship out (one at a time). */
    private static final Map<UUID, Integer> SHIPS = new HashMap<>();

    private TrizubServer() {
    }

    public static void handleRequest(TrizubRequest req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        if (req.action() == TrizubRequest.START) {
            start(p, req.entityId());
        } else if (req.action() == TrizubRequest.DONE) {
            Track t = TRACKS.get(p.getUUID());
            if (t != null) t.done = true;
        }
    }

    private static void start(ServerPlayer p, int id) {
        ServerLevel level = p.serverLevel();
        Entity e = level.getEntity(id);
        var item = p.getMainHandItem();
        // a ship that vanished some other way (unloaded) no longer blocks a new one
        Integer old = SHIPS.get(p.getUUID());
        if (old != null && !(level.getEntity(old) instanceof GhostShipEntity)) SHIPS.remove(p.getUUID());
        if (SHIPS.containsKey(p.getUUID()) || !TrizubItem.isTrizub(item) || p.getCooldowns().isOnCooldown(item.getItem())
                || !BallistaServer.loadable(e) || e.distanceTo(p) > RANGE + 2.0D || WheelMod.held(e)) {
            send(p, TrizubState.END, -1, id, p.position());
            return;
        }
        LivingEntity target = (LivingEntity) e;
        Vec3 dir = target.position().subtract(p.position()).multiply(1.0D, 0.0D, 1.0D);
        dir = dir.lengthSqr() < 1.0E-4D ? Vec3.directionFromRotation(0.0F, p.getYRot()) : dir.normalize();
        GhostShipEntity ship = new GhostShipEntity(WheelMod.GHOST_SHIP.get(), level);
        ship.setup(p, target, dir);
        level.addFreshEntity(ship);
        SHIPS.put(p.getUUID(), ship.getId());
        p.getCooldowns().addCooldown(item.getItem(), 20 * 30);
        TRACKS.remove(p.getUUID());
        send(p, TrizubState.START, ship.getId(), id, target.position());
        // the hero calls on the sea
        Vec3 c = p.position().add(0.0D, 1.2D, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.PLAYERS, 1.2F, 0.6F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.0F, 0.6F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.CONDUIT_ACTIVATE, SoundSource.PLAYERS, 1.0F, 0.7F);
        level.sendParticles(ParticleTypes.NAUTILUS, c.x, c.y + 0.5D, c.z, 40, 0.5D, 0.6D, 0.5D, 0.5D);
        level.sendParticles(ParticleTypes.SOUL, c.x, c.y, c.z, 12, 0.6D, 0.6D, 0.6D, 0.02D);
    }

    /** The crew have the target: the camera can follow its body now. */
    static void capture(ServerPlayer p, LivingEntity mob, Vec3 at) {
        TRACKS.put(p.getUUID(), new Track(p, mob, at));
        send(p, TrizubState.CAPTURE, SHIPS.getOrDefault(p.getUUID(), -1), mob.getId(), at);
    }

    /** The target got away before the crew had it (died, or another weapon took it). */
    static void lost(ServerPlayer p) {
        send(p, TrizubState.END, -1, -1, p.position());
    }

    /** Pushed over the stern: from now on it is a ragdoll in free fall (it keeps being tracked). */
    static void pushed(LivingEntity mob) {
        for (Track t : TRACKS.values()) if (t.mob == mob) t.falling = true;
    }

    /** The ship has melted into the fog. */
    static void shipGone(GhostShipEntity ship, ServerLevel level) {
        Vec3 c = ship.position().add(0.0D, 3.0D, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 0.8F, 0.5F);
        level.sendParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 60, 4.0D, 2.0D, 4.0D, 0.03D);
        level.sendParticles(ParticleTypes.SOUL, c.x, c.y, c.z, 20, 3.0D, 2.0D, 3.0D, 0.02D);
        Iterator<Map.Entry<UUID, Integer>> it = SHIPS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> en = it.next();
            if (en.getValue() != ship.getId()) continue;
            it.remove();
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(en.getKey());
            if (p != null) {
                var item = WheelMod.TRIZUB_ITEM.get();
                p.getCooldowns().removeCooldown(item);
                p.getCooldowns().addCooldown(item, 60);
            }
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Track> it = TRACKS.values().iterator();
        while (it.hasNext()) {
            Track t = it.next();
            t.age++;
            ServerPlayer p = t.player;
            LivingEntity mob = t.mob;
            if (t.done || p.isRemoved() || mob.isRemoved() || t.age > MAX_TRACK || mob.level() != p.level()) {
                if (!t.done && !p.isRemoved()) send(p, TrizubState.END, -1, mob.getId(), t.last);
                it.remove();
                continue;
            }
            Vec3 pos = WheelRagdolls.rootPosition(mob);
            if (pos == null) pos = WheelRagdolls.isRagdolled(mob) ? t.last : mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
            if (pos.distanceTo(t.last) > MAX_STEP) pos = t.last;
            t.last = pos;
            send(p, TrizubState.TRACK, -1, mob.getId(), pos);
            if (t.age % 5 == 0) WheelMod.keepLoaded((ServerLevel) mob.level(), pos);
            if (t.falling && t.age % 2 == 0) {
                ((ServerLevel) mob.level()).sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.x, pos.y, pos.z, 1, 0.1D, 0.1D, 0.1D, 0.0D);
            }
        }
    }

    public static void onServerStopping() {
        TRACKS.clear();
        SHIPS.clear();
    }

    private static void send(ServerPlayer p, int phase, int ship, int id, Vec3 pos) {
        PacketDistributor.sendToPlayer(p, new TrizubState(phase, ship, id, pos.x, pos.y, pos.z));
    }
}
