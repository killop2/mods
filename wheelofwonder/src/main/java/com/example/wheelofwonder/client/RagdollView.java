package com.example.wheelofwonder.client;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the followed ragdoll was actually drawn in the last frame (filled by RagdollRenderMixin while Sable renders
 * the ragdoll parts): the exact, smoothly interpolated position the player sees. Same as kickmod's RagdollView.
 */
public final class RagdollView {
    private static int targetId = Integer.MIN_VALUE;
    private static Vec3 torsoCur;
    private static final List<Vec3> partsCur = new ArrayList<>();
    private static Vec3 torso;
    private static List<Vec3> parts = List.of();
    private static long seenAt;

    private RagdollView() {
    }

    static void track(int entityId) {
        targetId = entityId;
        torso = torsoCur = null;
        parts = List.of();
        partsCur.clear();
        seenAt = 0L;
    }

    static void stop() {
        targetId = Integer.MIN_VALUE;
    }

    /** True if the model has a real TORSO part; otherwise torso() is only the average of the parts drawn this frame. */
    private static boolean torsoReal;

    static void beginFrame() {
        if (!partsCur.isEmpty()) {
            parts = List.copyOf(partsCur);
            torsoReal = torsoCur != null;
            torso = torsoCur != null ? torsoCur : average(parts);
            seenAt = System.nanoTime();
        }
        partsCur.clear();
        torsoCur = null;
    }

    public static boolean wants(int entityId) {
        return entityId == targetId;
    }

    public static void record(int entityId, boolean isTorso, Vec3 centre) {
        if (entityId != targetId) return;
        partsCur.add(centre);
        if (isTorso) torsoCur = centre;
    }

    static Vec3 torso() {
        return torso != null && System.nanoTime() - seenAt < 250_000_000L ? torso : null;
    }

    /**
     * The drawn torso, but only for models that really have a TORSO part. Models without one (Huggy and other
     * custom ragdolls with long limbs) get null: averaging whatever parts happen to be on screen jumps every time a
     * limb leaves the view, so for them the camera uses the server's root body position instead.
     */
    static Vec3 stableTorso() {
        Vec3 t = torso();
        return t != null && torsoReal ? t : null;
    }

    static double extent() {
        Vec3 c = torso();
        if (c == null) return 0.0D;
        double r = 0.0D;
        for (Vec3 p : parts) r = Math.max(r, p.distanceTo(c));
        return r;
    }

    private static Vec3 average(List<Vec3> l) {
        Vec3 s = Vec3.ZERO;
        for (Vec3 v : l) s = s.add(v);
        return s.scale(1.0D / l.size());
    }
}
