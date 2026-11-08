import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Masked Guard NPC (red jumpsuit, hood, black mask with a white circle / triangle / square), built from code:
 * a low-poly skinned mesh of smooth tubes (soft normals) + a painted 512x512 texture with folds, seams, zipper,
 * pockets, belt and a glossy mask, so it reads as a detailed model with few polygons. Writes the same .skin format
 * SkinToMc writes (version 4): skeleton, skin weights, sampled animations, ragdoll parts, head bone.
 *
 * Run: java tools/GuardGen.java <resources/assets/huggynpc> <previewDir>
 * Model space: blocks, +Y up, feet at 0, front +Z.
 */
public class GuardGen {
    // ------------------------------------------------------------------ skeleton
    static final String[] JN = {"hips", "spine", "chest", "neck", "head",
            "upperarm_l", "forearm_l", "hand_l", "upperarm_r", "forearm_r", "hand_r",
            "thigh_l", "shin_l", "foot_l", "thigh_r", "shin_r", "foot_r"};
    static final int[] JP = {-1, 0, 1, 2, 3, 2, 5, 6, 2, 8, 9, 0, 11, 12, 0, 14, 15};
    static final double[][] GUARD_JG = {
            {0, 0.98, 0}, {0, 1.12, 0}, {0, 1.32, 0}, {0, 1.50, 0}, {0, 1.58, 0},
            {0.215, 1.44, 0}, {0.225, 1.16, 0}, {0.23, 0.91, 0},
            {-0.215, 1.44, 0}, {-0.225, 1.16, 0}, {-0.23, 0.91, 0},
            {0.095, 0.92, 0}, {0.095, 0.50, 0}, {0.095, 0.10, 0.0},
            {-0.095, 0.92, 0}, {-0.095, 0.50, 0}, {-0.095, 0.10, 0.0}};
    /** The dolls: a child's proportions (1.53 blocks, big head). */
    static final double[][] DOLL_JG = {
            {0, 0.76, 0}, {0, 0.88, 0}, {0, 1.03, 0}, {0, 1.17, 0}, {0, 1.21, 0},
            {0.155, 1.13, 0}, {0.165, 0.94, 0}, {0.17, 0.77, 0},
            {-0.155, 1.13, 0}, {-0.165, 0.94, 0}, {-0.17, 0.77, 0},
            {0.072, 0.73, 0}, {0.072, 0.41, 0}, {0.072, 0.08, 0},
            {-0.072, 0.73, 0}, {-0.072, 0.41, 0}, {-0.072, 0.08, 0}};
    static double[][] JG = GUARD_JG;
    static final int HIPS = 0, SPINE = 1, CHEST = 2, NECK = 3, HEAD = 4, UAL = 5, FAL = 6, HL = 7, UAR = 8, FAR = 9, HR = 10,
            THL = 11, SHL = 12, FTL = 13, THR = 14, SHR = 15, FTR = 16;
    static final int J = JN.length;
    // ragdoll parts (names alternate roles like the converter's, so Sable joins them)
    static final String[] SEG = {"body", "head", "l_arm_a0", "l_tail_a1", "l_arm_a2", "r_arm_a0", "r_tail_a1", "r_arm_a2",
            "l_leg_g0", "l_tail_g1", "l_leg_g2", "r_leg_g0", "r_tail_g1", "r_leg_g2"};
    static final int[] SEG_PARENT = {-1, 0, 0, 2, 3, 0, 5, 6, 0, 8, 9, 0, 11, 12};
    static final int[] SEG_PIVOT = {HIPS, NECK, UAL, FAL, HL, UAR, FAR, HR, THL, SHL, FTL, THR, SHR, FTR};
    static final int[] JOINT_SEG = {0, 0, 0, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};

    // ------------------------------------------------------------------ mesh
    static final List<double[]> P = new ArrayList<>(), N = new ArrayList<>(), UV = new ArrayList<>();
    static final List<int[]> JI = new ArrayList<>();
    static final List<double[]> JW = new ArrayList<>();
    static final List<int[]> Q = new ArrayList<>();
    static final List<Integer> GROUP = new ArrayList<>();   // vertices of one tube share a smoothing group
    static int group;

    /** One ring of a tube: centre, radii (x, z front, z back), and up to two joints with weights. */
    record Ring(double x, double y, double z, double rx, double rzf, double rzb, int j1, int j2, double w2) {
        Ring(double x, double y, double z, double r, int j) { this(x, y, z, r, r, r, j, j, 0); }
    }

    /**
     * Vertical tube through rings (top first), {@code sides} around; u runs around starting at the back (front at
     * u = 0.5), v from the first ring (v0) to the last (v1) inside the atlas rectangle. Smooth normals.
     */
    static void tube(List<Ring> rings, int sides, double u0, double v0, double u1, double v1) {
        group++;
        int base = P.size();
        int R = rings.size();
        double[] cum = new double[R];
        for (int i = 1; i < R; i++) {
            Ring a = rings.get(i - 1), b = rings.get(i);
            cum[i] = cum[i - 1] + Math.abs(b.y - a.y) + 1e-6;   // v linear in height: the painted belt, mask... sit on their rings
        }
        for (int i = 0; i < R; i++) {
            Ring r = rings.get(i);
            double v = v0 + (v1 - v0) * cum[i] / cum[R - 1];
            for (int k = 0; k <= sides; k++) {
                double th = Math.PI * 2 * k / sides + Math.PI;   // k = 0: back
                double s = Math.sin(th), c = Math.cos(th);
                double rz = c >= 0 ? r.rzf : r.rzb;
                double f = MOD != null ? MOD.f(i, th) : 1.0;
                P.add(new double[]{r.x + s * r.rx * f, r.y, r.z + c * rz * f});
                N.add(new double[]{0, 0, 0});
                UV.add(new double[]{u0 + (u1 - u0) * k / sides, v});
                if (r.w2 > 0 && r.j2 != r.j1) {
                    JI.add(new int[]{r.j1, r.j2});
                    JW.add(new double[]{1 - r.w2, r.w2});
                } else {
                    JI.add(new int[]{r.j1});
                    JW.add(new double[]{1});
                }
                GROUP.add(group);
            }
        }
        for (int i = 0; i < R - 1; i++) {
            for (int k = 0; k < sides; k++) {
                int a = base + i * (sides + 1) + k, b = a + 1, c = a + sides + 2, d = a + sides + 1;
                // outward facing: (a, d, c, b)
                Q.add(new int[]{a, d, c, b});
            }
        }
    }

    static Ring ring(double y, double rx, double rzf, double rzb, double x, double z, int j1, int j2, double w2) {
        return new Ring(x, y, z, rx, rzf, rzb, j1, j2, w2);
    }

    static void reset() {
        P.clear(); N.clear(); UV.clear(); JI.clear(); JW.clear(); Q.clear(); GROUP.clear();
        group = 0;
        SWATCH.clear();
        MOD = null;
        FINE_GROUPS.clear();
    }

    // ------------------------------------------------------------------ more shapes

    /** Radius factor per ring and angle (pleats, gathers); null = round. */
    interface RMod { double f(int ring, double th); }
    static RMod MOD;

    /** Triangle wave -1..1 with period 1. */
    static double tri(double x) { double f = x - Math.floor(x); return Math.abs(f - 0.5) * 4 - 1; }

    /** Solid colours for small parts: 8x8 cells in the atlas at 384..512 x 320..448. */
    static final List<Integer> SWATCH = new ArrayList<>();

    static double[] swatch(int rgb) {
        int i = SWATCH.indexOf(rgb);
        if (i < 0) { SWATCH.add(rgb); i = SWATCH.size() - 1; }
        return new double[]{384 + (i % 16) * 8 + 4, 320 + (i / 16) * 8 + 4};
    }

    static void paintSwatches() {
        for (int i = 0; i < SWATCH.size(); i++) {
            int x0 = 384 + (i % 16) * 8, y0 = 320 + (i / 16) * 8, c = SWATCH.get(i);
            for (int y = y0; y < y0 + 8; y++) for (int x = x0; x < x0 + 8; x++) plain(x, y, c, 1);
        }
    }

    interface Surf { double[] at(double s, double t); }

    /**
     * Free surface over (s, t) in 0..1 x 0..1: rows along s, columns along t, textured over the atlas rectangle.
     * The faces are turned outwards: away from the part's centre, or along {@code hint} if given.
     */
    static void grid(int rows, int cols, Surf f, int j1, int j2, double w2, double u0, double v0, double u1, double v1, double[] hint) {
        group++;
        int base = P.size(), qBase = Q.size();
        for (int i = 0; i <= rows; i++) for (int k = 0; k <= cols; k++) {
            double s = i / (double) rows, t = k / (double) cols;
            double[] p = f.at(s, t);
            P.add(new double[]{p[0], p[1], p[2]});
            N.add(new double[]{0, 0, 0});
            // a surface may give its own texture position (x, y, z, u, v)
            UV.add(p.length >= 5 ? new double[]{p[3], p[4]} : new double[]{u0 + (u1 - u0) * t, v0 + (v1 - v0) * s});
            if (w2 > 0 && j2 != j1) { JI.add(new int[]{j1, j2}); JW.add(new double[]{1 - w2, w2}); }
            else { JI.add(new int[]{j1}); JW.add(new double[]{1}); }
            GROUP.add(group);
        }
        for (int i = 0; i < rows; i++) for (int k = 0; k < cols; k++) {
            int a = base + i * (cols + 1) + k;
            Q.add(new int[]{a, a + cols + 1, a + cols + 2, a + 1});
        }
        orient(base, qBase, hint);
    }

    static void grid(int rows, int cols, Surf f, int joint, double[] sw, double[] hint) {
        grid(rows, cols, f, joint, joint, 0, sw[0], sw[1], sw[0], sw[1], hint);
    }

    /** Flips the new quads if they mostly face inwards. */
    static void orient(int base, int qBase, double[] hint) {
        double[] c = new double[3];
        for (int v = base; v < P.size(); v++) for (int k = 0; k < 3; k++) c[k] += P.get(v)[k] / (P.size() - base);
        double sum = 0;
        for (int q = qBase; q < Q.size(); q++) {
            int[] qq = Q.get(q);
            double[] a = P.get(qq[0]), b = P.get(qq[1]), cc = P.get(qq[2]), d = P.get(qq[3]);
            double[] n1 = cross(sub(b, a), sub(cc, a)), n2 = cross(sub(cc, a), sub(d, a));
            double[] n = {n1[0] + n2[0], n1[1] + n2[1], n1[2] + n2[2]};
            double[] fc = {(a[0] + cc[0]) / 2 - c[0], (a[1] + cc[1]) / 2 - c[1], (a[2] + cc[2]) / 2 - c[2]};
            sum += hint != null ? n[0] * hint[0] + n[1] * hint[1] + n[2] * hint[2] : n[0] * fc[0] + n[1] * fc[1] + n[2] * fc[2];
        }
        if (sum < 0) for (int q = qBase; q < Q.size(); q++) {
            int[] qq = Q.get(q);
            Q.set(q, new int[]{qq[0], qq[3], qq[2], qq[1]});
        }
    }

    /** Rotation matrix (rows) from degrees: yaw about Y, pitch about X, roll about Z (applied roll, pitch, yaw). */
    static double[] rot(double yaw, double pitch, double roll) {
        double cy = Math.cos(Math.toRadians(yaw)), sy = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        double cr = Math.cos(Math.toRadians(roll)), sr = Math.sin(Math.toRadians(roll));
        double[] ry = {cy, 0, sy, 0, 1, 0, -sy, 0, cy};
        double[] rx = {1, 0, 0, 0, cp, -sp, 0, sp, cp};
        double[] rz = {cr, -sr, 0, sr, cr, 0, 0, 0, 1};
        return m3(ry, m3(rx, rz));
    }

    static double[] m3(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return r;
    }

    static double[] apply3(double[] m, double x, double y, double z) {
        return new double[]{m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z};
    }

    /** Ellipsoid (radii rx, ry, rz) turned by m around its centre c, one solid colour. */
    static void blob(double cx, double cy, double cz, double rx, double ry, double rz, double[] m, int joint, int rgb) {
        blob(cx, cy, cz, rx, ry, rz, m, joint, rgb, 8, 12);
    }

    static void blob(double cx, double cy, double cz, double rx, double ry, double rz, double[] m, int joint, int rgb, int rows, int cols) {
        double[] sw = swatch(rgb);
        grid(rows, cols, (s, t) -> {
            double a = Math.PI * s, th = Math.PI * 2 * t + Math.PI;
            double[] p = apply3(m, Math.sin(a) * Math.sin(th) * rx, Math.cos(a) * ry, Math.sin(a) * Math.cos(th) * rz);
            return new double[]{cx + p[0], cy + p[1], cz + p[2]};
        }, joint, sw, null);
    }

    /** Short ring band (a raised cuff, belt, sole...) of one colour around a vertical axis. */
    static void band(double x, double z, double yTop, double yBot, double rx, double rzf, double rzb, double bulge, int joint, int rgb, int sides) {
        double[] sw = swatch(rgb);
        List<Ring> r = new ArrayList<>();
        r.add(ring(yTop, rx * 0.93, rzf * 0.93, rzb * 0.93, x, z, joint, joint, 0));
        r.add(ring(yTop - (yTop - yBot) * 0.15, rx, rzf, rzb, x, z, joint, joint, 0));
        r.add(ring((yTop + yBot) / 2, rx + bulge, rzf + bulge, rzb + bulge, x, z, joint, joint, 0));
        r.add(ring(yBot + (yTop - yBot) * 0.15, rx, rzf, rzb, x, z, joint, joint, 0));
        r.add(ring(yBot, rx * 0.93, rzf * 0.93, rzb * 0.93, x, z, joint, joint, 0));
        tube(r, sides, sw[0], sw[1], sw[0], sw[1]);
    }

    static void buildMesh() {
        // ---- torso (atlas 0..256 x 0..256)
        List<Ring> t = new ArrayList<>();
        double[][] tr = {
                {1.53, 0.07, 0.06, 0.06}, {1.49, 0.13, 0.08, 0.09}, {1.45, 0.185, 0.105, 0.11}, {1.39, 0.195, 0.118, 0.115},
                {1.30, 0.185, 0.122, 0.112}, {1.20, 0.17, 0.112, 0.105}, {1.10, 0.163, 0.108, 0.105}, {1.04, 0.168, 0.112, 0.11},
                {0.97, 0.172, 0.115, 0.118}, {0.90, 0.165, 0.11, 0.12}, {0.86, 0.12, 0.085, 0.09}, {0.84, 0.03, 0.03, 0.03}};
        for (double[] r : tr) {
            double y = r[0];
            int j1, j2; double w;
            if (y >= 1.32) { j1 = CHEST; j2 = CHEST; w = 0; }
            else if (y >= 1.18) { j1 = SPINE; j2 = CHEST; w = (y - 1.18) / 0.14; }
            else if (y >= 1.04) { j1 = HIPS; j2 = SPINE; w = (y - 1.04) / 0.14; }
            else { j1 = HIPS; j2 = HIPS; w = 0; }
            t.add(ring(y, r[1], r[2], r[3], 0, 0, j1, j2, w));
        }
        tube(t, 24, 0, 0, 256, 256);

        // ---- head and hood (atlas 256..512 x 0..256)
        List<Ring> h = new ArrayList<>();
        double[][] hr = {
                {1.885, 0.004, 0.004, 0.004}, {1.865, 0.055, 0.05, 0.06}, {1.825, 0.1, 0.092, 0.112}, {1.765, 0.125, 0.112, 0.135},
                {1.69, 0.13, 0.118, 0.142}, {1.61, 0.126, 0.114, 0.138}, {1.55, 0.114, 0.1, 0.126}, {1.51, 0.1, 0.09, 0.105},
                {1.475, 0.15, 0.105, 0.12}, {1.44, 0.175, 0.112, 0.13}};
        for (double[] r : hr) {
            double y = r[0];
            int j1 = y >= 1.53 ? HEAD : (y >= 1.49 ? NECK : CHEST);
            int j2 = y >= 1.53 ? HEAD : (y >= 1.49 ? HEAD : CHEST);
            double w = (y >= 1.49 && y < 1.53) ? 0.4 : 0;
            h.add(ring(y, r[1], r[2], r[3], 0, 0.005, j1, j2, w));
        }
        tube(h, 24, 256, 0, 512, 256);

        // ---- arms (sleeve atlas 0..128 x 256..512, glove 256..320 x 256..320)
        for (int side = -1; side <= 1; side += 2) {
            boolean left = side > 0;
            int ua = left ? UAL : UAR, fa = left ? FAL : FAR, hd = left ? HL : HR;
            double x0 = 0.215 * side;
            List<Ring> a = new ArrayList<>();
            double[][] ar = {{1.50, 0.035, 0.0}, {1.47, 0.062, 0.003}, {1.40, 0.068, 0.006}, {1.30, 0.064, 0.008},
                    {1.19, 0.06, 0.01}, {1.15, 0.059, 0.01}, {1.06, 0.057, 0.013}, {0.97, 0.053, 0.015}, {0.94, 0.047, 0.015}};
            for (double[] r : ar) {
                double y = r[0];
                int j1 = y > 1.21 ? ua : (y > 1.13 ? ua : fa), j2 = y > 1.21 ? ua : fa;
                double w = y > 1.21 ? 0 : (y > 1.13 ? (1.21 - y) / 0.08 : 0);
                a.add(ring(y, r[1], r[1] * 0.95, r[1] * 0.95, x0 + r[2] * side, 0, j1, j2, w));
            }
            tube(a, 16, 0, 256, 128, 512);
            List<Ring> g = new ArrayList<>();
            double[][] gr = {{0.955, 0.04, 0.04}, {0.93, 0.045, 0.042}, {0.9, 0.045, 0.04}, {0.87, 0.042, 0.035}, {0.845, 0.038, 0.03}, {0.825, 0.03, 0.022}, {0.81, 0.012, 0.01}};
            for (double[] r : gr) {
                double y = r[0];
                g.add(ring(y, r[1], r[2], r[2], x0 + 0.016 * side, 0.004, y > 0.93 ? fa : hd, hd, y > 0.93 ? 0.5 : 0));
            }
            tube(g, 12, 256, 256, 320, 320);
        }

        // ---- legs (atlas 128..256 x 256..512, boots 320..384 x 256..320)
        for (int side = -1; side <= 1; side += 2) {
            boolean left = side > 0;
            int th = left ? THL : THR, sh = left ? SHL : SHR, ft = left ? FTL : FTR;
            double x0 = 0.095 * side;
            List<Ring> l = new ArrayList<>();
            double[][] lr = {{0.95, 0.088}, {0.86, 0.09}, {0.74, 0.083}, {0.62, 0.076}, {0.53, 0.072}, {0.47, 0.071},
                    {0.36, 0.069}, {0.25, 0.07}, {0.17, 0.074}, {0.135, 0.072}};
            for (double[] r : lr) {
                double y = r[0];
                int j1 = y > 0.55 ? th : (y > 0.45 ? th : (y > 0.16 ? sh : sh)), j2 = y > 0.55 ? th : sh;
                double w = y > 0.55 ? 0 : (y > 0.45 ? (0.55 - y) / 0.1 : 0);
                if (y < 0.16) { j1 = sh; j2 = ft; w = 0.3; }
                l.add(ring(y, r[1], r[1], r[1], x0 + 0.004 * side, 0, j1, j2, w));
            }
            double[] legReg = LEG_REG[left ? 0 : 1];
            tube(l, 16, legReg[0], legReg[1], legReg[2], legReg[3]);
            List<Ring> b = new ArrayList<>();
            double[][] br = {{0.16, 0.058, 0.065, 0.06, 0.0}, {0.115, 0.064, 0.11, 0.068, 0.02}, {0.06, 0.066, 0.125, 0.07, 0.03},
                    {0.025, 0.068, 0.132, 0.072, 0.03}, {0.0, 0.066, 0.128, 0.07, 0.03}, {-0.005, 0.03, 0.06, 0.03, 0.03}};
            for (double[] r : br) {
                double y = r[0];
                b.add(ring(Math.max(0, y), r[1], r[2], r[3], x0, r[4], y > 0.14 ? sh : ft, ft, y > 0.14 ? 0.5 : 0));
            }
            tube(b, 14, 320, 256, 384, 320);
            // boot cuff and a thick rubber sole
            band(x0, 0.0, 0.165, 0.14, 0.061, 0.068, 0.063, 0.003, sh, 0x141416, 16);
            band(x0, 0.03, 0.022, -0.004, 0.071, 0.136, 0.075, 0.002, ft, 0x2C2C2E, 20);
        }
        guardDetails(hr);
        smoothNormals();
    }

