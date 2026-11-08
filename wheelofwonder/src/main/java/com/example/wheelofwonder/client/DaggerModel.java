package com.example.wheelofwonder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

/**
 * Low-poly Dark Carnival throwing dagger, drawn from code on the plain white texture with vertex colours.
 * Local frame: the blade points along +Z, the flat of the blade faces +-Y, the origin is the crossguard.
 * - wavy "kris" blade with a diamond cross-section: a black-crimson ridge and red-hot edges (full bright),
 * - gold crossguard with forward horns and a violet gem,
 * - grip wrapped in black and crimson stripes, a gold pommel with a gem.
 */
final class DaggerModel {
    static final float BLADE = 0.56F;
    private static final int RIDGE = 0xFF2A0C14, EDGE = 0xFFFF3A2E, EDGE_TIP = 0xFFFFC07A;
    private static final int GOLD = 0xFFD9A22E, GOLD_DARK = 0xFF8A5A14, GEM = 0xFFC04BFF;
    private static final int GRIP_A = 0xFF1A0E16, GRIP_B = 0xFF9A1424;
    private static final int FULL = 0xF000F0;
    private static final int RINGS = 9;

    private DaggerModel() {
    }

    /** Half width of the blade along its length (s = 0 at the guard, 1 at the tip). */
    private static float width(float s) {
        float belly = 0.052F + 0.022F * (float) Math.sin(Math.PI * Math.min(1.0F, s * 1.6F));
        return belly * (1.0F - s * s * 0.95F) * (s >= 1.0F ? 0.0F : 1.0F);
    }

    /** Sideways wave of the blade (the flame shape of a kris). */
    private static float wave(float s) {
        return 0.016F * (float) Math.sin(s * Math.PI * 3.0F) * (1.0F - s);
    }

    static void draw(VertexConsumer c, PoseStack pose, int light) {
        PoseStack.Pose p = pose.last();
        blade(c, p, light);
        // crossguard with two forward horns
        Draw.box(c, p, -0.13F, -0.026F, -0.035F, 0.13F, 0.026F, 0.0F, GOLD, light);
        Draw.box(c, p, -0.15F, -0.02F, -0.02F, -0.11F, 0.02F, 0.07F, GOLD_DARK, light);
        Draw.box(c, p, 0.11F, -0.02F, -0.02F, 0.15F, 0.02F, 0.07F, GOLD_DARK, light);
        Draw.box(c, p, -0.165F, -0.016F, 0.05F, -0.125F, 0.016F, 0.1F, GOLD, light);
        Draw.box(c, p, 0.125F, -0.016F, 0.05F, 0.165F, 0.016F, 0.1F, GOLD, light);
        // the gem on both sides of the guard
        Draw.box(c, p, -0.03F, -0.036F, -0.03F, 0.03F, 0.036F, 0.012F, GEM, FULL);
        // grip and pommel: a lathe along +Y, turned onto -Z
        pose.pushPose();
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        PoseStack.Pose q = pose.last();
        float[] ys = {0.0F, 0.035F, 0.07F, 0.105F, 0.14F, 0.175F, 0.21F};
        float[] rs = {0.024F, 0.027F, 0.024F, 0.027F, 0.024F, 0.027F, 0.024F};
        int[] cols = {GRIP_A, GRIP_B, GRIP_A, GRIP_B, GRIP_A, GRIP_B};
        Draw.lathe(c, q, ys, rs, null, cols, 6, light, false);
        float[] py = {0.205F, 0.225F, 0.25F, 0.275F, 0.295F};
        float[] pr = {0.02F, 0.045F, 0.05F, 0.038F, 0.0F};
        Draw.lathe(c, q, py, pr, null, new int[]{GOLD_DARK, GOLD, GOLD, GOLD}, 6, light, true);
        Draw.cube(c, q, 0.0F, 0.255F, 0.0F, 0.018F, GEM, FULL);
        pose.popPose();
    }

    /** Diamond cross-section rings from the guard to the tip: the ridge is dark, the edges glow. */
    private static void blade(VertexConsumer c, PoseStack.Pose p, int light) {
        float[][] ring = new float[RINGS + 1][];
        for (int i = 0; i <= RINGS; i++) {
            float s = i / (float) RINGS;
            float z = 0.005F + s * BLADE;
            float w = width(s), x = wave(s), t = 0.016F * (1.0F - s * 0.85F);
            // left edge, top ridge, right edge, bottom ridge
            ring[i] = new float[]{x - w, 0.0F, z, x, t, z, x + w, 0.0F, z, x, -t, z};
        }
        for (int i = 0; i < RINGS; i++) {
            float s0 = i / (float) RINGS, s1 = (i + 1) / (float) RINGS;
            int e0 = mix(EDGE, EDGE_TIP, s0 * s0), e1 = mix(EDGE, EDGE_TIP, s1 * s1);
            float[] a = ring[i], b = ring[i + 1];
            // four faces: (edge L, ridge T), (ridge T, edge R), (edge R, ridge B), (ridge B, edge L)
            for (int f = 0; f < 4; f++) {
                int k0 = f, k1 = (f + 1) & 3;
                boolean edge0 = (k0 & 1) == 0;
                int c0a = edge0 ? e0 : RIDGE, c1a = edge0 ? RIDGE : e0;
                int c0b = edge0 ? e1 : RIDGE, c1b = edge0 ? RIDGE : e1;
                int l0 = edge0 ? FULL : light, l1 = edge0 ? light : FULL;
                float ny = f < 2 ? 1.0F : -1.0F;
                Draw.v(c, p, a[k0 * 3], a[k0 * 3 + 1], a[k0 * 3 + 2], c0a, 0.5F, 0.5F, l0, 0.0F, ny, 0.0F);
                Draw.v(c, p, b[k0 * 3], b[k0 * 3 + 1], b[k0 * 3 + 2], c0b, 0.5F, 0.5F, l0, 0.0F, ny, 0.0F);
                Draw.v(c, p, b[k1 * 3], b[k1 * 3 + 1], b[k1 * 3 + 2], c1b, 0.5F, 0.5F, l1, 0.0F, ny, 0.0F);
                Draw.v(c, p, a[k1 * 3], a[k1 * 3 + 1], a[k1 * 3 + 2], c1a, 0.5F, 0.5F, l1, 0.0F, ny, 0.0F);
            }
        }
        // the base of the blade is closed by the guard
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
