package io.github.rushivangala.folio;

/**
 * Scan filters. Every document filter first evens out the paper: it estimates the paper colour
 * across the page (which carries shadows, uneven light and glare) and divides it out, so the page
 * becomes evenly white and the ink keeps its colour. Pure Java.
 */
public final class Filters {
    public static final String AUTO = "auto", COLOUR = "colour", GREY = "grey", BW = "bw", PHOTO = "photo";
    public static final String[] ALL = {AUTO, COLOUR, GREY, BW, PHOTO};

    private Filters() {
    }

    /** Result of a filter: the image and, for Auto, which filter it chose. */
    public static final class Result {
        public final Img img;
        public final String used;

        Result(Img img, String used) {
            this.img = img;
            this.used = used;
        }
    }

    public static Result apply(Img src, String filter) {
        if (PHOTO.equals(filter)) return new Result(src.copy(), PHOTO);
        Img flat = flatten(src);
        String f = filter;
        if (AUTO.equals(f) || f == null) f = choose(src, flat);
        if (PHOTO.equals(f)) return new Result(src.copy(), PHOTO);
        if (BW.equals(f)) return new Result(blackAndWhite(flat), BW);
        if (GREY.equals(f)) return new Result(ink(grey(flat), 1.35), GREY);
        return new Result(saturate(ink(flat, 1.25), 1.2), COLOUR);
    }

    /** Auto: photographs stay as they are; documents become colour or greyscale depending on their ink. */
    static String choose(Img src, Img flat) {
        int[] p = flat.px;
        int step = Math.max(1, p.length / 40000);
        long n = 0, paper = 0;
        double sumRg = 0, sumYb = 0, sumRg2 = 0, sumYb2 = 0;
        int inked = 0;
        for (int i = 0; i < p.length; i += step) {
            int c = p[i];
            n++;
            if (Img.luma(c) >= 230) paper++;
            if (Img.luma(c) > 235) continue;
            int r = Img.r(c), g = Img.g(c), b = Img.b(c);
            double rg = r - g, yb = 0.5 * (r + g) - b;
            sumRg += rg;
            sumYb += yb;
            sumRg2 += rg * rg;
            sumYb2 += yb * yb;
            inked++;
        }
        // A page is mostly blank paper once flattened; a photograph keeps its texture and mid tones.
        if (n > 0 && paper < n * 0.35) return PHOTO;
        if (inked < 50) return GREY;
        double mRg = sumRg / inked, mYb = sumYb / inked;
        double sRg = Math.sqrt(Math.max(0, sumRg2 / inked - mRg * mRg)), sYb = Math.sqrt(Math.max(0, sumYb2 / inked - mYb * mYb));
        double colourfulness = Math.sqrt(sRg * sRg + sYb * sYb) + 0.3 * Math.sqrt(mRg * mRg + mYb * mYb);
        return colourfulness > 22 ? COLOUR : GREY;
    }

    // ---------------------------------------------------------------- paper flattening (shadow and glare)

