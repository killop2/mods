package com.example.huggynpc.client;

import com.example.huggynpc.HuggyNpcMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Verity's pixel body, built from little cubes in code: a yellow ball (16 cubes across, 0.5 block) with a
 * Pac-Man mouth cut in the front - white teeth round its rim, a dark inside and a red tongue - and two dark oval eyes
 * with a white glint above it. The yellow cubes vary a little in brightness and go orange towards the bottom, so
 * every pixel shows. Only faces between a cube and empty space are kept.
 * Meshes are made for six mouth openings and for open / closed eyes (chomping and blinking). The coarser cubes the
 * ball falls apart into come from the same colours (debris()).
 */
public final class VerityMesh {
    /** White: the colours are vertex colours. */
    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(HuggyNpcMod.MOD_ID, "textures/entity/verity.png");
    public static final float DIAMETER = 0.5F;
    static final int RES = 16;
    static final double[] MOUTH = {0.04D, 0.12D, 0.2D, 0.28D, 0.37D, 0.46D};
    /** Where the mouth's wedge starts (behind and below the centre) and the eyes. */
    private static final double MZ = -0.3D, MY = -0.15D, EYE_X = 0.3D, EYE_Y = 0.55D, EYE_RX = 0.15D, EYE_RY = 0.18D;

    static final int YELLOW = 0xFFD23A, ORANGE = 0xF29A12, DARK = 0x2A1208, MOUTH_IN = 0x3A140C, TONGUE = 0xC8424E, TOOTH = 0xF4F1E6, GAP = 0x5A4636;

    /** Quads: 4 corners (xyz, blocks, centred on the ball), a normal and a colour each. */
    public static final class Mesh {
        final float[] pos, nrm;
        final int[] col;
        final int count;

        Mesh(List<float[]> quads) {
            this.count = quads.size();
            this.pos = new float[this.count * 12];
            this.nrm = new float[this.count * 3];
            this.col = new int[this.count];
            for (int q = 0; q < this.count; q++) {
                float[] d = quads.get(q);
                System.arraycopy(d, 0, this.pos, q * 12, 12);
                System.arraycopy(d, 12, this.nrm, q * 3, 3);
                this.col[q] = Float.floatToRawIntBits(d[15]);
            }
        }
    }

    private static Mesh[][] meshes;

    private VerityMesh() {
    }

    /** The ball with mouth opening 0..5 and the eyes open or shut. */
    public static Mesh mesh(int mouth, boolean shut) {
        if (meshes == null) {
            Mesh[][] m = new Mesh[MOUTH.length][2];
            for (int i = 0; i < MOUTH.length; i++) {
                m[i][0] = build(MOUTH[i], false);
                m[i][1] = build(MOUTH[i], true);
            }
            meshes = m;
        }
        return meshes[Math.max(0, Math.min(MOUTH.length - 1, mouth))][shut ? 1 : 0];
    }

    // ------------------------------------------------------------------ shape and colour

    private static double c(int i) {
        return (i + 0.5D) / RES * 2.0D - 1.0D;
    }

    private static boolean inBall(double x, double y, double z) {
        return x * x + y * y + z * z <= 1.0D;
    }

    private static boolean inMouth(double y, double z, double a) {
        double dz = z - MZ, dy = y - MY;
        return dz > 0.0D && Math.abs(dy) < Math.tan(a) * dz;
    }

    private static boolean solid(int i, int j, int k, double a) {
        if (i < 0 || j < 0 || k < 0 || i >= RES || j >= RES || k >= RES) return false;
        double x = c(i), y = c(j), z = c(k);
        return inBall(x, y, z) && !inMouth(y, z, a);
    }

    private static boolean mouthGap(int i, int j, int k, double a) {
        if (i < 0 || j < 0 || k < 0 || i >= RES || j >= RES || k >= RES) return false;
        double x = c(i), y = c(j), z = c(k);
        return inBall(x, y, z) && inMouth(y, z, a);
    }

    private static int hashShade(int rgb, int i, int j, int k) {
        double h = Math.sin(i * 12.9898D + j * 78.233D + k * 37.719D) * 43758.5453D;
        h -= Math.floor(h);
        return shade(rgb, (float) (0.92D + 0.13D * h));
    }

