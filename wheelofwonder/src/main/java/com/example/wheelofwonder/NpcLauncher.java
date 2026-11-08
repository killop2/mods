package com.example.wheelofwonder;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** A Dark Carnival ride an NPC can be loaded into with the load key (ballista, rocket ride). */
public interface NpcLauncher {
    /** Free to take an NPC (works on both sides: uses the synced state). */
    boolean isIdle();

    /** An NPC this close can be loaded (blocks; server config, synced to clients). */
    double loadRadius();

    /** Server: the NPC gets in; the ride does the rest by itself. */
    void load(ServerPlayer player, LivingEntity mob);
}
