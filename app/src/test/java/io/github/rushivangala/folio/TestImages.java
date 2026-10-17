package io.github.rushivangala.folio;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

/** Helpers for tests: synthetic pages and JPEG encoding through the JVM's ImageIO. */
final class TestImages {
    private TestImages() {
    }

    static Img blank(int w, int h) {
        Img im = new Img(w, h);
        java.util.Arrays.fill(im.px, 0xffffffff);
        return im;
    }

    static void fillRect(Img im, int x0, int y0, int x1, int y1, int c) {
        for (int y = Math.max(0, y0); y < Math.min(im.h, y1); y++)
            for (int x = Math.max(0, x0); x < Math.min(im.w, x1); x++) im.px[y * im.w + x] = c;
    }

    /** Fake lines of text: dark bars with gaps, like words on a page. */
    static void text(Img im, int x0, int y0, int lines, int lineW, int ink) {
        for (int l = 0; l < lines; l++) {
            int y = y0 + l * 36;
            int x = x0;
            int seed = l * 7 + 3;
            while (x < x0 + lineW) {
                int wordW = 30 + (seed * 37 % 70);
                seed++;
                for (int c = x; c < Math.min(x + wordW, x0 + lineW); c += 9) fillRect(im, c, y, c + 5, y + 18, ink);
                x += wordW + 16;
            }
        }
    }

    static byte[] jpeg(Img im) throws Exception {
        BufferedImage b = new BufferedImage(im.w, im.h, BufferedImage.TYPE_INT_RGB);
        b.setRGB(0, 0, im.w, im.h, im.px, 0, im.w);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (!ImageIO.write(b, "jpg", o)) throw new IllegalStateException("no JPEG writer");
        return o.toByteArray();
    }

    static byte[] jpegGrey(Img im) throws Exception {
        BufferedImage b = new BufferedImage(im.w, im.h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < im.h; y++) for (int x = 0; x < im.w; x++) b.setRGB(x, y, im.px[y * im.w + x]);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (!ImageIO.write(b, "jpg", o)) throw new IllegalStateException("no JPEG writer");
        return o.toByteArray();
    }

    static double meanLuma(Img im, int x0, int y0, int x1, int y1) {
        long s = 0, n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) { s += Img.luma(im.px[y * im.w + x]); n++; }
        return (double) s / n;
    }
}
