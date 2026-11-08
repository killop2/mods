package com.example.huggynpc;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client settings (config/huggynpc-client.toml), read every frame, so edits apply without a restart. */
public final class HuggyConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue WALK_SPEED_REF;
    public static final ModConfigSpec.DoubleValue RUN_THRESHOLD;
    public static final ModConfigSpec.DoubleValue RUN_SPEED_REF;
    public static final ModConfigSpec.DoubleValue ATTACK_RATE;
    public static final ModConfigSpec.BooleanValue HEAD_TRACKING;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Animation choice and playback speed. Speeds are in blocks per tick (0.1 = 2 m/s).").push("animation");
        WALK_SPEED_REF = b.comment("Speed at which the walk animation plays at normal rate (faster NPCs play it faster).")
                .defineInRange("walkSpeedRef", 0.09, 0.01, 1.0);
        RUN_THRESHOLD = b.comment("Above this speed the run animation is used instead of walk.")
                .defineInRange("runThreshold", 0.17, 0.01, 2.0);
        RUN_SPEED_REF = b.comment("Speed at which the run animation plays at normal rate.")
                .defineInRange("runSpeedRef", 0.30, 0.01, 2.0);
        ATTACK_RATE = b.comment("Playback rate of the attack animation (1.0 = 1.3 s per hit).")
                .defineInRange("attackRate", 1.3, 0.2, 5.0);
        HEAD_TRACKING = b.comment("Turn the head towards where the NPC is looking.")
                .define("headTracking", true);
        b.pop();
        SPEC = b.build();
    }

    private HuggyConfig() {
    }
}
