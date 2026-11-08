import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Trident ("Тризуб!") art: the 3D item model (block-model elements) - a shaft of sea-green bronze wrapped in
 * seaweed, a bone skull with ghost-green eyes under the crossbar, three bone prongs with glowing ghost-fire tips -
 * its texture, and the 32x32 inventory icon (the trident on a slant in a ghostly green mist).
 * Run: java tools/TrizubArt.java src/main/resources/assets/wheelofwonder
 */
public class TrizubArt {
    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        texture(new File(root, "textures/item/trizub_3d.png"));
        icon(new File(root, "textures/item/trizub.png"));
        Files.writeString(new File(root, "models/item/trizub_3d.json").toPath(), model());
        Files.writeString(new File(root, "models/item/trizub.json").toPath(), """
                {
                  "loader": "neoforge:separate_transforms",
                  "base": { "parent": "wheelofwonder:item/trizub_3d" },
                  "perspectives": {
                    "gui": { "parent": "minecraft:item/generated", "textures": { "layer0": "wheelofwonder:item/trizub" } },
                    "fixed": { "parent": "minecraft:item/generated", "textures": { "layer0": "wheelofwonder:item/trizub" } }
                  }
                }
                """);
    }

    static final int BRONZE = 0, BRONZE_DARK = 1, WEED = 2, BONE = 3, BONE_DARK = 4, GHOST = 5, EYE = 6, IRON = 7;

    static void texture(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(23);
        for (int s = 0; s < 8; s++) {
            int ox = (s % 4) * 8, oy = (s / 4) * 8;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                double n = r.nextDouble();
                int c = switch (s) {
                    case BRONZE -> n < 0.25 ? mix(0x3E8C74, 0x58A88A, n * 4) : mix(0x5A4A2A, 0x7A6436, n);   // verdigris spots
                    case BRONZE_DARK -> mix(0x2E2A1E, 0x46402C, n);
                    case WEED -> ((x + y * 2) % 5 < 2) ? mix(0x1E4A2A, 0x2E6A38, n) : mix(0x16341E, 0x1E4A2A, n);
                    case BONE -> mix(0xB8B8A0, 0xDCDCC6, (7 - y) / 7.0 * 0.6 + n * 0.3);
                    case BONE_DARK -> mix(0x8A8A74, 0xA8A890, n);
                    case GHOST -> mix(0x40FFB8, 0xD0FFE8, (7 - y) / 7.0 * 0.7 + n * 0.2);
                    case EYE -> Math.hypot(x - 3.5, y - 3.5) < 2.0 ? 0xE0FFF0 : 0x40FFB8;
                    default -> mix(0x1E1E22, 0x34343A, n);
                };
                img.setRGB(ox + x, oy + y, 0xFF000000 | c);
            }
        }
        ImageIO.write(img, "png", out);
    }

    record El(double x0, double y0, double z0, double x1, double y1, double z1, int sw, boolean glow) {
    }

    static String model() {
        List<El> e = new ArrayList<>();
        e.add(new El(7.1, -12, 7.1, 8.9, -10, 8.9, IRON, false));
        e.add(new El(7.3, -10, 7.3, 8.7, 17.5, 8.7, BRONZE, false));
        for (double y : new double[]{-4, 2, 8}) e.add(new El(6.95, y, 6.95, 9.05, y + 1.6, 9.05, WEED, false));
        // the skull under the crossbar, ghost eyes
        e.add(new El(6.7, 17.0, 6.7, 9.3, 19.6, 9.3, BONE, false));
        e.add(new El(7.0, 16.4, 7.0, 9.0, 17.0, 9.0, BONE_DARK, false));
        e.add(new El(7.1, 18.2, 9.3, 7.8, 18.9, 9.5, EYE, true));
        e.add(new El(8.2, 18.2, 9.3, 8.9, 18.9, 9.5, EYE, true));
        // crossbar and the three prongs with glowing tips
        e.add(new El(3.8, 19.6, 7.2, 12.2, 21.0, 8.8, BRONZE_DARK, false));
        e.add(new El(7.25, 21.0, 7.4, 8.75, 29.0, 8.6, BONE, false));
        e.add(new El(7.45, 29.0, 7.6, 8.55, 31.8, 8.4, GHOST, true));
        e.add(new El(3.9, 21.0, 7.4, 5.3, 27.0, 8.6, BONE, false));
        e.add(new El(4.1, 27.0, 7.6, 5.1, 29.4, 8.4, GHOST, true));
        e.add(new El(10.7, 21.0, 7.4, 12.1, 27.0, 8.6, BONE, false));
        e.add(new El(10.9, 27.0, 7.6, 11.9, 29.4, 8.4, GHOST, true));
        // barbs
        e.add(new El(5.3, 26.0, 7.6, 6.2, 26.8, 8.4, BONE_DARK, false));
        e.add(new El(9.8, 26.0, 7.6, 10.7, 26.8, 8.4, BONE_DARK, false));
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"textures\": { \"particle\": \"wheelofwonder:item/trizub_3d\", \"t\": \"wheelofwonder:item/trizub_3d\" },\n");
        sb.append("  \"elements\": [\n");
        String[] fs = {"north", "south", "east", "west", "up", "down"};
        for (int i = 0; i < e.size(); i++) {
            El el = e.get(i);
            double u0 = (el.sw % 4) * 4, v0 = (el.sw / 4) * 4;
            String uv = String.format("[%s, %s, %s, %s]", f(u0 + 0.5), f(v0 + 0.5), f(u0 + 3.5), f(v0 + 3.5));
            String extra = el.glow ? ", \"neoforge_data\": { \"block_light\": 15, \"sky_light\": 15 }" : "";
            sb.append("    { \"from\": [").append(f(el.x0)).append(", ").append(f(el.y0)).append(", ").append(f(el.z0)).append("], ");
            sb.append("\"to\": [").append(f(el.x1)).append(", ").append(f(el.y1)).append(", ").append(f(el.z1)).append("],");
            if (el.glow) sb.append(" \"shade\": false,");
            sb.append("\n      \"faces\": {");
            for (int k = 0; k < fs.length; k++) {
                sb.append(" \"").append(fs[k]).append("\": { \"uv\": ").append(uv).append(", \"texture\": \"#t\"").append(extra).append(" }");
                if (k < fs.length - 1) sb.append(",");
            }
            sb.append(" } }").append(i < e.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");
        sb.append("""
                  "display": {
                    "thirdperson_righthand": { "rotation": [0, -90, 10], "translation": [0, 4, 0.5], "scale": [0.8, 0.8, 0.8] },
                    "thirdperson_lefthand": { "rotation": [0, 90, -100], "translation": [0, 4, 0.5], "scale": [0.8, 0.8, 0.8] },
                    "firstperson_righthand": { "rotation": [0, -90, -20], "translation": [1.13, 3.2, 1.13], "scale": [0.6, 0.6, 0.6] },
                    "firstperson_lefthand": { "rotation": [0, 90, -70], "translation": [1.13, 3.2, 1.13], "scale": [0.6, 0.6, 0.6] },
                    "ground": { "rotation": [0, 0, 90], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4] },
                    "head": { "rotation": [0, 0, 0], "translation": [0, 8, 0], "scale": [0.6, 0.6, 0.6] }
                  }
                }
                """);
        return sb.toString();
    }

    static String f(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 1000) / 1000.0);
    }

    // ------------------------------------------------------------------ icon

    static void icon(File out) throws Exception {
        int S = 32;
        double[] R = new double[S * S], G = new double[S * S], B = new double[S * S], A = new double[S * S];
        // ghost mist round the head (upper right)
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double d = Math.hypot(x + 0.5 - 22.5, y + 0.5 - 9.5);
            double k = Math.max(0, 1 - d / 11.0);
            if (k > 0) over(R, G, B, A, x + y * S, 0.25, 1.0, 0.72, 0.5 * k * k);
        }
        // shaft from the lower left (bronze with seaweed wraps)
        line(R, G, B, A, S, 4, 28, 17, 15, 0.36, 0.3, 0.17, 1.0);
        line(R, G, B, A, S, 5, 28, 18, 15, 0.46, 0.38, 0.21, 1.0);
        for (int[] w : new int[][]{{8, 24}, {9, 24}, {12, 20}, {13, 20}}) over(R, G, B, A, w[0] + w[1] * S, 0.15, 0.35, 0.18, 1.0);
        // the skull
        for (int y = 12; y <= 15; y++) for (int x = 16; x <= 19; x++) over(R, G, B, A, x + y * S, 0.84, 0.84, 0.76, 1.0);
        over(R, G, B, A, 17 + 13 * S, 0.3, 1.0, 0.75, 1.0);
        over(R, G, B, A, 19 + 13 * S, 0.3, 1.0, 0.75, 1.0);
        // crossbar across the shaft's direction, three prongs pointing up-right
        line(R, G, B, A, S, 16, 9, 22, 15, 0.27, 0.24, 0.16, 1.0);
        line(R, G, B, A, S, 19, 12, 26, 5, 0.84, 0.84, 0.74, 1.0);
        line(R, G, B, A, S, 16, 9, 21, 4, 0.84, 0.84, 0.74, 1.0);
        line(R, G, B, A, S, 22, 15, 27, 10, 0.84, 0.84, 0.74, 1.0);
        // glowing tips
        for (int[] tip : new int[][]{{26, 5}, {27, 4}, {21, 4}, {22, 3}, {27, 10}, {28, 9}}) over(R, G, B, A, tip[0] + tip[1] * S, 0.6, 1.0, 0.85, 1.0);
        int[] px = new int[S * S];
        for (int i = 0; i < S * S; i++) {
            double al = Math.min(1, A[i]);
            if (al <= 0) continue;
            px[i] = ((int) Math.round(al * 255) << 24) | ((int) Math.round(Math.min(1, R[i] / A[i]) * 255) << 16)
                    | ((int) Math.round(Math.min(1, G[i] / A[i]) * 255) << 8) | (int) Math.round(Math.min(1, B[i] / A[i]) * 255);
        }
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, S, S, px, 0, S);
        ImageIO.write(img, "png", out);
    }

    static void line(double[] R, double[] G, double[] B, double[] A, int S, int x0, int y0, int x1, int y1, double r, double g, double b, double a) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= n; i++) {
            int x = (int) Math.round(x0 + (x1 - x0) * (double) i / Math.max(1, n));
            int y = (int) Math.round(y0 + (y1 - y0) * (double) i / Math.max(1, n));
            if (x >= 0 && y >= 0 && x < S && y < S) over(R, G, B, A, x + y * S, r, g, b, a);
        }
    }

    static void over(double[] R, double[] G, double[] B, double[] A, int i, double r, double g, double b, double a) {
        R[i] = r * a + R[i] * (1 - a);
        G[i] = g * a + G[i] * (1 - a);
        B[i] = b * a + B[i] * (1 - a);
        A[i] = a + A[i] * (1 - a);
    }

    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
