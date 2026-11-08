package com.example.flatoutcar;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Единственное место, где мы касаемся Sable: Ragdolls.
 * Вызывается RagdollAPI.launch(ServerPlayer, velocity) через reflection (скорость в м/с),
 * поэтому у этого мода НЕТ compile-зависимости от ragdoll-модов и нет конфликтов по репозиториям.
 * Перед запуском проверяется RagdollAPI.isRagdolled, чтобы не запускать второй рэгдолл,
 * если игрока уже уронил другой мод (например, Ragdoll Reactions при ударе).
 * Физику полёта считает сам Sable — мы задаём только начальную скорость.
 */
public final class RagdollBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API_CLASS = "dev.leo.sableplayerragdoll.api.RagdollAPI";

    private static boolean resolved;
    private static Method launchMethod;
    private static Method isRagdolledMethod;
    private static Class<?> velocityType;

    private RagdollBridge() {
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> api = Class.forName(API_CLASS);
            for (Method m : api.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("isRagdolled") && p.length == 1 && p[0].isAssignableFrom(ServerPlayer.class)) {
                    isRagdolledMethod = m;
                }
                if (launchMethod == null && m.getName().equals("launch") && p.length == 2
                        && p[0].isAssignableFrom(ServerPlayer.class)
                        && (p[1].isAssignableFrom(Vec3.class) || p[1].getName().startsWith("org.joml.Vector3d"))) {
                    launchMethod = m;
                    velocityType = p[1];
                }
            }
            if (launchMethod == null) {
                LOGGER.warn("RagdollAPI found, but no launch(ServerPlayer, velocity) overload matched.");
            } else {
                LOGGER.info("Ragdoll bridge ready: {}", launchMethod);
            }
        } catch (ClassNotFoundException e) {
            LOGGER.warn("Sable: Ragdolls API not present ({}).", API_CLASS);
        } catch (Throwable t) {
            LOGGER.error("Failed to resolve RagdollAPI", t);
        }
    }

    public static boolean isAvailable() {
        resolve();
        return launchMethod != null;
    }

    /** true, если игрок уже в рэгдолле (свой или чужой мод). */
    public static boolean isRagdolled(ServerPlayer player) {
        resolve();
        if (isRagdolledMethod == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isRagdolledMethod.invoke(null, player));
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- мобы и NPC ----------

    private static final String REACTIONS_CLASS = "dev.leo.ragdollreactions.physics.ReactionMobLauncher";
    /** ReactionMobLauncher умножает скорость на 0.5 (MOB_LAUNCH_SCALE) — компенсируем, чтобы задавать реальные м/с. */
    private static final double REACTIONS_SCALE_FIX = 2.0D;

    private static boolean mobResolved;
    private static Method reactionsCanTarget;
    private static Method reactionsLaunch;
    private static Method apiLaunchMob;
    private static Method apiLaunchMobOptions;
    private static Constructor<?> launchOptionsCtor;
    private static Method apiIsMobRagdolled;
    /** Длительность «вечного» рэгдолла трупа: ~10 лет игрового времени. Убираем труп сами (CorpseManager). */
    private static final int CORPSE_RAGDOLL_TICKS = Integer.MAX_VALUE / 4;
    /** Случайное вращение тела при ударе, рад/с (как у Ragdoll Reactions). */
    private static final double RANDOM_SPIN = 5.0D;

    private static synchronized void resolveMobs() {
        if (mobResolved) {
            return;
        }
        mobResolved = true;
        try {
            Class<?> api = Class.forName(API_CLASS);
            for (Method m : api.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("isMobRagdolled") && p.length == 1) {
                    apiIsMobRagdolled = m;
                }
                if (m.getName().equals("launchMob") && p.length == 3 && p[2] == Vec3.class) {
                    apiLaunchMob = m;
                }
                if (m.getName().equals("launchMob") && p.length == 5 && p[2] == Vec3.class && p[3] == Vec3.class) {
                    apiLaunchMobOptions = m;
                    launchOptionsCtor = p[4].getConstructor(int.class);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> r = Class.forName(REACTIONS_CLASS);
            for (Method m : r.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("canTarget") && p.length == 2) {
                    reactionsCanTarget = m;
                }
                if (m.getName().equals("launch") && p.length == 4 && p[3].getName().equals("org.joml.Vector3d")) {
                    reactionsLaunch = m;
                }
            }
        } catch (Throwable ignored) {
        }
        LOGGER.info("Mob ragdoll bridge: ragdoll_reactions={}, sable launchMob={}", reactionsLaunch != null, apiLaunchMob != null);
    }

    public static boolean isMobRagdolled(LivingEntity mob) {
        resolveMobs();
        if (apiIsMobRagdolled == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(apiIsMobRagdolled.invoke(null, mob));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Переводит моба/NPC в рэгдолл со скоростью velocityMps (м/с).
     * Сначала через Ragdoll Reactions (он сам поднимает моба, когда тот успокоится, и держит кулдаун),
     * иначе напрямую через Sable: Ragdolls. @return true, если моб стал рэгдоллом.
     */
    public static boolean launchMob(ServerLevel level, LivingEntity mob, Vec3 velocityMps) {
        resolveMobs();
        if (isMobRagdolled(mob)) {
            return true;
        }
        try {
            if (reactionsLaunch != null) {
                long time = level.getGameTime();
                boolean can = reactionsCanTarget == null || Boolean.TRUE.equals(reactionsCanTarget.invoke(null, mob, time));
                if (can) {
                    Vec3 v = velocityMps.scale(REACTIONS_SCALE_FIX);
                    Object res = reactionsLaunch.invoke(null, level, mob, time, new Vector3d(v.x, v.y, v.z));
                    if (res != null) {
                        return true;
                    }
                }
            }
            if (apiLaunchMob != null) {
                Object session = apiLaunchMob.invoke(null, level, mob, velocityMps);
                if (session != null) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Mob ragdoll launch failed for {}", mob, t);
        }
        return isMobRagdolled(mob);
    }

    /**
     * Завершает рэгдолл моба так же, как его отпускает сам Sable (причина RELEASED): тело исчезает,
     * моб снова появляется на его месте и встаёт. @return true, если вызов удался.
     */
    public static boolean releaseMob(ServerLevel level, LivingEntity mob) {
        try {
            Class<?> asm = Class.forName("dev.leo.sableplayerragdoll.mob.MobRagdollAssembly");
            Class<?> reason = Class.forName("dev.leo.sableplayerragdoll.mob.api.MobRagdollEndEvent$Reason");
            Object released = reason.getField("RELEASED").get(null);
            Method despawn = asm.getMethod("despawn", ServerLevel.class, LivingEntity.class, reason);
            despawn.invoke(null, level, mob, released);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Could not release mob ragdoll for {}", mob, t);
            return false;
        }
    }

    /**
     * Рэгдолл-труп: тот же запуск через Sable: Ragdolls, но без срока окончания — моб не встаёт.
     * @return true, если моб стал рэгдоллом.
     */
    public static boolean launchMobCorpse(ServerLevel level, LivingEntity mob, Vec3 velocityMps) {
        resolveMobs();
        if (isMobRagdolled(mob)) {
            return true;
        }
        if (apiLaunchMobOptions == null || launchOptionsCtor == null) {
            return false;
        }
        try {
            var rnd = level.getRandom();
            Vec3 spin = new Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize()
                    .scale(RANDOM_SPIN * (0.5D + rnd.nextDouble()));
            Object options = launchOptionsCtor.newInstance(CORPSE_RAGDOLL_TICKS);
            Object session = apiLaunchMobOptions.invoke(null, level, mob, velocityMps, spin, options);
            return session != null || isMobRagdolled(mob);
        } catch (Throwable t) {
            LOGGER.debug("Corpse ragdoll launch failed for {}", mob, t);
            return false;
        }
    }

    /** @param velocityMps скорость в метрах (блоках) в секунду. @return true, если рэгдолл запущен. */
    public static boolean launch(ServerPlayer player, Vec3 velocityMps) {
        resolve();
        if (launchMethod == null) {
            return false;
        }
        try {
            Object arg = velocityMps;
            if (!velocityType.isAssignableFrom(Vec3.class)) {
                Class<?> joml = Class.forName("org.joml.Vector3d");
                Constructor<?> c = joml.getConstructor(double.class, double.class, double.class);
                arg = c.newInstance(velocityMps.x, velocityMps.y, velocityMps.z);
            }
            Object session = launchMethod.invoke(null, player, arg);
            return session != null || isRagdolled(player);
        } catch (Throwable t) {
            LOGGER.error("RagdollAPI.launch failed", t);
            return false;
        }
    }
}
