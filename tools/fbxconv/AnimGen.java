import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Hand-keyed fight animations for the kickmod player model + a stick-figure previewer that uses
 * exactly the Minecraft math (ModelPart ZYX rotations, PlayerRenderer scale, kickmod root transform).
 *
 *   java AnimGen.java gen  <outDir>             writes uppercut/cross/hook/spinkick/pushkick .anim
 *   java AnimGen.java prev <file.anim> <out.png> [everyNthFrame]
 *
 * Authoring units: degrees. Torso: yaw + = turns left (right shoulder forward), pitch + = leans forward,
 * roll + = leans left. Limbs: x - = raised forward, out + = away from the body, y = twist/sweep.
 * Feet are put on the ground automatically unless a key has lift > 0 (jump/tiptoe).
 */
public class AnimGen {
    static final int FPS = 30;
    // channel layout (v2): root xyz, quat xyzw, head xyz, rArm x z, lArm x z, rLeg x z, lLeg x z, rArm y, lArm y, rLeg y, lLeg y
    static final int CH = 22;

    // ------------------------------------------------------------------ authoring

    static final String[] P = {"fwd", "side", "lift", "yaw", "pitch", "roll", "hx", "hy", "hz",
            "rax", "rao", "ray", "lax", "lao", "lay", "rlx", "rlo", "rly", "llx", "llo", "lly"};

    static final class Key {
        double t; double[] v = new double[P.length];
        Key(double t, double[] base) { this.t = t; this.v = base.clone(); }
        Key s(String k, double val) { v[idx(k)] = val; return this; }
    }
    static int idx(String k) { for (int i = 0; i < P.length; i++) if (P[i].equals(k)) return i; throw new IllegalArgumentException(k); }

    static double[] guard() {
        double[] g = new double[P.length];
        g[idx("yaw")] = -15; g[idx("pitch")] = 5; g[idx("hx")] = 2; g[idx("hy")] = -15;
        g[idx("rax")] = -62; g[idx("rao")] = -18;
        g[idx("lax")] = -72; g[idx("lao")] = -16;
        g[idx("rlx")] = 13; g[idx("rlo")] = 5;
        g[idx("llx")] = -13; g[idx("llo")] = 5;
        return g;
    }
    static Key k(double t) { return new Key(t, guard()); }
    static Key k(double t, Key from) { return new Key(t, from.v); }

    static final class Anim { String name; double impact; List<Key> keys = new ArrayList<>(); }

