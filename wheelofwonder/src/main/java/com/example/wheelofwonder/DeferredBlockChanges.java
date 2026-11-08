package com.example.wheelofwonder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Block changes asked for from inside Sable's physics step (SableFragileMixin), done after the server tick instead,
 * in the same order. Doing them in the step itself deadlocks the physics engine (see the mixin).
 */
public final class DeferredBlockChanges {
    private record Change(ServerLevel level, BlockPos pos, BlockState state, boolean destroy, boolean drop) {
    }

    private static final List<Change> QUEUE = new ArrayList<>();

    private DeferredBlockChanges() {
    }

    private static boolean logged;

    public static synchronized void destroy(ServerLevel level, BlockPos pos, boolean drop) {
        if (!logged) {
            logged = true;
            org.slf4j.LoggerFactory.getLogger("wheelofwonder").info("[wheelofwonder] Sable freeze fix: a ragdoll broke a fragile block at {}; done after the physics step", pos);
        }
        if (QUEUE.size() < 4096) QUEUE.add(new Change(level, pos.immutable(), null, true, drop));
    }

    public static synchronized void set(ServerLevel level, BlockPos pos, BlockState state) {
        if (QUEUE.size() < 4096) QUEUE.add(new Change(level, pos.immutable(), state, false, false));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        List<Change> todo;
        synchronized (DeferredBlockChanges.class) {
            if (QUEUE.isEmpty()) return;
            todo = new ArrayList<>(QUEUE);
            QUEUE.clear();
        }
        for (Change c : todo) {
            try {
                if (!c.level().isLoaded(c.pos())) continue;
                if (c.destroy()) c.level().destroyBlock(c.pos(), c.drop());
                else c.level().setBlockAndUpdate(c.pos(), c.state());
            } catch (RuntimeException ignored) {
                // a block that cannot change now just stays as it is
            }
        }
    }

    public static synchronized void clear() {
        QUEUE.clear();
    }
}
