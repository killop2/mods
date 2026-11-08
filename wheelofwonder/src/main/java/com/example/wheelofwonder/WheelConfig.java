package com.example.wheelofwonder;

import net.neoforged.neoforge.common.ModConfigSpec;

/** config/wheelofwonder-server.toml (gameplay) and config/wheelofwonder-client.toml (camera). */
public final class WheelConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue MAX_RANGE;
    public static final ModConfigSpec.DoubleValue ROLL_SPEED;
    public static final ModConfigSpec.DoubleValue HOVER_HEIGHT;
    public static final ModConfigSpec.DoubleValue ATTRACT_RADIUS;
    public static final ModConfigSpec.DoubleValue PATH_RADIUS;
    public static final ModConfigSpec.DoubleValue PULL_SPEED;
    public static final ModConfigSpec.DoubleValue EMPTY_FUSE;
    public static final ModConfigSpec.DoubleValue CAPTURE_FUSE;
    public static final ModConfigSpec.DoubleValue BLAST_SPEED;
    public static final ModConfigSpec.DoubleValue BLAST_RADIUS;
    public static final ModConfigSpec.IntValue RAGDOLL_SECONDS;
    public static final ModConfigSpec.BooleanValue PROTECT;
    public static final ModConfigSpec.BooleanValue LIMP;
    public static final ModConfigSpec.IntValue COOLDOWN;
    public static final ModConfigSpec.BooleanValue MUSIC;
    public static final ModConfigSpec.DoubleValue PUNCH_DAMAGE;
    public static final ModConfigSpec.DoubleValue PUNCH_HEIGHT;
    public static final ModConfigSpec.DoubleValue PUNCH_RISE;
    public static final ModConfigSpec.DoubleValue PUNCH_DIVE;
    public static final ModConfigSpec.DoubleValue PUNCH_INTRO;
    public static final ModConfigSpec.DoubleValue PUNCH_REACH;
    public static final ModConfigSpec.IntValue PUNCH_COOLDOWN;
    public static final ModConfigSpec.DoubleValue DAGGER_RADIUS;
    public static final ModConfigSpec.DoubleValue DAGGER_SPIN;
    public static final ModConfigSpec.DoubleValue DAGGER_FLIGHT_SPEED;
    public static final ModConfigSpec.DoubleValue DAGGER_HITSTOP;
    public static final ModConfigSpec.BooleanValue DAGGER_SLOWMO;
    public static final ModConfigSpec.DoubleValue DAGGER_SLOWMO_SPEED;
    public static final ModConfigSpec.DoubleValue DAGGER_SLOWMO_TIME;
    public static final ModConfigSpec.DoubleValue DAGGER_DAMAGE;
    public static final ModConfigSpec.DoubleValue DAGGER_LAUNCH;
    public static final ModConfigSpec.DoubleValue DAGGER_LIFT;
    public static final ModConfigSpec.IntValue DAGGER_COOLDOWN;
    public static final ModConfigSpec.DoubleValue METEOR_RANGE, METEOR_ROLL, METEOR_KNOCK, METEOR_LIFT, METEOR_DAMAGE;
    public static final ModConfigSpec.IntValue METEOR_COOLDOWN;
    public static final ModConfigSpec.DoubleValue SHOCK_RANGE, SHOCK_SPEED, SHOCK_WIDTH, SHOCK_KNOCK, SHOCK_LIFT, SHOCK_DAMAGE;
    public static final ModConfigSpec.IntValue SHOCK_PULL, SHOCK_COOLDOWN;
    public static final ModConfigSpec.DoubleValue CHAIN_LENGTH;
    public static final ModConfigSpec.DoubleValue CHAIN_SNAP;
    public static final ModConfigSpec.DoubleValue BALLISTA_RADIUS;
    public static final ModConfigSpec.DoubleValue BALLISTA_DELAY;
    public static final ModConfigSpec.DoubleValue BALLISTA_SPEED;
    public static final ModConfigSpec.DoubleValue BALLISTA_ANGLE;
    public static final ModConfigSpec.DoubleValue ROCKET_RADIUS;
    public static final ModConfigSpec.DoubleValue RANGER_RADIUS, RANGER_SPEED, RANGER_LIFT;
    public static final ModConfigSpec.DoubleValue PRISM_RADIUS, PRISM_IMPULSE, PRISM_FLIGHT;
    public static final ModConfigSpec.DoubleValue ROCKET_SPIN_DELAY;
    public static final ModConfigSpec.DoubleValue ROCKET_LAUNCH_TIME;
    public static final ModConfigSpec.DoubleValue ROCKET_DISTANCE;
    public static final ModConfigSpec.DoubleValue ROCKET_HEIGHT;
    public static final ModConfigSpec.DoubleValue ROCKET_FLIGHT_TIME;

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue CINEMATIC;
    public static final ModConfigSpec.DoubleValue CAMERA_DISTANCE;
    public static final ModConfigSpec.DoubleValue CLOSE_DISTANCE;
    public static final ModConfigSpec.BooleanValue HIDE_HUD;
    public static final ModConfigSpec.BooleanValue HIDE_CROSSHAIR;
    public static final ModConfigSpec.BooleanValue BOTH_ARMS;
    public static final ModConfigSpec.BooleanValue HIDE_TOASTS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        MAX_RANGE = b.comment("How far the wheel can be sent (blocks)").defineInRange("maxRange", 48.0D, 4.0D, 128.0D);
        ROLL_SPEED = b.comment("Top rolling speed, blocks per second").defineInRange("rollSpeed", 10.0D, 1.0D, 40.0D);
        HOVER_HEIGHT = b.comment("How high the wheel floats above the ground after it stops (blocks)").defineInRange("hoverHeight", 1.3D, 0.2D, 6.0D);
        ATTRACT_RADIUS = b.comment("Radius in which the hovering wheel mesmerizes NPCs (blocks)").defineInRange("attractRadius", 14.0D, 2.0D, 64.0D);
        PATH_RADIUS = b.comment("NPCs this close to the rolling wheel are mesmerized already on its way (blocks)").defineInRange("pathRadius", 5.0D, 0.0D, 32.0D);
        PULL_SPEED = b.comment("Speed at which mesmerized NPCs float to the wheel, blocks per second").defineInRange("pullSpeed", 7.0D, 0.5D, 40.0D);
        EMPTY_FUSE = b.comment("Seconds of hovering until the wheel explodes when it has caught nobody").defineInRange("emptyFuseSeconds", 4.0D, 0.5D, 60.0D);
        CAPTURE_FUSE = b.comment("Seconds from the first NPC stuck to the wheel until it explodes").defineInRange("captureFuseSeconds", 6.0D, 0.5D, 60.0D);
        BLAST_SPEED = b.comment("Speed of the NPCs thrown by the explosion, m/s").defineInRange("blastSpeed", 16.0D, 0.0D, 80.0D);
        BLAST_RADIUS = b.comment("Other NPCs within this distance of the explosion are thrown too (blocks)").defineInRange("blastRadius", 5.0D, 0.0D, 32.0D);
        RAGDOLL_SECONDS = b.comment("How long thrown NPCs lie as a ragdoll. 0 = until /wheelofwonder release (or kickmod's /unragdoll)")
                .defineInRange("ragdollSeconds", 0, 0, 3600);
        PROTECT = b.comment("NPCs caught by the wheel take no damage (no fall damage while pulled, no landing damage as a ragdoll)")
                .define("protectNpcs", true);
        LIMP = b.comment("Ragdolls on the wheel are limp (needs kickmod installed: uses its soft-joint setting)").define("limpRagdolls", true);
        COOLDOWN = b.comment("Item cooldown in ticks (20 = 1 second)").defineInRange("cooldownTicks", 120, 0, 72000);
        MUSIC = b.comment("Carnival tune while the wheel hovers").define("music", true);
        b.push("walrusPunch");
        PUNCH_DAMAGE = b.comment("Damage of the critical uppercut").defineInRange("critDamage", 14.0D, 0.0D, 1000.0D);
        PUNCH_HEIGHT = b.comment("How high the uppercut lifts the NPC (blocks); lower automatically under a ceiling")
                .defineInRange("uppercutHeight", 11.0D, 2.0D, 40.0D);
        PUNCH_RISE = b.comment("Seconds the NPC takes to fly up to the top (slow-motion feel)").defineInRange("riseTime", 3.6D, 0.5D, 20.0D);
        PUNCH_INTRO = b.comment("Seconds of the side view before the uppercut (the NPC waits)").defineInRange("introTime", 1.5D, 0.0D, 10.0D);
        PUNCH_DIVE = b.comment("Seconds the smashed NPC takes to fall back to the ground (it speeds up all the way down)")
                .defineInRange("fallTime", 1.5D, 0.3D, 20.0D);
        PUNCH_REACH = b.comment("Max distance to the NPC for the combo (blocks)").defineInRange("reach", 4.5D, 1.0D, 8.0D);
        PUNCH_COOLDOWN = b.comment("Cooldown after a combo, ticks (meanwhile the gloves punch normally)").defineInRange("cooldownTicks", 60, 0, 72000);
        b.pop();
        b.push("impalementArts");
        DAGGER_RADIUS = b.comment("Max distance to the NPC the daggers are thrown at (blocks)").defineInRange("radius", 70.0D, 4.0D, 128.0D);
        DAGGER_SPIN = b.comment("Seconds the daggers circle the thrower before they fly").defineInRange("spinSeconds", 2.5D, 0.3D, 10.0D);
        DAGGER_FLIGHT_SPEED = b.comment("Flight speed of the daggers, blocks per second").defineInRange("daggerSpeed", 29.0D, 5.0D, 200.0D);
        DAGGER_HITSTOP = b.comment("Game seconds the NPC stands still with the daggers in it before it is carried away (in slow motion this lasts longer)")
                .defineInRange("hitStopSeconds", 0.15D, 0.0D, 3.0D);
        DAGGER_SLOWMO = b.comment("Slow motion around the hit: the whole game runs slower for a moment (like /tick rate).",
                "Affects everybody on the server, so turn it off on multiplayer servers if that bothers others").define("slowMotion", true);
        DAGGER_SLOWMO_SPEED = b.comment("Game speed during the slow motion (0.3 = 30 %)").defineInRange("slowMotionSpeed", 0.3D, 0.05D, 1.0D);
        DAGGER_SLOWMO_TIME = b.comment("Real seconds the slow motion lasts (the last 0.5 s speed back up)").defineInRange("slowMotionSeconds", 1.8D, 0.2D, 10.0D);
        DAGGER_DAMAGE = b.comment("Damage of the hit").defineInRange("damage", 12.0D, 0.0D, 1000.0D);
        DAGGER_LAUNCH = b.comment("Speed the ragdoll flies off with, along the daggers' flight, m/s").defineInRange("launchSpeed", 30.0D, 1.0D, 150.0D);
        DAGGER_LIFT = b.comment("Extra upward speed of the ragdoll, m/s (so it does not just slide along the ground)").defineInRange("liftSpeed", 5.0D, 0.0D, 60.0D);
        DAGGER_COOLDOWN = b.comment("Cooldown after a throw, ticks").defineInRange("cooldownTicks", 60, 0, 72000);
        b.pop();
        b.push("chaosMeteor");
        METEOR_RANGE = b.comment("How far away the meteor can be called down (blocks)").defineInRange("range", 64.0D, 8.0D, 160.0D);
        METEOR_ROLL = b.comment("How far it rolls on after landing (blocks)").defineInRange("rollDistance", 16.0D, 0.0D, 64.0D);
        METEOR_KNOCK = b.comment("Speed NPCs are thrown with along its way, m/s").defineInRange("knockSpeed", 9.0D, 1.0D, 80.0D);
        METEOR_LIFT = b.comment("Upward speed of thrown NPCs, m/s").defineInRange("liftSpeed", 15.0D, 0.0D, 60.0D);
        METEOR_DAMAGE = b.comment("Damage of a hit").defineInRange("damage", 8.0D, 0.0D, 1000.0D);
        METEOR_COOLDOWN = b.comment("Cooldown, ticks").defineInRange("cooldownTicks", 60, 0, 72000);
        b.pop();
        b.push("shockwave");
        SHOCK_RANGE = b.comment("How far the rift runs along the ground (blocks)").defineInRange("range", 13.0D, 4.0D, 96.0D);
        SHOCK_SPEED = b.comment("How fast it runs, blocks per second").defineInRange("speed", 7.0D, 2.0D, 60.0D);
        SHOCK_WIDTH = b.comment("NPCs this close to the rift (blocks) are caught").defineInRange("width", 2.3D, 0.5D, 8.0D);
        SHOCK_PULL = b.comment("How long the caught NPCs are pulled into the vortex before they are blasted apart, ticks").defineInRange("pullTicks", 70, 10, 200);
        SHOCK_KNOCK = b.comment("Speed NPCs are blasted away with, m/s").defineInRange("knockSpeed", 9.0D, 0.0D, 60.0D);
        SHOCK_LIFT = b.comment("Upward speed of the blasted NPCs, m/s").defineInRange("liftSpeed", 16.0D, 0.0D, 60.0D);
        SHOCK_DAMAGE = b.comment("Damage to every caught NPC").defineInRange("damage", 8.0D, 0.0D, 1000.0D);
        SHOCK_COOLDOWN = b.comment("Cooldown, ticks").defineInRange("cooldownTicks", 60, 0, 72000);
        b.pop();
        b.push("sinisterChain");
        CHAIN_LENGTH = b.comment("Length of the chain between two chained NPCs (blocks)").defineInRange("chainLength", 4.0D, 1.5D, 12.0D);
        CHAIN_SNAP = b.comment("If two chained NPCs get farther apart than this (blocks), the whole chain falls as ragdolls")
                .defineInRange("ragdollDistance", 5.0D, 2.0D, 20.0D);
        b.pop();
        b.push("ballista");
        BALLISTA_RADIUS = b.comment("An NPC can be loaded into a ballista this close to it (blocks)").defineInRange("loadRadius", 10.0D, 1.0D, 64.0D);
        BALLISTA_DELAY = b.comment("Seconds the NPC sits in the ballista before the shot").defineInRange("delaySeconds", 4.0D, 0.2D, 30.0D);
        BALLISTA_SPEED = b.comment("Launch speed of the NPC, m/s (higher = farther)").defineInRange("launchSpeed", 28.0D, 2.0D, 120.0D);
        BALLISTA_ANGLE = b.comment("Launch angle above the horizon, degrees").defineInRange("launchAngle", 38.0D, 0.0D, 89.0D);
        b.pop();
        b.push("rangerRide");
        RANGER_RADIUS = b.comment("An NPC can be loaded into a Ranger this close to it (blocks)").defineInRange("loadRadius", 30.0D, 1.0D, 64.0D);
        RANGER_SPEED = b.comment("Speed the NPC is flung out of the gondola with, m/s").defineInRange("launchSpeed", 34.0D, 1.0D, 120.0D);
        RANGER_LIFT = b.comment("Extra upward speed of the flung NPC, m/s").defineInRange("liftSpeed", 8.0D, 0.0D, 60.0D);
        b.pop();
        b.push("prismSplitter");
        PRISM_RADIUS = b.comment("An NPC can be loaded into the Prism Splitter this close to it (blocks)").defineInRange("loadRadius", 30.0D, 1.0D, 64.0D);
        PRISM_IMPULSE = b.comment("Push of each crystal beam on the flying ragdoll, m/s").defineInRange("beamImpulse", 8.0D, 0.0D, 60.0D);
        PRISM_FLIGHT = b.comment("Longest time the crystals steer the flying ragdoll, seconds").defineInRange("flightSeconds", 4.0D, 1.0D, 30.0D);
        b.pop();
        b.push("rocket");
        ROCKET_RADIUS = b.comment("An NPC can be loaded into a rocket ride this close to it (blocks)").defineInRange("loadRadius", 30.0D, 1.0D, 64.0D);
        ROCKET_SPIN_DELAY = b.comment("Seconds the NPC sits in the rocket before the ride starts spinning").defineInRange("spinDelaySeconds", 2.0D, 0.0D, 30.0D);
        ROCKET_LAUNCH_TIME = b.comment("Seconds from sitting down to the launch (the spin speeds up meanwhile)").defineInRange("launchSeconds", 6.0D, 1.0D, 60.0D);
        ROCKET_DISTANCE = b.comment("How far the rocket flies before it comes down (blocks). Keep it within your view distance:",
                "the server stops simulating things far from the player, and the client stops showing them")
                .defineInRange("flightDistance", 50.0D, 5.0D, 120.0D);
        ROCKET_HEIGHT = b.comment("How high the rocket's arc goes above the launch point (blocks)").defineInRange("flightHeight", 20.0D, 1.0D, 80.0D);
        ROCKET_FLIGHT_TIME = b.comment("Seconds of the arc up to where it starts diving down").defineInRange("flightSeconds", 4.0D, 1.0D, 20.0D);
        b.pop();
        SPEC = b.build();

        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        CINEMATIC = c.comment("Side camera that follows your wheel (Shift skips it)").define("cinematicCamera", true);
        CAMERA_DISTANCE = c.comment("Distance of the side camera from the wheel (blocks)").defineInRange("cameraDistance", 8.5D, 3.0D, 30.0D);
        CLOSE_DISTANCE = c.comment("Close-up distance once NPCs stick to the wheel (blocks)").defineInRange("closeUpDistance", 4.5D, 2.0D, 30.0D);
        HIDE_HUD = c.comment("Immersive mode: no HUD (hotbar, health, food, xp, armor, effects, item names) - items still work,",
                        "the inventory screen still shows everything. Toggle in game with the 'Toggle HUD' key (default H).")
                .define("hideHud", true);
        HIDE_CROSSHAIR = c.comment("In immersive mode also hide the crosshair").define("hideCrosshair", true);
        HIDE_TOASTS = c.comment("Hide the 'New recipes unlocked' and tutorial pop-ups in the corner").define("hideRecipeToasts", true);
        BOTH_ARMS = c.comment("Show both arms in first person when both hands are empty").define("bothArmsWhenEmpty", true);
        CLIENT_SPEC = c.build();
    }

    private WheelConfig() {
    }
}
