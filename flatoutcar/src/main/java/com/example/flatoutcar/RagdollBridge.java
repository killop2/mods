package com.example.flatoutcar;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Единственное место, где мы касаемся Sable: Ragdolls.
 * Вызывается RagdollAPI.launch(ServerPlayer, velocity) через reflection (скорость в м/с),
 * поэтому у этого мода НЕТ compile-зависимости от ragdoll-модов и нет конфликтов по репозиториям.
 * Длительность/правила рэгдолла берутся из конфига самого Sable: Ragdolls.
 */
public final class RagdollBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API_CLASS = "dev.leo.sableplayerragdoll.api.RagdollAPI";

    private static boolean resolved;
    private static Method launchMethod;
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
                if (!m.getName().equals("launch") || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 2) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (!p[0].isAssignableFrom(ServerPlayer.class)) {
                    continue;
                }
                if (p[1].isAssignableFrom(Vec3.class) || p[1].getName().startsWith("org.joml.Vector3d")) {
                    launchMethod = m;
                    velocityType = p[1];
                    break;
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

    /** @param velocityMps скорость в метрах (блоках) в секунду */
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
            launchMethod.invoke(null, player, arg);
            return true;
        } catch (Throwable t) {
            LOGGER.error("RagdollAPI.launch failed", t);
            return false;
        }
    }
}
