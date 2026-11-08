import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Telekinesis Orb art, green style: a staff (3D item model from block-model elements) with its texture - a dark
 * wood shaft with gold bands and green wraps, a gold crown with three green crystal prongs round a glowing green
 * orb - and the 32x32 inventory icon (the staff on a slant, a green burst of power at its head).
 * Run: java tools/StaffArt.java src/main/resources/assets/wheelofwonder
 */
public class StaffArt {
    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        texture(new File(root, "textures/item/telekinesis_staff.png"));
        icon(new File(root, "textures/item/telekinesis_orb.png"));
        Files.writeString(new File(root, "models/item/telekinesis_staff.json").toPath(), model());
        Files.writeString(new File(root, "models/item/telekinesis_orb.json").toPath(), """
                {
                  "loader": "neoforge:separate_transforms",
                  "base": { "parent": "wheelofwonder:item/telekinesis_staff" },
                  "perspectives": {
                    "gui": { "parent": "minecraft:item/generated", "textures": { "layer0": "wheelofwonder:item/telekinesis_orb" } },
                    "fixed": { "parent": "minecraft:item/generated", "textures": { "layer0": "wheelofwonder:item/telekinesis_orb" } }
                  }
                }
                """);
    }

    static final int WOOD = 0, GOLD = 1, GOLD_DARK = 2, WRAP = 3, CRYSTAL = 4, CRYSTAL_TIP = 5, ORB = 6, ORB_CORE = 7;

    static void texture(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(11);
        for (int s = 0; s < 8; s++) {
            int ox = (s % 4) * 8, oy = (s / 4) * 8;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                double n = r.nextDouble();
                int c = switch (s) {
                    case WOOD -> mix(0x2A2416, 0x433A22, (x == 2 || x == 5 ? 0.6 : 0.2) + n * 0.3);
                    case GOLD -> mix(0xC08A28, 0xF2CC5A, (7 - y) / 7.0 * 0.7 + n * 0.2);
                    case GOLD_DARK -> mix(0x7A5418, 0xB07E26, n * 0.6);
                    case WRAP -> ((x + y) % 4 < 2) ? mix(0x1E6B2A, 0x2E9A3A, n * 0.4) : mix(0x13451C, 0x1E6B2A, n * 0.4);
                    case CRYSTAL -> {
                        double f = (x + (7 - y)) / 14.0;
                        int base = mix(0x1E9E3A, 0x9CFF6A, f * 0.9 + n * 0.1);
                        yield (x == y || x == y + 1) ? mix(base, 0xE8FFD0, 0.5) : base;
                    }
                    case CRYSTAL_TIP -> mix(0xC8FF90, 0xF4FFE8, n * 0.6);
                    case ORB -> {
                        double d = Math.hypot(x - 3.5, y - 3.5) / 5.0;
                        yield mix(0xE8FFD0, 0x2EBF46, Math.min(1.0, d * 1.2));
                    }
                    default -> mix(0xF4FFE8, 0xB8FF80, n * 0.4);
                };
                img.setRGB(ox + x, oy + y, 0xFF000000 | c);
            }
        }
        ImageIO.write(img, "png", out);
    }

    record El(double x0, double y0, double z0, double x1, double y1, double z1, int sw, boolean glow, String axis, double angle, double[] origin) {
    }

    static El box(double x0, double y0, double z0, double x1, double y1, double z1, int sw, boolean glow) {
        return new El(x0, y0, z0, x1, y1, z1, sw, glow, null, 0, null);
    }

    static String model() {
        List<El> e = new ArrayList<>();
        // butt spike and ring
        e.add(box(7.2, -12, 7.2, 8.8, -10, 8.8, GOLD, false));
        e.add(box(6.8, -10, 6.8, 9.2, -9, 9.2, GOLD_DARK, false));
        // shaft, wraps and bands
        e.add(box(7.3, -9, 7.3, 8.7, 21.5, 8.7, WOOD, false));
        e.add(box(7.0, -3, 7.0, 9.0, -0.5, 9.0, WRAP, false));
        e.add(box(7.0, 2.5, 7.0, 9.0, 5, 9.0, WRAP, false));
        e.add(box(6.9, 10, 6.9, 9.1, 11, 9.1, GOLD, false));
        e.add(box(6.9, 19.5, 6.9, 9.1, 21.5, 9.1, GOLD, false));
        // the crown
        e.add(box(6.3, 21.5, 6.3, 9.7, 23, 9.7, GOLD, false));
        e.add(box(6.8, 23, 6.8, 9.2, 23.6, 9.2, GOLD_DARK, false));
        // three crystal prongs leaning out round the orb, with bright tips
        e.add(new El(5.0, 23, 7.4, 6.2, 29.5, 8.6, CRYSTAL, true, "z", 22.5, new double[]{6.2, 23, 8}));
        e.add(new El(5.0, 29.5, 7.6, 6.0, 31.5, 8.4, CRYSTAL_TIP, true, "z", 22.5, new double[]{6.2, 23, 8}));
        e.add(new El(9.8, 23, 7.4, 11.0, 29.5, 8.6, CRYSTAL, true, "z", -22.5, new double[]{9.8, 23, 8}));
        e.add(new El(10.0, 29.5, 7.6, 11.0, 31.5, 8.4, CRYSTAL_TIP, true, "z", -22.5, new double[]{9.8, 23, 8}));
        e.add(new El(7.4, 23, 9.8, 8.6, 29.5, 11.0, CRYSTAL, true, "x", 22.5, new double[]{8, 23, 9.8}));
        e.add(new El(7.6, 29.5, 10.0, 8.4, 31.5, 11.0, CRYSTAL_TIP, true, "x", 22.5, new double[]{8, 23, 9.8}));
        // the glowing orb in the middle (two crossed cubes for a rounder shape)
        e.add(box(6.6, 24.2, 6.6, 9.4, 27.0, 9.4, ORB, true));
        e.add(new El(6.6, 24.2, 6.6, 9.4, 27.0, 9.4, ORB, true, "y", 45, new double[]{8, 25.6, 8}));
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"textures\": { \"particle\": \"wheelofwonder:item/telekinesis_staff\", \"t\": \"wheelofwonder:item/telekinesis_staff\" },\n");
        sb.append("  \"elements\": [\n");
        String[] fs = {"north", "south", "east", "west", "up", "down"};
        for (int i = 0; i < e.size(); i++) {
            El el = e.get(i);
            double u0 = (el.sw % 4) * 4, v0 = (el.sw / 4) * 4;
            String uv = String.format("[%s, %s, %s, %s]", f(u0 + 0.5), f(v0 + 0.5), f(u0 + 3.5), f(v0 + 3.5));
            String extra = el.glow ? ", \"neoforge_data\": { \"block_light\": 15, \"sky_light\": 15 }" : "";
            sb.append("    { \"from\": [").append(f(el.x0)).append(", ").append(f(el.y0)).append(", ").append(f(el.z0)).append("], ");
            sb.append("\"to\": [").append(f(el.x1)).append(", ").append(f(el.y1)).append(", ").append(f(el.z1)).append("],");
            if (el.axis != null) {
                sb.append(" \"rotation\": { \"angle\": ").append(f(el.angle)).append(", \"axis\": \"").append(el.axis).append("\", \"origin\": [")
                        .append(f(el.origin[0])).append(", ").append(f(el.origin[1])).append(", ").append(f(el.origin[2])).append("] },");
            }
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
                    "thirdperson_righthand": { "rotation": [0, -90, 10], "translation": [0, 4, 0.5], "scale": [0.75, 0.75, 0.75] },
                    "thirdperson_lefthand": { "rotation": [0, 90, -100], "translation": [0, 4, 0.5], "scale": [0.75, 0.75, 0.75] },
                    "firstperson_righthand": { "rotation": [0, -90, -20], "translation": [1.13, 3.2, 1.13], "scale": [0.55, 0.55, 0.55] },
                    "firstperson_lefthand": { "rotation": [0, 90, -70], "translation": [1.13, 3.2, 1.13], "scale": [0.55, 0.55, 0.55] },
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
        Random rnd = new Random(5);
        // a green burst of power round the head (upper right), streaks of light falling down
        double cx = 23.5, cy = 8.5;
        double[] rays = new double[12];
        for (int i = 0; i < rays.length; i++) rays[i] = rnd.nextDouble() * Math.PI * 2;
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double dx = x + 0.5 - cx, dy = y + 0.5 - cy, d = Math.hypot(dx, dy), ang = Math.atan2(dy, dx);
            double ray = 0;
            for (double ra : rays) {
                double da = Math.abs(Math.atan2(Math.sin(ang - ra), Math.cos(ang - ra)));
                ray = Math.max(ray, Math.max(0, 1 - da / 0.14));
            }
            double core = Math.exp(-d * d / 14.0);
            double k = Math.min(1, core * 1.3 + ray * Math.max(0, 1 - d / 12.0) * 0.7);
            if (k > 0.02) over(R, G, B, A, x + y * S, 0.45 + 0.5 * core, 1.0, 0.35 + 0.5 * core, k);
        }
        // the staff on a slant from the lower left
        line(R, G, B, A, S, 4, 29, 20, 12, 0.17, 0.14, 0.08, 1.0);
        line(R, G, B, A, S, 5, 29, 21, 12, 0.26, 0.22, 0.13, 1.0);
        for (int[] band : new int[][]{{8, 25}, {9, 24}, {14, 18}, {15, 17}}) set(R, G, B, A, S, band[0], band[1], 0.12, 0.6, 0.18);
        line(R, G, B, A, S, 18, 13, 21, 10, 0.95, 0.75, 0.25, 1.0);   // crown
        // prongs and the orb
        line(R, G, B, A, S, 20, 10, 20, 4, 0.2, 0.75, 0.25, 1.0);
        line(R, G, B, A, S, 22, 12, 28, 12, 0.2, 0.75, 0.25, 1.0);
        line(R, G, B, A, S, 21, 11, 27, 5, 0.3, 0.9, 0.35, 1.0);
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double d = Math.hypot(x + 0.5 - 23.5, y + 0.5 - 8.5);
            if (d < 2.6) over(R, G, B, A, x + y * S, d < 1.2 ? 0.95 : 0.55, 1.0, d < 1.2 ? 0.85 : 0.45, 1.0);
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

    static void set(double[] R, double[] G, double[] B, double[] A, int S, int x, int y, double r, double g, double b) {
        over(R, G, B, A, x + y * S, r, g, b, 1.0);
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
