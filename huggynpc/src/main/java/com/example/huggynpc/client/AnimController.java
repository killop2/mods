package com.example.huggynpc.client;

import com.example.huggynpc.HuggyConfig;
import net.minecraft.Util;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chooses which animation each entity plays (per entity state, so every NPC has its own timing)
 * and writes the blended local joint pose into {@code mesh.poseA}.
 *
 * idle / walk / run      — from the entity's horizontal speed
 * attack                 — whenever the entity swings (Custom NPCs melee hit, mob attack)
 * jump                   — in the air
 * crouch / crouchwalk    — sneaking, or Custom NPCs animation "Crouch" / "Crawl"
 * sit                    — riding, or Custom NPCs animation "Sit"
 * dance                  — Custom NPCs animation "Dance"
 */
final class AnimController {
    private static final double BLEND = 0.22;

    private static final class State {
        String cur = "idle";
        String prev;
        double curT, prevT, prevRate = 1;
        double blendStart = -1;
        double last = -1;
        double speed;
        boolean wasSwinging;
        double attackLeft;
        int airTicks;
        int lastTick = Integer.MIN_VALUE;
    }

    private static final Map<LivingEntity, State> STATES = new WeakHashMap<>();

    private AnimController() {
    }

    static void pose(SkinMesh mesh, LivingEntity e) {
        State s = STATES.computeIfAbsent(e, k -> new State());
        double now = Util.getMillis() / 1000.0;
        double dt = s.last < 0 ? 0 : Math.min(0.25, Math.max(0, now - s.last));
        s.last = now;

        // horizontal speed in blocks per tick, smoothed so the walk/run choice does not flicker
        double dx = e.getX() - e.xo, dz = e.getZ() - e.zo;
        double sp = Math.sqrt(dx * dx + dz * dz);
        s.speed += (sp - s.speed) * Math.min(1.0, dt * 8.0);

        if (e.tickCount != s.lastTick) {
            s.lastTick = e.tickCount;
            boolean air = !e.onGround() && !e.isInWater() && !e.isPassenger() && Math.abs(e.getY() - e.yo) > 0.04;
            s.airTicks = air ? s.airTicks + 1 : 0;
        }

        // attack: start on every new swing
        SkinMesh.Anim attack = mesh.anim("attack");
        double attackRate = HuggyConfig.ATTACK_RATE.get();
        boolean swing = e.swinging;
        boolean newSwing = swing && !s.wasSwinging;
        s.wasSwinging = swing;
        if (newSwing) s.attackLeft = attack.duration / attackRate;
        if (s.attackLeft > 0) s.attackLeft -= dt;

        int npc = NpcCompat.animation(e);
        double walkRef = HuggyConfig.WALK_SPEED_REF.get(), runAt = HuggyConfig.RUN_THRESHOLD.get(), runRef = HuggyConfig.RUN_SPEED_REF.get();
        boolean moving = s.speed > 0.015;

        String target;
        double rate = 1;
        if (e.deathTime > 0) {
            target = s.cur;
        } else if (s.attackLeft > 0) {
            target = "attack";
            rate = attackRate;
        } else if (npc == NpcCompat.SIT || e.isPassenger()) {
            target = "sit";
        } else if (npc == NpcCompat.DANCE) {
            target = "dance";
        } else if (npc == NpcCompat.CROUCH || npc == NpcCompat.CRAWL || e.isCrouching()) {
            target = moving ? "crouchwalk" : "crouch";
            if (moving) rate = clamp(s.speed / 0.05, 0.5, 2.0);
        } else if (s.airTicks > 3) {
            target = "jump";
        } else if (moving && s.speed > runAt) {
            target = "run";
            rate = clamp(s.speed / runRef, 0.7, 1.8);
        } else if (moving) {
            target = "walk";
            rate = clamp(s.speed / walkRef, 0.5, 2.2);
        } else {
            target = "idle";
            // a model with moods (Lily): her mood, and so her idle, changes now and then
            if (mesh.hasAnim(MOODS[1])) target = MOODS[moodFor(e)];
        }

        if (!target.equals(s.cur)) {
            s.prev = s.cur;
            s.prevT = s.curT;
            s.prevRate = s.cur.equals("attack") ? 0 : 1;   // freeze the end of a hit while blending out
            s.cur = target;
            s.curT = 0;
            s.blendStart = now;
        } else if (newSwing && target.equals("attack")) {
            s.curT = 0;   // next hit restarts the swing
        }
        if (e.deathTime <= 0) s.curT += dt * rate;
        if (s.prev != null) s.prevT += dt * s.prevRate;

        mesh.sample(mesh.anim(s.cur), s.curT, mesh.poseA);
        double w = s.blendStart < 0 ? 1 : (now - s.blendStart) / BLEND;
        if (s.prev != null && w < 1) {
            mesh.sample(mesh.anim(s.prev), s.prevT, mesh.poseB);
            float k = (float) w;
            k = k * k * (3 - 2 * k);
            mesh.mix(mesh.poseB, mesh.poseA, k, mesh.poseA);
        } else {
            s.prev = null;
        }
    }

    /** Idle animations of the moods: happy, excited, curious, shy, pouting (the face changes with them). */
    static final String[] MOODS = {"idle", "idle_excited", "idle_curious", "idle_shy", "idle_pout"};
    private static final int[] MOOD_DECK = {0, 1, 0, 2, 3, 0, 4, 1, 2, 0, 3, 4};

    /** The mood an entity is in now: a new one about every 12 seconds, each NPC on its own schedule. */
    private static int moodFor(LivingEntity e) {
        long k = (e.tickCount + e.getId() * 131L) / 240;
        long h = (k * 0x9E3779B97F4A7C15L) ^ (e.getId() * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 29;
        return MOOD_DECK[(int) Math.floorMod(h, (long) MOOD_DECK.length)];
    }

    /** The mood shown on the face: the one of the idle playing (0 = happy while it walks, runs...). */
    static int mood(LivingEntity e) {
        State s = STATES.get(e);
        if (s == null) return 0;
        for (int i = 1; i < MOODS.length; i++) if (s.cur.equals(MOODS[i])) return i;
        return 0;
    }

    /** Head turn is skipped where it would fight the body animation. */
    static boolean headFree(LivingEntity e) {
        State s = STATES.get(e);
        return s == null || !(s.cur.equals("dance") || s.cur.equals("sit") || s.cur.equals("attack"));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
