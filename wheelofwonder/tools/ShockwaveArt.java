import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shockwave! art: the spear's 3D item model (block-model elements) with its texture, and the 32x32 inventory icon
 * (a burst of ice-blue light behind a crystal spear, a glowing crack at its foot).
 * Run: java tools/ShockwaveArt.java src/main/resources/assets/wheelofwonder
 */
public class ShockwaveArt {
    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        spearTexture(new File(root, "textures/item/shockwave_spear.png"));
        icon(new File(root, "textures/item/shockwave.png"));
        Files.writeString(new File(root, "models/item/shockwave_3d.json").toPath(), model());
        Files.writeString(new File(root, "models/item/shockwave.json").toPath(), """
                {
                  "loader": "neoforge:separate_transforms",
                  "base": { "parent": "wheelofwonder:item/shockwave_3d" },
                  "perspectives": {
                    "gui": { "parent": "minecraft:item/generated", "textures": { "layer0": "wheelofwonder:item/shockwave" } },
                    "fixed": { "parent": "minecraft:item/generated", "textures": { "layer0": "wheelofwonder:item/shockwave" } }
                  }
                }
                """);
    }

    // ------------------------------------------------------------------ texture: 4x4 swatches of 8x8 px

    static final int STEEL = 0, SHAFT = 1, GRIP = 2, SILVER = 3, CRYSTAL = 4, EDGE = 5, GEM = 6, PRONG = 7;

    static void spearTexture(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(7);
        for (int s = 0; s < 8; s++) {
            int ox = (s % 4) * 8, oy = (s / 4) * 8;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                double n = r.nextDouble();
                int c = switch (s) {
                    case STEEL -> mix(0x2B3448, 0x46536E, y / 7.0 * 0.6 + n * 0.3);
                    case SHAFT -> mix(0x161B2E, 0x2C3556, (x == 3 || x == 4 ? 0.6 : 0.2) + n * 0.25);       // dark lacquer, a sheen line
                    case GRIP -> ((x + y) % 4 < 2) ? mix(0x4A2A7A, 0x6B3DAE, n * 0.4) : mix(0x2A1848, 0x3A2266, n * 0.4);   // wrapped band
                    case SILVER -> mix(0x9AA9C4, 0xE2EAF7, (7 - y) / 7.0 * 0.7 + n * 0.2);
                    case CRYSTAL -> {
                        double f = (x + (7 - y)) / 14.0;                                             // facet light
                        int base = mix(0x2E8FE0, 0x9FF0FF, f * 0.9 + n * 0.1);
                        yield (x == y || x == y + 1) ? mix(base, 0xFFFFFF, 0.55) : base;
                    }
                    case EDGE -> mix(0xC8F8FF, 0xFFFFFF, n * 0.5);
                    case GEM -> {
                        double d = Math.hypot(x - 3.5, y - 3.5) / 5.0;
                        yield mix(0xF0C8FF, 0x7A2FD0, Math.min(1.0, d * 1.3));
                    }
                    default -> mix(0x3A6FD8, 0x7FD8FF, (7 - y) / 7.0 * 0.8 + n * 0.15);
                };
                img.setRGB(ox + x, oy + y, 0xFF000000 | c);
            }
        }
        ImageIO.write(img, "png", out);
    }

    // ------------------------------------------------------------------ model

    record El(double x0, double y0, double z0, double x1, double y1, double z1, int sw, boolean glow, double rotZ, double[] origin) {
    }

    static String model() {
        List<El> e = new ArrayList<>();
        // butt spike and ring
        e.add(new El(7.1, -12, 7.1, 8.9, -10, 8.9, STEEL, false, 0, null));
        e.add(new El(6.5, -10, 6.5, 9.5, -9, 9.5, SILVER, false, 0, null));
        // shaft
        e.add(new El(7.25, -9, 7.25, 8.75, 20, 8.75, SHAFT, false, 0, null));
        // grip wrap with silver bands
        e.add(new El(6.9, -3, 6.9, 9.1, 4, 9.1, GRIP, false, 0, null));
        e.add(new El(6.7, -3.6, 6.7, 9.3, -3, 9.3, SILVER, false, 0, null));
        e.add(new El(6.7, 4, 6.7, 9.3, 4.6, 9.3, SILVER, false, 0, null));
        // mid band
        e.add(new El(6.9, 11, 6.9, 9.1, 12, 9.1, SILVER, false, 0, null));
        // collar and the violet gem set in it (both sides)
        e.add(new El(6.4, 19.5, 6.4, 9.6, 21.5, 9.6, SILVER, false, 0, null));
        e.add(new El(7.1, 19.9, 6.0, 8.9, 21.1, 10.0, GEM, true, 0, null));
        // crescent prongs
        e.add(new El(4.2, 20.5, 7.4, 6.4, 23.5, 8.6, PRONG, true, 22.5, new double[]{6.4, 20.5, 8}));
        e.add(new El(9.6, 20.5, 7.4, 11.8, 23.5, 8.6, PRONG, true, -22.5, new double[]{9.6, 20.5, 8}));
        // the crystal blade, narrowing to a point, with a white edge down its spine
        e.add(new El(6.0, 21.5, 7.2, 10.0, 24.0, 8.8, CRYSTAL, true, 0, null));
        e.add(new El(6.5, 24.0, 7.35, 9.5, 27.0, 8.65, CRYSTAL, true, 0, null));
        e.add(new El(7.0, 27.0, 7.5, 9.0, 29.5, 8.5, CRYSTAL, true, 0, null));
        e.add(new El(7.5, 29.5, 7.65, 8.5, 31.5, 8.35, CRYSTAL, true, 0, null));
        e.add(new El(7.8, 22.0, 7.05, 8.2, 30.5, 8.95, EDGE, true, 0, null));
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"textures\": { \"particle\": \"wheelofwonder:item/shockwave_spear\", \"t\": \"wheelofwonder:item/shockwave_spear\" },\n");
        sb.append("  \"elements\": [\n");
        for (int i = 0; i < e.size(); i++) {
            El el = e.get(i);
            double u0 = (el.sw % 4) * 4, v0 = (el.sw / 4) * 4;
            String uv = String.format("[%s, %s, %s, %s]", f(u0 + 0.5), f(v0 + 0.5), f(u0 + 3.5), f(v0 + 3.5));
            String extra = el.glow ? ", \"neoforge_data\": { \"block_light\": 15, \"sky_light\": 15 }" : "";
            sb.append("    { \"from\": [").append(f(el.x0)).append(", ").append(f(el.y0)).append(", ").append(f(el.z0)).append("], ");
            sb.append("\"to\": [").append(f(el.x1)).append(", ").append(f(el.y1)).append(", ").append(f(el.z1)).append("],");
            if (el.rotZ != 0) {
                sb.append(" \"rotation\": { \"angle\": ").append(f(el.rotZ)).append(", \"axis\": \"z\", \"origin\": [")
                        .append(f(el.origin[0])).append(", ").append(f(el.origin[1])).append(", ").append(f(el.origin[2])).append("] },");
            }
            if (el.glow) sb.append(" \"shade\": false,");
            sb.append("\n      \"faces\": {");
            String[] fs = {"north", "south", "east", "west", "up", "down"};
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
        Random rnd = new Random(3);
        // burst of light from the spear's foot (lower left), rays fanning up and right
        double cx = 9.5, cy = 23.5;
        double[] rays = new double[14];
        for (int i = 0; i < rays.length; i++) rays[i] = rnd.nextDouble() * Math.PI * 2;
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double dx = x + 0.5 - cx, dy = y + 0.5 - cy, d = Math.hypot(dx, dy), ang = Math.atan2(dy, dx);
            double ray = 0;
            for (double ra : rays) {
                double da = Math.abs(Math.atan2(Math.sin(ang - ra), Math.cos(ang - ra)));
                ray = Math.max(ray, Math.max(0, 1 - da / 0.16));
            }
            double core = Math.exp(-d * d / 18.0);
            double k = Math.min(1, core * 1.2 + ray * Math.max(0, 1 - d / 17.0) * 0.85);
            if (k <= 0.02) continue;
            add(R, G, B, A, x + y * S, 0.25 + 0.75 * core, 0.75 + 0.25 * core, 1.0, k);
        }
        // the crack at the foot: jagged line going right along the ground
        int[][] crack = {{9, 24}, {12, 25}, {15, 24}, {18, 26}, {21, 25}, {24, 27}, {27, 26}, {30, 27}};
        for (int i = 0; i < crack.length - 1; i++) line(R, G, B, A, S, crack[i][0], crack[i][1], crack[i + 1][0], crack[i + 1][1], 0.9, 1.0, 1.0, 1.0);
        line(R, G, B, A, S, 15, 24, 17, 21, 0.6, 0.9, 1.0, 0.9);
        line(R, G, B, A, S, 21, 25, 23, 29, 0.6, 0.9, 1.0, 0.9);
        // the spear: shaft from lower left to upper right, crystal head at the top right
        line(R, G, B, A, S, 4, 28, 19, 13, 0.16, 0.2, 0.35, 1.0);
        line(R, G, B, A, S, 5, 28, 20, 13, 0.22, 0.27, 0.45, 1.0);
        line(R, G, B, A, S, 7, 25, 9, 23, 0.45, 0.28, 0.75, 1.0);    // grip
        line(R, G, B, A, S, 8, 25, 10, 23, 0.4, 0.24, 0.68, 1.0);
        line(R, G, B, A, S, 18, 15, 21, 12, 0.85, 0.9, 0.97, 1.0);   // collar
        // blade: a filled diamond from (20,13) to (29,4)
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double u = ((x + 0.5 - 20) + (13 - (y + 0.5))) / 2.0;      // along the blade
            double v = ((x + 0.5 - 20) - (13 - (y + 0.5))) / 2.0;      // across
            if (u < 0 || u > 9) continue;
            double half = u < 3 ? 1.0 + u * 0.55 : 2.65 * (1 - (u - 3) / 6.2);
            if (Math.abs(v) > half) continue;
            double light = 0.55 + 0.45 * (v / Math.max(0.5, half) * 0.5 + 0.5);
            boolean edge = Math.abs(v) < 0.45;
            set(R, G, B, A, x + y * S, edge ? 1.0 : 0.35 * light, edge ? 1.0 : 0.75 + 0.2 * light, 1.0, 1.0);
        }
        // the gem
        set(R, G, B, A, 19 + 14 * S, 0.85, 0.55, 1.0, 1.0);
        set(R, G, B, A, 20 + 14 * S, 0.7, 0.35, 0.95, 1.0);
        // dark outline around the solid parts
        int[] px = new int[S * S];
        for (int i = 0; i < S * S; i++) px[i] = argb(A[i], R[i], G[i], B[i]);
        int[] outP = px.clone();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            int i = x + y * S;
            if ((px[i] >>> 24) > 200) continue;
            boolean near = false;
            for (int k = 0; k < 4 && !near; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || ny < 0 || nx >= S || ny >= S) continue;
                int q = px[nx + ny * S];
                near = (q >>> 24) == 255 && ((q >> 16) & 255) < 120 && (q & 255) < 200;
            }
            if (near) outP[i] = 0xFF0C1022;
        }
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, S, S, outP, 0, S);
        ImageIO.write(img, "png", out);
    }

    static void line(double[] R, double[] G, double[] B, double[] A, int S, int x0, int y0, int x1, int y1, double r, double g, double b, double a) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= n; i++) {
            int x = (int) Math.round(x0 + (x1 - x0) * (double) i / Math.max(1, n));
            int y = (int) Math.round(y0 + (y1 - y0) * (double) i / Math.max(1, n));
            if (x >= 0 && y >= 0 && x < S && y < S) set(R, G, B, A, x + y * S, r, g, b, a);
        }
    }

    static void set(double[] R, double[] G, double[] B, double[] A, int i, double r, double g, double b, double a) {
        R[i] = r * a + R[i] * (1 - a);
        G[i] = g * a + G[i] * (1 - a);
        B[i] = b * a + B[i] * (1 - a);
        A[i] = a + A[i] * (1 - a);
    }

    static void add(double[] R, double[] G, double[] B, double[] A, int i, double r, double g, double b, double a) {
        set(R, G, B, A, i, r, g, b, a);
    }

    static int argb(double a, double r, double g, double b) {
        if (a <= 0) return 0;
        // stored premultiplied-ish above: un-premultiply for straight alpha
        int ia = (int) Math.round(Math.min(1, a) * 255);
        int ir = (int) Math.round(Math.min(1, r / a) * 255), ig = (int) Math.round(Math.min(1, g / a) * 255), ib = (int) Math.round(Math.min(1, b / a) * 255);
        return (ia << 24) | (ir << 16) | (ig << 8) | ib;
    }

    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
