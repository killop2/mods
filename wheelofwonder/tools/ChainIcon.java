import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Item icon of Sinister Chain (32x32): five links of dark iron on the diagonal, every other one seen edge-on,
 * their ends glowing with heat, a shackle with a violet rune at the top, and a soft hellfire glow behind.
 * Run: java tools/ChainIcon.java <out.png>
 */
public class ChainIcon {
    static final int S = 32;
    static int[] px = new int[S * S];

    public static void main(String[] a) throws Exception {
        double ax = 5, ay = 27, bx = 26, by = 6;
        double len = Math.hypot(bx - ax, by - ay), ux = (bx - ax) / len, uy = (by - ay) / len, vx = -uy, vy = ux;
        // glow
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double px0 = x + 0.5 - ax, py0 = y + 0.5 - ay;
            double t = (px0 * ux + py0 * uy) / len, d = Math.abs(px0 * vx + py0 * vy);
            if (t > -0.05 && t < 1.05) {
                double g = Math.max(0, 1 - d / 5.5);
                if (g > 0) px[y * S + x] = blend(px[y * S + x], argb((int) (g * g * 150), 255, 110, 30));
            }
        }
        int links = 5;
        for (int i = 0; i < links; i++) {
            double t0 = (i + 0.5) / links;
            double cx = ax + (bx - ax) * t0, cy = ay + (by - ay) * t0;
            boolean flat = (i & 1) == 0;
            double rl = 3.4, rw = flat ? 2.3 : 0.9;   // half length along the chain, half width across
            for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
                double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
                double l = dx * ux + dy * uy, w = dx * vx + dy * vy;
                double e = Math.pow(l / rl, 2) + Math.pow(w / rw, 2);
                double inner = flat ? Math.pow(l / (rl - 1.3), 2) + Math.pow(w / (rw - 1.2), 2) : 2;
                if (e <= 1.0 && inner > 1.0) {
                    double end = Math.abs(l) / rl;                      // the ends glow with heat
                    int r = (int) (52 + 200 * Math.pow(end, 3)), g = (int) (38 + 90 * Math.pow(end, 4)), b = (int) (46 + 10 * end);
                    if (w < 0 && end < 0.7) { r += 22; g += 18; b += 22; }   // lit edge
                    px[y * S + x] = argb(255, r, g, b);
                }
            }
        }
        // shackle at the top end with a rune
        double sx = bx + ux * 1.5, sy = by + uy * 1.5;
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double r = Math.hypot(x + 0.5 - sx, y + 0.5 - sy);
            if (r <= 3.6 && r >= 2.1) px[y * S + x] = Math.abs(Math.atan2(y + 0.5 - sy, x + 0.5 - sx)) < 0.5 ? argb(255, 200, 90, 255) : argb(255, 70, 52, 62);
        }
        // flames licking up from the links
        int[][] flames = {{9, 19}, {15, 14}, {20, 10}, {12, 22}};
        for (int[] f : flames) {
            for (int k = 0; k < 4; k++) {
                int x = f[0] + (k == 2 ? 1 : 0), y = f[1] - k;
                if (x >= 0 && y >= 0 && x < S && y < S && (px[y * S + x] >>> 24) < 255) px[y * S + x] = argb(255, 255, k < 2 ? 140 : 210, k < 2 ? 30 : 90);
            }
        }
        outline();
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, S, S, px, 0, S);
        ImageIO.write(img, "png", new File(a[0]));
    }

    static void outline() {
        int[] out = px.clone();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            if ((px[y * S + x] >>> 24) == 255) continue;
            boolean near = false;
            for (int k = 0; k < 4 && !near; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx >= 0 && ny >= 0 && nx < S && ny < S && (px[ny * S + nx] >>> 24) == 255) near = true;
            }
            if (near) out[y * S + x] = blend(px[y * S + x], argb(230, 20, 10, 16));
        }
        px = out;
    }

    static int argb(int a, int r, int g, int b) {
        return (cl(a) << 24) | (cl(r) << 16) | (cl(g) << 8) | cl(b);
    }

    static int cl(int v) { return Math.max(0, Math.min(255, v)); }

    static int blend(int dst, int src) {
        double sa = (src >>> 24) / 255.0, da = (dst >>> 24) / 255.0, oa = sa + da * (1 - sa);
        if (oa <= 0) return 0;
        int r = (int) ((((src >> 16) & 255) * sa + ((dst >> 16) & 255) * da * (1 - sa)) / oa);
        int g = (int) ((((src >> 8) & 255) * sa + ((dst >> 8) & 255) * da * (1 - sa)) / oa);
        int b = (int) (((src & 255) * sa + (dst & 255) * da * (1 - sa)) / oa);
        return argb((int) (oa * 255), r, g, b);
    }
}
