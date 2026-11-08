import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Finds the largest block size b such that the texture consists of uniform b x b cells; optionally writes the downscaled copy. */
public class TexBlocks {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        int w = img.getWidth(), h = img.getHeight();
        for (int b = 64; b >= 1; b /= 2) {
            if (w % b != 0 || h % b != 0) continue;
            long bad = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
                if (img.getRGB(x, y) != img.getRGB(x / b * b, y / b * b)) bad++;
            System.out.printf("block %d: mismatching pixels %d (%.3f%%)%n", b, bad, 100.0 * bad / (w * h));
            if (bad == 0 || (a.length > 2 && b == Integer.parseInt(a[2]))) {
                if (a.length > 1) {
                    BufferedImage o = new BufferedImage(w / b, h / b, BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < h / b; y++) for (int x = 0; x < w / b; x++) o.setRGB(x, y, img.getRGB(x * b + b / 2, y * b + b / 2));
                    ImageIO.write(o, "png", new File(a[1]));
                    System.out.println("written " + (w / b) + "x" + (h / b));
                }
                return;
            }
        }
    }
}
