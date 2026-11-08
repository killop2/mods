import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Telekinesis Orb art, the green glove: a ghostly green hand (3D item model from block-model elements) with a gold
 * cuff, glowing fingertips and a rune on its back, and the 32x32 inventory icon (the hand with strings hanging from
 * its fingers in a green glow). Run: java tools/GloveArt.java src/main/resources/assets/wheelofwonder
 */
public class GloveArt {
    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        texture(new File(root, "textures/item/telekinesis_glove.png"));
        icon(new File(root, "textures/item/telekinesis_orb.png"));
        Files.writeString(new File(root, "models/item/telekinesis_glove.json").toPath(), model());
        Files.writeString(new File(root, "models/item/telekinesis_orb.json").toPath(), """
                {
                  "loader": "neoforge:separate_transforms",
                  "base": { "parent": "wheelofwonder:item/telekinesis_glove" },
                  "perspectives": {
                    "gui": { "parent": "minecraft:item/generated", "textures": { "layer0": "wheelofwonder:item/telekinesis_orb" } },
                    "fixed": { "parent": "minecraft:item/generated", "textures": { "layer0": "wheelofwonder:item/telekinesis_orb" } }
                  }
                }
                """);
        new File(root, "models/item/telekinesis_staff.json").delete();
        new File(root, "textures/item/telekinesis_staff.png").delete();
    }

    static final int GOLD = 0, GOLD_DARK = 1, SKIN = 2, SKIN_DARK = 3, TIP = 4, RUNE = 5;

    static void texture(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(17);
        for (int s = 0; s < 8; s++) {
            int ox = (s % 4) * 8, oy = (s / 4) * 8;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                double n = r.nextDouble();
                int c = switch (s) {
                    case GOLD -> mix(0xC08A28, 0xF2CC5A, (7 - y) / 7.0 * 0.7 + n * 0.2);
                    case GOLD_DARK -> mix(0x7A5418, 0xB07E26, n * 0.6);
                    case SKIN -> mix(0x2FCF4A, 0x8CFF6A, (x + (7 - y)) / 14.0 * 0.8 + n * 0.15);   // glowing green
                    case SKIN_DARK -> mix(0x1A8A30, 0x2FCF4A, n * 0.5);
                    case TIP -> mix(0xD8FFB0, 0xF6FFEC, n * 0.5);
                    default -> Math.hypot(x - 3.5, y - 3.5) < 2.2 ? 0xF0FFE0 : mix(0x6AFF5A, 0x2FCF4A, n * 0.4);
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
        // the cuff
        e.add(box(4.8, 0, 5.8, 11.2, 2.4, 10.2, GOLD, false));
        e.add(box(5.0, 2.4, 6.0, 11.0, 3.0, 10.0, GOLD_DARK, false));
        // palm and the rune on its back
        e.add(box(5.2, 3, 6.6, 10.8, 9, 9.4, SKIN, true));
        e.add(box(7.1, 4.8, 9.4, 8.9, 6.8, 9.8, RUNE, true));
        // four fingers (spread a little), each with a glowing tip
        double[][] fingers = {{5.3, 6.5, 13.6}, {6.8, 8.0, 14.6}, {8.3, 9.5, 14.0}, {9.8, 10.8, 12.6}};
        // Minecraft only allows element turns of 0 / 22.5 / 45 degrees: the fingers stand straight
        double[] tilt = {0, 0, 0, 0};
        for (int i = 0; i < 4; i++) {
            double x0 = fingers[i][0], x1 = fingers[i][1], top = fingers[i][2];
            double[] o = {(x0 + x1) / 2, 9, 8};
            String ax = tilt[i] == 0 ? null : "z";
            e.add(new El(x0, 9, 7.3, x1, top - 1.2, 8.7, SKIN, true, ax, tilt[i], ax == null ? null : o));
            e.add(new El(x0 - 0.1, top - 1.2, 7.2, x1 + 0.1, top, 8.8, TIP, true, ax, tilt[i], ax == null ? null : o));
        }
        // the thumb, out to the side
        e.add(new El(3.4, 4, 7.3, 4.8, 8.6, 8.7, SKIN_DARK, true, "z", 22.5, new double[]{5.0, 4, 8}));
        e.add(new El(3.3, 8.6, 7.2, 4.9, 9.8, 8.8, TIP, true, "z", 22.5, new double[]{5.0, 4, 8}));
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"textures\": { \"particle\": \"wheelofwonder:item/telekinesis_glove\", \"t\": \"wheelofwonder:item/telekinesis_glove\" },\n");
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
        // held up in front, palm forward, as if casting
        sb.append("""
                  "display": {
                    "thirdperson_righthand": { "rotation": [0, 180, 0], "translation": [0, 3, 1], "scale": [0.6, 0.6, 0.6] },
                    "thirdperson_lefthand": { "rotation": [0, 180, 0], "translation": [0, 3, 1], "scale": [0.6, 0.6, 0.6] },
                    "firstperson_righthand": { "rotation": [0, 160, -10], "translation": [1.5, 3.5, -1], "scale": [0.6, 0.6, 0.6] },
                    "firstperson_lefthand": { "rotation": [0, 200, 10], "translation": [1.5, 3.5, -1], "scale": [0.6, 0.6, 0.6] },
                    "ground": { "rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5] },
                    "head": { "rotation": [0, 0, 0], "translation": [0, 8, 0], "scale": [0.8, 0.8, 0.8] }
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
        // the hand's shape: palm, four fingers, thumb (a mask), then a glow round it
        boolean[] hand = new boolean[S * S];
        fill(hand, S, 10, 12, 21, 21);                         // palm
        fill(hand, S, 10, 4, 11, 12);                          // fingers, with gaps between them
        fill(hand, S, 13, 2, 14, 12);
        fill(hand, S, 16, 3, 17, 12);
        fill(hand, S, 19, 6, 20, 12);
        for (int i = 0; i < 6; i++) fill(hand, S, 6 + i / 2, 12 + i, 9, 13 + i);   // thumb
        fill(hand, S, 9, 22, 22, 25);                          // cuff
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            double best = 99;
            for (int yy = Math.max(0, y - 4); yy < Math.min(S, y + 5); yy++)
                for (int xx = Math.max(0, x - 4); xx < Math.min(S, x + 5); xx++)
                    if (hand[xx + yy * S]) best = Math.min(best, Math.hypot(xx - x, yy - y));
            if (best > 0 && best < 4.0) over(R, G, B, A, x + y * S, 0.4, 1.0, 0.35, 0.4 * (1 - best / 4.0) * (1 - best / 4.0));
        }
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            if (!hand[x + y * S]) continue;
            boolean cuff = y >= 22;
            boolean tip = !cuff && y <= (x >= 19 ? 7 : x >= 16 ? 4 : x >= 13 ? 3 : 5) && x >= 10;
            double light = 0.7 + 0.3 * ((S - y) / (double) S);
            if (cuff) over(R, G, B, A, x + y * S, (y == 22 ? 0.95 : 0.75), (y == 22 ? 0.78 : 0.55), 0.2, 1.0);
            else if (tip) over(R, G, B, A, x + y * S, 0.9, 1.0, 0.8, 1.0);
            else over(R, G, B, A, x + y * S, 0.25 * light, 0.85 * light, 0.3 * light, 1.0);
        }
        // the rune on the palm
        over(R, G, B, A, 15 + 16 * S, 0.95, 1.0, 0.85, 1.0);
        over(R, G, B, A, 16 + 16 * S, 0.95, 1.0, 0.85, 1.0);
        over(R, G, B, A, 15 + 15 * S, 0.6, 1.0, 0.5, 1.0);
        over(R, G, B, A, 16 + 17 * S, 0.6, 1.0, 0.5, 1.0);
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

    static void fill(boolean[] m, int S, int x0, int y0, int x1, int y1) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) if (x >= 0 && y >= 0 && x < S && y < S) m[x + y * S] = true;
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
