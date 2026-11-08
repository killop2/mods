package com.example.wheelofwonder;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * After the explosion: streams the position of ONE thrown ragdoll to the wheel's owner every tick, so the client
 * camera can follow it (same idea as kickmod's KickServer track).
 */
public final class FollowTracker {
    /** Up to 9 s of following (the client decides when to return, earlier if the body lies still). */
    private static final int TRACK_TICKS = 185;
    /** A real body never moves more than this in one tick; bigger jumps are Sable's storage coordinates. */
    private static final double MAX_STEP = 8.0D;

    private static final class Track {
        final ServerPlayer player;
        final LivingEntity mob;
        Vec3 last;
        int ticksLeft = TRACK_TICKS;

        Track(ServerPlayer player, LivingEntity mob, Vec3 origin) {
            this.player = player;
            this.mob = mob;
            this.last = origin;
        }
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private FollowTracker() {
    }

    /** @param origin body centre at the moment of the explosion (before Sable may move the hidden entity away) */
    static void start(ServerPlayer player, LivingEntity mob, Vec3 origin) {
        TRACKS.put(player.getUUID(), new Track(player, mob, origin));
        PacketDistributor.sendToPlayer(player, new FollowPayload(mob.getId(), origin.x, origin.y, origin.z));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Track> it = TRACKS.values().iterator();
        while (it.hasNext()) {
            Track t = it.next();
            if (--t.ticksLeft < 0 || t.player.isRemoved() || t.player.level() != t.mob.level()) {
                it.remove();
                continue;
            }
            Vec3 pos = WheelRagdolls.rootPosition(t.mob);
            if (pos == null) {
                pos = WheelRagdolls.isRagdolled(t.mob) || WheelRagdolls.isPending(t.mob) || t.mob.isRemoved()
                        ? t.last : t.mob.position().add(0.0D, t.mob.getBbHeight() * 0.5D, 0.0D);
            }
            if (pos.distanceTo(t.last) > MAX_STEP) pos = t.last;
            t.last = pos;
            PacketDistributor.sendToPlayer(t.player, new FollowPayload(t.mob.getId(), pos.x, pos.y, pos.z));
        }
    }
}
