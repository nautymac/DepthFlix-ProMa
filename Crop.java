import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 스크린샷 일부를 잘라 확대 저장. 아티팩트를 눈으로 확인하기 위한 도구. */
public class Crop {
    public static void main(String[] a) throws Exception {
        String in = a[0], out = a[1];
        int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]);
        int w = Integer.parseInt(a[4]), h = Integer.parseInt(a[5]);
        int scale = a.length > 6 ? Integer.parseInt(a[6]) : 2;

        BufferedImage src = ImageIO.read(new File(in));
        x = Math.max(0, Math.min(x, src.getWidth() - 1));
        y = Math.max(0, Math.min(y, src.getHeight() - 1));
        w = Math.min(w, src.getWidth() - x);
        h = Math.min(h, src.getHeight() - y);

        BufferedImage dst = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < h * scale; j++) {
            for (int i = 0; i < w * scale; i++) {
                dst.setRGB(i, j, src.getRGB(x + i / scale, y + j / scale));
            }
        }
        ImageIO.write(dst, "png", new File(out));
        System.out.println("saved " + out + "  " + (w * scale) + "x" + (h * scale));
    }
}
