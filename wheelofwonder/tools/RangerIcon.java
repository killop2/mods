import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Item icon of the Ranger (32x32): a red tower on a navy base, the swing arm across it with a caged gondola at the
 * lower left and a striped mallet at the upper right, light bulbs along the arm, a gold disc on the pivot.
 * Run: java tools/RangerIcon.java <out.png>
 */
public class RangerIcon {
    static final int S = 32;
    static int[] px = new int[S * S];

    public static void main(String[] a) throws Exception {
        int navy = 0xFF241B4A, red = 0xFFB01A2A, redDark = 0xFF7A1020, gold = 0xFFE8B33A, goldDark = 0xFFA87418, cage = 0xFF3A3150;
        // base and tower
        rect(9, 28, 23, 30, navy);
        rect(9, 28, 23, 28, goldDark);
        rect(14, 9, 18, 28, red);
        rect(14, 9, 14, 28, redDark);
        for (int y = 12; y < 27; y += 3) { set(15, y + 1, gold); set(16, y, gold); set(17, y + 1, gold); }
        // the arm: from the gondola (lower left) through the pivot (16,12) to the mallet (upper right)
        line(6, 23, 26, 4, 3, red);
        line(6, 23, 26, 4, 1, redDark);
        // bulbs along the arm
        for (int i = 0; i < 6; i++) {
            int x = 8 + i * 3, y = 21 - (int) Math.round(i * 2.85);
            set(x, y - 1, i % 2 == 0 ? 0xFFFFD93B : 0xFFC84BFF);
        }
        // pivot disc
        disc(16, 12, 2.6, gold);
        disc(16, 12, 1.2, redDark);
        // mallet: a striped head across the arm end
        for (int i = -4; i <= 4; i++) {
            int col = Math.abs(i) >= 3 ? gold : (i % 2 == 0 ? red : 0xFFF2E6D0);
            line(26 + i, 4 + i, 26 + i, 4 + i, 3, col);
        }
        // the gondola: a cage with a red floor and roof
        rect(2, 21, 10, 22, red);
        rect(2, 28, 10, 29, red);
        for (int x = 2; x <= 10; x += 2) rect(x, 23, x, 27, cage);
        rect(2, 23, 2, 27, gold);
        rect(10, 23, 10, 27, gold);
        set(5, 25, 0xFFFF8A1F);
        set(6, 25, 0xFFFF8A1F);
        // dark outline
        int[] out = px.clone();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            if ((px[y * S + x] >>> 24) != 0) continue;
            for (int k = 0; k < 4; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx >= 0 && ny >= 0 && nx < S && ny < S && (px[ny * S + nx] >>> 24) != 0) {
                    out[y * S + x] = 0xFF120A1C;
                    break;
                }
            }
        }
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, S, S, out, 0, S);
        ImageIO.write(img, "png", new File(a[0]));
    }

    static void set(int x, int y, int c) {
        if (x >= 0 && y >= 0 && x < S && y < S) px[y * S + x] = c;
    }

    static void rect(int x0, int y0, int x1, int y1, int c) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) set(x, y, c);
    }

    static void disc(int cx, int cy, double r, int c) {
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) if (Math.hypot(x - cx, y - cy) <= r) set(x, y, c);
    }

    /** A thick line: every pixel within w/2 of the segment. */
    static void line(int x0, int y0, int x1, int y1, int w, int c) {
        double dx = x1 - x0, dy = y1 - y0, len2 = Math.max(1e-9, dx * dx + dy * dy);
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double t = Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / len2));
            double d = Math.hypot(x - (x0 + t * dx), y - (y0 + t * dy));
            if (d <= w / 2.0) set(x, y, c);
        }
    }
}