    static List<Anim> anims() {
        List<Anim> list = new ArrayList<>();

        // 1. Uppercut (right hand): drop and coil, then drive up from the legs, arm finishes above the head.
        Anim a = new Anim(); a.name = "uppercut"; a.impact = 1.25;
        Key a0 = k(0);
        Key a1 = k(0.6).s("yaw", -38).s("pitch", 24).s("roll", -10).s("hx", -18).s("hy", -30)
                .s("rax", 30).s("rao", 12).s("lax", -70).s("lao", -20)
                .s("rlx", 24).s("rlo", 9).s("llx", -22).s("llo", 9);
        Key a2 = k(1.08).s("yaw", 12).s("pitch", 6).s("roll", 4).s("hx", -8).s("hy", 5)
                .s("rax", -70).s("rao", -12).s("lax", -55).s("lao", -10)
                .s("rlx", 10).s("rlo", 5).s("llx", -10).s("llo", 5);
        Key a3 = k(1.25).s("yaw", 32).s("pitch", -18).s("roll", 9).s("hx", -8).s("hy", 20).s("lift", 0.14)
                .s("rax", -150).s("rao", -6).s("lax", -35).s("lao", 12)
                .s("rlx", 6).s("rlo", 3).s("llx", -6).s("llo", 3);
        Key a4 = k(1.65, a3).s("pitch", -12).s("lift", 0.04).s("rax", -156).s("hx", -6);
        Key a5 = k(2.25).s("yaw", 0).s("pitch", 0).s("rax", -40).s("rao", -10);
        a.keys.addAll(List.of(a0, a1, a2, a3, a4, a5, k(2.7)));
        list.add(a);

        // 2. Cross (right straight): load the back hip, step in, full extension at chin height.
        Anim c = new Anim(); c.name = "cross"; c.impact = 1.0;
        Key c1 = k(0.45).s("yaw", -32).s("pitch", 4).s("hy", -32).s("rax", -48).s("rao", -6)
                .s("rlx", 18).s("llx", -14);
        Key c2 = k(1.0).s("yaw", 38).s("pitch", 16).s("roll", -4).s("fwd", 0.22).s("hx", 8).s("hy", 36)
                .s("rax", -95).s("rao", -10).s("lax", -62).s("lao", -26)
                .s("rlx", 26).s("rlo", 4).s("llx", -22).s("llo", 6);
        Key c3 = k(1.28, c2).s("yaw", 34).s("rax", -93);
        Key c4 = k(1.85).s("yaw", -5).s("rax", -55);
        c.keys.addAll(List.of(k(0), c1, c2, c3, c4, k(2.25)));
        list.add(c);

        // 3. Left hook: coil right, arm bent out to the side, then a flat horizontal sweep across the target.
        Anim h = new Anim(); h.name = "hook"; h.impact = 1.05;
        Key h1 = k(0.5).s("yaw", -42).s("roll", -6).s("hy", -42)
                .s("lax", -84).s("lao", 0).s("lay", -58)
                .s("rlx", 16).s("llx", -12);
        Key h2 = k(1.05).s("yaw", 34).s("roll", 6).s("pitch", 12).s("fwd", 0.1).s("hy", 30)
                .s("lax", -88).s("lao", 0).s("lay", 12)
                .s("rax", -66).s("rao", -24).s("rlx", 18).s("llx", -16);
        Key h3 = k(1.35, h2).s("yaw", 52).s("lay", 38).s("hy", 44);
        Key h4 = k(1.9).s("yaw", 5).s("lax", -60);
        h.keys.addAll(List.of(k(0), h1, h2, h3, h4, k(2.35)));
        list.add(h);

        // 4. Spinning back kick (right leg): turn the back to the target, leg drives straight back, finish the turn.
        Anim s = new Anim(); s.name = "spinkick"; s.impact = 1.35;
        Key s1 = k(0.55).s("yaw", -105).s("pitch", 12).s("hy", 40).s("hx", 0)
                .s("rax", -25).s("rao", 55).s("lax", -25).s("lao", 55)
                .s("rlx", 35).s("rlo", 4).s("llx", -4).s("llo", 3);
        Key s2 = k(1.35).s("yaw", -180).s("pitch", 42).s("roll", 6).s("hx", -38).s("hy", 70)
                .s("rax", -10).s("rao", 62).s("lax", -30).s("lao", 50)
                .s("rlx", 96).s("rlo", 2).s("llx", -6).s("llo", 3);
        Key s3 = k(1.65, s2).s("yaw", -192).s("rlx", 92);
        Key s4 = k(2.3).s("yaw", -320).s("pitch", 6).s("hy", -20).s("rlx", 12).s("rax", -50).s("rao", 0).s("lax", -60).s("lao", 0);
        Key s5 = k(2.8).s("yaw", -375).s("hy", -15);
        s.keys.addAll(List.of(k(0), s1, s2, s3, s4, s5));
        list.add(s);

        // 5. Push kick (teep, right leg): chamber the leg high, lean back, drive the sole through the target.
        Anim p = new Anim(); p.name = "pushkick"; p.impact = 1.15;
        Key p1 = k(0.55).s("pitch", -8).s("hx", 6).s("rlx", -100).s("rlo", 4).s("llx", -2).s("llo", 3)
                .s("rax", -70).s("lax", -75);
        Key p2 = k(1.15).s("pitch", -26).s("fwd", 0.15).s("hx", 20).s("rlx", -84).s("rlo", 2).s("llx", 4)
                .s("rax", 18).s("rao", 22).s("lax", -70).s("lao", -12);
        Key p3 = k(1.5, p2).s("pitch", -18).s("rlx", -62);
        Key p4 = k(2.0).s("rlx", 0).s("pitch", 0);
        p.keys.addAll(List.of(k(0), p1, p2, p3, p4, k(2.5)));
        list.add(p);
        return list;
    }

