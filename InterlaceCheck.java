import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 스크린샷에 렌티큘러 인터레이스가 걸려 있는지 측정한다.
 *
 * 인터레이스 출력은 좌/우 뷰가 열 단위로 번갈아 짜여 있어서 "인접 열 차이"가 크고,
 * 인접 행 차이는 그대로다. 따라서 (열 차이 / 행 차이) 비율이 1보다 뚜렷하게 크면 3D 가 걸린 것.
 * 평범한 2D 화면은 이 비율이 1 근처다.
 */
public class InterlaceCheck {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            BufferedImage img = ImageIO.read(new File(path));
            int w = img.getWidth(), h = img.getHeight();

            // 화면 중앙부만 본다 (UI 오버레이/레터박스 제외)
            int x0 = w / 6, x1 = w / 2;          // 좌측 절반 안쪽 — 설정 패널을 피한다
            int y0 = h / 5, y1 = h * 3 / 5;

            double colDiff = 0, rowDiff = 0;
            long n = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1 - 1; x++) {
                    int a = img.getRGB(x, y), b = img.getRGB(x + 1, y), c = img.getRGB(x, y + 1);
                    colDiff += lumaDiff(a, b);
                    rowDiff += lumaDiff(a, c);
                    n++;
                }
            }
            double col = colDiff / n, row = rowDiff / n;
            System.out.printf("%-24s colDiff=%6.2f rowDiff=%6.2f ratio=%5.2f  %s%n",
                    new File(path).getName(), col, row, col / row,
                    (col / row > 1.35) ? "INTERLACED (3D)" : "FLAT (2D)");
        }
    }

    private static double lumaDiff(int p, int q) {
        int pr = (p >> 16) & 255, pg = (p >> 8) & 255, pb = p & 255;
        int qr = (q >> 16) & 255, qg = (q >> 8) & 255, qb = q & 255;
        double lp = 0.299 * pr + 0.587 * pg + 0.114 * pb;
        double lq = 0.299 * qr + 0.587 * qg + 0.114 * qb;
        return Math.abs(lp - lq);
    }
}
