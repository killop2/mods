package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Strange's Hammer, server side: the right click summons the sorcerer (StrangeEntity plays the act); once the hammer
 * has struck, the flying body is reported to the caster every tick for the camera until the camera is back (Ctrl).
 * While it flies it throws the NPCs it runs into as ragdolls and breaks leaves, trees, ice and glass on its way.
 */
public final class StrangeServer {
    public static final double RANGE = 48.0D;
    private static final int MAX_TRACK = 20 * 60, FLY_HITS = 70;
    private static final double MAX_STEP = 8.0D;

    private static final class Track {
        final ServerPlayer player;
        final LivingEntity mob;
        Vec3 last;
        int age;
        boolean done;

        Track(ServerPlayer player, LivingEntity mob, Vec3 at) {
            this.player = player;
            this.mob = mob;
            this.last = at;
        }
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    /** Players with a sorcerer out (one at a time). */
    private static final Map<UUID, Integer> ACTS = new HashMap<>();

    private StrangeServer() {
    }

    public static void handleRequest(StrangeRequest req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        if (req.action() == StrangeRequest.START) {
            start(p, req.entityId(), req.side());
        } else if (req.action() == StrangeRequest.DONE) {
            Track t = TRACKS.get(p.getUUID());
            if (t != null) t.done = true;
        }
    }

    private static void start(ServerPlayer p, int id, int side) {
        ServerLevel level = p.serverLevel();
        Entity e = level.getEntity(id);
        var item = p.getMainHandItem();
        Integer old = ACTS.get(p.getUUID());
        if (old != null && !(level.getEntity(old) instanceof StrangeEntity)) ACTS.remove(p.getUUID());
        if (ACTS.containsKey(p.getUUID()) || !StrangeHammerItem.isHammer(item) || p.getCooldowns().isOnCooldown(item.getItem())
                || !BallistaServer.loadable(e) || e.distanceTo(p) > RANGE + 2.0D || WheelMod.held(e)) {
            send(p, StrangeState.END, -1, id, p.position());
            return;
        }
        LivingEntity target = (LivingEntity) e;
        Vec3 dir = target.position().subtract(p.position()).multiply(1.0D, 0.0D, 1.0D);
        dir = dir.lengthSqr() < 1.0E-4D ? Vec3.directionFromRotation(0.0F, p.getYRot()) : dir.normalize();
        StrangeEntity act = new StrangeEntity(WheelMod.STRANGE.get(), level);
        act.setup(p, target, dir, side);
        level.addFreshEntity(act);
        ACTS.put(p.getUUID(), act.getId());
        p.getCooldowns().addCooldown(item.getItem(), 20 * 20);
        TRACKS.remove(p.getUUID());
        send(p, StrangeState.START, act.getId(), id, target.position());
        // the hero raises the hammer: a ring of sparks
        Vec3 c = p.position().add(0.0D, 1.2D, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.PLAYERS, 1.2F, 1.0F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0F, 1.5F);
        level.sendParticles(WheelEntity.dust(0xFF8A1E, 1.4F), c.x, c.y + 0.5D, c.z, 30, 0.6D, 0.6D, 0.6D, 0.0D);
        level.sendParticles(ParticleTypes.ENCHANT, c.x, c.y, c.z, 30, 0.6D, 0.6D, 0.6D, 0.5D);
    }

    /** The hammer struck: the camera can follow the flying body now. */
    static void launched(ServerPlayer p, LivingEntity mob, Vec3 at) {
        TRACKS.put(p.getUUID(), new Track(p, mob, at));
        send(p, StrangeState.CAPTURE, ACTS.getOrDefault(p.getUUID(), -1), mob.getId(), at);
    }

    /** The target got away before the blow (died, or another weapon took it). */
    static void lost(ServerPlayer p) {
        send(p, StrangeState.END, -1, -1, p.position());
    }

    /** The sorcerer is gone. */
    static void gone(StrangeEntity act, ServerLevel level) {
        Iterator<Map.Entry<UUID, Integer>> it = ACTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> en = it.next();
            if (en.getValue() != act.getId()) continue;
            it.remove();
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(en.getKey());
            if (p != null) {
                var item = WheelMod.STRANGE_HAMMER_ITEM.get();
                p.getCooldowns().removeCooldown(item);
                p.getCooldowns().addCooldown(item, 40);
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
                if (!t.done && !p.isRemoved()) send(p, StrangeState.END, -1, mob.getId(), t.last);
                it.remove();
                continue;
            }
            Vec3 pos = WheelRagdolls.rootPosition(mob);
            if (pos == null) pos = WheelRagdolls.isRagdolled(mob) ? t.last : mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
            if (pos.distanceTo(t.last) > MAX_STEP) pos = t.last;
            Vec3 vel = pos.subtract(t.last).scale(20.0D);
            t.last = pos;
            send(p, StrangeState.TRACK, -1, mob.getId(), pos);
            ServerLevel level = (ServerLevel) mob.level();
            if (t.age % 5 == 0) WheelMod.keepLoaded(level, pos);
            if (t.age < FLY_HITS && vel.length() > 4.0D) {
                // what the flying body runs into
                StrangeEntity.smashSphere(level, pos, 1.2D, mob);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(1.8D), StrangeEntity::rammable)) {
                    if (e == mob || e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D).distanceTo(pos) > 1.8D) continue;
                    StrangeEntity.knock(level, e, vel.scale(0.8D).add(0.0D, 6.0D, 0.0D));
                }
            }
            if (t.age < 50) {
                level.sendParticles(WheelEntity.dust(t.age % 2 == 0 ? 0xFF8A1E : 0xFFD060, 1.3F), pos.x, pos.y, pos.z, 2, 0.15D, 0.15D, 0.15D, 0.0D);
                if (t.age % 3 == 0) level.sendParticles(ParticleTypes.SMALL_FLAME, pos.x, pos.y, pos.z, 1, 0.1D, 0.1D, 0.1D, 0.0D);
            }
        }
    }

    public static void onServerStopping() {
        TRACKS.clear();
        ACTS.clear();
    }

    private static void send(ServerPlayer p, int phase, int act, int id, Vec3 pos) {
        PacketDistributor.sendToPlayer(p, new StrangeState(phase, act, id, pos.x, pos.y, pos.z));
    }
}