    // ------------------------------------------------------------------ evaluation

    static double[] eval(List<Key> keys, double t) {
        int n = keys.size();
        if (t <= keys.get(0).t) return keys.get(0).v.clone();
        if (t >= keys.get(n - 1).t) return keys.get(n - 1).v.clone();
        int i = 0;
        while (keys.get(i + 1).t < t) i++;
        Key k0 = keys.get(Math.max(0, i - 1)), k1 = keys.get(i), k2 = keys.get(i + 1), k3 = keys.get(Math.min(n - 1, i + 2));
        double u = (t - k1.t) / (k2.t - k1.t);
        double[] r = new double[P.length];
        for (int c = 0; c < P.length; c++) {
            // Catmull-Rom with time-aware tangents, clamped so poses do not overshoot much
            double m1 = (k2.v[c] - k0.v[c]) / Math.max(1e-6, k2.t - k0.t) * (k2.t - k1.t);
            double m2 = (k3.v[c] - k1.v[c]) / Math.max(1e-6, k3.t - k1.t) * (k2.t - k1.t);
            if (k1 == k0) m1 = 0; if (k3 == k2) m2 = 0;
            double u2 = u * u, u3 = u2 * u;
            r[c] = (2 * u3 - 3 * u2 + 1) * k1.v[c] + (u3 - 2 * u2 + u) * m1 + (-2 * u3 + 3 * u2) * k2.v[c] + (u3 - u2) * m2;
        }
        return r;
    }

    static double rad(double d) { return Math.toRadians(d); }

    /** authoring pose -> 22 channel frame (before ground fix) */
    static double[] toFrame(double[] v) {
        double[] f = new double[CH];
        f[0] = v[idx("side")]; f[1] = 0.75; f[2] = -v[idx("fwd")]; // lift is added by groundFix
        double[] q = quat(rad(v[idx("yaw")]), -rad(v[idx("pitch")]), rad(v[idx("roll")]));
        f[3] = q[0]; f[4] = q[1]; f[5] = q[2]; f[6] = q[3];
        f[7] = rad(v[idx("hx")]); f[8] = rad(v[idx("hy")]); f[9] = rad(v[idx("hz")]);
        f[10] = rad(v[idx("rax")]); f[11] = rad(v[idx("rao")]);
        f[12] = rad(v[idx("lax")]); f[13] = -rad(v[idx("lao")]);
        f[14] = rad(v[idx("rlx")]); f[15] = rad(v[idx("rlo")]);
        f[16] = rad(v[idx("llx")]); f[17] = -rad(v[idx("llo")]);
        f[18] = rad(v[idx("ray")]); f[19] = rad(v[idx("lay")]); f[20] = rad(v[idx("rly")]); f[21] = rad(v[idx("lly")]);
        return f;
    }

    /** q = Ry(yaw) * Rx(pitch) * Rz(roll), returns xyzw */
    static double[] quat(double yaw, double pitch, double roll) {
        double[] qy = {0, Math.sin(yaw / 2), 0, Math.cos(yaw / 2)};
        double[] qx = {Math.sin(pitch / 2), 0, 0, Math.cos(pitch / 2)};
        double[] qz = {0, 0, Math.sin(roll / 2), Math.cos(roll / 2)};
        return qmul(qmul(qy, qx), qz);
    }
    static double[] qmul(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }
    static double[] qrot(double[] q, double[] v) {
        double[] p = {v[0], v[1], v[2], 0};
        double[] qc = {-q[0], -q[1], -q[2], q[3]};
        double[] r = qmul(qmul(q, p), qc);
        return new double[]{r[0], r[1], r[2]};
    }

