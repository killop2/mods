package com.example.flatoutcar;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Серверный конфиг (world/serverconfig/flatoutcar-server.toml, синхронизируется клиентам).
 * Скорость вылета = база + коэффициент * скорость машины (всё в м/с = блоках в секунду).
 * Поставьте коэффициенты в 0, чтобы вылет всегда был с фиксированной скоростью.
 */
public final class CarConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue EJECT_FORWARD_BASE;
    public static final ModConfigSpec.DoubleValue EJECT_FORWARD_PER_SPEED;
    public static final ModConfigSpec.DoubleValue EJECT_UP_BASE;
    public static final ModConfigSpec.DoubleValue EJECT_UP_PER_SPEED;
    public static final ModConfigSpec.DoubleValue EJECT_MAX_SPEED;
    public static final ModConfigSpec.BooleanValue CRASH_EJECT;
    public static final ModConfigSpec.DoubleValue CRASH_MIN_SPEED_KMH;
    public static final ModConfigSpec.BooleanValue MOBS_ENABLED;
    public static final ModConfigSpec.DoubleValue MOBS_MIN_SPEED_KMH;
    public static final ModConfigSpec.DoubleValue MOBS_LAUNCH_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue MOBS_KILL_IF_NO_RAGDOLL;
    public static final ModConfigSpec.BooleanValue MOBS_CORPSES;
    public static final ModConfigSpec.IntValue MOBS_CORPSE_LIFETIME;
    public static final ModConfigSpec.IntValue MOBS_MAX_CORPSES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Ragdoll launch when ejecting (G key or crash). Units: m/s (= blocks per second).").push("eject");
        EJECT_FORWARD_BASE = b.comment("Forward launch speed even when the car stands still.")
                .defineInRange("forwardBase", 14.0D, 0.0D, 128.0D);
        EJECT_FORWARD_PER_SPEED = b.comment("Extra forward speed per 1 m/s of car speed (car top speed is 32 m/s).")
                .defineInRange("forwardPerCarSpeed", 0.9D, 0.0D, 10.0D);
        // Высота полёта ~ (скорость вверх)^2, поэтому для удвоения высоты скорость вверх умножена на sqrt(2):
        // 13 -> 18.4 и 0.3 -> 0.42.
        EJECT_UP_BASE = b.comment("Upward launch speed even when the car stands still. Flight height grows with the square of it.")
                .defineInRange("upBase", 18.4D, 0.0D, 128.0D);
        EJECT_UP_PER_SPEED = b.comment("Extra upward speed per 1 m/s of car speed.")
                .defineInRange("upPerCarSpeed", 0.42D, 0.0D, 10.0D);
        EJECT_MAX_SPEED = b.comment("Upper limit for the total launch speed.")
                .defineInRange("maxLaunchSpeed", 60.0D, 1.0D, 128.0D);
        b.pop();

        b.comment("Automatic eject when the car hits a block or an entity.").push("crash");
        CRASH_EJECT = b.comment("Eject the driver as a ragdoll on a hard crash.")
                .define("enabled", true);
        CRASH_MIN_SPEED_KMH = b.comment("Minimum car speed (km/h) at the moment of impact. Car top speed is ~115 km/h.")
                .defineInRange("minSpeedKmh", 45.0D, 5.0D, 200.0D);
        b.pop();

        b.comment("Running over mobs, NPCs and other players.",
                "Hit mobs become ragdolls first (via Ragdoll Reactions / Sable: Ragdolls); if a mob cannot ragdoll, it dies.",
                "Launch speed follows an inelastic impact: light mobs fly fast, heavy ones (iron golem, ravager) barely move",
                "and stop the car like a wall.").push("mobs");
        MOBS_ENABLED = b.comment("Enable hitting mobs/NPCs/players with the car.")
                .define("enabled", true);
        MOBS_MIN_SPEED_KMH = b.comment("Minimum car speed (km/h) for a hit to launch a mob.")
                .defineInRange("minSpeedKmh", 12.0D, 1.0D, 200.0D);
        MOBS_LAUNCH_MULTIPLIER = b.comment("Multiplier for mob launch speed (1.0 = physics estimate).")
                .defineInRange("launchMultiplier", 1.0D, 0.0D, 10.0D);
        MOBS_KILL_IF_NO_RAGDOLL = b.comment("Kill mobs that cannot become a ragdoll. If false, they only get speed-based damage.")
                .define("killIfNoRagdoll", true);
        MOBS_CORPSES = b.comment("Hit mobs stay lying on the ground as ragdoll corpses and never get up.",
                        "If false, they get up again when the ragdoll settles (Ragdoll Reactions behaviour).")
                .define("corpses", true);
        MOBS_CORPSE_LIFETIME = b.comment("How long a corpse stays, in seconds. 0 = forever (costs physics performance over time).")
                .defineInRange("corpseLifetimeSeconds", 600, 0, 86400);
        MOBS_MAX_CORPSES = b.comment("Maximum corpses per dimension; the oldest disappears first. Each corpse is a physics body.")
                .defineInRange("maxCorpses", 40, 1, 1000);
        b.pop();
        SPEC = b.build();
    }

    public static boolean mobsEnabled() {
        try {
            return MOBS_ENABLED.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Порог скорости наезда в блоках за тик; безопасно вызывать до загрузки конфига. */
    public static float mobMinSpeedPerTick() {
        try {
            return (float) (MOBS_MIN_SPEED_KMH.get() / 3.6D / 20.0D);
        } catch (IllegalStateException e) {
            return 12.0F / 3.6F / 20.0F;
        }
    }

    private CarConfig() {
    }

    /** Порог скорости столкновения в блоках за тик; безопасно вызывать до загрузки конфига. */
    public static float crashMinSpeedPerTick() {
        try {
            return (float) (CRASH_MIN_SPEED_KMH.get() / 3.6D / 20.0D);
        } catch (IllegalStateException e) {
            return 45.0F / 3.6F / 20.0F;
        }
    }

    public static boolean crashEjectEnabled() {
        try {
            return CRASH_EJECT.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }
}
