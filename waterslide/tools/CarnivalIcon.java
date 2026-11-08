import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 16x16 icon of the Dark Carnival slide (run: java tools/CarnivalIcon.java <textures/item dir>). */
public class CarnivalIcon {
    public static void main(String[] args) throws Exception {
        String[] px = {
                ".y.y.y..........",
                "kRkRkRk.........",
                "kmmmmmk.........",
                ".kPPPk..........",
                "..kPPPk...g.....",
                "...kPPPk.gRg....",
                "....kPPPkgRg....",
                ".....kPPPkg..o..",
                "......kPPPk.ofo.",
                ".......kPRRk.o..",
                "........kPPPk...",
                ".........kRRRk..",
                "..........kPPPkk",
                "...........kwwwk",
                "............kkk.",
                "................"};
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int c = switch (px[y].charAt(x)) {
                    case 'k' -> 0xFF14080F;
                    case 'R' -> 0xFF8E1424;
                    case 'P' -> 0xFF6A3A94;
                    case 'm' -> 0xFF9A7650;
                    case 'y' -> 0xFFFFD93B;
                    case 'g' -> 0xFFE8B33A;
                    case 'o' -> 0xFFFF7A10;
                    case 'f' -> 0xFFFFE070;
                    case 'w' -> 0xFFC8A0FF;
                    default -> 0;
                };
                img.setRGB(x, y, c);
            }
        }
        ImageIO.write(img, "png", new File(args[0], "dark_carnival_slide.png"));
        System.out.println("icon written");
    }
}