    // ------------------------------------------------------------------ forward kinematics (= Minecraft)

    static final double S = 0.9375;

    /** ModelPart point: pivot (px) + Rz(c) Ry(b) Rx(a) * local (px) */
    static double[] part(double[] pivot, double a, double b, double c, double[] local) {
        double x = local[0], y = local[1], z = local[2];
        double y1 = y * Math.cos(a) - z * Math.sin(a), z1 = y * Math.sin(a) + z * Math.cos(a); y = y1; z = z1;
        double x2 = x * Math.cos(b) + z * Math.sin(b), z2 = -x * Math.sin(b) + z * Math.cos(b); x = x2; z = z2;
        double x3 = x * Math.cos(c) - y * Math.sin(c), y3 = x * Math.sin(c) + y * Math.cos(c); x = x3; y = y3;
        return new double[]{pivot[0] + x, pivot[1] + y, pivot[2] + z};
    }

    /** model px -> entity-local blocks (y up, -z forward, +x right) through the kickmod root transform */
    static double[] toLocal(double[] f, double[] m) {
        double[] v = {-m[0] / 16 * S, -(m[1] / 16 - 1.501) * S, m[2] / 16 * S};
        v[1] -= 0.75 * S;
        v = qrot(new double[]{f[3], f[4], f[5], f[6]}, v);
        return new double[]{v[0] + f[0] * S, v[1] + f[1] * S, v[2] + f[2] * S};
    }

    static final double[] R_ARM = {-5, 2, 0}, L_ARM = {5, 2, 0}, R_LEG = {-1.9, 12, 0}, L_LEG = {1.9, 12, 0};

    /** joints: 0 neck,1 headTop,2 nose,3 rSh,4 rHand,5 lSh,6 lHand,7 rHip,8 rFoot,9 lHip,10 lFoot,11 pelvis */
    static double[][] joints(double[] f) {
        double[][] m = new double[12][];
        m[0] = new double[]{0, 0, 0};
        m[1] = part(new double[]{0, 0, 0}, f[7], f[8], f[9], new double[]{0, -8, 0});
        m[2] = part(new double[]{0, 0, 0}, f[7], f[8], f[9], new double[]{0, -4, -6});
        m[3] = R_ARM; m[4] = part(R_ARM, f[10], f[18], f[11], new double[]{-1, 10, 0});
        m[5] = L_ARM; m[6] = part(L_ARM, f[12], f[19], f[13], new double[]{1, 10, 0});
        m[7] = R_LEG; m[8] = part(R_LEG, f[14], f[20], f[15], new double[]{0, 12, 0});
        m[9] = L_LEG; m[10] = part(L_LEG, f[16], f[21], f[17], new double[]{0, 12, 0});
        m[11] = new double[]{0, 12, 0};
        double[][] r = new double[12][];
        for (int i = 0; i < 12; i++) r[i] = toLocal(f, m[i]);
        return r;
    }

    static void groundFix(double[] f, double lift) {
        double[][] j = joints(f);
        double minY = Math.min(j[8][1], j[10][1]);
        // feet of a straight leg end 0..2px above the sole; keep the lowest foot on the ground, then add lift
        f[1] += (-minY) / S + lift;
    }

    // ------------------------------------------------------------------ io

