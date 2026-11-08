package com.example.wheelofwonder.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Where (and how turned) each physics part of the impaled NPC's ragdoll was drawn: filled by RagdollRenderMixin
 * while Sable renders the parts. A dagger stuck in the body is bound to the part nearest to its blade and then
 * drawn in that part's frame, so it moves and turns with the limb or torso it went into.
 */
public final class DaggerStick {
    /** One part: its render matrix (camera-relative, world axes) and the camera position it was relative to. */
    static final class Part {
        final Matrix4f m = new Matrix4f();
        Vec3 cam = Vec3.ZERO;
        long seen;
    }

    private static int targetId = Integer.MIN_VALUE;
    private static final Map<Object, Part> PARTS = new HashMap<>();

    private DaggerStick() {
    }

    static void track(int entityId) {
        targetId = entityId;
        PARTS.clear();
    }

    static void stop() {
        targetId = Integer.MIN_VALUE;
        PARTS.clear();
    }

    public static boolean wants(int entityId) {
        return entityId == targetId;
    }

    public static void record(Object key, Matrix4f pose, Vec3 cam) {
        Part p = PARTS.computeIfAbsent(key, k -> new Part());
        p.m.set(pose);
        p.cam = cam;
        p.seen = System.nanoTime();
    }

    static Part part(Object key) {
        return PARTS.get(key);
    }

    /** Parts drawn in the last half second. */
    static Map<Object, Part> recent() {
        Map<Object, Part> out = new HashMap<>();
        long now = System.nanoTime();
        for (Map.Entry<Object, Part> e : PARTS.entrySet()) if (now - e.getValue().seen < 500_000_000L) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** World centre of a part (its block's centre). */
    static Vec3 centre(Part p) {
        Vector3f v = p.m.transformPosition(0.5F, 0.5F, 0.5F, new Vector3f());
        return p.cam.add(v.x, v.y, v.z);
    }
}