    /** Guard: the mask as a raised glossy plate, the hood's rolled edge round it, belt, buckle, zipper pull, glove cuffs and thumbs. */
    static void guardDetails(double[][] hr) {
        java.util.function.DoubleFunction<double[]> headAt = y -> {
            for (int i = 0; i < hr.length - 1; i++) {
                if (y <= hr[i][0] && y >= hr[i + 1][0]) {
                    double t = (hr[i][0] - y) / (hr[i][0] - hr[i + 1][0]);
                    return new double[]{hr[i][1] + (hr[i + 1][1] - hr[i][1]) * t, hr[i][2] + (hr[i + 1][2] - hr[i][2]) * t};
                }
            }
            return new double[]{hr[hr.length - 1][1], hr[hr.length - 1][2]};
        };
        // point on the head surface for mask coordinates (du, dv in -1..1 over the painted mask ellipse), lifted
        java.util.function.BiFunction<double[], Double, double[]> onHead = (d, lift) -> {
            double u = 0.5 + 0.165 * d[0], yy = 1.675 + 0.135 * d[1];
            double th = Math.PI * 2 * (u - 0.5);
            double[] r = headAt.apply(yy);
            double x = Math.sin(th) * (r[0] + lift), z = Math.cos(th) * (r[1] + lift) + 0.005;
            return new double[]{x, yy, z, 256 + u * 256, (1.885 - yy) / 0.445 * 256};
        };
        // the mask: a slightly domed plate over the painted mask, carrying the same picture (symbol and gloss)
        grid(8, 32, (s, t) -> {
            double a = Math.PI * 2 * t, r = s * 0.985;
            double lift = 0.004 + 0.007 * (1 - Math.pow(r, 4));
            return onHead.apply(new double[]{r * Math.cos(a), r * Math.sin(a)}, lift);
        }, HEAD, HEAD, 0, 0, 0, 0, 0, new double[]{0, 0, 1});
        // the hood's opening: a rolled red edge round the mask
        double[] rim = swatch(0x9E2236);
        grid(40, 8, (s, t) -> {
            double a = Math.PI * 2 * s;
            double[] b = onHead.apply(new double[]{1.06 * Math.cos(a), 1.06 * Math.sin(a)}, 0.004);
            double[] c = onHead.apply(new double[]{0, 0}, 0.004);
            double nx = Math.sin(Math.PI * 2 * 0.165 * 1.06 * Math.cos(a)), nz = Math.cos(Math.PI * 2 * 0.165 * 1.06 * Math.cos(a));
            double ox = b[0] - c[0], oy = b[1] - c[1], oz = b[2] - c[2];
            double ol = Math.sqrt(ox * ox + oy * oy + oz * oz) + 1e-9;
            double ang = Math.PI * 2 * t, rr = 0.011;
            return new double[]{b[0] + nx * rr * (1 + Math.cos(ang)) + ox / ol * rr * Math.sin(ang), b[1] + oy / ol * rr * Math.sin(ang),
                    b[2] + nz * rr * (1 + Math.cos(ang)) + oz / ol * rr * Math.sin(ang)};
        }, HEAD, HEAD, 0, rim[0], rim[1], rim[0], rim[1], null);
        // belt, buckle, zipper pull
        band(0, 0, 1.037, 0.963, 0.173, 0.117, 0.12, 0.004, HIPS, 0x18181A, 28);
        blob(0, 1.0, 0.121, 0.03, 0.024, 0.007, rot(0, 0, 0), HIPS, 0x55555C, 6, 12);
        blob(0, 1.0, 0.126, 0.018, 0.013, 0.004, rot(0, 0, 0), HIPS, 0x18181A, 4, 10);
        blob(0, 1.415, 0.12, 0.006, 0.015, 0.004, rot(0, -8, 0), CHEST, 0x3A3A40, 4, 8);
        // gloves: cuff over the sleeve end and a thumb
        for (int side = -1; side <= 1; side += 2) {
            int fa = side > 0 ? FAL : FAR, hd = side > 0 ? HL : HR;
            double x0 = 0.215 * side + 0.016 * side;
            band(x0, 0.004, 0.962, 0.932, 0.049, 0.047, 0.047, 0.004, fa, 0x161618, 16);
            blob(x0 - 0.006 * side, 0.885, 0.034, 0.012, 0.025, 0.012, rot(0, -20, 12 * side), hd, 0x1C1C1E, 6, 10);
        }
    }

    // ------------------------------------------------------------------ dolls (generic parts weighted by joint heights)

    /** Torso ring {y, rx, rzFront, rzBack}: chest above, spine and hips blended below. */
    static void torso(double[][] rings, int sides) {
        double hy = JG[HIPS][1], sy = JG[SPINE][1], cy = JG[CHEST][1];
        List<Ring> t = new ArrayList<>();
        for (double[] r : rings) {
            double y = r[0];
            int j1, j2; double w;
            if (y >= cy) { j1 = CHEST; j2 = CHEST; w = 0; }
            else if (y >= sy + 0.04) { j1 = SPINE; j2 = CHEST; w = (y - sy - 0.04) / (cy - sy - 0.04); }
            else if (y >= hy + 0.04) { j1 = HIPS; j2 = SPINE; w = (y - hy - 0.04) / (sy - hy); }
            else { j1 = HIPS; j2 = HIPS; w = 0; }
            t.add(ring(y, r[1], r[2], r[3], 0, r.length > 4 ? r[4] : 0, j1, j2, w));
        }
        double range = TORSO_Y0 - TORSO_Y1;
        tube(t, sides, 0, (TORSO_Y0 - rings[0][0]) / range * 256, 256, (TORSO_Y0 - rings[rings.length - 1][0]) / range * 256);
    }

    /** Head ring {y, rx, rzFront, rzBack}: the head joint, the neck below it, the chest at the very bottom. */
    static void head(double[][] rings, int sides) {
        double ny = JG[NECK][1];
        List<Ring> h = new ArrayList<>();
        for (double[] r : rings) {
            double y = r[0];
            int j1 = y >= ny + 0.02 ? HEAD : (y >= ny - 0.02 ? NECK : CHEST);
            int j2 = y >= ny + 0.02 ? HEAD : (y >= ny - 0.02 ? HEAD : CHEST);
            h.add(ring(y, r[1], r[2], r[3], 0, 0.0, j1, j2, (y >= ny - 0.02 && y < ny + 0.02) ? 0.4 : 0));
        }
        tube(h, sides, 256, 0, 512, 256);
    }

    /** Arm ring {y, r, xOffset}; hand ring {y, rx, rz}. Elbow blended, wrist blended. */
    static void arm(double[][] sleeve, double[][] hand) {
        double ey = JG[FAL][1];
        for (int side = -1; side <= 1; side += 2) {
            boolean left = side > 0;
            int ua = left ? UAL : UAR, fa = left ? FAL : FAR, hd = left ? HL : HR;
            double x0 = JG[ua][0];
            List<Ring> a = new ArrayList<>();
            for (double[] r : sleeve) {
                double y = r[0];
                int j1 = y > ey + 0.04 ? ua : (y > ey - 0.04 ? ua : fa), j2 = y > ey + 0.04 ? ua : fa;
                double w = y > ey + 0.04 ? 0 : (y > ey - 0.04 ? (ey + 0.04 - y) / 0.08 : 0);
                a.add(ring(y, r[1], r[1] * 0.95, r[1] * 0.95, x0 + r[2] * side, 0, j1, j2, w));
            }
            tube(a, 10, 0, 256, 128, 512);
            List<Ring> g = new ArrayList<>();
            double wy = JG[hd][1];
            for (double[] r : hand) {
                double y = r[0];
                g.add(ring(y, r[1], r[2], r[2], JG[hd][0] + 0.004 * side, 0.004, y > wy + 0.01 ? fa : hd, hd, y > wy + 0.01 ? 0.5 : 0));
            }
            tube(g, 8, 256, 256, 320, 320);
        }
    }

    /** Leg ring {y, r}; shoe ring {y, rx, rzFront, rzBack, zOffset}. Knee and ankle blended. */
    static void leg(double[][] legRings, double[][] shoe) {
        double ky = JG[SHL][1], ay = JG[FTL][1];
        for (int side = -1; side <= 1; side += 2) {
            boolean left = side > 0;
            int th = left ? THL : THR, sh = left ? SHL : SHR, ft = left ? FTL : FTR;
            double x0 = JG[th][0];
            List<Ring> l = new ArrayList<>();
            for (double[] r : legRings) {
                double y = r[0];
                int j1 = y > ky + 0.05 ? th : (y > ky - 0.05 ? th : sh), j2 = y > ky + 0.05 ? th : sh;
                double w = y > ky + 0.05 ? 0 : (y > ky - 0.05 ? (ky + 0.05 - y) / 0.1 : 0);
                if (y < ay + 0.06) { j1 = sh; j2 = ft; w = 0.3; }
                l.add(ring(y, r[1], r[1], r[1], x0, 0, j1, j2, w));
            }
            tube(l, 10, 128, 256, 256, 512);
            List<Ring> b = new ArrayList<>();
            for (double[] r : shoe) {
                double y = r[0];
                b.add(ring(Math.max(0, y), r[1], r[2], r[3], x0, r[4], y > ay + 0.01 ? sh : ft, ft, y > ay + 0.01 ? 0.5 : 0));
            }
            tube(b, 10, 320, 256, 384, 320);
        }
    }

    static final int EYE_U0 = 384, EYE_V0 = 448, EYE_W = 64, EYE_H = 32, EYE_RED_DV = 32;
    /** Height range the torso picture covers (top, bottom): a torso tube maps its rings into it by height. */
    static double TORSO_Y0 = 1.20, TORSO_Y1 = 0.55;