    /** Colour of the cube at (i, j, k), knowing whether it borders the mouth. */
    private static int colour(int i, int j, int k, boolean byMouth, boolean shut) {
        double x = c(i), y = c(j), z = c(k);
        double r = Math.sqrt(x * x + y * y + z * z);
        if (byMouth) {
            if (r > 0.8D) return (i % 3 == 0) ? GAP : TOOTH;   // a row of teeth round the rim
            if (y < MY && z < 0.55D) return TONGUE;
            return MOUTH_IN;
        }
        if (z > 0.2D) {
            for (int s = -1; s <= 1; s += 2) {
                double ex = (x - s * EYE_X) / EYE_RX, ey = (y - EYE_Y) / EYE_RY;
                if (ex * ex + ey * ey < 1.0D) {
                    if (shut) return Math.abs(y - EYE_Y) < 0.07D ? DARK : hashShade(YELLOW, i, j, k);
                    double gx = (x - s * EYE_X + 0.05D) / 0.07D, gy = (y - EYE_Y - 0.07D) / 0.07D;
                    return gx * gx + gy * gy < 1.0D ? 0xFFFFFF : DARK;
                }
            }
        }
        float down = (float) Math.max(0.0D, (0.3D - y) / 1.3D);
        return hashShade(mix(YELLOW, ORANGE, down * 0.75F), i, j, k);
    }

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static Mesh build(double a, boolean shut) {
        List<float[]> quads = new ArrayList<>();
        float vs = DIAMETER / RES, h = DIAMETER * 0.5F;
        for (int i = 0; i < RES; i++) for (int j = 0; j < RES; j++) for (int k = 0; k < RES; k++) {
            if (!solid(i, j, k, a)) continue;
            boolean byMouth = false;
            for (int[] d : DIRS) if (mouthGap(i + d[0], j + d[1], k + d[2], a)) byMouth = true;
            int col = -1;
            for (int[] d : DIRS) {
                if (solid(i + d[0], j + d[1], k + d[2], a)) continue;
                if (col == -1) col = colour(i, j, k, byMouth, shut);
                float x0 = i * vs - h, y0 = j * vs - h, z0 = k * vs - h, x1 = x0 + vs, y1 = y0 + vs, z1 = z0 + vs;
                float[] q = new float[16];
                float[][] v;
                if (d[0] == 1) v = new float[][]{{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}};
                else if (d[0] == -1) v = new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
                else if (d[1] == 1) v = new float[][]{{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}};
                else if (d[1] == -1) v = new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
                else if (d[2] == 1) v = new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
                else v = new float[][]{{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
                for (int n = 0; n < 4; n++) System.arraycopy(v[n], 0, q, n * 3, 3);
                q[12] = d[0];
                q[13] = d[1];
                q[14] = d[2];
                q[15] = Float.intBitsToFloat(col);
                quads.add(q);
            }
        }
        return new Mesh(quads);
    }

    // ------------------------------------------------------------------ drawing

    /** Draws the ball centred on the pose's origin (diameter 0.5 before the pose's scale), +Z = its face. */
    public static void emit(PoseStack.Pose p, VertexConsumer vc, Mesh m, int light, int overlay, int color) {
        float ca = ((color >>> 24) & 255) / 255.0F, cr = ((color >> 16) & 255) / 255.0F, cg = ((color >> 8) & 255) / 255.0F, cb = (color & 255) / 255.0F;
        for (int q = 0; q < m.count; q++) {
            int rgb = m.col[q];
            int argb = ((int) (ca * 255.0F) << 24) | ((int) (((rgb >> 16) & 255) * cr) << 16) | ((int) (((rgb >> 8) & 255) * cg) << 8) | (int) ((rgb & 255) * cb);
            float nx = m.nrm[q * 3], ny = m.nrm[q * 3 + 1], nz = m.nrm[q * 3 + 2];
            int o = q * 12;
            for (int n = 0; n < 4; n++) {
                vc.addVertex(p, m.pos[o + n * 3], m.pos[o + n * 3 + 1], m.pos[o + n * 3 + 2]).setColor(argb).setUv(0.5F, 0.5F)
                        .setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
            }
        }
    }

    /** Mouth opening and blink for this moment: slow chomps when idle, fast ones while it hops. */
    public static Mesh animated(float time, int id, boolean busy) {
        double open;
        if (busy) {
            open = Math.abs(Math.sin(time * 0.45D));
        } else {
            double s = 0.5D + 0.5D * Math.sin(time * 0.06D + id);
            open = s * s * s;
        }
        int mouth = (int) Math.round(open * (MOUTH.length - 1));
        boolean shut = Math.floorMod((int) time + id * 37, 90) < 4;
        return mesh(mouth, shut);
    }

    // ------------------------------------------------------------------ the cubes it falls apart into

    /** One cube: where it sits in the ball (blocks from the centre, +Z = face) and its colour. */
    public record Cube(float x, float y, float z, int color) {
    }

    private static List<Cube> debris;

    /** The ball as 8x8x8 coarser cubes (its outer shell): what it crumbles into. */
    public static List<Cube> debris() {
        if (debris != null) return debris;
        List<Cube> out = new ArrayList<>();
        int n = 8, f = RES / n;
        double a = MOUTH[2];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) for (int k = 0; k < n; k++) {
            double x = (i + 0.5D) / n * 2.0D - 1.0D, y = (j + 0.5D) / n * 2.0D - 1.0D, z = (k + 0.5D) / n * 2.0D - 1.0D;
            double r = Math.sqrt(x * x + y * y + z * z);
            if (r > 1.0D || r < 0.5D) continue;
            int fi = i * f + f / 2, fj = j * f + f / 2, fk = k * f + f / 2;
            boolean gap = inMouth(y, z, a);
            int col = gap ? (r > 0.8D ? TOOTH : MOUTH_IN) : colour(fi, fj, fk, false, false);
            out.add(new Cube((float) (x * DIAMETER * 0.5D), (float) (y * DIAMETER * 0.5D), (float) (z * DIAMETER * 0.5D), col));
        }
        debris = out;
        return out;
    }

    static int shade(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * f)), g = Math.min(255, (int) (((rgb >> 8) & 255) * f)), b = Math.min(255, (int) ((rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
