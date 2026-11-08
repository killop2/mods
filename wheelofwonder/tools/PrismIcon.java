import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

/**
 * Prism Splitter icon (32x32): a cart of violet and pink mana crystals with a gold rim on a neon rail that curves
 * up and ends in the air, a soft violet glow behind. Also writes the item model.
 * Run: java tools/PrismIcon.java src/main/resources/assets/wheelofwonder
 */
public class PrismIcon {
    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        int S = 32;
        double[] R = new double[S * S], G = new double[S * S], B = new double[S * S], A = new double[S * S];
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double d = Math.hypot(x + 0.5 - 15, y + 0.5 - 17);
            double k = Math.max(0, 1 - d / 15.0);
            if (k > 0) over(R, G, B, A, x + y * S, 0.6, 0.3, 1.0, 0.45 * k * k);
        }
        // the rail: flat, then curving up to the upper right and ending there
        for (int x = 1; x <= 29; x++) {
            double y = x < 14 ? 26 : 26 - Math.pow((x - 14) / 15.0, 1.6) * 20;
            put(R, G, B, A, S, x, (int) Math.round(y), 0.85, 0.45, 1.0);
            put(R, G, B, A, S, x, (int) Math.round(y) + 1, 0.45, 0.22, 0.7);
            if (x % 3 == 0) put(R, G, B, A, S, x, (int) Math.round(y) + 2, 0.95, 0.68, 0.25);
        }
        // the cart: crystal walls (alternating violet / pink / cyan), gold rim top and bottom
        for (int x = 4; x <= 15; x++) {
            for (int y = 18; y <= 24; y++) {
                double t = (x + y) % 3;
                double r = t == 0 ? 0.62 : t == 1 ? 1.0 : 0.4, g = t == 0 ? 0.32 : t == 1 ? 0.4 : 0.85, b = t == 0 ? 1.0 : t == 1 ? 0.85 : 1.0;
                double hi = (24 - y) / 10.0;
                put(R, G, B, A, S, x, y, Math.min(1, r + hi), Math.min(1, g + hi), Math.min(1, b + hi * 0.5));
            }
            put(R, G, B, A, S, x, 17, 0.95, 0.7, 0.25);
            put(R, G, B, A, S, x, 25, 0.95, 0.7, 0.25);
        }
        // crystals sticking up at the corners and in the middle
        int[][] tips = {{4, 13, 17}, {9, 11, 17}, {15, 13, 17}};
        for (int[] t : tips) for (int y = t[1]; y < t[2]; y++) {
            int w = (y - t[1]) / 2;
            for (int x = t[0] - w; x <= t[0] + w; x++) put(R, G, B, A, S, x, y, 0.8 + 0.2 * ((x + y) % 2), 0.5, 1.0);
        }
        // sparkles where the rail ends
        for (int[] s : new int[][]{{29, 5}, {30, 3}, {27, 3}, {31, 6}, {28, 1}}) put(R, G, B, A, S, s[0], s[1], 1.0, 0.85, 1.0);
        int[] px = new int[S * S];
        for (int i = 0; i < S * S; i++) {
            double al = Math.min(1, A[i]);
            if (al <= 0) continue;
            px[i] = ((int) Math.round(al * 255) << 24) | ((int) Math.round(Math.min(1, R[i] / A[i]) * 255) << 16)
                    | ((int) Math.round(Math.min(1, G[i] / A[i]) * 255) << 8) | (int) Math.round(Math.min(1, B[i] / A[i]) * 255);
        }
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, S, S, px, 0, S);
        ImageIO.write(img, "png", new File(root, "textures/item/prism_splitter.png"));
        Files.writeString(new File(root, "models/item/prism_splitter.json").toPath(), """
                {
                  "parent": "minecraft:item/generated",
                  "textures": {
                    "layer0": "wheelofwonder:item/prism_splitter"
                  }
                }
                """);
    }

    static void put(double[] R, double[] G, double[] B, double[] A, int S, int x, int y, double r, double g, double b) {
        if (x >= 0 && y >= 0 && x < S && y < S) over(R, G, B, A, x + y * S, r, g, b, 1.0);
    }

    static void over(double[] R, double[] G, double[] B, double[] A, int i, double r, double g, double b, double a) {
        R[i] = r * a + R[i] * (1 - a);
        G[i] = g * a + G[i] * (1 - a);
        B[i] = b * a + B[i] * (1 - a);
        A[i] = a + A[i] * (1 - a);
    }
}
