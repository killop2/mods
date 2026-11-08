package com.example.kickmod;

import net.neoforged.neoforge.common.ModConfigSpec;

/** config/kickmod-server.toml */
public final class KickConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue HORIZONTAL_MIN;
    public static final ModConfigSpec.DoubleValue HORIZONTAL_MAX;
    public static final ModConfigSpec.DoubleValue VERTICAL_SPEED;
    public static final ModConfigSpec.DoubleValue SPIN;
    public static final ModConfigSpec.DoubleValue MAX_REACH;
    public static final ModConfigSpec.DoubleValue JOINT_STIFFNESS;
    public static final ModConfigSpec.DoubleValue JOINT_DAMPING;
    public static final ModConfigSpec.BooleanValue PROTECT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        HORIZONTAL_MIN = b.comment("Horizontal launch speed of the kicked mob, m/s: random between min and max",
                "(each animation multiplies it by its own launch.forward from config/kickmod/animations.json)")
                .defineInRange("horizontalSpeedMin", 5.2D, 0.0D, 60.0D);
        HORIZONTAL_MAX = b.defineInRange("horizontalSpeedMax", 7.8D, 0.0D, 60.0D);
        VERTICAL_SPEED = b.comment("Upward launch speed, m/s (times the animation's launch.up). Flight height grows with its square.")
                .defineInRange("verticalSpeed", 5.5D, 0.0D, 40.0D);
        SPIN = b.comment("Backward tumble of the ragdoll, rad/s").defineInRange("spin", 2.5D, 0.0D, 30.0D);
        MAX_REACH = b.comment("Max distance between player and target when the hit lands, blocks").defineInRange("maxReach", 5.0D, 1.0D, 16.0D);
        JOINT_STIFFNESS = b.comment("Spring in the joints of kicked ragdolls that pulls limbs back to their starting pose.",
                        "Sable: Ragdolls uses 20 (stiff mannequin). Lower = limper body. 0 = completely loose.")
                .defineInRange("jointStiffness", 1.0D, 0.0D, 100.0D);
        JOINT_DAMPING = b.comment("Joint damping of kicked ragdolls (Sable: Ragdolls uses 20). Keeps limbs from flailing; lower = floppier.")
                .defineInRange("jointDamping", 2.0D, 0.0D, 100.0D);
        PROTECT = b.comment("Kicked mobs take no damage while they lie as a ragdoll (otherwise hard landings can kill them,",
                        "the ragdoll disappears and CustomNPCs respawns the NPC at its home).")
                .define("protectRagdolls", true);
        SPEC = b.build();
    }

    private KickConfig() {
    }
}