    /**
     * An eye as its own small curved patch just in front of the face (3 x 2 quads), textured from the eye rectangle
     * of the atlas. The game swaps these quads to the red-eye rectangle (EYE_RED_DV lower) when the eyes glow.
     */
    static void eyePatch(double cx, double cy, double w, double h, double faceRx, double faceRz, boolean mirror) {
        group++;
        int base = P.size();
        int nx = 3, ny = 2;
        for (int j = 0; j <= ny; j++) {
            for (int i = 0; i <= nx; i++) {
                double x = cx + (i / (double) nx - 0.5) * w;
                double y = cy + (0.5 - j / (double) ny) * h;
                double s = Math.max(-0.99, Math.min(0.99, x / faceRx));
                double z = faceRz * Math.sqrt(1 - s * s) + 0.0035;
                P.add(new double[]{x, y, z});
                N.add(new double[]{0, 0, 0});
                double u = mirror ? 1 - i / (double) nx : i / (double) nx;
                UV.add(new double[]{EYE_U0 + 0.5 + u * (EYE_W - 1), EYE_V0 + 0.5 + j / (double) ny * (EYE_H - 1)});
                JI.add(new int[]{HEAD});
                JW.add(new double[]{1});
                GROUP.add(group);
            }
        }
        for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
            int a = base + j * (nx + 1) + i;
            // counter-clockwise seen from the front (+Z): top-left, bottom-left, bottom-right, top-right
            Q.add(new int[]{a, a + nx + 1, a + nx + 2, a + 1});
        }
    }

    // ================================================================== detailed dolls

    /** Head profile of both dolls (top to the neck): y and the side radius; front 0.97x, back 1.03x of it. */
    static double[] HY;
    static final double[] DOLL_HY = {1.50, 1.495, 1.48, 1.455, 1.425, 1.39, 1.355, 1.32, 1.285, 1.255, 1.23, 1.21, 1.195, 1.18, 1.165, 1.13};
    static double[] HRX;
    /** Front / back depth of the head as a share of its width (the dolls: round heads). */
    static double HZF = 0.97, HZB = 1.03;
    static final double[] DOLL_HRX = {0.004, 0.045, 0.085, 0.12, 0.145, 0.157, 0.16, 0.158, 0.152, 0.14, 0.124, 0.104, 0.08, 0.058, 0.046, 0.047};

    static { HY = DOLL_HY; HRX = DOLL_HRX; }

    static double headRx(double y) {
        if (y >= HY[0]) return HRX[0];
        for (int i = 0; i < HY.length - 1; i++) {
            if (y <= HY[i] && y >= HY[i + 1]) {
                double t = (HY[i] - y) / (HY[i] - HY[i + 1]);
                return HRX[i] + (HRX[i + 1] - HRX[i]) * t;
            }
        }
        return HRX[HRX.length - 1];
    }

    static void dollHead() {
        double[][] r = new double[HY.length][];
        for (int i = 0; i < HY.length; i++) r[i] = new double[]{HY[i], HRX[i], HRX[i] * HZF, HRX[i] * HZB};
        head(r, 24);
    }

    /**
     * Hair over the head: a shell 13-19 mm above the skull down to bottom(th) (th = 0 is the front), its edge
     * tucked in for thickness. Textured with the strand picture (atlas 256..384 x 320..448).
     */
    static void hairShell(java.util.function.DoubleUnaryOperator bottom, double top, double volume) {
        grid(14, 32, (s, t) -> {
            double th = Math.PI * 2 * t + Math.PI;
            double yb = bottom.applyAsDouble(th);
            double ss = Math.min(1, s / 0.9);
            double y = top + (yb - top) * Math.pow(ss, 0.95);
            double off = s <= 0.9 ? volume + 0.006 * (1 - ss) : volume - (s - 0.9) / 0.1 * (volume - 0.002);
            if (s > 0.9) y += 0.004 * (s - 0.9) / 0.1;
            double rx = headRx(y) + off;
            double c = Math.cos(th);
            return new double[]{Math.sin(th) * rx, y, c * rx * (c >= 0 ? HZF : HZB)};
        }, HEAD, HEAD, 0, 256, 320, 384, 448, null);
    }

    static double smooth01(double x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    /** Hands: a mitten (flat palm facing the body) with a thumb; a raised cuff/joint band at the wrist. */
    static void hands(double[][] handRings, int skin) {
        for (int side = -1; side <= 1; side += 2) {
            int hd = side > 0 ? HL : HR, fa = side > 0 ? FAL : FAR;
            double x0 = JG[hd][0] + 0.004 * side;
            List<Ring> g = new ArrayList<>();
            double wy = JG[hd][1];
            for (double[] r : handRings) {
                g.add(ring(r[0], r[1], r[2], r[2] * 0.9, x0, 0.006, r[0] > wy + 0.01 ? fa : hd, hd, r[0] > wy + 0.01 ? 0.5 : 0));
            }
            tube(g, 12, 256, 256, 320, 320);
            // thumb on the front, bent a little towards the palm
            blob(x0 - 0.004 * side, wy - 0.035, 0.034, 0.01, 0.021, 0.01, rot(0, -22, 14 * side), hd, skin, 6, 10);
        }
    }

    /** Arms: sleeve tube (optionally gathered by a modifier) + bands given as {yTop, yBot, r, joint(0 upper/1 fore), rgb}. */
    static void sleeves(double[][] rings, RMod gather, double[][] bands) {
        double ey = JG[FAL][1];
        for (int side = -1; side <= 1; side += 2) {
            boolean left = side > 0;
            int ua = left ? UAL : UAR, fa = left ? FAL : FAR;
            double x0 = JG[ua][0];
            List<Ring> a = new ArrayList<>();
            for (double[] r : rings) {
                double y = r[0];
                int j1 = y > ey + 0.04 ? ua : (y > ey - 0.04 ? ua : fa), j2 = y > ey + 0.04 ? ua : fa;
                double w = y > ey + 0.04 ? 0 : (y > ey - 0.04 ? (ey + 0.04 - y) / 0.08 : 0);
                a.add(ring(y, r[1], r[1] * 0.95, r[1] * 0.95, x0 + r[2] * side, 0, j1, j2, w));
            }
            MOD = gather;
            double[] sr = SLEEVE_REG[left ? 0 : 1];
            tube(a, 16, sr[0], sr[1], sr[2], sr[3]);
            MOD = null;
            for (double[] b : bands) {
                double xo = x0 + b[5] * side;
                band(xo, 0, b[0], b[1], b[2], b[2], b[2], 0.003, b[3] == 0 ? ua : fa, (int) b[4], 16);
            }
        }
    }

    /** Legs: one textured tube each + bands {yTop, yBot, r, joint(0 thigh/1 shin), rgb}; shoes with a sole. */
    static void legs(double[][] rings, double[][] bands, double[][] shoe, int soleRgb, double[][] shoeBands) {
        double ky = JG[SHL][1], ay = JG[FTL][1];
        for (int side = -1; side <= 1; side += 2) {
            boolean left = side > 0;
            int th = left ? THL : THR, sh = left ? SHL : SHR, ft = left ? FTL : FTR;
            double x0 = JG[th][0];
            List<Ring> l = new ArrayList<>();
            for (double[] r : rings) {
                double y = r[0];
                int j1 = y > ky + 0.05 ? th : (y > ky - 0.05 ? th : sh), j2 = y > ky + 0.05 ? th : sh;
                double w = y > ky + 0.05 ? 0 : (y > ky - 0.05 ? (ky + 0.05 - y) / 0.1 : 0);
                if (y < ay + 0.06) { j1 = sh; j2 = ft; w = 0.3; }
                l.add(ring(y, r[1], r[1], r[1], x0, 0, j1, j2, w));
            }
            double[] legReg = LEG_REG[left ? 0 : 1];
            tube(l, 16, legReg[0], legReg[1], legReg[2], legReg[3]);
            for (double[] b : bands) band(x0, 0, b[0], b[1], b[2], b[2], b[2], 0.004, b[3] == 0 ? th : sh, (int) b[4], 16);
            List<Ring> s = new ArrayList<>();
            for (double[] r : shoe) {
                double y = r[0];
                s.add(ring(Math.max(0, y), r[1], r[2], r[3], x0, r[4], y > ay + 0.01 ? sh : ft, ft, y > ay + 0.01 ? 0.5 : 0));
            }
            tube(s, 16, 320, 256, 384, 320);
            band(x0, 0.02, 0.016, -0.002, shoe[3][1] + 0.002, shoe[3][2] + 0.003, shoe[3][3] + 0.002, 0.002, ft, soleRgb, 18);
            for (double[] b : shoeBands) band(x0, b[5], b[0], b[1], b[2], b[3], b[4], 0.002, ft, (int) b[6], 16);
        }
    }

    static void eyes(double y, double x, double w, double h) {
        int first = P.size();
        double rx = headRx(y);
        eyePatch(x, y, w, h, rx, rx * HZF, false);
        eyePatch(-x, y, w, h, rx, rx * HZF, true);
        for (int v = first; v < P.size(); v++) {
            double[] p = P.get(v);
            N.set(v, norm(new double[]{p[0] / rx * 0.9, 0.04, 1.0}));
        }
    }

    static final int BOY_GREEN_LIGHT = 0x7DB091, BOY_SAGE = 0x6E9E84, BOY_SAGE_BAND = 0x86B39A, KHAKI_DARK = 0x9E8460;

    static void buildBoyHD() {
        dollHead();
        // nose and ears
        blob(0, 1.298, headRx(1.298) * 0.97 + 0.001, 0.013, 0.017, 0.011, rot(0, -15, 0), HEAD, SKIN_BOY, 6, 10);
        for (int s = -1; s <= 1; s += 2) blob(0.155 * s, 1.302, -0.008, 0.011, 0.027, 0.019, rot(-12 * s, 0, 0), HEAD, SKIN_BOY, 6, 10);
        // bowl cut: straight fringe in front, over the ears' tops at the sides, short at the back
        hairShell(th -> {
            double c = Math.cos(th);
            double front = smooth01((c - 0.3) / 0.3), back = smooth01((-c - 0.2) / 0.4);
            return 1.30 + front * (1.362 - 1.30) + back * (1.225 - 1.30);
        }, 1.513, 0.013);
        // eight-panel cap with a band and a button
        // a soft dome: the panels bulge a little between the seams (texture) and the crown is round
        double capR = headRx(1.405) + 0.03;
        grid(12, 32, (s, t) -> {
            double th = Math.PI * 2 * t + Math.PI, c = Math.cos(th);
            double rim = 1.402 + 0.006 * c;
            double phi = s * Math.PI / 2;
            double r = capR * Math.sin(phi) * (1 + 0.07 * Math.sin(2 * phi));
            double y = rim + (1.535 - rim) * Math.cos(phi);
            return new double[]{Math.sin(th) * r, y, c * r * (c >= 0 ? 0.98 : 1.03)};
        }, HEAD, HEAD, 0, 384, 256, 512, 320, null);
        band(0, 0, 1.414, 1.396, headRx(1.405) + 0.029, (headRx(1.405) + 0.029) * 0.98, (headRx(1.405) + 0.029) * 1.03, 0.004, HEAD, BOY_SAGE_BAND, 32);
        blob(0, 1.536, 0, 0.013, 0.006, 0.013, rot(0, 0, 0), HEAD, 0x557F69, 6, 10);
        eyes(1.333, 0.055, 0.06, 0.037);

        // body: polo shirt tucked into the shorts
        torso(new double[][]{
                {1.20, 0.05, 0.05, 0.05}, {1.185, 0.085, 0.066, 0.066}, {1.17, 0.118, 0.079, 0.08}, {1.15, 0.142, 0.089, 0.091},
                {1.12, 0.152, 0.095, 0.097}, {1.08, 0.155, 0.099, 0.099}, {1.03, 0.153, 0.098, 0.098}, {0.98, 0.149, 0.096, 0.096},
                {0.93, 0.145, 0.094, 0.095}, {0.88, 0.142, 0.093, 0.096}, {0.84, 0.142, 0.094, 0.098}, {0.815, 0.143, 0.095, 0.1},
                {0.79, 0.146, 0.097, 0.102}, {0.76, 0.149, 0.099, 0.105}, {0.735, 0.15, 0.1, 0.106}, {0.715, 0.146, 0.098, 0.104},
                {0.70, 0.12, 0.085, 0.09}, {0.69, 0.03, 0.03, 0.03}}, 24);
        // polo collar: a band round the neck and two points lying on the chest; buttons on the placket
        band(0, 0.002, 1.212, 1.172, 0.062, 0.064, 0.066, 0.004, CHEST, BOY_GREEN_LIGHT, 24);
        for (int s = -1; s <= 1; s += 2) blob(0.036 * s, 1.152, 0.091, 0.03, 0.03, 0.006, rot(24 * s, -22, -14 * s), CHEST, BOY_GREEN_LIGHT, 6, 10);
        for (double by : new double[]{1.125, 1.095}) blob(0, by, 0.1, 0.007, 0.007, 0.003, rot(0, 0, 0), CHEST, 0xE8E8E0, 4, 8);
        // belt with a buckle and loops
        band(0, 0, 0.818, 0.787, 0.147, 0.099, 0.104, 0.004, HIPS, 0x161616, 28);
        blob(0, 0.8025, 0.106, 0.022, 0.014, 0.006, rot(0, 0, 0), HIPS, 0xBDBDB8, 6, 10);
        blob(0, 0.8025, 0.11, 0.012, 0.007, 0.003, rot(0, 0, 0), HIPS, 0x161616, 4, 8);
        for (int s = -1; s <= 1; s += 2) blob(0.116 * s, 0.802, 0.066, 0.006, 0.018, 0.004, rot(50 * s, 0, 0), HIPS, KHAKI_DARK, 4, 8);

        sleeves(new double[][]{{1.165, 0.028, 0}, {1.157, 0.045, 0.002}, {1.135, 0.053, 0.004}, {1.10, 0.054, 0.006}, {1.04, 0.052, 0.007},
                        {0.98, 0.049, 0.009}, {0.94, 0.048, 0.01}, {0.90, 0.047, 0.011}, {0.85, 0.046, 0.012}, {0.80, 0.045, 0.013},
                        {0.785, 0.044, 0.013}, {0.775, 0.03, 0.013}}, null,
                new double[][]{{0.806, 0.778, 0.047, 1, BOY_GREEN_LIGHT, 0.013}});
        hands(new double[][]{{0.79, 0.025, 0.026}, {0.77, 0.022, 0.032}, {0.745, 0.019, 0.037}, {0.715, 0.018, 0.037},
                {0.69, 0.016, 0.032}, {0.672, 0.011, 0.02}, {0.664, 0.003, 0.006}}, SKIN_BOY);
        legs(new double[][]{{0.79, 0.06}, {0.76, 0.085}, {0.73, 0.095}, {0.69, 0.097}, {0.62, 0.094}, {0.56, 0.092}, {0.50, 0.092}, {0.475, 0.093},
                        {0.465, 0.052}, {0.44, 0.05}, {0.41, 0.052}, {0.38, 0.049}, {0.33, 0.047}, {0.27, 0.045}, {0.22, 0.043},
                        {0.205, 0.043}, {0.20, 0.047}, {0.15, 0.047}, {0.11, 0.046}, {0.09, 0.045}},
                new double[][]{{0.49, 0.461, 0.093, 0, KHAKI_DARK}, {0.215, 0.19, 0.0475, 1, 0xF2F2EE}},
                new double[][]{{0.10, 0.044, 0.05, 0.045, 0.0}, {0.085, 0.049, 0.07, 0.049, 0.01}, {0.06, 0.053, 0.09, 0.052, 0.016},
                        {0.035, 0.055, 0.102, 0.054, 0.02}, {0.015, 0.056, 0.106, 0.055, 0.02}, {0.006, 0.055, 0.104, 0.054, 0.02},
                        {0.0, 0.03, 0.06, 0.03, 0.02}}, 0x2A2A2C, new double[][]{});
        smoothNormals();
    }

    static final int GIRL_YELLOW = 0xF0D02C, GIRL_YELLOW_LIGHT = 0xF7E68A, GIRL_ORANGE = 0xE5661C, GIRL_ORANGE_DARK = 0xC9541A, PURPLE = 0x8B3FBF;

    static void buildGirlHD() {
        dollHead();
        blob(0, 1.298, headRx(1.298) * 0.97 + 0.001, 0.012, 0.015, 0.01, rot(0, -15, 0), HEAD, SKIN_GIRL, 6, 10);
        // straight bangs in front, a bob to the jaw at the sides and back
        hairShell(th -> {
            double c = Math.cos(th);
            double front = smooth01((c - 0.45) / 0.25), back = smooth01((-c - 0.1) / 0.5);
            return 1.235 + front * (1.372 - 1.235) + back * (1.222 - 1.235);
        }, 1.517, 0.015);
        // pigtails: a purple tie and a tuft in two lobes, behind each ear
        for (int s = -1; s <= 1; s += 2) {
            blob(0.163 * s, 1.29, -0.062, 0.026, 0.019, 0.026, rot(0, 0, 25 * s), HEAD, PURPLE, 6, 12);
            blob(0.17 * s, 1.302, -0.058, 0.012, 0.012, 0.012, rot(0, 0, 0), HEAD, 0xA75BDB, 5, 8);
            grid(8, 14, blobSurf(0.186 * s, 1.248, -0.066, 0.043, 0.052, 0.041, rot(0, 10 * s, 28 * s)), HEAD, HEAD, 0, 256, 320, 384, 448, null);
            grid(8, 14, blobSurf(0.197 * s, 1.205, -0.072, 0.032, 0.036, 0.031, rot(0, 0, 18 * s)), HEAD, HEAD, 0, 256, 320, 384, 448, null);
        }
        eyes(1.337, 0.058, 0.07, 0.045);

        // blouse and bodice down to the waist; the skirt is its own pleated part
        torso(new double[][]{
                {1.20, 0.05, 0.05, 0.05}, {1.185, 0.08, 0.066, 0.066}, {1.17, 0.108, 0.078, 0.078}, {1.15, 0.132, 0.088, 0.09},
                {1.12, 0.146, 0.094, 0.095}, {1.08, 0.15, 0.097, 0.097}, {1.03, 0.145, 0.095, 0.094}, {0.98, 0.14, 0.092, 0.092},
                {0.92, 0.136, 0.09, 0.09}, {0.88, 0.135, 0.09, 0.09}, {0.86, 0.13, 0.088, 0.088}, {0.85, 0.05, 0.04, 0.04}}, 24);
        band(0, 0, 1.079, 1.063, 0.151, 0.099, 0.099, 0.003, CHEST, GIRL_ORANGE, 28);           // top edge of the bodice
        band(0, 0, 0.887, 0.86, 0.138, 0.092, 0.092, 0.003, HIPS, GIRL_ORANGE_DARK, 28);       // waistband
        // Peter Pan collar: two round flaps on the chest, and a band round the neck
        band(0, 0.002, 1.205, 1.175, 0.06, 0.062, 0.064, 0.003, CHEST, GIRL_YELLOW_LIGHT, 24);
        for (int s = -1; s <= 1; s += 2) blob(0.044 * s, 1.152, 0.09, 0.047, 0.034, 0.006, rot(22 * s, -24, -6 * s), CHEST, GIRL_YELLOW_LIGHT, 6, 12);
        // skirt with real accordion pleats, deeper towards the hem
        double[][] sk = {{0.875, 0.138, 0.092, 0.092}, {0.86, 0.15, 0.1, 0.1}, {0.83, 0.165, 0.115, 0.112}, {0.78, 0.185, 0.135, 0.13},
                {0.72, 0.2, 0.15, 0.145}, {0.66, 0.212, 0.162, 0.155}, {0.60, 0.222, 0.172, 0.164}, {0.565, 0.226, 0.176, 0.168},
                {0.56, 0.222, 0.172, 0.164}, {0.555, 0.205, 0.158, 0.15}, {0.552, 0.05, 0.04, 0.04}};
        double[] amp = {0, 0.01, 0.025, 0.035, 0.042, 0.048, 0.05, 0.05, 0.05, 0.04, 0};
        List<Ring> skirt = new ArrayList<>();
        for (double[] r : sk) skirt.add(ring(r[0], r[1], r[2], r[3], 0, 0, HIPS, HIPS, 0));
        MOD = (i, th) -> 1 + amp[i] * tri(th / (Math.PI * 2) * 24 + 0.25);
        tube(skirt, 48, 0, (1.20 - 0.875) / 0.65 * 256, 256, (1.20 - 0.552) / 0.65 * 256);
        MOD = null;

        // puffed sleeves gathered at the shoulder and at the band; the doll's arm with ball joints below
        double[] gAmp = {0, 0.4, 0.8, 1, 1, 0.8, 0.5, 0, 0, 0, 0, 0, 0, 0};
        sleeves(new double[][]{{1.172, 0.032, 0}, {1.165, 0.052, 0.003}, {1.145, 0.067, 0.006}, {1.12, 0.074, 0.008}, {1.075, 0.073, 0.01},
                        {1.035, 0.064, 0.01}, {1.012, 0.05, 0.01}, {1.005, 0.036, 0.01}, {0.97, 0.034, 0.011}, {0.94, 0.033, 0.012},
                        {0.90, 0.032, 0.012}, {0.85, 0.031, 0.013}, {0.80, 0.03, 0.013}, {0.778, 0.026, 0.013}},
                (i, th) -> 1 + 0.06 * gAmp[Math.min(i, gAmp.length - 1)] * tri(th / (Math.PI * 2) * 10),
                new double[][]{{1.016, 0.998, 0.052, 0, GIRL_YELLOW, 0.01}, {0.952, 0.928, 0.0345, 1, SKIN_GIRL, 0.011},
                        {0.812, 0.79, 0.031, 1, SKIN_GIRL, 0.013}});
        hands(new double[][]{{0.79, 0.024, 0.025}, {0.77, 0.021, 0.031}, {0.745, 0.018, 0.035}, {0.715, 0.017, 0.035},
                {0.69, 0.015, 0.03}, {0.672, 0.01, 0.019}, {0.664, 0.003, 0.006}}, SKIN_GIRL);
        legs(new double[][]{{0.74, 0.05}, {0.66, 0.05}, {0.58, 0.049}, {0.52, 0.048}, {0.47, 0.047}, {0.44, 0.046}, {0.425, 0.05},
                        {0.40, 0.049}, {0.35, 0.047}, {0.30, 0.045}, {0.25, 0.043}, {0.20, 0.041}, {0.15, 0.04}, {0.11, 0.041}, {0.09, 0.041}},
                new double[][]{{0.445, 0.416, 0.05, 1, 0xF4F4F0}},
                new double[][]{{0.10, 0.042, 0.048, 0.043, 0.0}, {0.085, 0.047, 0.066, 0.047, 0.01}, {0.06, 0.051, 0.086, 0.05, 0.015},
                        {0.035, 0.053, 0.097, 0.052, 0.018}, {0.015, 0.054, 0.1, 0.053, 0.018}, {0.006, 0.053, 0.098, 0.052, 0.018},
                        {0.0, 0.03, 0.058, 0.03, 0.018}}, 0x2A2A2C,
                new double[][]{{0.084, 0.072, 0.045, 0.06, 0.046, 0.008, 0x101012}});   // Mary Jane strap
        smoothNormals();
    }

    // ================================================================== player 456 (tracksuit)

    /** Parts voxelised with the extra fine cubes (the number patches), and that cube size (0 = none). */
    static final Set<Integer> FINE_GROUPS = new HashSet<>();
    /** Texture rectangles of the left / right sleeve and leg (the same for symmetric clothes). */
    static double[][] SLEEVE_REG = {{0, 256, 128, 512}, {0, 256, 128, 512}};
    static double[][] LEG_REG = {{128, 256, 256, 512}, {128, 256, 256, 512}};
    static double FINE_CELL = 0;
    static final boolean PIXEL_DIGITS = true;

    static final double[] PLAYER_HY = {1.868, 1.862, 1.845, 1.815, 1.775, 1.73, 1.69, 1.65, 1.615, 1.59, 1.57, 1.555, 1.54, 1.52, 1.48};
    static final double[] PLAYER_HRX = {0.004, 0.05, 0.085, 0.106, 0.117, 0.12, 0.118, 0.113, 0.102, 0.088, 0.072, 0.058, 0.05, 0.052, 0.056};
    static final double[][] PLAYER_TORSO = {
            {1.53, 0.07, 0.06, 0.06}, {1.50, 0.13, 0.085, 0.09}, {1.46, 0.192, 0.11, 0.115}, {1.40, 0.202, 0.12, 0.118},
            {1.30, 0.194, 0.126, 0.116}, {1.20, 0.182, 0.12, 0.11}, {1.10, 0.178, 0.118, 0.11}, {1.03, 0.18, 0.12, 0.114},
            {1.0, 0.174, 0.116, 0.112}, {0.975, 0.165, 0.108, 0.112}, {0.92, 0.168, 0.11, 0.12}, {0.88, 0.152, 0.1, 0.112},
            {0.86, 0.08, 0.06, 0.06}, {0.845, 0.03, 0.03, 0.03}};
    static final int TEAL = 0x2F6E7C, TEAL_DARK = 0x255A66, TRACK_WHITE = 0xEDEFEE, SKIN_ADULT = 0xE0B593;

    /** The torso's surface at height y and angle th (front 0), from its rings. */
    static double[] torsoAt(double y, double th) {
        double[][] r = PLAYER_TORSO;
        int i = 0;
        while (i < r.length - 2 && r[i + 1][0] > y) i++;
        double t = (r[i][0] - y) / (r[i][0] - r[i + 1][0]);
        double rx = r[i][1] + (r[i + 1][1] - r[i][1]) * t;
        double c = Math.cos(th);
        double rz = c >= 0 ? r[i][2] + (r[i + 1][2] - r[i][2]) * t : r[i][3] + (r[i + 1][3] - r[i][3]) * t;
        return new double[]{Math.sin(th) * rx, y, c * rz};
    }

    /**
     * A number patch sewn on the jacket: a plate following the torso, 2 cm above it, showing the patch painted in
     * the torso picture (u0..u1 around, y0..y1 down); voxelised with the fine cubes so the digits stay readable.
     */
    static void patch(double u0, double u1, double y0, double y1, int[] rect) {
        group++;
        FINE_GROUPS.add(group);
        group--;
        double range = TORSO_Y0 - TORSO_Y1;
        grid(6, 12, (s, t) -> {
            double u = u0 + (u1 - u0) * t, y = y0 + (y1 - y0) * s;
            double th = Math.PI * 2 * (u - 0.5);
            double[] p = torsoAt(y, th);
            double nx = Math.sin(th), nz = Math.cos(th);
            double lift = 0.03;
            // the patch picture has its own spot in the texture (no wrap-around at the back)
            return new double[]{p[0] + nx * lift, y, p[2] + nz * lift, rect[0] + 0.5 + t * (rect[2] - 1), rect[1] + 0.5 + s * (rect[3] - 1)};
        }, CHEST, CHEST, 0, 0, 0, 0, 0, null);
    }

    static void buildPlayer() {
        // head: short dark hair, ears, nose, eyes
        HY = PLAYER_HY;
        HRX = PLAYER_HRX;
        HZF = 1.04;
        HZB = 1.1;
        dollHead();
        blob(0, 1.662, headRx(1.662) * HZF + 0.002, 0.014, 0.022, 0.014, rot(0, -12, 0), HEAD, SKIN_ADULT, 6, 10);
        for (int s = -1; s <= 1; s += 2) blob(headRx(1.68) * s, 1.68, -0.012, 0.012, 0.03, 0.021, rot(-10 * s, 0, 0), HEAD, SKIN_ADULT, 6, 10);
        hairShell(th -> {
            double c = Math.cos(th);
            double front = smooth01((c - 0.35) / 0.3), back = smooth01((-c - 0.2) / 0.4);
            return 1.70 + front * (1.79 - 1.70) + back * (1.615 - 1.70);
        }, 1.88, 0.012);
        eyes(1.705, 0.043, 0.046, 0.022);

        // jacket and the top of the trousers
        TORSO_Y0 = 1.56;
        TORSO_Y1 = 0.845;
        torso(PLAYER_TORSO, 28);
        // stand collar, zipper pull, ribbed waistband
        band(0, 0.004, 1.57, 1.50, 0.066, 0.07, 0.073, 0.004, CHEST, TEAL, 28);
        blob(0.004, 1.49, 0.096, 0.007, 0.016, 0.004, rot(0, -6, 0), CHEST, 0x9AA3A6, 4, 8);
        band(0, 0, 1.035, 0.975, 0.182, 0.122, 0.117, 0.005, HIPS, TEAL_DARK, 32);
        // the numbers: front on the left chest, a big one on the back
        patch(0.54, 0.72, 1.44, 1.345, FRONT_PATCH);
        patch(-0.11, 0.11, 1.44, 1.355, BACK_PATCH);

        // sleeves: wide, gathered into ribbed cuffs
        sleeves(new double[][]{{1.50, 0.035, 0}, {1.47, 0.068, 0.003}, {1.40, 0.076, 0.006}, {1.30, 0.074, 0.008}, {1.19, 0.07, 0.01},
                        {1.12, 0.068, 0.011}, {1.04, 0.066, 0.013}, {0.995, 0.062, 0.014}, {0.975, 0.05, 0.015}, {0.96, 0.03, 0.015}},
                (i, th) -> 1 + (i >= 5 && i <= 7 ? 0.04 * tri(th / (Math.PI * 2) * 9) : 0),
                new double[][]{{0.99, 0.932, 0.047, 1, TEAL_DARK, 0.016}});
        hands(new double[][]{{0.95, 0.03, 0.03}, {0.92, 0.026, 0.036}, {0.88, 0.022, 0.042}, {0.84, 0.021, 0.042},
                {0.81, 0.018, 0.035}, {0.79, 0.012, 0.022}, {0.78, 0.004, 0.006}}, SKIN_ADULT);
        // joggers with elastic ankle cuffs; white slip-on shoes
        legs(new double[][]{{0.97, 0.094}, {0.86, 0.096}, {0.74, 0.09}, {0.62, 0.084}, {0.53, 0.08}, {0.47, 0.079}, {0.36, 0.078},
                        {0.26, 0.08}, {0.2, 0.082}, {0.17, 0.07}, {0.13, 0.064}, {0.105, 0.06}},
                new double[][]{{0.175, 0.12, 0.068, 1, TEAL_DARK}},
                new double[][]{{0.105, 0.055, 0.065, 0.058, 0.0}, {0.08, 0.062, 0.1, 0.064, 0.018}, {0.045, 0.065, 0.122, 0.068, 0.025},
                        {0.02, 0.067, 0.13, 0.07, 0.028}, {0.006, 0.066, 0.128, 0.069, 0.028}, {0.0, 0.035, 0.065, 0.035, 0.028}},
                0xCFCFCB, new double[][]{});
        smoothNormals();
    }

    static void paintPlayer(Path out) throws IOException {
        tex = new int[T * T];
        double range = TORSO_Y0 - TORSO_Y1;
        // ---- torso: jacket (zipper, white raglan stripes, the patches), trousers below the waistband
        region(0, 0, 256, 256, TORSO_Y0, TORSO_Y1, (x, y, u, yy) -> {
            double side = Math.cos((u - 0.5) * Math.PI * 2);
            double fold = 0.25 * Math.sin(u * 30 + yy * 7) * (yy < 1.2 ? 1 : 0.4) + 0.35 * Math.sin(u * 70) * (yy < 1.08 && yy > 0.98 ? 1 : 0);
            cloth(x, y, TEAL, fold, 0.92 + 0.08 * side, 0.18 * sheen(u, 4));
            // raglan stripe: from the collar over each shoulder (sides of the torso, u = 0.25 / 0.75)
            double ds = Math.min(Math.abs(u - 0.25), Math.abs(u - 0.75));
            double dsf = Math.min(Math.abs(u - 0.29), Math.abs(u - 0.71));   // a little towards the front, like the sleeves
            if (yy > 1.40 && dsf < 0.05 + (yy - 1.40) * 0.5) plain(x, y, TRACK_WHITE, 0.95 + 0.05 * sheen(u, 3));
            // trousers' side stripe
            if (yy < 0.975 && ds < 0.014) plain(x, y, TRACK_WHITE, 0.95);
            // zipper from the collar to the hem
            if (yy > 0.99 && Math.abs(u - 0.5) < 0.004) plain(x, y, 0x8C9598, Math.abs(u - 0.5) < 0.0015 ? 1.0 : 0.8);
        });
        // the number patches: dark teal with white "456"
        numberPatch(FRONT_PATCH);
        numberPatch(BACK_PATCH);
        region(256, 0, 256, 256, HY[0], HY[HY.length - 1], (x, y, u, yy) -> faceHD(x, y, u, yy - 0.37, SKIN_ADULT, false));
        // ---- sleeves: teal; the white stripe runs over the top-outside of each arm from the shoulder to the elbow
        // (left arm = +x: its outside is u = 0.75; right arm: u = 0.25; both slightly turned to the front)
        for (int side = 0; side < 2; side++) {
            double[] r = SLEEVE_REG[side];
            double stripeU = side == 0 ? 0.70 : 0.30;
            region((int) r[0], (int) r[1], (int) (r[2] - r[0]), (int) (r[3] - r[1]), 1.50, 0.96, (x, y, u, yy) -> {
                double fold = 0.45 * Math.sin(yy * 150 + Math.sin(u * 6) * 1.5) * (Math.abs(yy - 1.16) < 0.05 ? 1 : 0) + 0.2 * Math.sin(u * 22 + yy * 6)
                        + 0.5 * tri(u * 9) * (yy < 1.05 ? 1 : 0);
                cloth(x, y, TEAL, fold, 1, 0.2 * sheen(u, 4));
                // wide at the shoulder, narrowing towards the elbow
                double w = 0.05 + 0.08 * Math.max(0, (yy - 1.2) / 0.3);
                if (yy > 1.17 && Math.abs(u - stripeU) < w) plain(x, y, TRACK_WHITE, 0.94 + 0.06 * sheen(u, 3));
            });
        }
        // ---- legs: teal joggers, the white stripe on the outside only, bunched above the cuffs
        for (int side = 0; side < 2; side++) {
            double[] r = LEG_REG[side];
            double stripeU = side == 0 ? 0.73 : 0.27;
            region((int) r[0], (int) r[1], (int) (r[2] - r[0]), (int) (r[3] - r[1]), 0.97, 0.105, (x, y, u, yy) -> {
                double fold = 0.3 * Math.sin(u * 20 + yy * 25) + 0.6 * Math.sin(yy * 140 + u * 4) * (yy < 0.26 ? 1 : 0)
                        + 0.4 * Math.sin(yy * 60) * (Math.abs(yy - 0.5) < 0.06 ? 1 : 0);
                cloth(x, y, TEAL, fold, 1, 0.15 * sheen(u, 4));
                if (Math.abs(u - stripeU) < 0.04 && yy > 0.17) plain(x, y, TRACK_WHITE, 0.95);
            });
        }
        paintHands(SKIN_ADULT);
        region(320, 256, 64, 64, 0.105, 0.0, (x, y, u, yy) -> plain(x, y, TRACK_WHITE, 0.86 + 0.16 * sheen(u, 5) - (yy < 0.012 ? 0.1 : 0)));
        paintHair(0x2A2624);
        paintEyesHD(SKIN_ADULT, false, 0.046, 0.022);
        paintSwatches();
        fillRest(0xFF2F6E7C);
        save(out);
    }

    /** Dark teal patch with "456" in white, drawn with the system font into the torso picture. */
    /** Texture spots of the two patch pictures {x, y, w, h} (the free strip at 256..384 x 448..512). */
    static final int[] FRONT_PATCH = {256, 448, 48, 26}, BACK_PATCH = {256, 476, 64, 30};

    static void numberPatch(int[] rect) {
        int px0 = rect[0], py0 = rect[1], w = rect[2], h = rect[3];
        if (PIXEL_DIGITS) {
            // a 5x7 pixel font: every font pixel becomes one cube, so the digits stay sharp
            String[][] font = {
                    {"...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."},
                    {"#####", "#....", "####.", "....#", "....#", "#...#", ".###."},
                    {"..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."}};
            double areaW = w * 0.84, areaH = h * 0.7;
            double cellW = areaW / 17.0, cellH = areaH / 7.0;   // 3 digits of 5 + 2 gaps of 1
            double ox = (w - areaW) / 2, oy = (h - areaH) / 2;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int col = (int) Math.floor((x + 0.5 - ox) / cellW), row = (int) Math.floor((y + 0.5 - oy) / cellH);
                boolean on = false;
                if (row >= 0 && row < 7 && col >= 0 && col < 17) {
                    int d = col / 6, c = col % 6;
                    on = c < 5 && d < 3 && font[d][row].charAt(c) == '#';
                }
                int tx = px0 + x, ty = py0 + y;
                if (on) put(tx, ty, 240, 242, 240);
                else put(tx, ty, 0x1F, 0x3F, 0x47);
            }
            return;
        }
        BufferedImage img = new BufferedImage(w * 8, h * 8, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x1F3F47));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        java.awt.Font f = new java.awt.Font("SansSerif", java.awt.Font.BOLD, 10);
        // fill the patch: the picture is squashed (torso pixels are wider than tall), so scale the font to fit
        java.awt.font.FontRenderContext frc = g.getFontRenderContext();
        java.awt.geom.Rectangle2D b = f.getStringBounds("456", frc);
        double sx = img.getWidth() * 0.86 / b.getWidth(), sy = img.getHeight() * 0.8 / (b.getHeight() * 0.75);
        g.setFont(f.deriveFont(java.awt.geom.AffineTransform.getScaleInstance(sx, sy)));
        g.setColor(java.awt.Color.WHITE);
        java.awt.FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth("456");
        int tx0 = (img.getWidth() - tw) / 2, ty0 = (int) (img.getHeight() * 0.5 + b.getHeight() * 0.75 * sy * 0.36);
        // drawn several times slightly shifted: thick strokes that survive the cubes
        int k = Math.max(1, img.getWidth() / 90);
        for (int dx = -k; dx <= k; dx++) for (int dy = -k; dy <= k; dy++) g.drawString("456", tx0 + dx, ty0 + dy);
        g.dispose();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            // box-filter 8x8 down to one texture pixel
            double r = 0, gg = 0, bb = 0;
            for (int dy = 0; dy < 8; dy++) for (int dx = 0; dx < 8; dx++) {
                int c = img.getRGB(x * 8 + dx, y * 8 + dy);
                r += (c >> 16) & 255; gg += (c >> 8) & 255; bb += c & 255;
            }
            int tx = Math.floorMod(px0 + x, 256), ty = py0 + y;
            put(tx, ty, r / 64, gg / 64, bb / 64);
        }
    }

    static void player(Path assets, Path prev) throws IOException {
        reset();
        JG = GUARD_JG;
        ASCALE = 1.0;
        FINE_CELL = 0.008;
        // each arm and leg its own picture: the white stripes only on the outside
        SLEEVE_REG = new double[][]{{0, 256, 128, 512}, {384, 256, 512, 320}};
        LEG_REG = new double[][]{{128, 256, 256, 512}, {448, 448, 512, 512}};
        buildPlayer();
        System.out.println("player_456: smooth model " + P.size() + " vertices, " + Q.size() + " quads");
        Path png = assets.resolve("textures/entity/player_456.png");
        paintPlayer(png);
        snapshot();
        voxelize(tex.clone(), 0.034, 0.013, false);
        savePalette(png);
        BufferedImage[] texs = {ImageIO.read(png.toFile())};
        writeSkin(assets.resolve("models/entity/player_456.skin"));
        SPACING = 0.9;
        ZOOM = 1500;
        CAMY = 1.0;
        YAWS = new double[]{18, 180};
        preview(prev.resolve("player_456_front_back.png"), new String[]{"idle", "idle"}, new double[]{0.3, 0.3}, new int[]{0, 0}, texs);
        ZOOM = 2600;
        CAMY = 1.45;
        YAWS = new double[]{-24, 160};
        preview(prev.resolve("player_456_closeup.png"), new String[]{"idle", "idle"}, new double[]{0.3, 0.3}, new int[]{0, 0}, texs);
        ZOOM = 1150;
        CAMY = 1.0;
        YAWS = new double[]{25, -25};
        preview(prev.resolve("player_456_anims.png"), new String[]{"walk", "run", "attack", "dance"}, new double[]{0.25, 0.15, 0.3, 0.3}, new int[]{0, 0, 0, 0}, texs);
        // back to the dolls' head for anything built after
        HY = DOLL_HY; HRX = DOLL_HRX; HZF = 0.97; HZB = 1.03;
        FINE_CELL = 0;
        SLEEVE_REG = new double[][]{{0, 256, 128, 512}, {0, 256, 128, 512}};
        LEG_REG = new double[][]{{128, 256, 256, 512}, {128, 256, 256, 512}};
    }

    /** Ellipsoid as a surface for grid() (when it should carry a texture instead of a solid colour). */
    static Surf blobSurf(double cx, double cy, double cz, double rx, double ry, double rz, double[] m) {
        return (s, t) -> {
            double a = Math.PI * s, th = Math.PI * 2 * t + Math.PI;
            double[] p = apply3(m, Math.sin(a) * Math.sin(th) * rx, Math.cos(a) * ry, Math.sin(a) * Math.cos(th) * rz);
            return new double[]{cx + p[0], cy + p[1], cz + p[2]};
        };
    }

    static void buildBoy() {
        torso(new double[][]{
                {1.20, 0.05, 0.05, 0.05}, {1.175, 0.1, 0.075, 0.075}, {1.14, 0.142, 0.09, 0.092}, {1.08, 0.152, 0.098, 0.098},
                {0.98, 0.146, 0.096, 0.095}, {0.88, 0.14, 0.092, 0.095}, {0.815, 0.143, 0.095, 0.1}, {0.79, 0.146, 0.097, 0.102},
                {0.735, 0.15, 0.1, 0.106}, {0.70, 0.135, 0.092, 0.098}, {0.685, 0.05, 0.04, 0.04}}, 14);
        head(new double[][]{
                {1.525, 0.004, 0.004, 0.004}, {1.51, 0.07, 0.07, 0.075}, {1.475, 0.13, 0.128, 0.135}, {1.43, 0.16, 0.158, 0.162},
                {1.405, 0.167, 0.172, 0.166}, {1.395, 0.157, 0.152, 0.158}, {1.34, 0.156, 0.15, 0.156}, {1.28, 0.148, 0.142, 0.146},
                {1.225, 0.122, 0.118, 0.112}, {1.19, 0.07, 0.07, 0.068}, {1.17, 0.045, 0.045, 0.045}, {1.13, 0.047, 0.047, 0.047}}, 16);
        arm(new double[][]{{1.19, 0.03, 0}, {1.165, 0.05, 0.003}, {1.10, 0.052, 0.006}, {1.0, 0.048, 0.008},
                        {0.95, 0.046, 0.01}, {0.88, 0.044, 0.012}, {0.80, 0.043, 0.013}, {0.78, 0.042, 0.013}, {0.775, 0.03, 0.013}},
                new double[][]{{0.79, 0.027, 0.025}, {0.765, 0.031, 0.022}, {0.72, 0.036, 0.016}, {0.68, 0.03, 0.013}, {0.655, 0.01, 0.006}});
        leg(new double[][]{{0.76, 0.074}, {0.70, 0.084}, {0.62, 0.085}, {0.52, 0.084}, {0.475, 0.085}, {0.465, 0.05}, {0.40, 0.047}, {0.30, 0.045},
                        {0.21, 0.042}, {0.20, 0.046}, {0.12, 0.045}, {0.09, 0.044}},
                new double[][]{{0.10, 0.044, 0.05, 0.045, 0.0}, {0.07, 0.05, 0.085, 0.05, 0.015}, {0.035, 0.054, 0.1, 0.054, 0.02},
                        {0.01, 0.055, 0.104, 0.055, 0.02}, {0.0, 0.053, 0.1, 0.052, 0.02}, {-0.004, 0.025, 0.05, 0.025, 0.02}});
        smoothNormals();
    }

    static void buildGirl() {
        torso(new double[][]{
                {1.20, 0.05, 0.05, 0.05}, {1.175, 0.1, 0.076, 0.075}, {1.14, 0.14, 0.09, 0.09}, {1.08, 0.148, 0.096, 0.096},
                {0.98, 0.14, 0.092, 0.092}, {0.89, 0.136, 0.09, 0.09}, {0.86, 0.142, 0.094, 0.094}, {0.80, 0.17, 0.12, 0.118},
                {0.72, 0.198, 0.148, 0.142}, {0.63, 0.218, 0.168, 0.16}, {0.565, 0.226, 0.176, 0.168}, {0.555, 0.205, 0.158, 0.15},
                {0.55, 0.04, 0.04, 0.04}}, 16);
        head(new double[][]{
                {1.515, 0.004, 0.004, 0.004}, {1.505, 0.07, 0.068, 0.075}, {1.47, 0.135, 0.13, 0.142}, {1.42, 0.162, 0.158, 0.17},
                {1.37, 0.163, 0.157, 0.172}, {1.32, 0.156, 0.15, 0.165}, {1.27, 0.146, 0.14, 0.155}, {1.225, 0.12, 0.116, 0.13},
                {1.19, 0.07, 0.07, 0.072}, {1.17, 0.042, 0.042, 0.042}, {1.13, 0.045, 0.045, 0.045}}, 16);
        // pigtails behind the ears: a purple tie, then the black tuft
        for (int side = -1; side <= 1; side += 2) {
            List<Ring> pt = new ArrayList<>();
            double x = 0.155 * side, z = -0.055;
            double[][] pr = {{1.31, 0.012}, {1.30, 0.03}, {1.285, 0.036}, {1.27, 0.03}, {1.255, 0.046}, {1.225, 0.05}, {1.195, 0.04}, {1.175, 0.022}, {1.165, 0.004}};
            for (double[] r : pr) pt.add(ring(r[0], r[1], r[1], r[1], x + 0.012 * side, z, HEAD, HEAD, 0));
            tube(pt, 8, 384, 256, 448, 320);
        }
        arm(new double[][]{{1.19, 0.035, 0}, {1.165, 0.06, 0.004}, {1.12, 0.068, 0.008}, {1.06, 0.066, 0.01}, {1.015, 0.058, 0.01},
                        {1.005, 0.036, 0.01}, {0.94, 0.034, 0.012}, {0.86, 0.032, 0.013}, {0.79, 0.031, 0.013}, {0.775, 0.026, 0.013}},
                new double[][]{{0.79, 0.026, 0.024}, {0.765, 0.03, 0.02}, {0.72, 0.035, 0.015}, {0.68, 0.029, 0.012}, {0.655, 0.01, 0.006}});
        leg(new double[][]{{0.74, 0.05}, {0.62, 0.05}, {0.52, 0.048}, {0.44, 0.045}, {0.43, 0.049}, {0.35, 0.047}, {0.25, 0.042},
                        {0.15, 0.04}, {0.09, 0.041}},
                new double[][]{{0.10, 0.042, 0.048, 0.043, 0.0}, {0.07, 0.048, 0.08, 0.048, 0.014}, {0.035, 0.052, 0.096, 0.052, 0.018},
                        {0.01, 0.053, 0.1, 0.053, 0.018}, {0.0, 0.051, 0.096, 0.05, 0.018}, {-0.004, 0.024, 0.048, 0.024, 0.018}});
        smoothNormals();
        // the eyes as their own patches (after the smoothing of the tubes; they get their own flat-ish normals)
        int first = P.size();
        eyePatch(0.052, 1.335, 0.05, 0.03, 0.156, 0.152, false);
        eyePatch(-0.052, 1.335, 0.05, 0.03, 0.156, 0.152, true);
        for (int v = first; v < P.size(); v++) {
            double[] p = P.get(v);
            N.set(v, norm(new double[]{p[0] * 0.8, 0.05, 1.0}));
        }
    }

    /** Vertex normals = average of the faces around; vertices at the same spot in one tube (the seam) share them. */
    static void smoothNormals() {
        for (int[] q : Q) {
            double[] a = P.get(q[0]), b = P.get(q[1]), c = P.get(q[2]), d = P.get(q[3]);
            // counter-clockwise seen from outside: the two triangles' normals (one may be degenerate at a tip)
            double[] n1 = cross(sub(b, a), sub(c, a)), n2 = cross(sub(c, a), sub(d, a));
            double[] n = {n1[0] + n2[0], n1[1] + n2[1], n1[2] + n2[2]};
            for (int v : q) { double[] t = N.get(v); t[0] += n[0]; t[1] += n[1]; t[2] += n[2]; }
        }
        Map<String, double[]> acc = new HashMap<>();
        for (int v = 0; v < P.size(); v++) {
            String k = key(v);
            double[] s = acc.computeIfAbsent(k, x -> new double[3]);
            double[] n = N.get(v);
            s[0] += n[0]; s[1] += n[1]; s[2] += n[2];
        }
        for (int v = 0; v < P.size(); v++) {
            double[] s = acc.get(key(v));
            double l = len(s);
            N.set(v, l < 1e-12 ? new double[]{0, 1, 0} : new double[]{s[0] / l, s[1] / l, s[2] / l});
        }
    }

    static String key(int v) {
        double[] p = P.get(v);
        return GROUP.get(v) + ":" + Math.round(p[0] * 2000) + "," + Math.round(p[1] * 2000) + "," + Math.round(p[2] * 2000);
    }

    // ------------------------------------------------------------------ animations
    interface PoseFn { void pose(double t, double[][] rot, double[] hips); }

    record AnimSpec(String name, boolean loop, double dur, PoseFn fn) {}

    static double sm(double x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    /** Key-framed scalar: times[] / values[] with smooth easing between keys. */
    static double keys(double t, double[] ts, double[] vs) {
        if (t <= ts[0]) return vs[0];
        for (int i = 0; i < ts.length - 1; i++) if (t <= ts[i + 1]) return vs[i] + (vs[i + 1] - vs[i]) * sm((t - ts[i]) / (ts[i + 1] - ts[i]));
        return vs[vs.length - 1];
    }

    static List<AnimSpec> anims() {
        List<AnimSpec> l = new ArrayList<>();
        l.add(new AnimSpec("idle", true, 3.0, (t, r, h) -> {
            double s = Math.sin(t / 3.0 * Math.PI * 2);
            r[CHEST][0] = 1.5 * s; r[HEAD][0] = -1.0 * s; r[NECK][1] = 2 * Math.sin(t / 3.0 * Math.PI * 2 * 0.5);
            r[UAL][2] = 4 + s; r[UAR][2] = -4 - s; r[FAL][0] = -6; r[FAR][0] = -6;
            r[UAL][0] = 2; r[UAR][0] = 2;
            h[1] = 0.004 * s;
        }));
        l.add(new AnimSpec("walk", true, 1.0, (t, r, h) -> {
            double ph = t * Math.PI * 2, s = Math.sin(ph);
            r[THL][0] = -28 * s; r[THR][0] = 28 * s;
            r[SHL][0] = 6 + 34 * Math.max(0, -Math.sin(ph + 0.8)); r[SHR][0] = 6 + 34 * Math.max(0, Math.sin(ph + 0.8));
            r[FTL][0] = -r[SHL][0] * 0.3 - r[THL][0] * 0.4; r[FTR][0] = -r[SHR][0] * 0.3 - r[THR][0] * 0.4;
            r[UAL][0] = 20 * s; r[UAR][0] = -20 * s; r[UAL][2] = 5; r[UAR][2] = -5;
            r[FAL][0] = -12 - 8 * Math.max(0, -s); r[FAR][0] = -12 - 8 * Math.max(0, s);
            r[CHEST][1] = 4 * s; r[HIPS][1] = -4 * s; r[CHEST][0] = 3;
            h[1] = 0.015 * Math.cos(2 * ph) - 0.005;
        }));
        l.add(new AnimSpec("run", true, 0.62, (t, r, h) -> {
            double ph = t / 0.62 * Math.PI * 2, s = Math.sin(ph);
            r[THL][0] = -50 * s; r[THR][0] = 50 * s;
            r[SHL][0] = 15 + 75 * Math.max(0, -Math.sin(ph + 0.9)); r[SHR][0] = 15 + 75 * Math.max(0, Math.sin(ph + 0.9));
            r[FTL][0] = -r[SHL][0] * 0.25; r[FTR][0] = -r[SHR][0] * 0.25;
            r[UAL][0] = 45 * s; r[UAR][0] = -45 * s; r[UAL][2] = 8; r[UAR][2] = -8;
            r[FAL][0] = -80; r[FAR][0] = -80;
            r[CHEST][0] = 12; r[CHEST][1] = 7 * s; r[HEAD][0] = -10;
            h[1] = 0.03 * Math.cos(2 * ph) - 0.02;
        }));
        l.add(new AnimSpec("attack", false, 0.6, (t, r, h) -> {
            double[] ts = {0, 0.18, 0.3, 0.42, 0.6};
            r[UAR][0] = keys(t, ts, new double[]{0, 25, -88, -80, 0});
            r[UAR][2] = keys(t, ts, new double[]{-4, -12, -6, -6, -4});
            r[FAR][0] = keys(t, ts, new double[]{-6, -110, -8, -15, -6});
            r[CHEST][1] = keys(t, ts, new double[]{0, 18, -22, -18, 0});
            r[CHEST][0] = keys(t, ts, new double[]{0, 2, 8, 6, 0});
            r[UAL][0] = keys(t, ts, new double[]{2, -20, 20, 15, 2});
            r[FAL][0] = keys(t, ts, new double[]{-6, -60, -40, -30, -6});
            r[UAL][2] = 6;
            r[THL][0] = keys(t, ts, new double[]{0, -8, -18, -14, 0});
            r[SHL][0] = keys(t, ts, new double[]{0, 10, 20, 14, 0});
            r[THR][0] = keys(t, ts, new double[]{0, 6, 14, 10, 0});
            h[1] = keys(t, ts, new double[]{0, -0.02, -0.04, -0.03, 0});
        }));
        l.add(new AnimSpec("jump", true, 0.8, (t, r, h) -> {
            r[THL][0] = -45; r[THR][0] = -35; r[SHL][0] = 70; r[SHR][0] = 55; r[FTL][0] = -20; r[FTR][0] = -15;
            r[UAL][0] = -35; r[UAR][0] = -35; r[UAL][2] = 25; r[UAR][2] = -25; r[FAL][0] = -30; r[FAR][0] = -30;
            r[CHEST][0] = 8;
        }));
        l.add(new AnimSpec("crouch", true, 2.0, (t, r, h) -> {
            double s = Math.sin(t * Math.PI);
            r[THL][0] = -55; r[THR][0] = -55; r[SHL][0] = 95; r[SHR][0] = 95; r[FTL][0] = -40; r[FTR][0] = -40;
            r[CHEST][0] = 22 + s; r[HEAD][0] = -18; r[UAL][0] = -20; r[UAR][0] = -20; r[FAL][0] = -40; r[FAR][0] = -40;
            h[1] = -0.2; h[2] = -0.04;
        }));
        l.add(new AnimSpec("crouchwalk", true, 1.2, (t, r, h) -> {
            double ph = t / 1.2 * Math.PI * 2, s = Math.sin(ph);
            r[THL][0] = -55 - 18 * s; r[THR][0] = -55 + 18 * s; r[SHL][0] = 95 + 10 * s; r[SHR][0] = 95 - 10 * s;
            r[FTL][0] = -40; r[FTR][0] = -40;
            r[CHEST][0] = 22; r[HEAD][0] = -18; r[UAL][0] = -20 + 12 * s; r[UAR][0] = -20 - 12 * s; r[FAL][0] = -40; r[FAR][0] = -40;
            h[1] = -0.2 + 0.01 * Math.cos(2 * ph); h[2] = -0.04;
        }));
        l.add(new AnimSpec("sit", true, 3.0, (t, r, h) -> {
            r[THL][0] = -88; r[THR][0] = -88; r[SHL][0] = 88; r[SHR][0] = 88; r[THL][2] = 4; r[THR][2] = -4;
            r[UAL][0] = -25; r[UAR][0] = -25; r[FAL][0] = -45; r[FAR][0] = -45;
            r[CHEST][0] = 1.5 * Math.sin(t / 3.0 * Math.PI * 2);
        }));
        l.add(new AnimSpec("dance", true, 1.2, (t, r, h) -> {
            double ph = t / 1.2 * Math.PI * 2, s = Math.sin(ph), s2 = Math.sin(2 * ph);
            r[UAL][2] = 120 + 25 * s; r[UAR][2] = -120 + 25 * s; r[FAL][2] = 20 * s2; r[FAR][2] = 20 * s2;
            r[HIPS][1] = 15 * s; r[CHEST][1] = -10 * s; r[HEAD][2] = 8 * s2;
            r[THL][0] = -15 * Math.max(0, s2); r[SHL][0] = 25 * Math.max(0, s2); r[THR][0] = -15 * Math.max(0, -s2); r[SHR][0] = 25 * Math.max(0, -s2);
            h[1] = 0.03 * Math.abs(s2) - 0.02; h[0] = 0.03 * s;
        }));
        return l;
    }

    /** Quaternion (x, y, z, w) from degrees, applied Z then X then Y (q = qy * qx * qz). */
    static double[] quat(double[] deg) {
        double[] qx = axis(1, 0, 0, deg[0]), qy = axis(0, 1, 0, deg[1]), qz = axis(0, 0, 1, deg[2]);
        return qmul(qy, qmul(qx, qz));
    }

    static double[] axis(double x, double y, double z, double deg) {
        double a = Math.toRadians(deg) / 2, s = Math.sin(a);
        return new double[]{x * s, y * s, z * s, Math.cos(a)};
    }

    static double[] qmul(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    // ------------------------------------------------------------------ write .skin
    static double[] restT(int j) {
        int p = JP[j];
        return p < 0 ? JG[j].clone() : new double[]{JG[j][0] - JG[p][0], JG[j][1] - JG[p][1], JG[j][2] - JG[p][2]};
    }

    static void writeSkin(Path out) throws IOException {
        List<AnimSpec> specs = anims();
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(out))))) {
            o.writeInt(0x534B494E);
            // 4: plain; 5: with the far model; 6: like 5 with 32-bit vertex indices (over 65535 vertices)
            boolean lod = !LODQ.isEmpty();
            int version = P.size() > 65535 ? 6 : (lod ? 5 : 4);
            boolean wide = version >= 6;
            o.writeInt(version);
            o.writeInt(J);
            double[] id = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            for (double d : id) o.writeFloat((float) d);
            for (int j = 0; j < J; j++) {
                o.writeUTF(JN[j]);
                o.writeShort(JP[j]);
                for (double d : restT(j)) o.writeFloat((float) d);
                o.writeFloat(0); o.writeFloat(0); o.writeFloat(0); o.writeFloat(1);
                o.writeFloat(1); o.writeFloat(1); o.writeFloat(1);
                double[] ibm = {1, 0, 0, -JG[j][0], 0, 1, 0, -JG[j][1], 0, 0, 1, -JG[j][2], 0, 0, 0, 1};
                for (double d : ibm) o.writeFloat((float) d);
            }
            o.writeInt(P.size());
            for (int v = 0; v < P.size(); v++) {
                for (double d : P.get(v)) o.writeFloat((float) d);
                for (double d : N.get(v)) o.writeFloat((float) d);
                o.writeFloat((float) (UV.get(v)[0] / 512.0));
                o.writeFloat((float) (UV.get(v)[1] / 512.0));
                int[] ji = JI.get(v);
                double[] jw = JW.get(v);
                o.writeByte(ji.length);
                for (int k = 0; k < ji.length; k++) { o.writeByte(ji[k]); o.writeFloat((float) jw[k]); }
            }
            o.writeInt(Q.size());
            for (int[] q : Q) for (int c : q) { if (wide) o.writeInt(c); else o.writeShort(c); }
            o.writeInt(specs.size());
            for (AnimSpec a : specs) {
                int frames = Math.max(2, (int) Math.round(a.dur * 20) + 1);
                o.writeUTF(a.name); o.writeBoolean(a.loop); o.writeFloat((float) a.dur); o.writeInt(frames);
                for (int j = 0; j < J; j++) o.writeByte(j == HIPS ? 3 : 1);
                for (int f = 0; f < frames; f++) {
                    double t = a.dur * f / (frames - 1);
                    double[][] rot = new double[J][3];
                    double[] hp = new double[3];
                    a.fn.pose(t, rot, hp);
                    for (int j = 0; j < J; j++) {
                        double[] q = quat(rot[j]);
                        for (double d : q) o.writeShort((short) Math.round(Math.max(-1, Math.min(1, d)) * 32767));
                        if (j == HIPS) {
                            double[] rt = restT(j);
                            o.writeFloat((float) (rt[0] + hp[0] * ASCALE)); o.writeFloat((float) (rt[1] + hp[1] * ASCALE)); o.writeFloat((float) (rt[2] + hp[2] * ASCALE));
                        }
                    }
                }
            }
            writeRagdoll(o);
            o.writeInt(HEAD);
            if (version >= 5) {
                o.writeInt(LODV.size());
                for (int v : LODV) { if (wide) o.writeInt(v); else o.writeShort(v); }
                o.writeInt(LODQ.size());
                for (int[] q : LODQ) for (int c : q) { if (wide) o.writeInt(c); else o.writeShort(c); }
            }
            Set<String> up = new HashSet<>(), un = new HashSet<>();
            for (int v = 0; v < P.size(); v++) {
                String infl = Arrays.toString(JI.get(v)) + Arrays.toString(JW.get(v));
                up.add(Arrays.toString(P.get(v)) + infl);
                un.add(Arrays.toString(N.get(v)) + infl);
            }
            System.out.println("  written " + out.getFileName() + ": version " + version + ", " + P.size() + " vertices; skinning work: "
                    + up.size() + " points + " + un.size() + " normals");
        }
    }

    /** One box per ragdoll part: bounds of its vertices in the rest pose, overlaps of unjoined parts shrunk away. */
    static void writeRagdoll(DataOutputStream o) throws IOException {
        int S = SEG.length;
        double[][] mn = new double[S][], mx = new double[S][];
        double[] us = new double[S], vs = new double[S];
        for (int s = 0; s < S; s++) { mn[s] = new double[]{1e9, 1e9, 1e9}; mx[s] = new double[]{-1e9, -1e9, -1e9}; }
        for (int v = 0; v < P.size(); v++) {
            int[] ji = JI.get(v); double[] jw = JW.get(v);
            int best = 0; for (int k = 1; k < ji.length; k++) if (jw[k] > jw[best]) best = k;
            int s = JOINT_SEG[ji[best]];
            for (int c = 0; c < 3; c++) { mn[s][c] = Math.min(mn[s][c], P.get(v)[c]); mx[s][c] = Math.max(mx[s][c], P.get(v)[c]); }
            us[s] = UV.get(v)[0] / 512.0; vs[s] = UV.get(v)[1] / 512.0;
        }
        for (int pass = 0; pass < 20; pass++) {
            boolean any = false;
            for (int a = 0; a < S; a++) for (int b = a + 1; b < S; b++) {
                if (SEG_PARENT[a] == b || SEG_PARENT[b] == a) continue;
                double best = 1e9; int axis = -1;
                for (int c = 0; c < 3; c++) {
                    double ov = Math.min(mx[a][c], mx[b][c]) - Math.max(mn[a][c], mn[b][c]);
                    if (ov <= 0) { axis = -1; break; }
                    if (ov < best) { best = ov; axis = c; }
                }
                if (axis < 0) continue;
                any = true;
                double cut = best / 2 + 0.01;
                for (int s : new int[]{a, b}) {
                    double centre = (mn[s][axis] + mx[s][axis]) / 2, half = Math.max(0.03, (mx[s][axis] - mn[s][axis]) / 2 - cut);
                    mn[s][axis] = centre - half; mx[s][axis] = centre + half;
                }
            }
            if (!any) break;
        }
        for (int j = 0; j < J; j++) o.writeByte(JOINT_SEG[j]);
        o.writeInt(S);
        for (int s = 0; s < S; s++) {
            double[] g = JG[SEG_PIVOT[s]];
            o.writeUTF(SEG[s]);
            o.writeByte(SEG_PARENT[s]);
            o.writeFloat((float) g[0]); o.writeFloat((float) g[1]); o.writeFloat((float) g[2]);
            o.writeInt(6);
            for (int axis = 0; axis < 3; axis++) for (int sign = -1; sign <= 1; sign += 2) {
                int ua = (axis + 1) % 3, va = (axis + 2) % 3;
                double plane = sign > 0 ? mx[s][axis] : mn[s][axis];
                double[][] corners = sign > 0
                        ? new double[][]{{mn[s][ua], mn[s][va]}, {mx[s][ua], mn[s][va]}, {mx[s][ua], mx[s][va]}, {mn[s][ua], mx[s][va]}}
                        : new double[][]{{mn[s][ua], mn[s][va]}, {mn[s][ua], mx[s][va]}, {mx[s][ua], mx[s][va]}, {mx[s][ua], mn[s][va]}};
                for (int i = 0; i < 4; i++) {
                    double[] p = new double[3]; p[axis] = plane; p[ua] = corners[i][0]; p[va] = corners[i][1];
                    o.writeFloat((float) p[0]); o.writeFloat((float) p[1]); o.writeFloat((float) p[2]); o.writeFloat((float) us[s]); o.writeFloat((float) vs[s]);
                }
                for (int c = 0; c < 3; c++) o.writeFloat(c == axis ? sign : 0);
            }
            System.out.printf(Locale.ROOT, "ragdoll %-10s x[%.2f %.2f] y[%.2f %.2f] z[%.2f %.2f]%n", SEG[s], mn[s][0], mx[s][0], mn[s][1], mx[s][1], mn[s][2], mx[s][2]);
        }
    }

    // ------------------------------------------------------------------ texture
    static final int T = 512;
    static int[] tex;
    static final Random RNG = new Random(7);
    static float[] noise = new float[T * T];

    static void makeNoise() {
        // soft fabric noise: a few octaves of smoothed random values
        float[] acc = new float[T * T];
        for (int oct = 0; oct < 4; oct++) {
            int cell = 64 >> oct;
            int n = T / cell + 2;
            float[] g = new float[n * n];
            for (int i = 0; i < g.length; i++) g[i] = RNG.nextFloat();
            for (int y = 0; y < T; y++) for (int x = 0; x < T; x++) {
                float fx = x / (float) cell, fy = y / (float) cell;
                int ix = (int) fx, iy = (int) fy;
                float tx = fx - ix, ty = fy - iy;
                tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty);
                float a = g[iy * n + ix], b = g[iy * n + ix + 1], c = g[(iy + 1) * n + ix], d = g[(iy + 1) * n + ix + 1];
                acc[y * T + x] += ((a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty) / (1 << oct);
            }
        }
        for (int i = 0; i < acc.length; i++) noise[i] = acc[i] / 1.875f - 0.5f;
    }

    static final double[] RED = {214, 50, 74};

    static void put(int x, int y, double r, double g, double b) {
        if (x < 0 || y < 0 || x >= T || y >= T) return;
        tex[y * T + x] = 0xFF000000 | (cl(r) << 16) | (cl(g) << 8) | cl(b);
    }

    static int cl(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    /** Red fabric pixel: base colour, grain, a fold value (-1 dark crease .. +1 lit ridge) and an AO factor. */
    static void fabric(int x, int y, double fold, double ao) {
        double n = noise[y * T + x] * 0.10 + ((x * 7 + y * 13) % 3 - 1) * 0.012;
        double k = (1 + n + fold * 0.16) * ao;
        put(x, y, RED[0] * k, RED[1] * k * (1 + fold * 0.05), RED[2] * k);
    }

    static void paintTexture(int symbol, Path out) throws IOException {
        tex = new int[T * T];
        // ---- torso: u around (front at 0.5), v from neck (0) to crotch (1); rings 1.53 .. 0.84
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            double u = x / 256.0, v = y / 256.0;
            double yy = 1.53 - v * 0.69;
            double side = Math.cos((u - 0.5) * Math.PI * 2);         // 1 front, -1 back
            double fold = 0.35 * Math.sin(u * 40 + Math.sin(v * 9) * 1.5) * smoothBand(yy, 0.9, 1.25)   // folds over the belly
                    + 0.5 * Math.sin(u * 22 + 1.3) * smoothBand(yy, 1.30, 1.50) * 0.6;
            double ao = 0.86 + 0.14 * (0.5 + 0.5 * side * 0.4 + 0.3);
            if (yy > 1.47) ao *= 0.8;                                   // under the hood
            fabric(x, y, fold, Math.min(1.05, ao));
        }
        // seams: shoulder seam ring, side seams
        for (int x = 0; x < 256; x++) darkLine(x, (int) ((1.53 - 1.40) / 0.69 * 256), 0.75);
        for (int y = 0; y < 256; y++) { darkLine(64, y, 0.8); darkLine(192, y, 0.8); }
        // zipper at the front from the collar to the belt
        int beltTop = (int) ((1.53 - 1.035) / 0.69 * 256), beltBot = (int) ((1.53 - 0.965) / 0.69 * 256);
        for (int y = 0; y < beltTop; y++) for (int x = 126; x <= 130; x++) {
            boolean teeth = x == 128 ? (y % 3 != 0) : (x == 127 || x == 129);
            put(x, y, teeth ? 30 : 60, teeth ? 30 : 18, teeth ? 32 : 24);
        }
        // chest pockets with flaps
        pocket(96, 46, 26, 30); pocket(134, 46, 26, 30);
        // belt with a buckle
        for (int y = beltTop; y <= beltBot; y++) for (int x = 0; x < 256; x++) {
            double t = (y - beltTop) / (double) (beltBot - beltTop);
            double k = 0.75 + 0.35 * Math.sin(t * Math.PI) + noise[y * T + x] * 0.15;
            put(x, y, 22 * k, 20 * k, 24 * k);
        }
        for (int y = beltTop - 2; y <= beltBot + 2; y++) for (int x = 118; x <= 138; x++) {
            boolean edge = y == beltTop - 2 || y == beltBot + 2 || x == 118 || x == 138;
            put(x, y, edge ? 70 : 40, edge ? 70 : 40, edge ? 78 : 46);
        }
        // ---- head and hood: u around (front 0.5), v top (1.885) .. bottom (1.44)
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            double u = x / 256.0, v = y / 256.0, yy = 1.885 - v * 0.445;
            double du = (u - 0.5) / 0.165, dv = (yy - 1.675) / 0.135;
            double r = Math.sqrt(du * du + dv * dv);
            int px = 256 + x;
            if (r < 1.0) {
                // the mask: glossy black, a soft highlight top-left and a faint rim reflection
                double hl = Math.max(0, 1 - Math.hypot(du + 0.35, dv - 0.45) / 0.55);
                double rim = Math.pow(r, 6) * 0.35;
                double k = 14 + 70 * hl * hl + 40 * rim + noise[y * T + x] * 6;
                put(px, y, k, k, k * 1.08);
            } else if (r < 1.12) {
                // the hood opening: a dark rolled edge
                double t = (r - 1.0) / 0.12;
                double k = 0.45 + 0.4 * Math.sin(t * Math.PI);
                put(px, y, RED[0] * k * 0.8, RED[1] * k * 0.7, RED[2] * k * 0.8);
            } else {
                double fold = 0.3 * Math.sin(u * 30 + v * 4) * smoothBand(yy, 1.44, 1.56) + 0.25 * Math.sin(v * 20 + u * 3) * (1 - Math.abs(Math.cos((u - 0.5) * Math.PI * 2)));
                double ao = yy < 1.5 ? 0.82 : 1.0;
                fabric(px, y, fold, ao);
            }
        }
        // hood seam over the top, from front to back
        for (int y = 0; y < 150; y++) { darkLine(256 + 0, y, 0.7); darkLine(256 + 255, y, 0.7); }
        symbol(symbol);
        // ---- sleeves: u around, v shoulder (0) .. wrist (1); arm rings 1.50 .. 0.94
        for (int y = 256; y < 512; y++) for (int x = 0; x < 128; x++) {
            double u = x / 128.0, v = (y - 256) / 256.0, yy = 1.50 - v * 0.56;
            double fold = 0.6 * Math.sin(v * 70 + Math.sin(u * 6) * 2) * smoothBand(yy, 1.08, 1.24)   // elbow creases
                    + 0.4 * Math.sin(u * 18 + v * 8) * 0.5 + 0.5 * Math.sin(v * 90) * smoothBand(yy, 0.93, 1.0);
            double ao = yy < 0.98 ? 0.78 : (yy > 1.46 ? 0.85 : 1.0);
            fabric(x, y, fold, ao);
        }
        // ---- legs: u around, v hip (0) .. ankle (1); rings 0.95 .. 0.135
        for (int y = 256; y < 512; y++) for (int x = 128; x < 256; x++) {
            double u = (x - 128) / 128.0, v = (y - 256) / 256.0, yy = 0.95 - v * 0.815;
            double fold = 0.55 * Math.sin(v * 60 + Math.sin(u * 5) * 2) * smoothBand(yy, 0.42, 0.58)    // knee
                    + 0.7 * Math.sin(v * 110 + u * 4) * smoothBand(yy, 0.13, 0.26)                       // bunched at the boots
                    + 0.25 * Math.sin(u * 14) * smoothBand(yy, 0.6, 0.95);
            double ao = yy < 0.17 ? 0.75 : 1.0;
            fabric(x, y, fold, ao);
        }
        // side cargo pockets (outer side of each leg: both sides are painted, the mesh shows one each)
        pocket(128 + 24, 256 + 70, 22, 40); pocket(128 + 82, 256 + 70, 22, 40);
        for (int y = 256; y < 512; y++) { darkLine(128 + 32, y, 0.82); darkLine(128 + 96, y, 0.82); }
        // ---- gloves: black leather with a soft sheen
        for (int y = 256; y < 320; y++) for (int x = 256; x < 320; x++) {
            double u = (x - 256) / 64.0, v = (y - 256) / 64.0;
            double sheen = Math.pow(Math.max(0, Math.cos((u - 0.42) * Math.PI * 2)), 6) * (1 - v * 0.5);
            double k = 20 + 55 * sheen + noise[y * T + x] * 8 + (v < 0.12 ? -6 : 0);
            put(x, y, k, k, k * 1.05);
        }
        // ---- boots: black, a rubber sole band at the bottom, a lace line at the front
        for (int y = 256; y < 320; y++) for (int x = 320; x < 384; x++) {
            double u = (x - 320) / 64.0, v = (y - 256) / 64.0;
            double sheen = Math.pow(Math.max(0, Math.cos((u - 0.45) * Math.PI * 2)), 8) * 0.8;
            double k = 18 + 45 * sheen + noise[y * T + x] * 6;
            if (v > 0.72) k = 34 + noise[y * T + x] * 10;              // sole
            if (Math.abs(u - 0.5) < 0.03 && v < 0.45 && ((y / 3) & 1) == 0) k = 60;   // laces
            put(x, y, k, k, k);
        }
        paintSwatches();
        // the rest of the atlas: plain red (never sampled)
        for (int i = 0; i < tex.length; i++) if (tex[i] == 0) tex[i] = 0xFFD6324A;
        BufferedImage img = new BufferedImage(T, T, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, T, T, tex, 0, T);
        ImageIO.write(img, "png", out.toFile());
    }

    // ------------------------------------------------------------------ doll textures

    static void cloth(int x, int y, int rgb, double fold, double ao, double gloss) {
        double n = noise[y * T + x] * 0.08 + ((x * 7 + y * 13) % 3 - 1) * 0.008;
        double k = (1 + n + fold * 0.14) * ao;
        double r = ((rgb >> 16) & 255) * k, g = ((rgb >> 8) & 255) * k, b = (rgb & 255) * k;
        if (gloss > 0) { r += gloss * 60; g += gloss * 60; b += gloss * 60; }
        put(x, y, r, g, b);
    }

    static void plain(int x, int y, int rgb, double k) {
        put(x, y, ((rgb >> 16) & 255) * k, ((rgb >> 8) & 255) * k, (rgb & 255) * k);
    }

    /** Torso pixel -> (u, y in blocks); the torso v runs linearly from yTop to yBot. */
    interface Px { void at(int x, int y, double u, double yy); }

    static void region(int x0, int y0, int w, int h, double yTop, double yBot, Px f) {
        for (int y = y0; y < y0 + h; y++) for (int x = x0; x < x0 + w; x++) {
            double u = (x - x0) / (double) w, v = (y - y0) / (double) h;
            f.at(x, y, u, yTop + (yBot - yTop) * v);
        }
    }

    /** Glossy sheen around the front-left (u = 0.42), like a vinyl doll. */
    static double sheen(double u, double pow) {
        return Math.pow(Math.max(0, Math.cos((u - 0.42) * Math.PI * 2)), pow);
    }

    static final int SKIN_BOY = 0xE6BC9C, SKIN_GIRL = 0xF0D2B4, HAIR = 0x17161A;

    /** Face features on the head region (u around, front 0.5); circumference in blocks for round shapes. */
    static void face(int x, int y, double u, double yy, int skin, double eyeY, boolean eyes, double circ) {
        double bx = (u - 0.5) * circ;
        double k = 0.93 + 0.1 * sheen(u, 3);
        int col = skin;
        // cheeks
        double ch = Math.max(0, 1 - Math.hypot(Math.abs(bx) - 0.07, (yy - 1.29) * 1.3) / 0.035);
        double r = ((col >> 16) & 255), g = ((col >> 8) & 255), b = (col & 255);
        r = r * k + ch * 18; g = g * k - ch * 14; b = b * k - ch * 8;
        // nose shading and mouth
        double nose = Math.max(0, 1 - Math.hypot(bx * 2.2, yy - 1.305) / 0.025);
        r -= nose * 14; g -= nose * 16; b -= nose * 14;
        double mouth = Math.abs(yy - 1.268) < 0.0055 && Math.abs(bx) < 0.017 ? 1 - Math.abs(bx) / 0.017 : 0;
        r = r * (1 - mouth) + 190 * mouth; g = g * (1 - mouth) + 92 * mouth; b = b * (1 - mouth) + 96 * mouth;
        // eyebrows
        if (Math.abs(yy - (eyeY + 0.03)) < 0.0035 && Math.abs(Math.abs(bx) - 0.052) < 0.022) { r = 60; g = 44; b = 40; }
        if (eyes) {
            double ex = Math.abs(bx) - 0.052, ey = yy - eyeY;
            double e = Math.hypot(ex / 0.017, ey / 0.0105);
            if (e < 1) {
                double ir = Math.hypot(ex / 0.0095, ey / 0.0095);
                if (ir < 1) { r = 40; g = 26; b = 22; if (Math.hypot(ex + 0.004, ey - 0.004) < 0.0028) { r = 235; g = 235; b = 235; } }
                else { r = 238; g = 234; b = 228; }
            } else if (e < 1.25 && ey > -0.004) { r = 50; g = 36; b = 34; }
        }
        put(x, y, r, g, b);
    }

    static void paintBoy(Path out) throws IOException {
        tex = new int[T * T];
        final int GREEN = 0x1E4B33, BAND = 0x79AE92, TEAL = 0x2E9C78, COLLAR = 0x6E9E84, KHAKI = 0xB39874;
        // ---- torso: shirt, belt, shorts (y 1.20 .. 0.585)
        region(0, 0, 256, 256, 1.20, 0.685, (x, y, u, yy) -> {
            double side = Math.cos((u - 0.5) * Math.PI * 2);
            double fold = 0.3 * Math.sin(u * 30 + yy * 8) * (yy < 0.95 && yy > 0.82 ? 1 : 0.3);
            double ao = 0.9 + 0.1 * side;
            if (yy > 0.815) {
                int c = GREEN;
                if (yy > 1.012 && yy < 1.05) c = BAND;
                if ((yy > 1.05 && yy < 1.058) || (yy > 1.004 && yy < 1.012)) c = TEAL;
                if (yy > 1.14 && Math.abs(u - 0.5) < 0.16) c = COLLAR;                     // collar
                cloth(x, y, c, fold, ao, 0.35 * sheen(u, 4) * (yy > 0.9 ? 1 : 0.6));
                if (yy > 1.06 && yy < 1.14 && Math.abs(u - 0.5) < 0.006) plain(x, y, GREEN, 0.6);   // placket
                if (yy > 1.06 && yy < 1.14 && Math.abs(u - 0.5) < 0.004 && ((int) (yy * 400)) % 12 == 0) plain(x, y, 0xD8D8D0, 1);
                if (yy > 1.135 && yy < 1.145 && Math.abs(u - 0.5) < 0.16) plain(x, y, GREEN, 0.75);
            } else if (yy > 0.79) {
                double t = (yy - 0.79) / 0.025;
                plain(x, y, 0x161616, 0.8 + 0.4 * Math.sin(t * Math.PI));
                if (Math.abs(u - 0.5) < 0.035) plain(x, y, 0x9A9A98, 0.9 + 0.3 * Math.sin(t * Math.PI));  // buckle
                if (Math.abs(u - 0.3) < 0.006 || Math.abs(u - 0.7) < 0.006) plain(x, y, KHAKI, 0.85);      // loops
            } else {
                cloth(x, y, KHAKI, 0.25 * Math.sin(u * 24 + yy * 30), ao, 0.1 * sheen(u, 4));
                if (Math.abs(u - 0.5) < 0.004 && yy > 0.66) plain(x, y, KHAKI, 0.72);                      // fly
                if (Math.abs(Math.abs(u - 0.5) - 0.17 - (0.79 - yy) * 0.6) < 0.004 && yy > 0.72) plain(x, y, KHAKI, 0.75);   // pockets
            }
        });
        // ---- head: cap, hair, face (y 1.525 .. 1.13)
        double circ = 2 * Math.PI * 0.155;
        region(256, 0, 256, 256, 1.525, 1.13, (x, y, u, yy) -> {
            double bx = (u - 0.5) * circ;
            if (yy > 1.395) {
                double seam = Math.abs(((u * 6) % 1) - 0.5) > 0.48 ? 0.72 : 1;
                double k = (0.88 + 0.2 * sheen(u, 2) + 0.1 * (yy - 1.395) / 0.13) * seam;
                if (yy > 1.512) k = 0.6;                                                         // button
                if (yy < 1.41 && Math.abs(u - 0.5) < 0.2) k *= 0.85;                            // visor
                plain(x, y, COLLAR, k);
            } else if (yy > 1.355 || (Math.abs(bx) > 0.105 && yy > 1.22)) {
                // bowl cut: straight fringe in front, short at the sides and back
                double strand = 0.9 + 0.12 * Math.sin(u * 220) + noise[y * T + x] * 0.2;
                plain(x, y, 0x2A2A30, strand * (0.7 + 0.5 * sheen(u, 3)));
            } else {
                face(x, y, u, yy, SKIN_BOY, 1.335, true, circ);
            }
        });
        // ---- sleeves: green with the stripes, a light cuff (y 1.19 .. 0.775)
        region(0, 256, 128, 256, 1.19, 0.775, (x, y, u, yy) -> {
            int c = GREEN;
            if (yy > 1.012 && yy < 1.05) c = BAND;
            if ((yy > 1.05 && yy < 1.058) || (yy > 1.004 && yy < 1.012)) c = TEAL;
            if (yy < 0.80) c = COLLAR;
            double fold = 0.5 * Math.sin(yy * 160) * (Math.abs(yy - 0.94) < 0.04 ? 1 : 0);
            cloth(x, y, c, fold, 1, 0.3 * sheen(u, 4));
        });
        // ---- legs: shorts, cuff, skin, socks (y 0.74 .. 0.09)
        region(128, 256, 128, 256, 0.76, 0.09, (x, y, u, yy) -> {
            if (yy > 0.49) cloth(x, y, KHAKI, 0.3 * Math.sin(u * 20 + yy * 25), 1, 0.08 * sheen(u, 4));
            else if (yy > 0.465) cloth(x, y, KHAKI, 0.6 * Math.sin((yy - 0.465) / 0.025 * Math.PI), 0.92, 0);
            else if (yy > 0.205) plain(x, y, SKIN_BOY, 0.82 + 0.12 * sheen(u, 3) - (0.46 - yy) * 0.25);
            else plain(x, y, 0xF2F2EE, 0.88 + 0.08 * Math.sin(u * 120) + (yy > 0.19 ? -0.08 : 0));
        });
        paintHands(SKIN_BOY);
        paintShoes(false);
        fillRest(0xFF1E4B33);
        save(out);
    }

    static void paintGirl(Path out) throws IOException {
        tex = new int[T * T];
        final int YELLOW = 0xF0D02C, YELLOW_LIGHT = 0xF6E07A, ORANGE = 0xE5661C;
        // ---- torso: yellow blouse, round collar, orange pleated dress (y 1.20 .. 0.55)
        region(0, 0, 256, 256, 1.20, 0.55, (x, y, u, yy) -> {
            double side = Math.cos((u - 0.5) * Math.PI * 2);
            double ao = 0.9 + 0.1 * side;
            if (yy > 1.07) {
                boolean collar = yy > 1.13 && Math.hypot((u - 0.5) * 2.2, (yy - 1.2) * 8) < 0.62 ;
                cloth(x, y, collar ? YELLOW_LIGHT : YELLOW, 0.2 * Math.sin(u * 26), ao, 0.12 * sheen(u, 4));
                if (collar && Math.abs(Math.hypot((u - 0.5) * 2.2, (yy - 1.2) * 8) - 0.6) < 0.025) plain(x, y, 0xC8A61E, 1);
                if (yy > 1.13 && Math.abs(u - 0.5) < 0.003) plain(x, y, 0xC8A61E, 1);
            } else {
                double pleat = yy < 0.86 ? Math.sin(u * Math.PI * 2 * 18) : 0.25 * Math.sin(u * 20);
                double sharp = yy < 0.86 ? Math.signum(pleat) * Math.pow(Math.abs(pleat), 0.6) : pleat;
                double hem = yy < 0.575 ? 0.85 : 1;
                cloth(x, y, ORANGE, sharp * (yy < 0.86 ? 1.1 : 0.4), ao * hem, 0.12 * sheen(u, 4));
                if (Math.abs(yy - 0.862) < 0.004) plain(x, y, ORANGE, 0.75);                       // waist seam
                if (yy > 1.06) plain(x, y, ORANGE, 0.8);                                             // neckline edge
            }
        });
        double circ = 2 * Math.PI * 0.156;
        // ---- head: black hair with straight bangs, hair to the neck at the sides and back; face
        region(256, 0, 256, 256, 1.515, 1.13, (x, y, u, yy) -> {
            double bx = (u - 0.5) * circ;
            double bang = 1.37 + 0.006 * Math.sin(bx * 260);
            if (yy > bang || (Math.abs(bx) > 0.112 && yy > 1.215)) {
                double strand = 0.9 + 0.14 * Math.sin(u * 240 + yy * 30) + noise[y * T + x] * 0.2;
                plain(x, y, 0x26252B, strand * (0.72 + 0.55 * sheen(u, 3)));
            } else {
                face(x, y, u, yy, SKIN_GIRL, 1.335, true, circ);
            }
        });
        // ---- pigtails: purple ties at the top, black hair below (y 1.31 .. 1.165)
        region(384, 256, 64, 64, 1.31, 1.165, (x, y, u, yy) -> {
            if (yy > 1.268) plain(x, y, 0x8B3FBF, 0.8 + 0.35 * sheen(u, 2) + (((int) (u * 8)) % 2 == 0 ? 0.08 : 0));
            else plain(x, y, 0x26252B, 0.8 + 0.4 * sheen(u, 3) + 0.1 * Math.sin(u * 60));
        });
        // ---- arms: puffed yellow sleeves, then the doll's arm with joint lines (y 1.19 .. 0.775)
        region(0, 256, 128, 256, 1.19, 0.775, (x, y, u, yy) -> {
            if (yy > 1.008) cloth(x, y, YELLOW, 0.6 * Math.sin(u * 40) * (yy > 1.1 ? 0.6 : 1), yy < 1.02 ? 0.85 : 1, 0.1 * sheen(u, 4));
            else {
                double k = 0.86 + 0.14 * sheen(u, 3);
                if (Math.abs(yy - 0.94) < 0.003 || Math.abs(yy - 0.80) < 0.003) k *= 0.6;            // ball joints
                if (Math.abs(yy - 0.94) < 0.012) k *= 0.93;
                plain(x, y, SKIN_GIRL, k);
            }
        });
        // ---- legs: knees, white knee socks with a zigzag top (y 0.74 .. 0.09)
        region(128, 256, 128, 256, 0.74, 0.09, (x, y, u, yy) -> {
            double zig = 0.428 + 0.004 * Math.abs(((u * 40) % 2) - 1);
            if (yy > zig) plain(x, y, SKIN_GIRL, 0.8 + 0.14 * sheen(u, 3));
            else {
                double rib = 0.92 + 0.07 * Math.sin(u * Math.PI * 2 * 24);
                plain(x, y, 0xF4F4F0, rib * (yy > 0.41 ? 0.9 : 1));
            }
        });
        paintHands(SKIN_GIRL);
        paintShoes(true);
        paintEyes();
        fillRest(0xFFE5661C);
        save(out);
    }

    // ------------------------------------------------------------------ detailed doll textures

    /** Face skin with nose shading, cheeks, brows and mouth (the eyes are separate patches). */
    static void faceHD(int x, int y, double u, double yy, int skin, boolean girl) {
        double circ = 2 * Math.PI * 0.158;
        double bx = (u - 0.5) * circ;
        double k = 0.9 + 0.12 * sheen(u, 3) + 0.04 * (yy - 1.25);
        double r = ((skin >> 16) & 255) * k, g = ((skin >> 8) & 255) * k, b = (skin & 255) * k;
        // soft shade under the hairline and on the jaw
        if (yy < 1.23) { double s = (1.23 - yy) * 3; r *= 1 - s; g *= 1 - s * 1.05; b *= 1 - s; }
        // cheeks
        double ch = Math.max(0, 1 - Math.hypot(Math.abs(bx) - 0.072, (yy - 1.287) * 1.4) / 0.04);
        r += ch * ch * 26; g -= ch * ch * 16; b -= ch * ch * 6;
        // brows: soft arcs over the eyes
        double bxx = Math.abs(bx) - 0.056, browY = 1.372 - 0.0035 * Math.cos(bxx / 0.03 * 1.4);
        if (Math.abs(bxx) < 0.03 && Math.abs(yy - browY) < 0.0028 + 0.0012 * (1 - Math.abs(bxx) / 0.03)) {
            double d = 1 - Math.abs(bxx) / 0.03;
            r = r * (1 - 0.55 * d) + 70 * 0.55 * d; g = g * (1 - 0.55 * d) + 50 * 0.55 * d; b = b * (1 - 0.55 * d) + 45 * 0.55 * d;
        }
        // mouth: small lips; the girl's red with a cupid's bow
        // (big enough to survive being rebuilt from 5 mm cubes)
        double mw = girl ? 0.024 : 0.022, my = 1.264;
        if (Math.abs(bx) < mw) {
            double t = bx / mw;
            double upper = my + 0.0035 + 0.005 * (girl ? (1 - t * t) - 0.35 * Math.exp(-t * t * 40) : (1 - t * t) * 0.6);
            double lower = my - 0.0035 - 0.008 * (1 - t * t) * (girl ? 1.1 : 0.7);
            if (yy <= upper && yy >= lower) {
                int lr = girl ? 196 : 186, lg = girl ? 72 : 110, lb = girl ? 74 : 104;
                double edge = Math.min(upper - yy, yy - lower) / 0.0025;
                double a = Math.min(1, edge * 1.5 + 0.35);
                r = r * (1 - a) + lr * a; g = g * (1 - a) + lg * a; b = b * (1 - a) + lb * a;
                if (Math.abs(yy - my + 0.0005) < 0.0007) { r *= 0.7; g *= 0.6; b *= 0.6; }
            }
        }
        // a faint nose shadow under the nose blob
        double ns = Math.max(0, 1 - Math.hypot(bx * 1.6, yy - 1.283) / 0.012);
        r -= ns * 22; g -= ns * 24; b -= ns * 20;
        put(x, y, r, g, b);
    }

    /** Glossy black hair: strands along the length, a bright sheen band near the top, lighter tips. */
    static void paintHair(int base) {
        region(256, 320, 128, 128, 0, 1, (x, y, u, s) -> {
            double strand = 0.85 + 0.12 * Math.sin(u * 2 * Math.PI * 46 + Math.sin(s * 7) * 0.8) + 0.06 * Math.sin(u * 2 * Math.PI * 131) + noise[y * T + x] * 0.25;
            double band = Math.exp(-Math.pow((s - 0.38) / 0.06, 2)) * (0.6 + 0.4 * sheen(u, 2));
            double k = strand * (0.75 + 0.45 * sheen(u, 3)) + band * 1.6 + (s > 0.88 ? 0.15 : 0);
            plain(x, y, base, k);
        });
    }

    static void paintCap() {
        region(384, 256, 128, 64, 0, 1, (x, y, u, s) -> {
            double seam = Math.abs(((u * 8) % 1) - 0.5) > 0.475 ? 0.7 : 1;
            double panel = 0.92 + 0.08 * Math.cos(((u * 8) % 1 - 0.5) * Math.PI);
            double k = (0.82 + 0.25 * sheen(u, 2) + 0.12 * (1 - s)) * seam * panel + noise[y * T + x] * 0.06;
            plain(x, y, BOY_SAGE, k);
        });
    }

    /**
     * The eye picture (64 x 32): white with a shadow under the upper lid, a brown iris with a darker rim and a
     * lighter lower half, a black pupil, two highlights, a thick upper lid line (with lashes for the girl).
     * The second copy (EYE_RED_DV lower) is the glowing red version with a red haze around the eye.
     */
    static void paintEyesHD(int skin, boolean lashes, double eyeW, double eyeH) {
        double irisR = 0.0118 * (lashes ? 1.08 : 1.0);
        for (int pass = 0; pass < 2; pass++) {
            int v0 = EYE_V0 + pass * EYE_RED_DV;
            for (int y = v0; y < v0 + EYE_H; y++) for (int x = EYE_U0; x < EYE_U0 + EYE_W; x++) {
                double ex = (x + 0.5 - EYE_U0) / EYE_W * 2 - 1, ey = (y + 0.5 - v0) / EYE_H * 2 - 1;   // -1..1, ey down
                double bx = ex * eyeW / 2, by = ey * eyeH / 2;                                           // blocks
                // almond: a fuller upper lid, a flatter lower one
                double top = -0.82 * Math.sqrt(Math.max(0, 1 - ex * ex)) * (1 - 0.12 * ex), bot = 0.62 * Math.sqrt(Math.max(0, 1 - ex * ex));
                boolean in = ey > top && ey < bot && Math.abs(ex) < 0.97;
                double r, g, b;
                double sr = ((skin >> 16) & 255) * 0.95, sg = ((skin >> 8) & 255) * 0.95, sb = (skin & 255) * 0.95;
                if (!in) {
                    r = sr; g = sg; b = sb;
                    double dTop = ey - top;   // negative above the lid
                    if (dTop > -0.16 && dTop <= 0 && Math.abs(ex) < 1.0) { r = 46; g = 32; b = 30; }                       // lid line
                    else if (lashes && dTop > -0.42 && dTop < -0.16 && ex > -0.85 && Math.abs(Math.sin((ex + 1) * 11)) < 0.22) { r = 40; g = 28; b = 28; }
                    else if (ey > bot && ey < bot + 0.12 && Math.abs(ex) < 0.85) { r = sr * 0.82; g = sg * 0.78; b = sb * 0.78; }  // lower lid
                    else {
                        // plain skin around the eye: transparent, the face below shows through (cutout rendering)
                        tex[y * T + x] = 0x00000000;
                        continue;
                    }
                    put(x, y, r, g, b);
                    continue;
                }
                double ir = Math.hypot(bx, by - 0.001) / irisR;
                double lidShade = Math.max(0, 1 - (ey - top) / 0.45) * 0.25;
                if (ir < 1) {
                    if (pass == 0) {
                        double lower = Math.max(0, by) / irisR;
                        r = 70 + 50 * lower; g = 44 + 30 * lower; b = 30 + 14 * lower;
                        if (ir > 0.82) { r *= 0.55; g *= 0.55; b *= 0.55; }
                        if (ir < 0.45) { r = 12; g = 10; b = 10; }
                    } else {
                        double glow = 1 - ir * 0.5;
                        r = 255; g = 40 + 90 * Math.max(0, 0.5 - ir) * glow; b = 26 + 40 * Math.max(0, 0.4 - ir);
                        if (ir > 0.86) { r = 190; g = 20; b = 16; }
                        if (ir < 0.38) { r = 120; g = 8; b = 8; }
                    }
                    r *= 1 - lidShade; g *= 1 - lidShade; b *= 1 - lidShade;
                    if (Math.hypot(bx + irisR * 0.35, by + irisR * 0.4) < irisR * 0.22) { r = 250; g = pass == 0 ? 250 : 210; b = pass == 0 ? 250 : 200; }
                    else if (Math.hypot(bx - irisR * 0.3, by - irisR * 0.35) < irisR * 0.1) { r = 230; g = pass == 0 ? 230 : 190; b = pass == 0 ? 230 : 180; }
                } else {
                    if (pass == 0) { r = 240; g = 236; b = 230; }
                    else { r = 215; g = 120; b = 112; }
                    double corner = Math.max(0, Math.abs(ex) - 0.6) * 0.5;
                    double k = 1 - lidShade - corner;
                    r *= k; g *= k; b *= k;
                }
                put(x, y, r, g, b);
            }
        }
    }

    static void paintBoyHD(Path out) throws IOException {
        tex = new int[T * T];
        final int GREEN = 0x1E4B33, BAND = 0x79AE92, TEAL = 0x2E9C78, KHAKI = 0xB39874;
        region(0, 0, 256, 256, TORSO_Y0, TORSO_Y1, (x, y, u, yy) -> {
            double side = Math.cos((u - 0.5) * Math.PI * 2);
            double ao = 0.9 + 0.1 * side;
            if (yy > 0.79) {
                int c = GREEN;
                if (yy > 1.012 && yy < 1.05) c = BAND;
                if ((yy > 1.05 && yy < 1.058) || (yy > 1.004 && yy < 1.012)) c = TEAL;
                double fold = 0.25 * Math.sin(u * 34 + yy * 9) * (yy < 0.93 ? 1 : 0.3) + 0.3 * Math.sin(u * 60) * (yy < 0.83 ? 1 : 0);
                cloth(x, y, c, fold, ao, 0.4 * sheen(u, 4) * (yy > 0.9 ? 1 : 0.6));
                if (yy > 1.06 && yy < 1.175 && Math.abs(u - 0.5) < 0.01) plain(x, y, GREEN, Math.abs(u - 0.5) > 0.007 ? 0.6 : 0.9);   // placket
                if (Math.abs(u - 0.25) < 0.003 || Math.abs(u - 0.75) < 0.003) plain(x, y, c, 0.78);                               // side seams
            } else {
                double fold = 0.3 * Math.sin(u * 24 + yy * 30) + 0.4 * Math.sin(u * 70) * (yy > 0.76 ? 1 : 0);
                cloth(x, y, KHAKI, fold, 1, 0.1 * sheen(u, 4));   // same shade as the shorts' legs: no visible seam
                if (Math.abs(u - 0.5) < 0.004 && yy > 0.70) plain(x, y, KHAKI, 0.7);                                               // fly
                if (Math.abs(u - 0.515) < 0.003 && yy > 0.715) plain(x, y, KHAKI, 0.78);
                if (Math.abs(Math.abs(u - 0.5) - 0.16 - (0.79 - yy) * 0.7) < 0.004 && yy > 0.72) plain(x, y, KHAKI, 0.72);          // pockets
            }
        });
        region(256, 0, 256, 256, 1.50, 1.13, (x, y, u, yy) -> faceHD(x, y, u, yy, SKIN_BOY, false));
        region(0, 256, 128, 256, 1.165, 0.775, (x, y, u, yy) -> {
            int c = GREEN;
            if (yy > 1.012 && yy < 1.05) c = BAND;
            if ((yy > 1.05 && yy < 1.058) || (yy > 1.004 && yy < 1.012)) c = TEAL;
            double fold = 0.6 * Math.sin(yy * 170 + Math.sin(u * 6) * 1.5) * (Math.abs(yy - 0.94) < 0.045 ? 1 : 0) + 0.25 * Math.sin(u * 22 + yy * 6);
            cloth(x, y, c, fold, yy > 1.17 ? 0.85 : 1, 0.35 * sheen(u, 4));
        });
        region(128, 256, 128, 256, 0.79, 0.09, (x, y, u, yy) -> {
            if (yy > 0.465) cloth(x, y, KHAKI, 0.35 * Math.sin(u * 20 + yy * 25) + 0.4 * Math.sin(yy * 120) * (yy > 0.68 ? 1 : 0), 1, 0.1 * sheen(u, 4));
            else if (yy > 0.205) {
                double knee = Math.exp(-Math.pow((yy - 0.41) / 0.02, 2)) * 0.06;
                plain(x, y, SKIN_BOY, 0.8 + 0.16 * sheen(u, 3) - (0.46 - yy) * 0.2 + knee);
            } else plain(x, y, 0xF2F2EE, 0.86 + 0.09 * Math.sin(u * Math.PI * 2 * 26));
        });
        paintHands(SKIN_BOY);
        paintShoes(false);
        paintHair(0x2A2A30);
        paintCap();
        paintEyesHD(SKIN_BOY, false, 0.06, 0.037);
        paintSwatches();
        fillRest(0xFF1E4B33);
        save(out);
    }

    static void paintGirlHD(Path out) throws IOException {
        tex = new int[T * T];
        region(0, 0, 256, 256, TORSO_Y0, TORSO_Y1, (x, y, u, yy) -> {
            double side = Math.cos((u - 0.5) * Math.PI * 2);
            double ao = 0.9 + 0.1 * side;
            if (yy > 1.07) {
                cloth(x, y, GIRL_YELLOW, 0.25 * Math.sin(u * 30) + 0.2 * Math.sin(u * 70) * (yy > 1.15 ? 1 : 0), ao, 0.15 * sheen(u, 4));
            } else if (yy > 0.875) {
                // bodice: darts and a little shading
                double dart = Math.exp(-Math.pow((Math.abs(u - 0.5) - 0.11) / 0.008, 2)) * (yy < 1.0 ? 1 : 0);
                cloth(x, y, GIRL_ORANGE, -dart * 1.4 + 0.15 * Math.sin(u * 30), ao, 0.18 * sheen(u, 4));
            } else {
                // skirt (its own pleated tube shares this picture): pleat shading, darker near the hem
                double p = tri(u * 24 + 0.25);
                double hem = yy < 0.575 ? 0.82 : 1;
                cloth(x, y, GIRL_ORANGE, p * 0.9, ao * hem, 0.15 * sheen(u, 4) * Math.max(0, p));
                if (Math.abs(p) > 0.94) plain(x, y, GIRL_ORANGE, 0.62);
            }
        });
        region(256, 0, 256, 256, 1.50, 1.13, (x, y, u, yy) -> faceHD(x, y, u, yy, SKIN_GIRL, true));
        region(0, 256, 128, 256, 1.172, 0.778, (x, y, u, yy) -> {
            if (yy > 1.008) cloth(x, y, GIRL_YELLOW, 0.5 * tri(u * 10) + 0.2 * Math.sin(yy * 120), yy < 1.02 ? 0.88 : 1, 0.15 * sheen(u, 4));
            else plain(x, y, SKIN_GIRL, 0.84 + 0.16 * sheen(u, 3));
        });
        region(128, 256, 128, 256, 0.74, 0.09, (x, y, u, yy) -> {
            if (yy > 0.43) plain(x, y, SKIN_GIRL, 0.8 + 0.16 * sheen(u, 3) + Math.exp(-Math.pow((yy - 0.47) / 0.02, 2)) * 0.05);
            else {
                double rib = 0.9 + 0.08 * Math.sin(u * Math.PI * 2 * 30);
                plain(x, y, 0xF4F4F0, rib * (0.92 + 0.08 * sheen(u, 3)));
            }
        });
        paintHands(SKIN_GIRL);
        paintShoes(true);
        paintHair(0x26252B);
        paintEyesHD(SKIN_GIRL, true, 0.07, 0.045);
        paintSwatches();
        fillRest(0xFFE5661C);
        save(out);
    }

    static void paintHands(int skin) {
        region(256, 256, 64, 64, 0.79, 0.655, (x, y, u, yy) -> plain(x, y, skin, 0.82 + 0.15 * sheen(u, 3) - (yy < 0.68 ? 0.05 : 0)));
    }

    /** Black glossy shoes; the girl's are Mary Janes with a strap. */
    static void paintShoes(boolean strap) {
        region(320, 256, 64, 64, 0.10, -0.004, (x, y, u, yy) -> {
            double k = 18 + 70 * Math.pow(sheen(u, 6), 1) + noise[y * T + x] * 6;
            if (yy < 0.012) k = 30;
            if (strap && Math.abs(yy - 0.075) < 0.008) k = 12 + 30 * sheen(u, 6);
            put(x, y, k, k, k * 1.05);
        });
    }

    /** The girl's eyes: normal ones, and below them the red glowing ones the game switches to. */
    static void paintEyes() {
        for (int pass = 0; pass < 2; pass++) {
            int v0 = EYE_V0 + pass * EYE_RED_DV;
            for (int y = v0; y < v0 + EYE_H; y++) for (int x = EYE_U0; x < EYE_U0 + EYE_W; x++) {
                double ex = (x + 0.5 - EYE_U0) / EYE_W * 2 - 1, ey = (y + 0.5 - v0) / EYE_H * 2 - 1;   // -1..1
                double e = Math.hypot(ex, ey * 1.0);
                double ir = Math.hypot(ex * 1.6, ey * 0.9);
                double r, g, b;
                if (e > 1.06) {
                    // around the eye: the face's skin (a faint red haze in the glowing version)
                    double k = 0.95, haze = pass == 1 ? Math.max(0, 1.5 - e) * 0.45 : 0;
                    r = ((SKIN_GIRL >> 16) & 255) * k; g = ((SKIN_GIRL >> 8) & 255) * k; b = (SKIN_GIRL & 255) * k;
                    r = r + (255 - r) * haze * 0.6; g *= 1 - haze * 0.5; b *= 1 - haze * 0.5;
                    put(x, y, r, g, b);
                    continue;
                }
                if (pass == 0) {
                    if (e > 0.94) { r = 60; g = 44; b = 40; }                                   // lid line
                    else if (ir < 0.62) {
                        double p = ir < 0.3 ? 1 : 0;
                        r = 48 - 30 * p; g = 32 - 20 * p; b = 26 - 16 * p;
                        if (Math.hypot(ex * 1.6 + 0.25, ey * 0.9 + 0.3) < 0.14) { r = g = b = 240; }
                    } else { r = 236; g = 232; b = 226; }
                } else {
                    // red: a glowing iris with a soft halo over a darkened white
                    double glow = Math.max(0, 1 - ir / 1.1);
                    if (e > 0.94) { r = 90; g = 24; b = 22; }
                    else if (ir < 0.62) {
                        double c = ir < 0.25 ? 0.75 : 1;
                        r = 255 * c; g = 40 + 70 * Math.max(0, 0.35 - ir) * c; b = 30 * c;
                        if (Math.hypot(ex * 1.6 + 0.25, ey * 0.9 + 0.3) < 0.12) { r = 255; g = 200; b = 180; }
                    } else { r = 150 + 90 * glow; g = 70 + 20 * glow; b = 66; }
                }
                put(x, y, r, g, b);
            }
        }
    }

    static void fillRest(int argb) {
        for (int i = 0; i < tex.length; i++) {
            int x = i % T, y = i / T;
            boolean eyeArea = x >= EYE_U0 && x < EYE_U0 + EYE_W && y >= EYE_V0 && y < EYE_V0 + EYE_RED_DV + EYE_H;
            if (tex[i] == 0 && !eyeArea) tex[i] = argb;
        }
    }

    static void save(Path out) throws IOException {
        BufferedImage img = new BufferedImage(T, T, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, T, T, tex, 0, T);
        ImageIO.write(img, "png", out.toFile());
    }

    static double smoothBand(double y, double a, double b) {
        if (y < a || y > b) return 0;
        double t = (y - a) / (b - a);
        return Math.sin(t * Math.PI);
    }

    static void darkLine(int x, int y, double k) {
        if (x < 0 || y < 0 || x >= T || y >= T) return;
        int c = tex[y * T + x];
        put(x, y, ((c >> 16) & 255) * k, ((c >> 8) & 255) * k, (c & 255) * k);
    }

    static void pocket(int x0, int y0, int w, int h) {
        for (int y = y0; y < y0 + h; y++) for (int x = x0; x < x0 + w; x++) {
            boolean edge = x == x0 || x == x0 + w - 1 || y == y0 + h - 1;
            boolean flap = y < y0 + h * 0.3;
            if (edge || y == (int) (y0 + h * 0.3)) darkLine(x, y, 0.68);
            else if (flap) darkLine(x, y, 1.06);
            else darkLine(x, y, 0.95);
        }
    }

    /**
     * The white symbol on the mask, as a line drawing: 0 circle, 1 triangle, 2 square. Pixel space is stretched
     * (the head is 0.8 blocks around but 0.45 high), so v distances are scaled to look right on the model.
     */
    static void symbol(int kind) {
        double cu = 128, cv = (1.885 - 1.675) / 0.445 * 256;
        double su = 256 / (2 * Math.PI * 0.125), sv = 256 / 0.445;   // pixels per block around / down
        // thick enough for the cube version: a line at least one cube (1.3 cm) wide
        double size = 0.056, line = 0.0135;
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            double bx = (x - cu) / su, by = (y - cv) / sv;               // blocks on the mask, y down
            double d;
            if (kind == 0) d = Math.abs(Math.hypot(bx, by) - size);
            else if (kind == 1) d = triangleDist(bx, by + 0.008, size * 1.15);
            else d = Math.abs(Math.max(Math.abs(bx), Math.abs(by)) - size * 0.88);
            if (d < line) {
                double a = Math.min(1, (line - d) / 0.0025);
                int c = tex[y * T + 256 + x];
                double k = 232 + noise[y * T + x] * 10;
                put(256 + x, y, ((c >> 16) & 255) * (1 - a) + k * a, ((c >> 8) & 255) * (1 - a) + k * a, (c & 255) * (1 - a) + k * a);
            }
        }
    }

    /** Distance to the outline of an upward equilateral triangle of circumradius r centred at 0. */
    static double triangleDist(double x, double y, double r) {
        double[][] p = new double[3][];
        for (int i = 0; i < 3; i++) {
            double a = -Math.PI / 2 + i * 2 * Math.PI / 3;
            p[i] = new double[]{Math.cos(a) * r, Math.sin(a) * r};
        }
        double best = 1e9;
        for (int i = 0; i < 3; i++) {
            double[] a = p[i], b = p[(i + 1) % 3];
            double dx = b[0] - a[0], dy = b[1] - a[1];
            double t = Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / (dx * dx + dy * dy)));
            best = Math.min(best, Math.hypot(x - a[0] - t * dx, y - a[1] - t * dy));
        }
        return best;
    }

    // ------------------------------------------------------------------ preview (CPU skinning + raster)
    static double[][] globalsFor(AnimSpec a, double t) {
        double[][] rot = new double[J][3];
        double[] hp = new double[3];
        a.fn.pose(t, rot, hp);
        double[][] g = new double[J][];
        for (int j = 0; j < J; j++) {
            double[] q = quat(rot[j]);
            double[] tr = restT(j);
            if (j == HIPS) { tr[0] += hp[0] * ASCALE; tr[1] += hp[1] * ASCALE; tr[2] += hp[2] * ASCALE; }
            double[] m = qmat(q, tr);
            g[j] = JP[j] < 0 ? m : mul(g[JP[j]], m);
        }
        return g;
    }

    static double[] qmat(double[] q, double[] t) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        return new double[]{1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), t[0],
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), t[1],
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), t[2]};
    }

    static double[] mul(double[] a, double[] b) {
        double[] r = new double[12];
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 4; k++) r[i * 4 + k] = a[i * 4] * b[k] + a[i * 4 + 1] * b[4 + k] + a[i * 4 + 2] * b[8 + k] + (k == 3 ? a[i * 4 + 3] : 0);
        }
        return r;
    }

    static double[][] skinned(double[][] g) {
        double[][] out = new double[P.size()][];
        for (int v = 0; v < P.size(); v++) {
            double[] p = P.get(v), s = new double[6];
            int[] ji = JI.get(v); double[] jw = JW.get(v);
            for (int k = 0; k < ji.length; k++) {
                double[] m = g[ji[k]], b = JG[ji[k]];
                double x = p[0] - b[0], y = p[1] - b[1], z = p[2] - b[2];
                double[] n = N.get(v);
                for (int r = 0; r < 3; r++) {
                    s[r] += jw[k] * (m[r * 4] * x + m[r * 4 + 1] * y + m[r * 4 + 2] * z + m[r * 4 + 3]);
                    s[3 + r] += jw[k] * (m[r * 4] * n[0] + m[r * 4 + 1] * n[1] + m[r * 4 + 2] * n[2]);
                }
            }
            out[v] = s;
        }
        return out;
    }

    static double ZOOM = 1500, CAMY = 1.0;
    /** Preview yaw per figure (degrees), cycled. */
    static double[] YAWS = {22, -22};

    static void preview(Path file, String[] animNames, double[] times, int[] syms, BufferedImage[] texs) throws IOException {
        int W = 1500, H = 900;
        float[] zb = new float[W * H];
        int[] img = new int[W * H];
        for (int i = 0; i < W * H; i++) {
            zb[i] = Float.MAX_VALUE;
            double t = (i / W) / (double) H;
            img[i] = 0xFF000000 | (cl(206 - 40 * t) << 16) | (cl(206 - 50 * t) << 8) | cl(214 - 40 * t);
        }
        List<AnimSpec> all = anims();
        int n = animNames.length;
        for (int f = 0; f < n; f++) {
            String want = animNames[f];
            AnimSpec a = all.stream().filter(s -> s.name.equals(want)).findFirst().orElseThrow();
            double[][] sk = skinned(globalsFor(a, times[f]));
            double ox = (f - (n - 1) / 2.0) * (n > 3 ? SPACING * 0.83 : SPACING);
            BufferedImage tx = texs[syms[f]];
            double yaw = Math.toRadians(YAWS[f % YAWS.length]);
            double cy = Math.cos(yaw), sy = Math.sin(yaw);
            double[] light = norm(new double[]{-0.45, 0.75, 0.6});
            for (int[] q : Q) {
                for (int tri = 0; tri < 2; tri++) {
                    int[] ids = tri == 0 ? new int[]{q[0], q[1], q[2]} : new int[]{q[0], q[2], q[3]};
                    double[][] s = new double[3][];
                    double[][] uvs = new double[3][];
                    double[] shade = new double[3];
                    boolean ok = true;
                    for (int i = 0; i < 3; i++) {
                        double[] v = sk[ids[i]];
                        double x = v[0] * cy + v[2] * sy, z = -v[0] * sy + v[2] * cy, y = v[1];
                        double nx = v[3] * cy + v[5] * sy, nz = -v[3] * sy + v[5] * cy, ny = v[4];
                        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-9;
                        x += ox;
                        // camera at (0, 1.15, 4.2) looking at -z
                        double cz = 4.6 - z, k = ZOOM / cz;
                        if (cz < 0.1) ok = false;
                        s[i] = new double[]{W / 2.0 + x * k, H * 0.55 - (y - CAMY) * k, cz};
                        uvs[i] = UV.get(ids[i]);
                        shade[i] = 0.45 + 0.55 * Math.max(0, (nx * light[0] + ny * light[1] + nz * light[2]) / nl) + 0.12 * Math.max(0, nz / nl);
                    }
                    if (!ok) continue;
                    raster(s, uvs, shade, tx, zb, img, W, H);
                }
            }
        }
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, W, H, img, 0, W);
        ImageIO.write(out, "png", file.toFile());
    }

    static void raster(double[][] s, double[][] uvs, double[] shade, BufferedImage tx, float[] zb, int[] img, int W, int H) {
        double area = (s[1][0] - s[0][0]) * (s[2][1] - s[0][1]) - (s[2][0] - s[0][0]) * (s[1][1] - s[0][1]);
        if (Math.abs(area) < 1e-9) return;
        int minX = (int) Math.max(0, Math.floor(Math.min(s[0][0], Math.min(s[1][0], s[2][0]))));
        int maxX = (int) Math.min(W - 1, Math.ceil(Math.max(s[0][0], Math.max(s[1][0], s[2][0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(s[0][1], Math.min(s[1][1], s[2][1]))));
        int maxY = (int) Math.min(H - 1, Math.ceil(Math.max(s[0][1], Math.max(s[1][1], s[2][1]))));
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            double px = x + 0.5, py = y + 0.5;
            double w0 = ((s[1][0] - px) * (s[2][1] - py) - (s[2][0] - px) * (s[1][1] - py)) / area;
            double w1 = ((s[2][0] - px) * (s[0][1] - py) - (s[0][0] - px) * (s[2][1] - py)) / area;
            double w2 = 1 - w0 - w1;
            if (w0 < 0 || w1 < 0 || w2 < 0) continue;
            float z = (float) (w0 * s[0][2] + w1 * s[1][2] + w2 * s[2][2]);
            int i = y * W + x;
            if (z >= zb[i]) continue;
            double u = w0 * uvs[0][0] + w1 * uvs[1][0] + w2 * uvs[2][0], v = w0 * uvs[0][1] + w1 * uvs[1][1] + w2 * uvs[2][1];
            int c = tx.getRGB(Math.max(0, Math.min(T - 1, (int) u)), Math.max(0, Math.min(T - 1, (int) v)));
            if ((c >>> 24) < 26) continue;   // cutout, like the game
            zb[i] = z;
            double sh = w0 * shade[0] + w1 * shade[1] + w2 * shade[2];
            img[i] = 0xFF000000 | (cl(((c >> 16) & 255) * sh) << 16) | (cl(((c >> 8) & 255) * sh) << 8) | cl((c & 255) * sh);
        }
    }

    // ------------------------------------------------------------------ main
    public static void main(String[] args) throws Exception {
        Path assets = Paths.get(args[0]);
        Path prev = Paths.get(args[1]);
        Files.createDirectories(prev);
        String which = args.length > 2 ? args[2] : "all";
        makeNoise();
        if (which.equals("all") || which.equals("dolls")) dolls(assets, prev);
        if (which.equals("all") || which.equals("player")) player(assets, prev);
        if (!(which.equals("all") || which.equals("guards"))) return;
        reset();
        JG = GUARD_JG;
        ASCALE = 1.0;
        ZOOM = 1500;
        CAMY = 1.0;
        SPACING = 0.75;
        YAWS = new double[]{22, -22};
        buildMesh();
        System.out.println("smooth model: vertices " + P.size() + ", quads " + Q.size());
        snapshot();
        String[] ids = {"guard_circle", "guard_triangle", "guard_square"};
        BufferedImage[] texs = new BufferedImage[3];
        for (int s = 0; s < 3; s++) {
            Path png = assets.resolve("textures/entity/" + ids[s] + ".png");
            paintTexture(s, png);
            // the guard in cubes: body 3.4 cm, head 1.6 cm (the mask symbol must stay readable)
            voxelize(tex.clone(), 0.034, 0.013, false);
            savePalette(png);
            texs[s] = ImageIO.read(png.toFile());
            writeSkin(assets.resolve("models/entity/" + ids[s] + ".skin"));
            // preview of this guard with its own cubes and palette (the cube colours differ per symbol)
            ZOOM = 2600;
            CAMY = 1.55;
            YAWS = new double[]{14, -30};
            preview(prev.resolve(ids[s] + "_closeup.png"), new String[]{"idle", "walk"}, new double[]{0.4, 0.25}, new int[]{s, s}, texs);
            ZOOM = 1500;
            CAMY = 1.0;
            YAWS = new double[]{22, -22};
            if (s < 2) {
                // the next symbol: same voxels, so rebuild from the smooth model with its own colours
                P.clear(); UV.clear(); JI.clear(); JW.clear(); Q.clear(); GROUP.clear(); N.clear();
                for (double[] p : SP) { P.add(p.clone()); N.add(new double[]{0, 1, 0}); }
                for (double[] p : SUV) UV.add(p.clone());
                for (double[] p : SJW) JW.add(p.clone());
                for (int[] p : SJI) JI.add(p.clone());
                for (int[] p : SQ) Q.add(p.clone());
                GROUP.addAll(SGROUP);
            }
        }
        preview(prev.resolve("guard_lineup.png"), new String[]{"idle", "idle", "idle"}, new double[]{0.4, 1.2, 2.0}, new int[]{0, 1, 2}, texs);
        ZOOM = 1150;
        preview(prev.resolve("guard_anims.png"), new String[]{"walk", "run", "attack", "crouch", "dance"}, new double[]{0.25, 0.15, 0.3, 0.5, 0.3}, new int[]{0, 1, 2, 0, 1}, texs);
        System.out.println("done");
    }

    /** Boy and girl dolls: mesh, texture, .skin and previews (front, back, animations). */
    static void dolls(Path assets, Path prev) throws IOException {
        JG = DOLL_JG;
        BufferedImage[] texs = new BufferedImage[1];
        for (String id : new String[]{"doll_boy", "doll_girl"}) {
            reset();
            boolean girl = id.equals("doll_girl");
            TORSO_Y0 = 1.20;
            TORSO_Y1 = girl ? 0.55 : 0.69;
            JG = DOLL_JG;
            if (girl) buildGirlHD(); else buildBoyHD();
            System.out.println(id + ": smooth model " + P.size() + " vertices, " + Q.size() + " quads");
            Path png = assets.resolve("textures/entity/" + id + ".png");
            if (girl) paintGirlHD(png); else paintBoyHD(png);
            // a bit taller than Huggy (2.8): 3 blocks; then rebuilt from cubes (body 5 cm, head 2.5 cm)
            scaleAll(DOLL_SCALE);
            snapshot();
            voxelize(tex.clone(), 0.04, 0.025, girl);
            savePalette(png);
            texs[0] = ImageIO.read(png.toFile());
            writeSkin(assets.resolve("models/entity/" + id + ".skin"));
            SPACING = 1.45;
            ZOOM = 780;
            CAMY = 1.5;
            YAWS = new double[]{12, 180};
            preview(prev.resolve(id + "_front_back.png"), new String[]{"idle", "idle"}, new double[]{0.3, 0.3}, new int[]{0, 0}, texs);
            ZOOM = 1300;
            CAMY = 2.0;
            YAWS = new double[]{-28, 150};
            preview(prev.resolve(id + "_closeup.png"), new String[]{"idle", "idle"}, new double[]{0.3, 0.3}, new int[]{0, 0}, texs);
            if (girl) {
                // the red eyes: same frame with the eye cubes moved to the red palette rows
                for (int v = 0; v < UV.size(); v++) if (UV.get(v)[1] >= PAL_EYE_V0 && UV.get(v)[1] < PAL_EYE_V0 + PAL_EYE_H) UV.get(v)[1] += PAL_EYE_DV;
                ZOOM = 1900;
                CAMY = 2.55;
                YAWS = new double[]{8};
                preview(prev.resolve(id + "_red_eyes.png"), new String[]{"idle"}, new double[]{0.3}, new int[]{0}, texs);
                for (int v = 0; v < UV.size(); v++) if (UV.get(v)[1] >= PAL_EYE_V0 + PAL_EYE_DV) UV.get(v)[1] -= PAL_EYE_DV;
            }
            ZOOM = 580;
            CAMY = 1.5;
            YAWS = new double[]{25, -25};
            preview(prev.resolve(id + "_anims.png"), new String[]{"walk", "run", "attack", "dance"}, new double[]{0.25, 0.15, 0.3, 0.3}, new int[]{0, 0, 0, 0}, texs);
        }
    }

    // ================================================================== voxel style (like the converted Huggy)

    /** Translation scale of the animations (hip bob etc.), set with the model's scale. */
    static double ASCALE = 1.0;
    /** The dolls' scale: 1.55 blocks as built -> 3.0 blocks (Huggy is 2.8). */
    static final double DOLL_SCALE = 3.0 / 1.55;
    static double SPACING = 0.75;

    /** Scales the smooth model and its skeleton (the dolls are made a bit taller than Huggy). */
    static void scaleAll(double s) {
        for (double[] p : P) { p[0] *= s; p[1] *= s; p[2] *= s; }
        double[][] j = new double[JG.length][];
        for (int i = 0; i < JG.length; i++) j[i] = new double[]{JG[i][0] * s, JG[i][1] * s, JG[i][2] * s};
        JG = j;
        ASCALE = s;
    }

    static List<double[]> SP, SUV, SJW;
    static List<int[]> SJI, SQ;
    static List<Integer> SGROUP;

    /** Keeps the smooth model (the voxel model is rebuilt from it, once per texture). */
    static void snapshot() {
        SP = new ArrayList<>(); for (double[] p : P) SP.add(p.clone());
        SUV = new ArrayList<>(); for (double[] p : UV) SUV.add(p.clone());
        SJW = new ArrayList<>(); for (double[] p : JW) SJW.add(p.clone());
        SJI = new ArrayList<>(); for (int[] p : JI) SJI.add(p.clone());
        SQ = new ArrayList<>(); for (int[] p : Q) SQ.add(p.clone());
        SGROUP = new ArrayList<>(GROUP);
    }

    static final class Cell {
        int group = -1, n, en;
        double r, g, b, er, eg, eb;
        double best = Double.MAX_VALUE;   // distance of the sample whose colour the cube takes (nearest to its centre)
        final double[] jw = new double[J];
    }

    static final int PAL_EYE_V0 = 448, PAL_EYE_H = 16, PAL_EYE_DV = 32;

    static long key(int x, int y, int z) { return ((long) (x + 4096) << 26) | ((long) (y + 4096) << 13) | (z + 4096); }
    static int kx(long k) { return (int) ((k >> 26) & 8191) - 4096; }
    static int ky(long k) { return (int) ((k >> 13) & 8191) - 4096; }
    static int kz(long k) { return (int) (k & 8191) - 4096; }

    /**
     * Rebuilds the model from small cubes, like Huggy: the surface of the smooth model (sampled densely) fills a
     * grid - the head part a finer one - every cube gets the averaged texture colour (with a slight random tint) and
     * the averaged bone weights; only cube faces towards the open air (or towards another body part, so moving
     * limbs never show holes) are kept. Where parts overlap, the one built later (outer: hair, collar...) wins.
     * The texture becomes a palette (one pixel per colour); eye cubes get their own rows, and their red twins
     * PAL_EYE_DV rows lower, so the game can still swap the doll's eyes.
     */
    /** Reduced model for far NPCs and shadows: bigger cubes, appended to the same vertex list (format version 5/6). */
    static final List<int[]> LODQ = new ArrayList<>();
    static final List<Integer> LODV = new ArrayList<>();
    static List<int[]> FACE_OUT;
    static final Map<Integer, Integer> PAL = new HashMap<>(), EYEPAL = new HashMap<>();
    static final List<Integer> PAL_COLS = new ArrayList<>(), EYE_COLS = new ArrayList<>(), EYE_RED = new ArrayList<>();

    static void voxelize(int[] src, double cBody, double cHead, boolean eyes) {
        voxelize(src, cBody, cHead, eyes, false);
        int first = P.size();
        voxelize(src, cBody * 2.0, cHead * 1.8, eyes, true);
        for (int v = first; v < P.size(); v++) LODV.add(v);
        System.out.println("  far model: " + LODQ.size() + " faces, " + LODV.size() + " vertices");
    }

    static void voxelize(int[] src, double cBody, double cHead, boolean eyes, boolean lod) {
        @SuppressWarnings("unchecked")
        Map<Long, Cell>[] grids = new Map[]{new HashMap<Long, Cell>(), new HashMap<Long, Cell>(), new HashMap<Long, Cell>()};
        double[] cs = {cBody, cHead, FINE_CELL > 0 ? FINE_CELL * (lod ? 1.8 : 1.0) : cHead};
        double[] w = new double[J];
        for (int[] q : SQ) {
            int group = SGROUP.get(q[0]);
            for (int[] tr : new int[][]{{q[0], q[1], q[2]}, {q[0], q[2], q[3]}}) {
                double[] a = SP.get(tr[0]), b = SP.get(tr[1]), c = SP.get(tr[2]);
                double edge = Math.max(len(sub(a, b)), Math.max(len(sub(b, c)), len(sub(c, a))));
                int segA = JOINT_SEG[dominant(SJI.get(tr[0]), SJW.get(tr[0]))];
                // texture pixels this triangle may use (an edge at u = 384 must not read the region next to it)
                double uMin = Math.min(SUV.get(tr[0])[0], Math.min(SUV.get(tr[1])[0], SUV.get(tr[2])[0]));
                double uMax = Math.max(SUV.get(tr[0])[0], Math.max(SUV.get(tr[1])[0], SUV.get(tr[2])[0]));
                double vMin = Math.min(SUV.get(tr[0])[1], Math.min(SUV.get(tr[1])[1], SUV.get(tr[2])[1]));
                double vMax = Math.max(SUV.get(tr[0])[1], Math.max(SUV.get(tr[1])[1], SUV.get(tr[2])[1]));
                int pu0 = (int) Math.floor(uMin), pu1 = Math.max(pu0, (int) Math.ceil(uMax) - 1);
                int pv0 = (int) Math.floor(vMin), pv1 = Math.max(pv0, (int) Math.ceil(vMax) - 1);
                double cell = FINE_GROUPS.contains(group) ? cs[2] : (segA == 1 ? cHead : cBody);
                int n = Math.max(1, (int) Math.ceil(edge / (cell * 0.4)));
                for (int i = 0; i <= n; i++) for (int j = 0; j <= n - i; j++) {
                    double wa = i / (double) n, wb = j / (double) n, wc = 1 - wa - wb;
                    double x = a[0] * wa + b[0] * wb + c[0] * wc, y = a[1] * wa + b[1] * wb + c[1] * wc, z = a[2] * wa + b[2] * wb + c[2] * wc;
                    double u = SUV.get(tr[0])[0] * wa + SUV.get(tr[1])[0] * wb + SUV.get(tr[2])[0] * wc;
                    double v = SUV.get(tr[0])[1] * wa + SUV.get(tr[1])[1] * wb + SUV.get(tr[2])[1] * wc;
                    int px = Math.max(Math.max(0, pu0), Math.min(Math.min(T - 1, pu1), (int) Math.floor(u)));
                    int py = Math.max(Math.max(0, pv0), Math.min(Math.min(T - 1, pv1), (int) Math.floor(v)));
                    int col = src[py * T + px];
                    if ((col >>> 24) < 26) continue;   // transparent (around the eyes)
                    Arrays.fill(w, 0);
                    double[] bw = {wa, wb, wc};
                    for (int k = 0; k < 3; k++) {
                        int[] ji = SJI.get(tr[k]); double[] jw = SJW.get(tr[k]);
                        for (int m = 0; m < ji.length; m++) w[ji[m]] += jw[m] * bw[k];
                    }
                    int dj = 0; for (int m = 1; m < J; m++) if (w[m] > w[dj]) dj = m;
                    int gi = FINE_GROUPS.contains(group) ? 2 : (JOINT_SEG[dj] == 1 ? 1 : 0);
                    double cc = cs[gi];
                    long k = key((int) Math.floor(x / cc), (int) Math.floor(y / cc), (int) Math.floor(z / cc));
                    Cell ce = grids[gi].computeIfAbsent(k, kk -> new Cell());
                    if (group < ce.group) continue;
                    if (group > ce.group) {
                        ce.group = group; ce.n = 0; ce.en = 0; ce.r = ce.g = ce.b = ce.er = ce.eg = ce.eb = 0;
                        ce.best = Double.MAX_VALUE;
                        Arrays.fill(ce.jw, 0);
                    }
                    ce.n++;
                    for (int m = 0; m < J; m++) ce.jw[m] += w[m];
                    // colour of the sample nearest to the cube's centre: crisp eyes, lips and symbols (no blur)
                    double dx = x / cc - Math.floor(x / cc) - 0.5, dy = y / cc - Math.floor(y / cc) - 0.5, dz = z / cc - Math.floor(z / cc) - 0.5;
                    double dist = dx * dx + dy * dy + dz * dz;
                    if (dist < ce.best) {
                        ce.best = dist;
                        ce.r = (col >> 16) & 255; ce.g = (col >> 8) & 255; ce.b = col & 255;
                        boolean eye = eyes && px >= EYE_U0 && px < EYE_U0 + EYE_W && py >= EYE_V0 && py < EYE_V0 + EYE_H;
                        ce.en = eye ? 1 : 0;
                        if (eye) {
                            int rc = src[Math.min(T - 1, py + EYE_RED_DV) * T + px];
                            ce.er = (rc >> 16) & 255; ce.eg = (rc >> 8) & 255; ce.eb = rc & 255;
                        }
                    }
                }
            }
        }
        // new mesh (the far model is appended to it, sharing the palette)
        if (!lod) {
            P.clear(); N.clear(); UV.clear(); JI.clear(); JW.clear(); Q.clear(); GROUP.clear();
            LODQ.clear(); LODV.clear();
            PAL.clear(); EYEPAL.clear(); PAL_COLS.clear(); EYE_COLS.clear(); EYE_RED.clear();
        }
        FACE_OUT = lod ? LODQ : Q;
        Map<Integer, Integer> pal = PAL, eyePal = EYEPAL;
        List<Integer> palCols = PAL_COLS, eyeCols = EYE_COLS, eyeRed = EYE_RED;
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int gi = 0; gi < 3; gi++) {
            Map<Long, Cell> grid = grids[gi];
            grid.values().removeIf(ce -> ce.n == 0);
            if (grid.isEmpty()) continue;
            double c = cs[gi];
            // outside air: flood fill from the corner of the bounding box (enclosed air inside the shells is not)
            int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
            for (long k : grid.keySet()) {
                x0 = Math.min(x0, kx(k)); y0 = Math.min(y0, ky(k)); z0 = Math.min(z0, kz(k));
                x1 = Math.max(x1, kx(k)); y1 = Math.max(y1, ky(k)); z1 = Math.max(z1, kz(k));
            }
            x0--; y0--; z0--; x1++; y1++; z1++;
            Set<Long> outside = new HashSet<>();
            ArrayDeque<Long> todo = new ArrayDeque<>();
            long start = key(x0, y0, z0);
            outside.add(start); todo.add(start);
            while (!todo.isEmpty()) {
                long k = todo.poll();
                int x = kx(k), y = ky(k), z = kz(k);
                for (int[] d : dirs) {
                    int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                    if (nx < x0 || ny < y0 || nz < z0 || nx > x1 || ny > y1 || nz > z1) continue;
                    long nk = key(nx, ny, nz);
                    if (grid.containsKey(nk) || !outside.add(nk)) continue;
                    todo.add(nk);
                }
            }
            for (Map.Entry<Long, Cell> e : grid.entrySet()) {
                long k = e.getKey();
                Cell ce = e.getValue();
                int x = kx(k), y = ky(k), z = kz(k);
                // the cube's bones: the two strongest
                int j1 = 0, j2 = -1;
                for (int m = 1; m < J; m++) if (ce.jw[m] > ce.jw[j1]) j1 = m;
                for (int m = 0; m < J; m++) if (m != j1 && (j2 < 0 || ce.jw[m] > ce.jw[j2])) j2 = m;
                double tot = ce.jw[j1] + (j2 >= 0 ? ce.jw[j2] : 0);
                double w2 = j2 >= 0 && ce.jw[j2] / tot > 0.08 ? ce.jw[j2] / tot : 0;
                int seg = JOINT_SEG[j1];
                // colour: average, a slight random tint per cube (stronger on dark hair), quantised to the palette
                double luma = ce.r * 0.3 + ce.g * 0.59 + ce.b * 0.11;
                double var = 1 + (hash(x, y, z, gi) - 0.5) * (luma < 70 ? 0.26 : 0.1);
                int rgb = q6(ce.r * var, ce.g * var, ce.b * var);
                double[] uv;
                if (ce.en > 0) {
                    int red = q6(ce.er, ce.eg, ce.eb);
                    int key2 = rgb * 31 + red;
                    Integer idx = eyePal.get(key2);
                    if (idx == null) { idx = eyeCols.size(); eyePal.put(key2, idx); eyeCols.add(rgb); eyeRed.add(red); }
                    uv = new double[]{idx % 512 + 0.5, PAL_EYE_V0 + idx / 512 + 0.5};
                } else {
                    Integer idx = pal.get(rgb);
                    if (idx == null) { idx = palCols.size(); pal.put(rgb, idx); palCols.add(rgb); }
                    uv = new double[]{idx % 512 + 0.5, idx / 512 + 0.5};
                }
                for (int[] d : dirs) {
                    long nk = key(x + d[0], y + d[1], z + d[2]);
                    Cell nb = grid.get(nk);
                    boolean open = outside.contains(nk) || (nb != null && JOINT_SEG[strongest(nb.jw)] != seg);
                    if (!open) continue;
                    face(x, y, z, c, d, uv, j1, w2 > 0 ? j2 : j1, w2);
                }
            }
        }
        // palette texture
        tex = new int[T * T];
        for (int i = 0; i < palCols.size(); i++) tex[(i / 512) * T + i % 512] = 0xFF000000 | palCols.get(i);
        for (int i = 0; i < eyeCols.size(); i++) {
            tex[(PAL_EYE_V0 + i / 512) * T + i % 512] = 0xFF000000 | eyeCols.get(i);
            tex[(PAL_EYE_V0 + PAL_EYE_DV + i / 512) * T + i % 512] = 0xFF000000 | eyeRed.get(i);
        }
        if (!lod) System.out.println("voxels: " + grids[0].size() + " body + " + grids[1].size() + " head cubes, " + Q.size() + " faces, "
                + P.size() + " vertices, " + palCols.size() + " colours (+" + eyeCols.size() + " eye)");
    }

    static int dominant(int[] ji, double[] jw) {
        int b = 0; for (int i = 1; i < ji.length; i++) if (jw[i] > jw[b]) b = i;
        return ji[b];
    }

    static int strongest(double[] jw) { int b = 0; for (int m = 1; m < jw.length; m++) if (jw[m] > jw[b]) b = m; return b; }

    static double hash(int x, int y, int z, int g) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ g * 2654435761L;
        h ^= (h >>> 13); h *= 0x5bd1e995L; h ^= (h >>> 15);
        return (h & 0xFFFF) / 65535.0;
    }

    static int q6(double r, double g, double b) {
        int ri = (int) Math.max(0, Math.min(255, r)) & 0xFC, gi = (int) Math.max(0, Math.min(255, g)) & 0xFC, bi = (int) Math.max(0, Math.min(255, b)) & 0xFC;
        return (ri << 16) | (gi << 8) | bi;
    }

    /** One cube face (axis direction d) of the cell, counter-clockwise from outside, flat normal, one palette pixel. */
    static void face(int x, int y, int z, double c, int[] d, double[] uv, int j1, int j2, double w2) {
        double[] o = {x * c, y * c, z * c};
        int axis = d[0] != 0 ? 0 : d[1] != 0 ? 1 : 2, sgn = d[axis];
        int ua = (axis + 1) % 3, va = (axis + 2) % 3;
        double[][] corners = new double[4][];
        int[][] uvSteps = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            double[] p = o.clone();
            p[axis] += sgn > 0 ? c : 0;
            p[ua] += uvSteps[i][0] * c;
            p[va] += uvSteps[i][1] * c;
            corners[i] = p;
        }
        double[] nrm = {d[0], d[1], d[2]};
        double[] cr = cross(sub(corners[1], corners[0]), sub(corners[2], corners[0]));
        if (cr[0] * nrm[0] + cr[1] * nrm[1] + cr[2] * nrm[2] < 0) { double[] t = corners[1]; corners[1] = corners[3]; corners[3] = t; }
        int base = P.size();
        for (double[] p : corners) {
            P.add(p);
            N.add(nrm.clone());
            UV.add(uv.clone());
            if (w2 > 0 && j2 != j1) { JI.add(new int[]{j1, j2}); JW.add(new double[]{1 - w2, w2}); }
            else { JI.add(new int[]{j1}); JW.add(new double[]{1}); }
            GROUP.add(0);
        }
        FACE_OUT.add(new int[]{base, base + 1, base + 2, base + 3});
    }

    static void savePalette(Path out) throws IOException {
        BufferedImage img = new BufferedImage(T, T, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, T, T, tex, 0, T);
        ImageIO.write(img, "png", out.toFile());
    }

    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double len(double[] a) { return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); }
    static double[] norm(double[] a) { double l = len(a); return new double[]{a[0] / l, a[1] / l, a[2] / l}; }
}
