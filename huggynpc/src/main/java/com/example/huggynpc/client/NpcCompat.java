package com.example.huggynpc.client;

import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the Custom NPCs animation mode (EntityNPCInterface.currentAnimation) without a compile dependency.
 * Values from noppes.npcs.api.constants.AnimationType.
 */
final class NpcCompat {
    static final int SIT = 1;
    static final int CROUCH = 4;
    static final int DANCE = 5;
    static final int CRAWL = 7;

    private static final Map<Class<?>, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Field NONE;

    static {
        Field f = null;
        try {
            f = NpcCompat.class.getDeclaredField("NONE");
        } catch (NoSuchFieldException ignored) {
        }
        NONE = f;
    }

    private NpcCompat() {
    }

    /** -1 when the entity is not a Custom NPC. */
    static int animation(LivingEntity e) {
        Field f = FIELDS.computeIfAbsent(e.getClass(), NpcCompat::find);
        if (f == NONE) return -1;
        try {
            return f.getInt(e);
        } catch (Exception ex) {
            return -1;
        }
    }

    private static Field find(Class<?> c) {
        for (Class<?> k = c; k != null && k != LivingEntity.class; k = k.getSuperclass()) {
            if (!k.getName().startsWith("noppes.npcs.")) continue;
            try {
                Field f = k.getDeclaredField("currentAnimation");
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        return NONE;
    }
}
