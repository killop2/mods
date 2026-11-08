package com.example.kickmod;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * The only place that touches Sable: Ragdolls (sable_player_ragdoll), through reflection only:
 * no compile dependency, nothing of it is bundled, and if the mod is missing we just fall back to vanilla knockback.
 * Uses its public API (RagdollAPI.launchMob / isMobRagdolled / releaseMob) — the same calls Ragdoll Reactions uses,
 * so the ragdolls are ordinary Sable ragdolls that the other mods understand.
 */
public final class RagdollBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API = "dev.leo.sableplayerragdoll.api.RagdollAPI";
    private static final String ASSEMBLY = "dev.leo.sableplayerragdoll.mob.MobRagdollAssembly";
    /** "Never gets up by itself" (~850 years of game time; same value the flatoutcar corpses use). Ended by /unragdoll. */
    private static final int FOREVER_TICKS = Integer.MAX_VALUE / 4;

    private static boolean resolved;
    private static Method launchMob;
    private static Constructor<?> optionsCtor;
    private static Method isMobRagdolled;
    private static Method releaseMob;
    private static Map<?, ?> states;
    private static Method rootPosition;
    private static Method hasPendingLaunch;

    private RagdollBridge() {
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> api = Class.forName(API);
            for (Method m : api.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                switch (m.getName()) {
                    case "launchMob" -> {
                        if (p.length == 5 && p[2] == Vec3.class && p[3] == Vec3.class) {
                            launchMob = m;
                            optionsCtor = p[4].getConstructor(int.class);
                        }
                    }
                    case "isMobRagdolled" -> {
                        if (p.length == 1) isMobRagdolled = m;
                    }
                    case "releaseMob" -> {
                        if (p.length == 1) releaseMob = m;
                    }
                    default -> {
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[kickmod] Sable: Ragdolls API not available, kicks will use vanilla knockback ({})", t.toString());
        }
        try {
            Class<?> asm = Class.forName(ASSEMBLY);
            Field f = asm.getDeclaredField("RAGDOLL_STATES");
            f.setAccessible(true);
            states = (Map<?, ?>) f.get(null);
            for (Method m : asm.getDeclaredMethods()) {
                if (m.getName().equals("rootPosition") && m.getParameterCount() == 1 && m.getReturnType() == Vec3.class) {
                    m.setAccessible(true);
                    rootPosition = m;
                }
                if (m.getName().equals("hasPendingLaunch") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == java.util.UUID.class) {
                    m.setAccessible(true);
                    hasPendingLaunch = m;
                }
            }
        } catch (Throwable t) {
            LOGGER.info("[kickmod] ragdoll position lookup unavailable, camera will follow the entity ({})", t.toString());
        }
        LOGGER.info("[kickmod] ragdoll bridge: launch={}, release={}, track={}", launchMob != null, releaseMob != null, rootPosition != null);
    }

    public static boolean isRagdolled(LivingEntity mob) {
        resolve();
        if (isMobRagdolled == null) return false;
        try {
            return Boolean.TRUE.equals(isMobRagdolled.invoke(null, mob));
        } catch (Throwable t) {
            return false;
        }
    }

    /** True while Sable is still waiting for the client to send the mob's model. */
    public static boolean isPending(LivingEntity mob) {
        resolve();
        if (hasPendingLaunch == null) return false;
        try {
            return Boolean.TRUE.equals(hasPendingLaunch.invoke(null, mob.getUUID()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ragdoll that does not get up by itself. @return true if the mob is now a ragdoll (or its body is being built). */
    public static boolean launchForever(ServerLevel level, LivingEntity mob, Vec3 velocity, Vec3 spin) {
        resolve();
        if (launchMob == null) return false;
        if (isRagdolled(mob)) return true;
        try {
            Object options = optionsCtor.newInstance(FOREVER_TICKS);
            Object session = launchMob.invoke(null, level, mob, velocity, spin, options);
            return session != null || isRagdolled(mob);
        } catch (Throwable t) {
            LOGGER.warn("[kickmod] ragdoll launch failed for {}", mob, t);
            return false;
        }
    }

    /** Ends the ragdoll the normal Sable way: the body disappears, the mob reappears where it landed and stands up. */
    public static boolean release(LivingEntity mob) {
        resolve();
        if (releaseMob == null) return false;
        try {
            releaseMob.invoke(null, mob);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[kickmod] ragdoll release failed for {}", mob, t);
            return false;
        }
    }

    /** World position of the ragdoll body, or the entity position if it is not (yet) a ragdoll. */
    public static Vec3 bodyPosition(LivingEntity mob) {
        resolve();
        if (states != null && rootPosition != null) {
            try {
                Object state = states.get(mob.getUUID());
                if (state != null) {
                    Object v = rootPosition.invoke(null, state);
                    if (v instanceof Vec3 pos) return pos;
                }
            } catch (Throwable ignored) {
            }
        }
        return mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
    }
}
