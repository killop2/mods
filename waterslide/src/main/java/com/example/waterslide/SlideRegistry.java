package com.example.waterslide;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Slides currently known on each side (client / server). Lookups are a few AABB checks: slides are rare. */
public final class SlideRegistry {
    private static final List<SlideInstance> CLIENT = new CopyOnWriteArrayList<>();
    private static final List<SlideInstance> SERVER = new CopyOnWriteArrayList<>();

    private SlideRegistry() {
    }

    public static List<SlideInstance> list(boolean client) {
        return client ? CLIENT : SERVER;
    }

    public static SlideInstance get(boolean client, ResourceKey<Level> dim, BlockPos anchor) {
        for (SlideInstance s : list(client)) {
            if (SlideInstance.sameSlide(s, dim, anchor)) return s;
        }
        return null;
    }

    public static synchronized SlideInstance register(boolean client, ResourceKey<Level> dim, BlockPos anchor, Direction facing, int type) {
        SlideInstance s = get(client, dim, anchor);
        if (s != null && s.facing == facing && s.type == type) return s;
        if (s != null) list(client).remove(s);
        s = new SlideInstance(dim, anchor, facing, type);
        list(client).add(s);
        return s;
    }

    public static void add(boolean client, SlideInstance s) {
        if (get(client, s.dim, s.anchor) == null) list(client).add(s);
    }

    public static void unregister(boolean client, ResourceKey<Level> dim, BlockPos anchor) {
        list(client).removeIf(s -> SlideInstance.sameSlide(s, dim, anchor));
    }

    public static void clear(boolean client) {
        list(client).clear();
    }

    public static SlideInstance find(boolean client, ResourceKey<Level> dim, BlockPos pos) {
        for (SlideInstance s : list(client)) {
            if (s.dim.equals(dim) && s.box.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) && s.contains(pos)) return s;
        }
        return null;
    }

    /** For block shape queries: the getter may be a chunk cache or a physics-engine view rather than a Level. */
    public static SlideInstance lookup(BlockGetter getter, BlockPos pos) {
        if (getter instanceof Level level) return find(level.isClientSide(), level.dimension(), pos);
        for (SlideInstance s : SERVER) if (s.contains(pos)) return s;
        for (SlideInstance s : CLIENT) if (s.contains(pos)) return s;
        return null;
    }
}
