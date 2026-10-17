package io.github.rushivangala.folio;

/** Helpers for tests: synthetic pages and JPEG encoding (TestJpeg). */
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

    static byte[] jpeg(Img im) {
        return TestJpeg.encode(im, false);
    }

    static byte[] jpegGrey(Img im) {
        return TestJpeg.encode(im, true);
    }

    static double meanLuma(Img im, int x0, int y0, int x1, int y1) {
        long s = 0, n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) { s += Img.luma(im.px[y * im.w + x]); n++; }
        return (double) s / n;
    }
}