    static void write(Path file, List<double[]> frames, int impact, String leg) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println("KICKANIM 2");
            w.println(FPS + " " + frames.size() + " " + impact + " " + leg);
            for (double[] f : frames) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < f.length; i++) { if (i > 0) sb.append(' '); sb.append(String.format(Locale.ROOT, "%.4f", f[i])); }
                w.println(sb);
            }
        }
    }

    static List<double[]> read(Path file, int[] impactOut) throws IOException {
        List<String> lines = Files.readAllLines(file);
        String[] h = lines.get(1).trim().split("\\s+");
        impactOut[0] = Integer.parseInt(h[2]);
        List<double[]> out = new ArrayList<>();
        for (int i = 2; i < lines.size(); i++) {
            String l = lines.get(i).trim(); if (l.isEmpty()) continue;
            String[] t = l.split("\\s+"); double[] f = new double[CH];
            for (int c = 0; c < Math.min(CH, t.length); c++) f[c] = Double.parseDouble(t[c]);
            out.add(f);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("gen")) {
            Path dir = Paths.get(args[1]);
            for (Anim a : anims()) {
                double end = a.keys.get(a.keys.size() - 1).t;
                int n = (int) Math.round(end * FPS) + 1;
                List<double[]> frames = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    double[] v = eval(a.keys, i / (double) FPS);
                    double[] f = toFrame(v);
                    groundFix(f, Math.max(0, v[idx("lift")]));
                    frames.add(f);
                }
                int impact = (int) Math.round(a.impact * FPS);
                write(dir.resolve(a.name + ".anim"), frames, impact, "-");
                System.out.printf("%s: %d frames (%.2f s), impact frame %d%n", a.name, n, end, impact);
            }
        } else if (args[0].equals("prev")) {
            int[] imp = new int[1];
            List<double[]> frames = read(Paths.get(args[1]), imp);
            int every = args.length > 3 ? Integer.parseInt(args[3]) : 4;
            preview(frames, imp[0], every, Paths.get(args[2]));
        }
    }

    // ------------------------------------------------------------------ preview

    static void preview(List<double[]> frames, int impact, int every, Path out) throws IOException {
        List<Integer> pick = new ArrayList<>();
        for (int i = 0; i < frames.size(); i += every) pick.add(i);
        if (!pick.contains(impact)) { pick.add(impact); Collections.sort(pick); }
        int cw = 150, chh = 170, cols = Math.min(8, pick.size()), rows = (pick.size() + cols - 1) / cols;
        BufferedImage img = new BufferedImage(cols * cw, rows * chh * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int k = 0; k < pick.size(); k++) {
            int fi = pick.get(k);
            double[][] j = joints(frames.get(fi));
            for (int view = 0; view < 2; view++) {
                int ox = (k % cols) * cw, oy = (k / cols) * chh * 2 + view * chh;
                g.setColor(fi == impact ? new Color(255, 235, 235) : new Color(245, 245, 245));
                g.fillRect(ox + 1, oy + 1, cw - 2, chh - 2);
                double sc = 70; int gx = ox + cw / 2, gy = oy + chh - 20;
                g.setColor(Color.GRAY); g.drawLine(ox, gy, ox + cw, gy);
                // side view: forward (-z) to the right; front view: character's right on the viewer's left
                java.util.function.Function<double[], Point> pr = view == 0
                        ? p -> new Point((int) (gx - p[2] * sc), (int) (gy - p[1] * sc))
                        : p -> new Point((int) (gx - p[0] * sc), (int) (gy - p[1] * sc));
                g.setStroke(new BasicStroke(3));
                line(g, pr, j[0], j[11], Color.BLACK);
                line(g, pr, j[3], j[5], Color.BLACK);
                line(g, pr, j[7], j[9], Color.BLACK);
                line(g, pr, j[0], j[1], new Color(0, 150, 0));
                line(g, pr, j[0], j[2], new Color(0, 220, 0));
                line(g, pr, j[3], j[4], Color.RED);
                line(g, pr, j[7], j[8], new Color(160, 0, 0));
                line(g, pr, j[5], j[6], Color.BLUE);
                line(g, pr, j[9], j[10], new Color(0, 0, 150));
                g.setColor(Color.BLACK);
                g.drawString((view == 0 ? "side " : "front ") + fi + (fi == impact ? " HIT" : ""), ox + 4, oy + 14);
            }
        }
        ImageIO.write(img, "png", out.toFile());
    }

    static void line(Graphics2D g, java.util.function.Function<double[], Point> pr, double[] a, double[] b, Color c) {
        Point p = pr.apply(a), q = pr.apply(b); g.setColor(c); g.drawLine(p.x, p.y, q.x, q.y);
    }
}
