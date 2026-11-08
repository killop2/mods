import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Item icon of the Telekinesis Orb (32x32): a glowing violet sphere with a cyan swirl inside, a bright highlight,
 * a soft aura round it, held in a small gold claw. Run: java tools/OrbIcon.java <out.png>
 */
public class OrbIcon {
    static final int S = 32;

    public static void main(String[] a) throws Exception {
        double[] R = new double[S * S], G = new double[S * S], B = new double[S * S], A = new double[S * S];
        double cx = 16, cy = 14, r = 9.5;
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            int i = x + y * S;
            double dx = x + 0.5 - cx, dy = y + 0.5 - cy, d = Math.hypot(dx, dy);
            if (d > r) {
                // aura
                double k = Math.max(0, 1 - (d - r) / 4.5);
                if (k > 0) over(R, G, B, A, i, 0.75, 0.45, 1.0, 0.55 * k * k);
                continue;
            }
            double f = d / r;
            // body: deep violet at the rim, magenta, a pale core
            double cr = lerp(0.95, 0.35, f), cg = lerp(0.8, 0.12, f), cb = lerp(1.0, 0.7, f);
            // the swirl: a spiral band of cyan
            double ang = Math.atan2(dy, dx);
            double sw = Math.sin(ang * 2 + f * 7.5);
            if (sw > 0.55 && f > 0.2 && f < 0.92) {
                double k = (sw - 0.55) / 0.45;
                cr = lerp(cr, 0.45, k);
                cg = lerp(cg, 0.9, k);
                cb = lerp(cb, 1.0, k);
            }
            // rim darker
            if (f > 0.88) { cr *= 0.7; cg *= 0.7; cb *= 0.85; }
            // highlight top left
            double hl = Math.hypot(dx + 3.2, dy + 3.4);
            if (hl < 2.0) { cr = 1; cg = 1; cb = 1; }
            over(R, G, B, A, i, cr, cg, cb, 1.0);
        }
        // gold claw: a cup under the orb and three prongs
        int gold = 0xE8B33A, goldDark = 0xA87418;
        for (int x = 11; x <= 21; x++) set(R, G, B, A, x, 24, gold);
        for (int x = 12; x <= 20; x++) set(R, G, B, A, x, 25, goldDark);
        for (int y = 26; y <= 29; y++) { set(R, G, B, A, 15, y, goldDark); set(R, G, B, A, 16, y, gold); }
        for (int x = 12; x <= 20; x++) set(R, G, B, A, x, 30, gold);
        int[][] prongs = {{9, 18}, {10, 21}, {11, 23}, {23, 18}, {22, 21}, {21, 23}};
        for (int[] p : prongs) set(R, G, B, A, p[0], p[1], gold);
        int[] px = new int[S * S];
        for (int i = 0; i < S * S; i++) {
            double al = Math.min(1, A[i]);
            if (al <= 0) continue;
            px[i] = ((int) Math.round(al * 255) << 24) | ((int) Math.round(Math.min(1, R[i] / A[i]) * 255) << 16)
                    | ((int) Math.round(Math.min(1, G[i] / A[i]) * 255) << 8) | (int) Math.round(Math.min(1, B[i] / A[i]) * 255);
        }
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, S, S, px, 0, S);
        ImageIO.write(img, "png", new File(a[0]));
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static void over(double[] R, double[] G, double[] B, double[] A, int i, double r, double g, double b, double a) {
        R[i] = r * a + R[i] * (1 - a);
        G[i] = g * a + G[i] * (1 - a);
        B[i] = b * a + B[i] * (1 - a);
        A[i] = a + A[i] * (1 - a);
    }

    static void set(double[] R, double[] G, double[] B, double[] A, int x, int y, int rgb) {
        int i = x + y * S;
        R[i] = ((rgb >> 16) & 255) / 255.0;
        G[i] = ((rgb >> 8) & 255) / 255.0;
        B[i] = (rgb & 255) / 255.0;
        A[i] = 1.0;
    }
}
