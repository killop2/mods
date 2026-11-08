import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Strange's Hammer ("Молот Стрэнджа") art: the 3D item model (block-model elements) - a handle wrapped in red
 * leather with gold rings and a gold pommel, a big dark-steel head with gold bands, burning orange runes round a
 * green gem on both faces and polished striking faces - its texture, and the 32x32 inventory icon (the hammer on a
 * slant over a glowing mystic circle).
 * Run: java tools/StrangeArt.java src/main/resources/assets/wheelofwonder
 */
public class StrangeArt {
    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        texture(new File(root, "textures/item/strange_hammer_3d.png"));
        icon(new File(root, "textures/item/strange_hammer.png"));
        Files.writeString(new File(root, "models/item/strange_hammer_3d.json").toPath(), model());
        Files.writeString(new File(root, "models/item/strange_hammer.json").toPath(), """
                {
                  "loader": "neoforge:separate_transforms",
                  "base": { "parent": "wheelofwonder:item/strange_hammer_3d" },
                  "perspectives": {
                    "gui": { "parent": "minecraft:item/generated", "textures": { "layer0": "wheelofwonder:item/strange_hammer" } },
                    "fixed": { "parent": "minecraft:item/generated", "textures": { "layer0": "wheelofwonder:item/strange_hammer" } }
                  }
                }
                """);
    }

    static final int STEEL = 0, STEEL_L = 1, GOLD = 2, LEATHER = 3, RUNE = 4, GEM = 5, GOLD_D = 6, DARK = 7;

    static void texture(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(41);
        for (int s = 0; s < 8; s++) {
            int ox = (s % 4) * 8, oy = (s / 4) * 8;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                double n = r.nextDouble();
                int c = switch (s) {
                    case STEEL -> mix(0x2E333E, 0x444B5A, n * 0.7 + (7 - y) / 7.0 * 0.3);
                    case STEEL_L -> mix(0x7E889A, 0xB4BECE, n * 0.5 + (7 - y) / 7.0 * 0.5);
                    case GOLD -> mix(0xB8861E, 0xF2CC4A, (7 - y) / 7.0 * 0.6 + n * 0.3);
                    case LEATHER -> ((x + y) % 4 < 2) ? mix(0x5E1014, 0x7A1A1A, n) : mix(0x7A1A1A, 0x942424, n);   // the wrap
                    case RUNE -> mix(0xFF7A10, 0xFFD070, (7 - y) / 7.0 * 0.6 + n * 0.3);
                    case GEM -> Math.hypot(x - 3.5, y - 3.5) < 2.0 ? 0xC8FFD0 : 0x3CE85A;
                    case GOLD_D -> mix(0x8A6414, 0xB8861E, n);
                    default -> mix(0x15161A, 0x24262C, n);
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
        // the handle
        e.add(new El(6.9, -11, 6.9, 9.1, -9, 9.1, GOLD, false));          // pommel
        e.add(new El(7.5, -11.6, 7.5, 8.5, -11, 8.5, GEM, true));
        e.add(new El(7.25, -9, 7.25, 8.75, 15, 8.75, LEATHER, false));
        for (double y : new double[]{-4, 2, 8}) e.add(new El(7.0, y, 7.0, 9.0, y + 1.0, 9.0, GOLD_D, false));
        e.add(new El(6.6, 14.5, 6.6, 9.4, 16, 9.4, GOLD, false));          // collar
        // the head
        e.add(new El(1.5, 16, 4.5, 14.5, 23.5, 11.5, STEEL, false));
        e.add(new El(4.0, 15.6, 4.1, 5.2, 23.9, 11.9, GOLD, false));        // bands
        e.add(new El(10.8, 15.6, 4.1, 12.0, 23.9, 11.9, GOLD, false));
        e.add(new El(0.8, 16.7, 5.2, 1.5, 22.8, 10.8, STEEL_L, false));     // striking faces
        e.add(new El(14.5, 16.7, 5.2, 15.2, 22.8, 10.8, STEEL_L, false));
        // burning runes round the gem, both faces
        for (double[] z : new double[][]{{11.5, 11.7}, {4.3, 4.5}}) {
            e.add(new El(6.0, 21.6, z[0], 10.0, 22.2, z[1], RUNE, true));
            e.add(new El(6.0, 17.3, z[0], 10.0, 17.9, z[1], RUNE, true));
            e.add(new El(6.0, 17.9, z[0], 6.6, 21.6, z[1], RUNE, true));
            e.add(new El(9.4, 17.9, z[0], 10.0, 21.6, z[1], RUNE, true));
            e.add(new El(7.6, 22.2, z[0], 8.4, 23.2, z[1], RUNE, true));
            e.add(new El(7.6, 16.3, z[0], 8.4, 17.3, z[1], RUNE, true));
        }
        e.add(new El(7.2, 19.0, 11.5, 8.8, 20.6, 11.9, GEM, true));
        e.add(new El(7.2, 19.0, 4.1, 8.8, 20.6, 4.5, GEM, true));
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"textures\": { \"particle\": \"wheelofwonder:item/strange_hammer_3d\", \"t\": \"wheelofwonder:item/strange_hammer_3d\" },\n");
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
                    "thirdperson_righthand": { "rotation": [0, -90, 10], "translation": [0, 4.5, 0.5], "scale": [0.85, 0.85, 0.85] },
                    "thirdperson_lefthand": { "rotation": [0, 90, -10], "translation": [0, 4.5, 0.5], "scale": [0.85, 0.85, 0.85] },
                    "firstperson_righthand": { "rotation": [0, -90, 15], "translation": [1.13, 4.2, 1.13], "scale": [0.6, 0.6, 0.6] },
                    "firstperson_lefthand": { "rotation": [0, 90, -15], "translation": [1.13, 4.2, 1.13], "scale": [0.6, 0.6, 0.6] },
                    "ground": { "rotation": [0, 0, 90], "translation": [0, 2, 0], "scale": [0.45, 0.45, 0.45] },
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
        // the mystic circle behind it: a soft orange glow, a ring of sparks and an inner ring
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double d = Math.hypot(x + 0.5 - 16, y + 0.5 - 16);
            double k = Math.max(0, 1 - d / 15.5);
            if (k > 0) over(R, G, B, A, x + y * S, 1.0, 0.55, 0.12, 0.35 * k);
            if (Math.abs(d - 13.5) < 0.8) over(R, G, B, A, x + y * S, 1.0, 0.7 + 0.25 * ((x * 7 + y * 3) % 3) / 2.0, 0.2, 0.95);
            if (Math.abs(d - 9.0) < 0.5) over(R, G, B, A, x + y * S, 1.0, 0.62, 0.15, 0.6);
        }
        // the handle from the lower left (red leather, gold rings, gold pommel)
        line(R, G, B, A, S, 5, 27, 17, 15, 0.48, 0.1, 0.1, 1.0);
        line(R, G, B, A, S, 6, 27, 18, 15, 0.6, 0.14, 0.14, 1.0);
        for (int[] g : new int[][]{{9, 23}, {10, 23}, {12, 20}, {13, 20}}) over(R, G, B, A, g[0] + g[1] * S, 0.95, 0.78, 0.25, 1.0);
        for (int[] g : new int[][]{{4, 27}, {5, 28}, {4, 28}, {5, 27}}) over(R, G, B, A, g[0] + g[1] * S, 0.95, 0.8, 0.28, 1.0);
        // the head across the handle's end: dark steel, gold bands, polished faces, orange runes and the green gem
        for (int i = -7; i <= 7; i++) for (int j = -3; j <= 3; j++) {
            int x = (int) Math.round(19 + i * 0.707 + j * 0.707), y = (int) Math.round(12 - i * 0.707 + j * 0.707);
            if (x < 0 || y < 0 || x >= S || y >= S) continue;
            double r = 0.22, g = 0.25, b = 0.31;
            if (Math.abs(i) == 4 || Math.abs(i) == 3 && Math.abs(j) == 3) { r = 0.92; g = 0.72; b = 0.2; }
            if (Math.abs(i) == 7) { r = 0.6; g = 0.65; b = 0.75; }
            if (Math.abs(i) <= 1 && Math.abs(j) <= 1) { r = 0.3; g = 1.0; b = 0.45; }
            else if (Math.abs(i) <= 2 && Math.abs(j) <= 2 && (Math.abs(i) == 2 || Math.abs(j) == 2)) { r = 1.0; g = 0.6; b = 0.12; }
            over(R, G, B, A, x + y * S, r, g, b, 1.0);
        }
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
