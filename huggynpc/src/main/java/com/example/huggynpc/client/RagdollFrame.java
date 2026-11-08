package com.example.huggynpc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/** Transforms of all ragdoll parts of one body in the current frame (see HuggyModel, SkinMesh.emitMerged). */
final class RagdollFrame {
    private static long frame;
    private static final Map<Object, RagdollFrame> FRAMES = new HashMap<>();

    final Matrix4f[] mats;
    final Matrix3f[] nmats;
    final int[] lights;
    private final boolean[] seen;
    private int count;
    private long frameId = -1;
    private boolean drawn;

    private RagdollFrame(int parts) {
        this.mats = new Matrix4f[parts];
        this.nmats = new Matrix3f[parts];
        this.lights = new int[parts];
        this.seen = new boolean[parts];
        for (int i = 0; i < parts; i++) {
            this.mats[i] = new Matrix4f();
            this.nmats[i] = new Matrix3f();
        }
    }

    /** Number of the frame being rendered (counts up once per frame). */
    static long currentFrame() {
        return frame;
    }

    /** Called once per rendered frame. */
    static void nextFrame() {
        frame++;
        if ((frame & 255) == 0) FRAMES.values().removeIf(f -> frame - f.frameId > 600);   // bodies no longer drawn
    }

    static RagdollFrame of(net.minecraft.world.entity.Entity body, int parts) {
        Object key = body != null ? body.getUUID() : RagdollFrame.class;
        // keyed by UUID: Sable may hand over a fresh copy of the entity for every part
        RagdollFrame f = FRAMES.get(key);
        if (f == null || f.mats.length != parts) {
            f = new RagdollFrame(parts);
            FRAMES.put(key, f);
        }
        if (f.frameId != frame) {
            f.frameId = frame;
            f.count = 0;
            f.drawn = false;
            java.util.Arrays.fill(f.seen, false);
        }
        return f;
    }

    void put(int part, PoseStack.Pose pose, int light) {
        this.mats[part].set(pose.pose());
        this.nmats[part].set(pose.normal());
        this.lights[part] = light;
        if (!this.seen[part]) {
            this.seen[part] = true;
            this.count++;
        }
    }

    /** True exactly once per frame: when every part has been seen. */
    boolean complete() {
        if (this.drawn || this.count < this.seen.length) return false;
        this.drawn = true;
        return true;
    }
}
