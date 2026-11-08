package com.example.huggynpc.client;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/** Remembers which living entity is being drawn right now (Custom NPCs draws Huggy inside the NPC's own render). */
final class RenderTracker {
    private static LivingEntity current;

    private RenderTracker() {
    }

    static LivingEntity current() {
        return current;
    }

    static void pre(RenderLivingEvent.Pre<?, ?> event) {
        current = event.getEntity();
    }

    static void post(RenderLivingEvent.Post<?, ?> event) {
        if (current == event.getEntity()) current = null;
    }
}
