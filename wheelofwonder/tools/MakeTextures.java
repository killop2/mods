import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Pixel textures for Wheel of Wonder (run: java tools/MakeTextures.java src/main/resources/assets/wheelofwonder/textures).
 * The wheel face follows the reference picture: dark red disc with one white spiral, worn cracks and a dark edge.
 */
public class MakeTextures {
    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        new File(root, "entity").mkdirs();
        new File(root, "item").mkdirs();
        ImageIO.write(face(64), "png", new File(root, "entity/wheel_face.png"));
        ImageIO.write(white(), "png", new File(root, "entity/wheel_white.png"));
        ImageIO.write(glow(32), "png", new File(root, "entity/wheel_glow.png"));
        ImageIO.write(item(), "png", new File(root, "item/wheel_of_wonder.png"));
        ImageIO.write(trail(), "png", new File(root, "entity/punch_trail.png"));
        ImageIO.write(gloveItem(), "png", new File(root, "item/walrus_punch.png"));
        ImageIO.write(ballistaItem(), "png", new File(root, "item/dark_ballista.png"));
        ImageIO.write(rocketSkin(), "png", new File(root, "entity/rocket_skin.png"));
        ImageIO.write(glowSoft(32), "png", new File(root, "entity/glow_soft.png"));
        ImageIO.write(rocketItem(), "png", new File(root, "item/rocket_ride.png"));
        System.out.println("textures written to " + root.getAbsolutePath());
    }

    static int argb(int a, int r, int g, int b) {
        return (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    static int mix(int[] c1, int[] c2, double t) {
        return argb(255, (int) (c1[0] + (c2[0] - c1[0]) * t), (int) (c1[1] + (c2[1] - c1[1]) * t), (int) (c1[2] + (c2[2] - c1[2]) * t));
    }

    /** One-armed Archimedean spiral on dark red, shaded towards the edge, with cracks and a glossy upper-left. */
    static BufferedImage face(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        double c = n / 2.0;
        double rMax = n / 2.0 - 0.5;
        double turn = n / 7.0; // pixels between spiral turns
        int[] redHi = {196, 38, 52}, redLo = {104, 14, 30};
        int[] whiteHi = {255, 240, 228}, whiteLo = {214, 178, 168};
        int[] edge = {52, 8, 16};
        java.util.Random rnd = new java.util.Random(7);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x + 0.5 - c, dy = y + 0.5 - c;
                double r = Math.sqrt(dx * dx + dy * dy);
                if (r > rMax) {
                    img.setRGB(x, y, 0);
                    continue;
                }
                double t = r / rMax;
                double ang = Math.atan2(dy, dx) / (2 * Math.PI);
                double s = r / turn - ang;
                double f = s - Math.floor(s);
                boolean stripe = f < 0.40 && r > 1.5;
                // gloss: lighter on the upper-left
                double gloss = Math.max(0, (-dx - dy) / (n * 0.9));
                int col;
                if (r > rMax - 2.2) {
                    col = argb(255, edge[0], edge[1], edge[2]);
                } else if (stripe) {
                    double edgeShade = Math.abs(f - 0.20) / 0.20; // darker at the stripe edges
                    col = mix(whiteHi, whiteLo, Math.min(1, 0.35 * edgeShade + 0.55 * t));
                } else {
                    col = mix(redHi, redLo, Math.min(1, 0.25 + 0.75 * t));
                }
                int a = 255, rr = (col >> 16) & 255, gg = (col >> 8) & 255, bb = col & 255;
                rr += (int) (gloss * 70); gg += (int) (gloss * 55); bb += (int) (gloss * 55);
                // pixel noise for the voxel look
                int nz = rnd.nextInt(13) - 6;
                img.setRGB(x, y, argb(a, rr + nz, gg + nz, bb + nz));
            }
        }
        // a few worn cracks like on the reference
        int[][] cracks = {{40, 14, 1, 1, 7}, {18, 42, 1, -1, 6}, {46, 40, -1, 1, 5}, {22, 18, 1, 0, 4}, {34, 50, 0, -1, 4}};
        for (int[] k : cracks) {
            int x = k[0], y = k[1];
            for (int i = 0; i < k[4]; i++) {
                if (x >= 0 && y >= 0 && x < n && y < n && (img.getRGB(x, y) >>> 24) != 0) {
                    int p = img.getRGB(x, y);
                    img.setRGB(x, y, argb(255, ((p >> 16) & 255) / 2, ((p >> 8) & 255) / 3, (p & 255) / 3));
                }
                x += k[2] + (i % 2 == 0 ? 0 : (rnd.nextBoolean() ? 1 : 0));
                y += k[3];
            }
        }
        // centre dot
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) img.setRGB((int) c + x - 1, (int) c + y - 1, argb(255, 255, 236, 200));
        return img;
    }

    static BufferedImage white() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, 0xFFFFFFFF);
        return img;
    }

    /** Soft round light for the additive bulb halos. */
    static BufferedImage glow(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        double c = n / 2.0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x + 0.5 - c, dy = y + 0.5 - c;
                double r = Math.sqrt(dx * dx + dy * dy) / c;
                double v = Math.max(0, 1 - r);
                v = v * v * (0.6 + 0.4 * v);
                int g = (int) (v * 255);
                img.setRGB(x, y, argb(g, g, g, g));
            }
        }
        return img;
    }

    /** Glow for shader packs (normal blending): pure white, only the alpha fades out, so no dark rim. */
    static BufferedImage glowSoft(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        double c = n / 2.0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x + 0.5 - c, dy = y + 0.5 - c;
                double r = Math.sqrt(dx * dx + dy * dy) / c;
                double v = Math.max(0, 1 - r);
                v = v * v * (0.55 + 0.45 * v);
                img.setRGB(x, y, argb((int) (v * 255), 255, 255, 255));
            }
        }
        return img;
    }

    /** Ribbon for the punch trails: bright core along the middle, soft edges, fading at both ends (u = length). */
    static BufferedImage trail() {
        int w = 32, h = 16;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            double across = 1.0 - Math.abs((y + 0.5) / h - 0.5) * 2.0;
            double core = Math.pow(across, 1.6);
            for (int x = 0; x < w; x++) {
                int g = (int) (Math.min(1.0, core * 1.15) * 255);
                img.setRGB(x, y, argb(g, g, g, g));
            }
        }
        return img;
    }

    /** 16x16 icon: a pair of blue boxing gloves with white cuffs, gold bands and little walrus tusks. */
    static BufferedImage gloveItem() {
        String[] px = {
                "................",
                "...bbbb.........",
                "..bBBBBb..bbbb..",
                ".bBBLBBBbbBBBBb.",
                ".bBLLBBBbBBLBBBb",
                ".bBBBBBBbBLLBBBb",
                ".bBBBBBbbBBBBBBb",
                "..bBBBBbtbBBBBBb",
                "..bbBBbt.tbBBBb.",
                "...yyyyt..tbBBb.",
                "...wwww..yyyyb..",
                "...wwww..wwww...",
                "...wwww..wwww...",
                "....ww...wwww...",
                ".........ww.....",
                "................"};
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int c = switch (px[y].charAt(x)) {
                    case 'b' -> 0xFF0C1E5C;
                    case 'B' -> 0xFF2453D6;
                    case 'L' -> 0xFF7FB2FF;
                    case 'y' -> 0xFFE8B33A;
                    case 'w' -> 0xFFEAF4FF;
                    case 't' -> 0xFFFFF4DC;
                    default -> 0;
                };
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    /** 16x16 icon: Dark Carnival catapult - striped base, spiral wheels, violet arm with a red bucket, jack-o'-lantern. */
    static BufferedImage ballistaItem() {
        String[] px = {
                "............kk..",
                "...........kRRk.",
                "..........kRRRk.",
                ".........kVkkk..",
                "........kVk.....",
                "...kk..kVk.okk..",
                "..kgg.kVk.koOok.",
                "..kgggVk..kooOk.",
                "...kgVk....kkk..",
                "...kVkV.....Vk..",
                "..kVk..V...Vk...",
                ".kVk....V.Vk....",
                "kRBRBRBRBRBRBRk.",
                "kBRBRBRBRBRBRBk.",
                ".kSSk.....kSSk..",
                "..kk.......kk..."};
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int c = switch (px[y].charAt(x)) {
                    case 'k' -> 0xFF14080F;
                    case 'R' -> 0xFFB01E3C;
                    case 'B' -> 0xFF2A1830;
                    case 'V' -> 0xFF6A3A84;
                    case 'g' -> 0xFFE8B33A;
                    case 'o' -> 0xFFFF7A10;
                    case 'O' -> 0xFFFFE070;
                    case 'S' -> 0xFFF6E2D2;
                    default -> 0;
                };
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    /**
     * Rocket hull skin, 32x32: u (x) runs once around the hull - x 16..31 is the top half (navy with red flame
     * waves), x 0..15 the bottom (blood red); v (y) runs from the tail (gold band) to the nose (glossy black).
     */
    static BufferedImage rocketSkin() {
        int n = 32;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(11);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int r, g, b;
                if (y < 3) {
                    boolean line = y == 2 || x % 4 == 0;
                    r = line ? 168 : 232; g = line ? 116 : 179; b = line ? 24 : 58;
                } else if (y >= 24 && y < 26) {
                    r = 200; g = 196; b = 210;
                } else if (y >= 26) {
                    boolean gloss = x >= 21 && x <= 24;
                    r = gloss ? 70 : 20; g = gloss ? 64 : 16; b = gloss ? 82 : 26;
                } else if (x == 0 || x == 15 || x == 16 || x == 31) {
                    r = 210; g = 200; b = 205;   // silver seam between the halves
                } else if (x > 16) {
                    double wave = y + 2.2 * Math.sin((x - 16) / 15.0 * Math.PI * 2.0);
                    boolean flame = y > 6 && y < 22 && ((int) Math.floor(wave)) % 6 < 2;
                    if (flame) { r = 176; g = 26; b = 40; } else { r = 36; g = 27; b = 74; }
                } else {
                    int shade = Math.abs(x - 8) > 5 ? -18 : 0;
                    r = 154 + shade; g = 20; b = 36;
                }
                int nz = rnd.nextInt(9) - 4;
                img.setRGB(x, y, argb(255, r + nz, g + nz, b + nz));
            }
        }
        return img;
    }

    /** 16x16 icon: a navy-and-red rocket with a black nose and fire, a gold bulb arm under it. */
    static BufferedImage rocketItem() {
        String[] px = {
                "...........kk...",
                "..........kKKk..",
                ".........kNNKk..",
                "...kk...kNNNk...",
                "..kNNk.kNNRNk...",
                "...kNNkNNRNk....",
                "....kNNNRRk.....",
                "....kRRRRk......",
                "...kRRRRk.......",
                "..kNkRRk........",
                ".ofkNkk.........",
                "offfk...........",
                ".ofo............",
                "gygygygygygyg...",
                "................",
                "................"};
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int c = switch (px[y].charAt(x)) {
                    case 'k' -> 0xFF14080F;
                    case 'K' -> 0xFF2A2630;
                    case 'N' -> 0xFF2C2266;
                    case 'R' -> 0xFFB01E3C;
                    case 'o' -> 0xFFFF7A10;
                    case 'f' -> 0xFFFFE070;
                    case 'g' -> 0xFFE8B33A;
                    case 'y' -> 0xFFC84BFF;
                    default -> 0;
                };
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    /** 16x16 inventory icon: red spiral disc, gold rim, coloured bulbs. */
    static BufferedImage item() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        double c = 8.0;
        int[] bulbs = {0xFFFF4040, 0xFFFFE040, 0xFF40FF70, 0xFF40B0FF, 0xFFD050FF, 0xFFFF9020};
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double dx = x + 0.5 - c, dy = y + 0.5 - c;
                double r = Math.sqrt(dx * dx + dy * dy);
                int col = 0;
                if (r <= 7.6) {
                    if (r > 6.4) col = 0xFF3A2208;            // outline
                    else if (r > 5.2) col = (x + y) % 2 == 0 ? 0xFFE8B33A : 0xFFC58D22; // gold rim
                    else {
                        double ang = Math.atan2(dy, dx) / (2 * Math.PI);
                        double s = r / 2.9 - ang;
                        double f = s - Math.floor(s);
                        col = (f < 0.38 && r > 0.8) ? 0xFFF6E2D2 : (r > 3.5 ? 0xFF8E1424 : 0xFFB8202F);
                    }
                }
                img.setRGB(x, y, col);
            }
        }
        // 8 bulbs on the rim
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + Math.PI / 8;
            int x = (int) Math.floor(c + Math.cos(a) * 5.8), y = (int) Math.floor(c + Math.sin(a) * 5.8);
            img.setRGB(x, y, bulbs[i % bulbs.length]);
        }
        img.setRGB(7, 7, 0xFFFFF0C0);
        return img;
    }
}
