package io.github.rushivangala.folio;

/**
 * A plain ARGB pixel buffer. Folio's image work (warp, filters, page finding) runs on this so it is
 * pure Java: no android.* imports, unit-tested on the JVM. MainActivity converts to and from Bitmap.
 */
public final class Img {
    public final int w, h;
    public final int[] px;

    public Img(int w, int h) {
        this(w, h, new int[w * h]);
    }

    public Img(int w, int h, int[] px) {
        if (w <= 0 || h <= 0 || px.length < w * h) throw new IllegalArgumentException("bad image " + w + "x" + h);
        this.w = w;
        this.h = h;
        this.px = px;
    }

    public int get(int x, int y) {
        return px[y * w + x];
    }

    public static int r(int c) {
        return (c >> 16) & 0xff;
    }

    public static int g(int c) {
        return (c >> 8) & 0xff;
    }

    public static int b(int c) {
        return c & 0xff;
    }

    public static int rgb(int r, int g, int b) {
        return 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    public static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    /** Rec. 601 luma, 0..255. */
    public static int luma(int c) {
        return (r(c) * 299 + g(c) * 587 + b(c) * 114) / 1000;
    }

    public Img copy() {
        return new Img(w, h, px.clone());
    }

    /** Rotates clockwise by quarter turns (0..3). */
    public Img rotate(int quarters) {
        int q = ((quarters % 4) + 4) % 4;
        if (q == 0) return copy();
        Img o = q == 2 ? new Img(w, h) : new Img(h, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int nx, ny;
                if (q == 1) { nx = h - 1 - y; ny = x; }
                else if (q == 2) { nx = w - 1 - x; ny = h - 1 - y; }
                else { nx = y; ny = w - 1 - x; }
                o.px[ny * o.w + nx] = c;
            }
        }
        return o;
    }

    /** Box-filter downscale so the longer side is at most maxSide (never upscales). */
    public Img fit(int maxSide) {
        int side = Math.max(w, h);
        if (side <= maxSide) return this;
        double s = (double) maxSide / side;
        int nw = Math.max(1, (int) Math.round(w * s)), nh = Math.max(1, (int) Math.round(h * s));
        Img o = new Img(nw, nh);
        for (int y = 0; y < nh; y++) {
            int y0 = y * h / nh, y1 = Math.max(y0 + 1, (y + 1) * h / nh);
            for (int x = 0; x < nw; x++) {
                int x0 = x * w / nw, x1 = Math.max(x0 + 1, (x + 1) * w / nw);
                long sr = 0, sg = 0, sb = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    int row = yy * w;
                    for (int xx = x0; xx < x1; xx++) {
                        int c = px[row + xx];
                        sr += r(c);
                        sg += g(c);
                        sb += b(c);
                        n++;
                    }
                }
                o.px[y * nw + x] = rgb((int) (sr / n), (int) (sg / n), (int) (sb / n));
            }
        }
        return o;
    }

    /** Bilinear sample at a fractional position; outside the image the edge pixel is used. */
    public int sample(double fx, double fy) {
        if (fx < 0) fx = 0;
        if (fy < 0) fy = 0;
        if (fx > w - 1) fx = w - 1;
        if (fy > h - 1) fy = h - 1;
        int x0 = (int) fx, y0 = (int) fy;
        int x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
        double ax = fx - x0, ay = fy - y0;
        int c00 = px[y0 * w + x0], c10 = px[y0 * w + x1], c01 = px[y1 * w + x0], c11 = px[y1 * w + x1];
        return rgb(lerp2(r(c00), r(c10), r(c01), r(c11), ax, ay),
                lerp2(g(c00), g(c10), g(c01), g(c11), ax, ay),
                lerp2(b(c00), b(c10), b(c01), b(c11), ax, ay));
    }

    private static int lerp2(int a, int b, int c, int d, double ax, double ay) {
        double top = a + (b - a) * ax, bot = c + (d - c) * ax;
        return (int) Math.round(top + (bot - top) * ay);
    }
}
