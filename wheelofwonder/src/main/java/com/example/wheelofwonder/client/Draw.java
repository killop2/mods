package com.example.wheelofwonder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Small vertex helpers for the voxel-style wheel: boxes and quads. */
final class Draw {
    private Draw() {
    }

    static void v(VertexConsumer c, PoseStack.Pose p, float x, float y, float z, int argb, float u, float v, int light,
                  float nx, float ny, float nz) {
        c.addVertex(p, x, y, z).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
    }

    /** Quad in the XY plane at depth z, facing +Z (front = true) or -Z. */
    static void quadZ(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float x1, float y1, float z, boolean front,
                      int argb, float u0, float v0, float u1, float v1, int light) {
        if (front) {
            v(c, p, x0, y0, z, argb, u0, v1, light, 0, 0, 1);
            v(c, p, x1, y0, z, argb, u1, v1, light, 0, 0, 1);
            v(c, p, x1, y1, z, argb, u1, v0, light, 0, 0, 1);
            v(c, p, x0, y1, z, argb, u0, v0, light, 0, 0, 1);
        } else {
            // mirrored so the texture reads the same way from behind
            v(c, p, x1, y0, z, argb, u0, v1, light, 0, 0, -1);
            v(c, p, x0, y0, z, argb, u1, v1, light, 0, 0, -1);
            v(c, p, x0, y1, z, argb, u1, v0, light, 0, 0, -1);
            v(c, p, x1, y1, z, argb, u0, v0, light, 0, 0, -1);
        }
    }

    static int shade(int argb, float f) {
        int a = argb >>> 24, r = (int) (((argb >> 16) & 255) * f), g = (int) (((argb >> 8) & 255) * f), b = (int) ((argb & 255) * f);
        return (a << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    /** Axis-aligned box on a white texture, tinted; faces slightly shaded for the blocky look. */
    static void box(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1, float z1, int argb, int light) {
        int top = argb, side = shade(argb, 0.82F), bot = shade(argb, 0.62F), front = shade(argb, 0.92F);
        // +Z / -Z
        v(c, p, x0, y0, z1, front, 0, 1, light, 0, 0, 1); v(c, p, x1, y0, z1, front, 1, 1, light, 0, 0, 1);
        v(c, p, x1, y1, z1, front, 1, 0, light, 0, 0, 1); v(c, p, x0, y1, z1, front, 0, 0, light, 0, 0, 1);
        v(c, p, x1, y0, z0, front, 0, 1, light, 0, 0, -1); v(c, p, x0, y0, z0, front, 1, 1, light, 0, 0, -1);
        v(c, p, x0, y1, z0, front, 1, 0, light, 0, 0, -1); v(c, p, x1, y1, z0, front, 0, 0, light, 0, 0, -1);
        // +X / -X
        v(c, p, x1, y0, z1, side, 0, 1, light, 1, 0, 0); v(c, p, x1, y0, z0, side, 1, 1, light, 1, 0, 0);
        v(c, p, x1, y1, z0, side, 1, 0, light, 1, 0, 0); v(c, p, x1, y1, z1, side, 0, 0, light, 1, 0, 0);
        v(c, p, x0, y0, z0, side, 0, 1, light, -1, 0, 0); v(c, p, x0, y0, z1, side, 1, 1, light, -1, 0, 0);
        v(c, p, x0, y1, z1, side, 1, 0, light, -1, 0, 0); v(c, p, x0, y1, z0, side, 0, 0, light, -1, 0, 0);
        // +Y / -Y
        v(c, p, x0, y1, z1, top, 0, 1, light, 0, 1, 0); v(c, p, x1, y1, z1, top, 1, 1, light, 0, 1, 0);
        v(c, p, x1, y1, z0, top, 1, 0, light, 0, 1, 0); v(c, p, x0, y1, z0, top, 0, 0, light, 0, 1, 0);
        v(c, p, x0, y0, z0, bot, 0, 1, light, 0, -1, 0); v(c, p, x1, y0, z0, bot, 1, 1, light, 0, -1, 0);
        v(c, p, x1, y0, z1, bot, 1, 0, light, 0, -1, 0); v(c, p, x0, y0, z1, bot, 0, 0, light, 0, -1, 0);
    }

    /**
     * Round body around the Y axis from a profile: ring i is at height ys[i] with radius rs[i]. Normals point
     * outwards per vertex, so a few sides already look smooth. u runs around (0..1), v = vs[i] (or 0).
     * cols: colour per section (null = white); ribbed: every other side a bit darker.
     */
    static void lathe(VertexConsumer c, PoseStack.Pose p, float[] ys, float[] rs, float[] vs, int[] cols, int sides, int light, boolean ribbed) {
        float[] cs = new float[sides + 1], sn = new float[sides + 1];
        for (int k = 0; k <= sides; k++) {
            double a = k * Math.PI * 2.0D / sides;
            cs[k] = (float) Math.cos(a);
            sn[k] = (float) Math.sin(a);
        }
        for (int i = 0; i < ys.length - 1; i++) {
            float y0 = ys[i], y1 = ys[i + 1], r0 = rs[i], r1 = rs[i + 1];
            float dy = Math.max(1.0E-4F, Math.abs(y1 - y0));
            float slope = (r0 - r1) / dy * Math.signum(y1 - y0 == 0 ? 1 : y1 - y0);
            float inv = 1.0F / (float) Math.sqrt(1.0F + slope * slope);
            float ny = slope * inv;
            int col = cols == null ? 0xFFFFFFFF : cols[Math.min(i, cols.length - 1)];
            float v0 = vs == null ? 0.0F : vs[i], v1 = vs == null ? 1.0F : vs[i + 1];
            for (int k = 0; k < sides; k++) {
                int cc = ribbed && (k & 1) == 1 ? shade(col, 0.8F) : col;
                float u0 = k / (float) sides, u1 = (k + 1) / (float) sides;
                v(c, p, cs[k] * r0, y0, sn[k] * r0, cc, u0, v0, light, cs[k] * inv, ny, sn[k] * inv);
                v(c, p, cs[k + 1] * r0, y0, sn[k + 1] * r0, cc, u1, v0, light, cs[k + 1] * inv, ny, sn[k + 1] * inv);
                v(c, p, cs[k + 1] * r1, y1, sn[k + 1] * r1, cc, u1, v1, light, cs[k + 1] * inv, ny, sn[k + 1] * inv);
                v(c, p, cs[k] * r1, y1, sn[k] * r1, cc, u0, v1, light, cs[k] * inv, ny, sn[k] * inv);
            }
        }
    }

    static void cube(VertexConsumer c, PoseStack.Pose p, float x, float y, float z, float h, int argb, int light) {
        box(c, p, x - h, y - h, z - h, x + h, y + h, z + h, argb, light);
    }

    /** Camera-facing additive glow quad; the pose must already be rotated to the camera. */
    static void halo(VertexConsumer c, PoseStack.Pose p, float size, int argb) {
        int light = 0xF000F0;
        argb = ShaderCompat.glowColor(argb);
        if (argb == 0) return;
        v(c, p, -size, -size, 0, argb, 0, 1, light, 0, 0, 1);
        v(c, p, size, -size, 0, argb, 1, 1, light, 0, 0, 1);
        v(c, p, size, size, 0, argb, 1, 0, light, 0, 0, 1);
        v(c, p, -size, size, 0, argb, 0, 0, light, 0, 0, 1);
    }
}
