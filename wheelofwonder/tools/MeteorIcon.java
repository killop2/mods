import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Item icon of Chaos Meteor (32x32): a craggy dark rock with glowing lava cracks in the lower right, a fiery tail
 * streaming up to the top left. Run: java tools/MeteorIcon.java <out.png>
 */
public class MeteorIcon {
    static final int S = 32;

    public static void main(String[] a) throws Exception {
        int[] px = new int[S * S];
        double cx = 20.5, cy = 20.5, r = 8.2;
        // the tail: a cone of fire from the rock towards the top left, hot near the rock
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
            double along = -(dx + dy) / Math.sqrt(2), across = (dx - dy) / Math.sqrt(2);
            if (along > 0 && along < 26) {
                double w = r * (1 - along / 30.0);
                double f = 1 - Math.abs(across) / Math.max(0.5, w);
                double wave = 0.15 * Math.sin(along * 0.9 + across * 0.6);
                if (f + wave > 0) {
                    double k = Math.min(1, (f + wave)) * (1 - along / 26.0);
                    int al = (int) (255 * Math.min(1, k * 1.6));
                    int rr = 255, gg = (int) (60 + 170 * k * k), bb = (int) (20 + 80 * k * k * k);
                    px[y * S + x] = blend(px[y * S + x], argb(al, rr, gg, bb));
                }
            }
        }
        // the rock: lumpy outline, dark stone, lit top left, cracks glowing
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
            double ang = Math.atan2(dy, dx);
            double rr = r * (1 + 0.1 * Math.sin(ang * 5 + 1) + 0.06 * Math.sin(ang * 9));
            double d = Math.hypot(dx, dy);
            if (d > rr) continue;
            double light = Math.max(0, (-dx - dy) / (r * 1.4)) * 0.6 + 0.4;
            double crack = Math.abs(Math.sin(dx * 0.9 + Math.sin(dy * 0.7) * 2.0)) < 0.18 || Math.abs(Math.sin(dy * 1.1 - dx * 0.4)) < 0.12 ? 1 : 0;
            int col;
            if (crack > 0 && d < rr - 1.0) col = argb(255, 255, 120 + (int) (60 * (1 - d / rr)), 30);
            else col = argb(255, (int) (70 * light), (int) (56 * light), (int) (62 * light));
            if (d > rr - 1.2 && (dx + dy) < 0) col = argb(255, 255, 150, 60);   // rim lit by the fire
            px[y * S + x] = col;
        }
        // outline
        int[] out = px.clone();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            if ((px[y * S + x] >>> 24) == 255) continue;
            for (int k = 0; k < 4; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx >= 0 && ny >= 0 && nx < S && ny < S && (px[ny * S + nx] >>> 24) == 255 && ((px[ny * S + nx] >> 16) & 255) < 120) {
                    out[y * S + x] = blend(px[y * S + x], argb(220, 20, 10, 12));
                    break;
                }
            }
        }
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, S, S, out, 0, S);
        ImageIO.write(img, "png", new File(a[0]));
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
