package io.github.rushivangala.folio;

/**
 * Perspective flattening: maps the page outline (four corners in the photo) onto a rectangle.
 * Corners are given as {x0,y0, x1,y1, x2,y2, x3,y3} in the order top-left, top-right,
 * bottom-right, bottom-left, in source pixels. Pure Java.
 */
public final class Warp {
    private Warp() {
    }

    /** Output size for a quad: the longer of each pair of opposite edges, capped at maxSide. */
    public static int[] outputSize(double[] q, int maxSide) {
        double top = dist(q[0], q[1], q[2], q[3]), bottom = dist(q[6], q[7], q[4], q[5]);
        double left = dist(q[0], q[1], q[6], q[7]), right = dist(q[2], q[3], q[4], q[5]);
        double w = Math.max(top, bottom), h = Math.max(left, right);
        double s = Math.min(1.0, maxSide / Math.max(w, h));
        return new int[]{Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s))};
    }

    public static Img flatten(Img src, double[] quad, int maxSide) {
        int[] size = outputSize(quad, maxSide);
        int ow = size[0], oh = size[1];
        // Homography from the output rectangle to the source quad (dst -> src), so each output pixel samples the photo.
        double[] hm = homography(new double[]{0, 0, ow - 1, 0, ow - 1, oh - 1, 0, oh - 1}, quad);
        Img out = new Img(ow, oh);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                double d = hm[6] * x + hm[7] * y + 1;
                double sx = (hm[0] * x + hm[1] * y + hm[2]) / d;
                double sy = (hm[3] * x + hm[4] * y + hm[5]) / d;
                out.px[y * ow + x] = src.sample(sx, sy);
            }
        }
        return out;
    }

    /** True when the quad is the whole image (no flattening needed). */
    public static boolean isFull(double[] q, int w, int h) {
        double e = 0.5;
        return near(q[0], 0, e) && near(q[1], 0, e) && near(q[2], w - 1, e) && near(q[3], 0, e)
                && near(q[4], w - 1, e) && near(q[5], h - 1, e) && near(q[6], 0, e) && near(q[7], h - 1, e);
    }

    public static double[] full(int w, int h) {
        return new double[]{0, 0, w - 1, 0, w - 1, h - 1, 0, h - 1};
    }

    /** A quad is usable when it is convex, wound clockwise and not tiny. */
    public static boolean valid(double[] q) {
        if (q == null || q.length != 8) return false;
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) % 4) * 2, c = ((i + 2) % 4) * 2;
            double cross = (q[b] - q[a]) * (q[c + 1] - q[b + 1]) - (q[b + 1] - q[a + 1]) * (q[c] - q[b]);
            int s = cross > 0 ? 1 : cross < 0 ? -1 : 0;
            if (s == 0) return false;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return sign > 0 && area(q) > 64;
    }

    static double area(double[] q) {
        double a = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1];
        }
        return Math.abs(a) / 2;
    }

    /**
     * Solves the 8 coefficients of the projective map taking points `from` to `to`:
     * x' = (h0 x + h1 y + h2) / (h6 x + h7 y + 1), y' = (h3 x + h4 y + h5) / (h6 x + h7 y + 1).
     */
    public static double[] homography(double[] from, double[] to) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = from[i * 2], y = from[i * 2 + 1], u = to[i * 2], v = to[i * 2 + 1];
            a[i * 2] = new double[]{x, y, 1, 0, 0, 0, -x * u, -y * u, u};
            a[i * 2 + 1] = new double[]{0, 0, 0, x, y, 1, -x * v, -y * v, v};
        }
        // Gaussian elimination with partial pivoting.
        for (int c = 0; c < 8; c++) {
            int p = c;
            for (int r = c + 1; r < 8; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
            double[] t = a[c];
            a[c] = a[p];
            a[p] = t;
            if (Math.abs(a[c][c]) < 1e-12) throw new IllegalArgumentException("degenerate quad");
            for (int r = 0; r < 8; r++) {
                if (r == c) continue;
                double f = a[r][c] / a[c][c];
                if (f == 0) continue;
                for (int k = c; k < 9; k++) a[r][k] -= f * a[c][k];
            }
        }
        double[] h = new double[8];
        for (int i = 0; i < 8; i++) h[i] = a[i][8] / a[i][i];
        return h;
    }

    static double[] apply(double[] hm, double x, double y) {
        double d = hm[6] * x + hm[7] * y + 1;
        return new double[]{(hm[0] * x + hm[1] * y + hm[2]) / d, (hm[3] * x + hm[4] * y + hm[5]) / d};
    }

    private static double dist(double ax, double ay, double bx, double by) {
        return Math.hypot(bx - ax, by - ay);
    }

    private static boolean near(double a, double b, double e) {
        return Math.abs(a - b) <= e;
    }
}