    /** Divides out the paper colour so the page reads evenly white. */
    public static Img flatten(Img src) {
        int cell = Math.max(8, Math.min(src.w, src.h) / 32);
        int gw = (src.w + cell - 1) / cell, gh = (src.h + cell - 1) / cell;
        float[][] bg = new float[3][gw * gh];
        int[] hist = new int[256];
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int x0 = gx * cell, y0 = gy * cell, x1 = Math.min(src.w, x0 + cell), y1 = Math.min(src.h, y0 + cell);
                java.util.Arrays.fill(hist, 0);
                int count = 0;
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) { hist[Img.luma(src.px[y * src.w + x])]++; count++; }
                // The paper is the bright part of each cell: average the pixels at or above its 85th percentile.
                int target = count - Math.max(1, count * 15 / 100), acc = 0, cut = 255;
                for (int v = 0; v < 256; v++) { acc += hist[v]; if (acc >= target) { cut = v; break; } }
                long sr = 0, sg = 0, sb = 0;
                int k = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int c = src.px[y * src.w + x];
                        if (Img.luma(c) >= cut) { sr += Img.r(c); sg += Img.g(c); sb += Img.b(c); k++; }
                    }
                }
                int i = gy * gw + gx;
                bg[0][i] = k == 0 ? 255 : (float) sr / k;
                bg[1][i] = k == 0 ? 255 : (float) sg / k;
                bg[2][i] = k == 0 ? 255 : (float) sb / k;
            }
        }
        // Cells full of ink (a big heading, a dark logo) read too dark: let brighter neighbours win, then smooth.
        for (int ch = 0; ch < 3; ch++) {
            bg[ch] = smooth(max3(bg[ch], gw, gh), gw, gh);
            bg[ch] = smooth(bg[ch], gw, gh);
        }
        Img out = new Img(src.w, src.h);
        for (int y = 0; y < src.h; y++) {
            double fy = (y + 0.5) / cell - 0.5;
            for (int x = 0; x < src.w; x++) {
                double fx = (x + 0.5) / cell - 0.5;
                int c = src.px[y * src.w + x];
                int r = divide(Img.r(c), bilerp(bg[0], gw, gh, fx, fy));
                int g = divide(Img.g(c), bilerp(bg[1], gw, gh, fx, fy));
                int b = divide(Img.b(c), bilerp(bg[2], gw, gh, fx, fy));
                out.px[y * src.w + x] = Img.rgb(r, g, b);
            }
        }
        return out;
    }

    private static int divide(int v, double bg) {
        if (bg < 24) bg = 24; // a black border must not blow up to white noise
        return (int) Math.round(Math.min(255.0, v * 255.0 / bg));
    }

    private static float[] max3(float[] a, int w, int h) {
        float[] o = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float m = a[y * w + x], self = m;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                        m = Math.max(m, a[yy * w + xx]);
                    }
                }
                // Only lift cells that are clearly darker than their surroundings (ink, not a real shadow edge).
                o[y * w + x] = self < m * 0.8f ? m * 0.9f + self * 0.1f : self;
            }
        }
        return o;
    }

    private static float[] smooth(float[] a, int w, int h) {
        float[] o = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                        float wt = (dx == 0 && dy == 0) ? 4 : (dx == 0 || dy == 0) ? 2 : 1;
                        s += a[yy * w + xx] * wt;
                        n += wt;
                    }
                }
                o[y * w + x] = s / n;
            }
        }
        return o;
    }

    private static double bilerp(float[] a, int w, int h, double fx, double fy) {
        if (fx < 0) fx = 0;
        if (fy < 0) fy = 0;
        if (fx > w - 1) fx = w - 1;
        if (fy > h - 1) fy = h - 1;
        int x0 = (int) fx, y0 = (int) fy, x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
        double ax = fx - x0, ay = fy - y0;
        double top = a[y0 * w + x0] + (a[y0 * w + x1] - a[y0 * w + x0]) * ax;
        double bot = a[y1 * w + x0] + (a[y1 * w + x1] - a[y1 * w + x0]) * ax;
        return top + (bot - top) * ay;
    }

    // ---------------------------------------------------------------- tone

    /** Darkens ink while keeping white paper white: v' = 255 - (255 - v) * gain. */
    static Img ink(Img src, double gain) {
        Img o = new Img(src.w, src.h);
        for (int i = 0; i < o.px.length; i++) {
            int c = src.px[i];
            o.px[i] = Img.rgb(inkCh(Img.r(c), gain), inkCh(Img.g(c), gain), inkCh(Img.b(c), gain));
        }
        return o;
    }

    private static int inkCh(int v, double gain) {
        // Paper that is nearly white becomes pure white (with a short ramp, so there is no visible edge).
        if (v >= 234) return 255;
        double d = (255 - v) * gain;
        if (v > 214) d *= (234 - v) / 20.0;
        return (int) Math.round(255 - d);
    }

    static Img grey(Img src) {
        Img o = new Img(src.w, src.h);
        for (int i = 0; i < o.px.length; i++) {
            int l = Img.luma(src.px[i]);
            o.px[i] = Img.rgb(l, l, l);
        }
        return o;
    }

    static Img saturate(Img src, double s) {
        Img o = new Img(src.w, src.h);
        for (int i = 0; i < o.px.length; i++) {
            int c = src.px[i], l = Img.luma(c);
            o.px[i] = Img.rgb((int) Math.round(l + (Img.r(c) - l) * s), (int) Math.round(l + (Img.g(c) - l) * s), (int) Math.round(l + (Img.b(c) - l) * s));
        }
        return o;
    }

    /** Crisp black text on white: Sauvola's local threshold over the flattened page. */
    static Img blackAndWhite(Img flat) {
        int w = flat.w, h = flat.h;
        int[] l = new int[w * h];
        for (int i = 0; i < l.length; i++) l[i] = Img.luma(flat.px[i]);
        long[] sum = new long[(w + 1) * (h + 1)], sq = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long rs = 0, rq = 0;
            for (int x = 0; x < w; x++) {
                int v = l[y * w + x];
                rs += v;
                rq += (long) v * v;
                sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + rs;
                sq[(y + 1) * (w + 1) + x + 1] = sq[y * (w + 1) + x + 1] + rq;
            }
        }
        int rad = Math.max(7, Math.min(w, h) / 40);
        Img o = new Img(w, h);
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - rad), y1 = Math.min(h, y + rad + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - rad), x1 = Math.min(w, x + rad + 1);
                long n = (long) (x1 - x0) * (y1 - y0);
                long s = sum[y1 * (w + 1) + x1] - sum[y0 * (w + 1) + x1] - sum[y1 * (w + 1) + x0] + sum[y0 * (w + 1) + x0];
                long q = sq[y1 * (w + 1) + x1] - sq[y0 * (w + 1) + x1] - sq[y1 * (w + 1) + x0] + sq[y0 * (w + 1) + x0];
                double mean = (double) s / n, sd = Math.sqrt(Math.max(0, (double) q / n - mean * mean));
                double t = mean * (1 + 0.2 * (sd / 128.0 - 1));
                int v = l[y * w + x];
                boolean black = v < t && v < 225 || v < 90;
                o.px[y * w + x] = black ? 0xff000000 : 0xffffffff;
            }
        }
        return o;
    }
}
