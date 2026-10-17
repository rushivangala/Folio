package io.github.rushivangala.folio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImageTest {

    @Test
    public void homographyMapsCornersExactly() {
        double[] from = {0, 0, 99, 0, 99, 149, 0, 149};
        double[] to = {12, 30, 210, 8, 250, 300, 5, 260};
        double[] h = Warp.homography(from, to);
        for (int i = 0; i < 4; i++) {
            double[] p = Warp.apply(h, from[i * 2], from[i * 2 + 1]);
            assertEquals(to[i * 2], p[0], 1e-6);
            assertEquals(to[i * 2 + 1], p[1], 1e-6);
        }
    }

    @Test
    public void flattenRecoversAPageFromASkewedPhoto() {
        // A red page with a blue top-left mark, drawn skewed into a 400x400 photo on a grey table.
        int pw = 200, ph = 280;
        Img page = TestImages.blank(pw, ph);
        TestImages.fillRect(page, 0, 0, pw, ph, 0xffdd2222);
        TestImages.fillRect(page, 0, 0, 40, 40, 0xff2222dd);
        double[] quad = {60, 40, 330, 70, 350, 370, 40, 340};
        double[] h = Warp.homography(quad, new double[]{0, 0, pw - 1, 0, pw - 1, ph - 1, 0, ph - 1});
        Img photo = new Img(400, 400);
        for (int y = 0; y < 400; y++) {
            for (int x = 0; x < 400; x++) {
                double[] p = Warp.apply(h, x, y);
                boolean in = p[0] >= 0 && p[1] >= 0 && p[0] <= pw - 1 && p[1] <= ph - 1;
                photo.px[y * 400 + x] = in ? page.sample(p[0], p[1]) : 0xff808080;
            }
        }
        Img flat = Warp.flatten(photo, quad, 2000);
        assertTrue(flat.w > 250 && flat.h > 280);
        // Corners come back as the page's colours, not the table.
        int tl = flat.get(5, 5);
        assertTrue("top-left is the blue mark: " + Integer.toHexString(tl), Img.b(tl) > 180 && Img.r(tl) < 80);
        int br = flat.get(flat.w - 6, flat.h - 6);
        assertTrue(Img.r(br) > 180 && Img.b(br) < 80);
        int mark = flat.get(flat.w * 10 / 100, flat.h * 7 / 100);
        assertTrue("top-left mark is blue: " + Integer.toHexString(mark), Img.b(mark) > 180 && Img.r(mark) < 80);
    }

    @Test
    public void outputSizeIsCapped() {
        int[] s = Warp.outputSize(new double[]{0, 0, 3999, 0, 3999, 5999, 0, 5999}, 3000);
        assertArrayEquals(new int[]{2000, 3000}, s);
    }

    @Test
    public void quadValidity() {
        assertTrue(Warp.valid(Warp.full(100, 100)));
        assertFalse(Warp.valid(new double[]{0, 0, 99, 99, 99, 0, 0, 99})); // crossed
        assertFalse(Warp.valid(new double[]{0, 0, 0, 99, 99, 99, 99, 0})); // anticlockwise
        assertFalse(Warp.valid(new double[]{0, 0, 2, 0, 2, 2, 0, 2})); // tiny
        assertTrue(Warp.isFull(Warp.full(50, 60), 50, 60));
    }

    @Test
    public void rotateQuarterTurns() {
        Img im = new Img(3, 2, new int[]{1, 2, 3, 4, 5, 6});
        Img r = im.rotate(1);
        assertEquals(2, r.w);
        assertEquals(3, r.h);
        assertArrayEquals(new int[]{4, 1, 5, 2, 6, 3}, r.px);
        assertArrayEquals(im.px, im.rotate(4).px);
        assertArrayEquals(new int[]{6, 5, 4, 3, 2, 1}, im.rotate(2).px);
        assertArrayEquals(im.px, r.rotate(3).px);
    }

    @Test
    public void fitDownscalesLongSide() {
        Img im = TestImages.blank(4000, 3000);
        Img f = im.fit(1000);
        assertEquals(1000, f.w);
        assertEquals(750, f.h);
        assertTrue(im.fit(5000) == im);
    }

    @Test
    public void jpegInfoReadsFrameHeader() throws Exception {
        JpegInfo info = JpegInfo.read(TestImages.jpeg(TestImages.blank(123, 45)));
        assertEquals(123, info.width);
        assertEquals(45, info.height);
        assertEquals(3, info.components);
    }

    @Test(expected = java.io.IOException.class)
    public void jpegInfoRejectsOtherData() throws Exception {
        JpegInfo.read(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0});
    }
}
