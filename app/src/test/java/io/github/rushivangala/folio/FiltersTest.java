package io.github.rushivangala.folio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FiltersTest {

    /** A white page of text photographed with a shadow falling across the right half and a glare spot. */
    static Img shadowedPage() {
        int w = 800, h = 1000;
        Img im = TestImages.blank(w, h);
        TestImages.text(im, 60, 80, 24, 680, 0xff1e1e1e);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double light = x < 380 ? 0.95 : Math.max(0.45, 0.95 - (x - 380) / 600.0); // shadow
                double gx = x - 200, gy = y - 300;
                double glare = 70 * Math.exp(-(gx * gx + gy * gy) / (2 * 60.0 * 60.0)); // glare spot
                int c = im.px[y * w + x];
                im.px[y * w + x] = Img.rgb((int) (Img.r(c) * light * 0.97 + glare), (int) (Img.g(c) * light + glare), (int) (Img.b(c) * light * 0.9 + glare));
            }
        }
        return im;
    }

    /** Paper pixels: well away from the fake words. */
    static double paperSpread(Img im) {
        double min = 255, max = 0;
        int[][] spots = {{30, 30}, {770, 30}, {30, 970}, {770, 970}, {400, 960}, {760, 500}, {20, 500}};
        for (int[] s : spots) {
            double m = TestImages.meanLuma(im, s[0] - 8, s[1] - 8, s[0] + 8, s[1] + 8);
            min = Math.min(min, m);
            max = Math.max(max, m);
        }
        return max - min;
    }

    @Test
    public void flattenEvensOutShadows() {
        Img src = shadowedPage();
        assertTrue("shadowed input really is uneven: " + paperSpread(src), paperSpread(src) > 80);
        Img flat = Filters.flatten(src);
        assertTrue("paper evened out: " + paperSpread(flat), paperSpread(flat) < 18);
    }

    @Test
    public void textStaysDarkInsideTheShadow() {
        Img out = Filters.apply(shadowedPage(), Filters.GREY).img;
        // A word stroke in the shadowed right half (line 10, starting x around 700) and paper next to it.
        double ink = darkest(out, 600, 440, 740, 460);
        double paper = TestImages.meanLuma(out, 600, 462, 740, 470);
        assertTrue("ink " + ink, ink < 90);
        assertTrue("paper " + paper, paper > 225);
    }

    @Test
    public void blackAndWhiteIsBinaryAndKeepsText() {
        Img bw = Filters.apply(shadowedPage(), Filters.BW).img;
        int black = 0;
        for (int c : bw.px) {
            assertTrue(c == 0xff000000 || c == 0xffffffff);
            if (c == 0xff000000) black++;
        }
        double frac = (double) black / bw.px.length;
        assertTrue("some text, no shadow blotches: " + frac, frac > 0.03 && frac < 0.25);
        assertEquals(255, TestImages.meanLuma(bw, 760, 940, 795, 995), 1); // shadowed corner is clean white
    }

    @Test
    public void autoPicksGreyForBlackTextAndColourForColouredInk() {
        assertEquals(Filters.GREY, Filters.apply(shadowedPage(), Filters.AUTO).used);
        Img coloured = TestImages.blank(800, 1000);
        TestImages.text(coloured, 60, 80, 12, 680, 0xffcc2020);
        TestImages.text(coloured, 60, 520, 12, 680, 0xff2040cc);
        TestImages.fillRect(coloured, 500, 40, 760, 70, 0xff20a040);
        assertEquals(Filters.COLOUR, Filters.apply(coloured, Filters.AUTO).used);
    }

    @Test
    public void autoLeavesPhotographsAlone() {
        // A textured scene: foliage-like mid tones everywhere, no paper.
        Img photo = new Img(300, 200);
        java.util.Random rnd = new java.util.Random(7);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 300; x++) {
                int n = rnd.nextInt(90);
                photo.px[y * 300 + x] = Img.rgb(40 + n + x / 6, 70 + n + (y / 20) * 6, 30 + n / 2);
            }
        }
        Filters.Result r = Filters.apply(photo, Filters.AUTO);
        assertEquals(Filters.PHOTO, r.used);
        assertEquals(photo.px[1234], r.img.px[1234]);
    }

    @Test
    public void photoFilterReturnsACopy() {
        Img im = shadowedPage();
        Img out = Filters.apply(im, Filters.PHOTO).img;
        assertTrue(out != im);
        assertEquals(im.px[5000], out.px[5000]);
    }

    static double darkest(Img im, int x0, int y0, int x1, int y1) {
        int m = 255;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) m = Math.min(m, Img.luma(im.px[y * im.w + x]));
        return m;
    }
}
