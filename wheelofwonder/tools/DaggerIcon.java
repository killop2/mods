import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Item icon of Impalement Arts (32x32): a Dark Carnival kris dagger on the diagonal, tip up-right: a wavy
 * black-crimson blade with red-hot edges, gold crossguard with a violet gem, striped grip, and a faint hellfire glow.
 * Run: java tools/DaggerIcon.java <out.png>
 */
public class DaggerIcon {
    static final int S = 32;
    static int[] px = new int[S * S];

    public static void main(String[] a) throws Exception {
        // diagonal axis from the pommel (bottom-left) to the tip (top-right)
        double ax = 4.5, ay = 27.5, bx = 28.5, by = 3.5;
        double len = Math.hypot(bx - ax, by - ay);
        double ux = (bx - ax) / len, uy = (by - ay) / len;   // along
        double vx = -uy, vy = ux;                            // across
        double guardT = 0.33;                                // fraction of the length where the guard sits
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                double px0 = x + 0.5 - ax, py0 = y + 0.5 - ay;
                double t = (px0 * ux + py0 * uy) / len;       // 0 pommel .. 1 tip
                double d = px0 * vx + py0 * vy;               // signed distance across
                int col = 0;
                // soft hellfire glow around the blade
                if (t > guardT - 0.02 && t < 1.04) {
                    double s = (t - guardT) / (1 - guardT);
                    double w = halfWidth(s) + 2.6;
                    double g = 1.0 - Math.abs(d - wave(s)) / w;
                    if (g > 0) col = blendOver(col, argb((int) (g * g * 120), 255, 60 + (int) (60 * s), 40));
                }
                if (t >= guardT && t <= 1.0) {
                    double s = (t - guardT) / (1 - guardT);
                    double w = halfWidth(s);
                    double dd = d - wave(s);
                    if (Math.abs(dd) <= w) {
                        double e = Math.abs(dd) / Math.max(0.6, w);            // 0 ridge .. 1 edge
                        int r = (int) (42 + e * e * 213), gg = (int) (12 + e * e * (40 + 140 * s * s)), b = (int) (20 + e * e * 30);
                        if (dd < -w * 0.15 && e < 0.55) { r += 18; gg += 8; b += 14; }   // light side of the ridge
                        col = argb(255, r, gg, b);
                    }
                } else if (t >= guardT - 0.055 && t < guardT) {
                    // crossguard with horns
                    if (Math.abs(d) <= 5.2) col = Math.abs(d) < 1.3 ? argb(255, 192, 75, 255) : (Math.abs(d) > 4.0 ? argb(255, 140, 92, 22) : argb(255, 222, 168, 52));
                } else if (t >= 0.07 && t < guardT - 0.055) {
                    if (Math.abs(d) <= 1.35) {
                        int band = (int) Math.floor(t * len / 1.8) & 1;
                        col = band == 0 ? argb(255, 30, 16, 26) : argb(255, 160, 24, 38);
                    }
                } else if (t >= 0.0 && t < 0.07) {
                    double rr = Math.hypot(d, (t - 0.035) * len);
                    if (rr <= 2.0) col = rr < 0.9 ? argb(255, 192, 75, 255) : argb(255, 222, 168, 52);
                }
                // horns of the guard bending towards the blade
                double hornT = guardT + 0.03;
                if (Math.abs(t - hornT) < 0.03 && Math.abs(Math.abs(d) - 4.6) < 0.8) col = argb(255, 222, 168, 52);
                px[y * S + x] = col;
            }
        }
        outline();
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, S, S, px, 0, S);
        ImageIO.write(img, "png", new File(a[0]));
    }

    static double halfWidth(double s) {
        double belly = 1.7 + 0.8 * Math.sin(Math.PI * Math.min(1.0, s * 1.6));
        return belly * (1.0 - s * s * 0.97);
    }

    static double wave(double s) {
        return 0.55 * Math.sin(s * Math.PI * 3.0) * (1.0 - s);
    }

    /** Dark outline around the opaque shape (over the glow), like vanilla item sprites. */
    static void outline() {
        int[] out = px.clone();
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                int c = px[y * S + x];
                if ((c >>> 24) == 255) continue;
                boolean near = false;
                for (int k = 0; k < 4 && !near; k++) {
                    int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (nx >= 0 && ny >= 0 && nx < S && ny < S && (px[ny * S + nx] >>> 24) == 255) near = true;
                }
                if (near) out[y * S + x] = blendOver(c, argb(235, 18, 6, 12));
            }
        }
        px = out;
    }

    static int argb(int a, int r, int g, int b) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    static int blendOver(int dst, int src) {
        double sa = (src >>> 24) / 255.0, da = (dst >>> 24) / 255.0;
        double oa = sa + da * (1 - sa);
        if (oa <= 0) return 0;
        int r = (int) ((((src >> 16) & 255) * sa + ((dst >> 16) & 255) * da * (1 - sa)) / oa);
        int g = (int) ((((src >> 8) & 255) * sa + ((dst >> 8) & 255) * da * (1 - sa)) / oa);
        int b = (int) (((src & 255) * sa + (dst & 255) * da * (1 - sa)) / oa);
        return argb((int) (oa * 255), r, g, b);
    }
}
