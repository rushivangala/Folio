package io.github.rushivangala.folio;

import java.io.ByteArrayOutputStream;

/**
 * A small baseline JPEG encoder for tests (Android's unit-test classpath has no ImageIO).
 * Standard luminance/chrominance tables at quality 90, 4:4:4 colour or 1-component greyscale.
 */
final class TestJpeg {
    private TestJpeg() {
    }

    private static final int[] ZZ = {0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21,
            28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};
    private static final int[] QL = {16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};
    private static final int[] QC = {17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99};
    // Standard Huffman tables (ITU T.81 Annex K): code lengths then values.
    private static final int[] DC_L_BITS = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] DC_L_VALS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] DC_C_BITS = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
    private static final int[] AC_L_BITS = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
    private static final int[] AC_L_VALS = {0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08,
            0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x34, 0x35, 0x36,
            0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73,
            0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6,
            0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8,
            0xd9, 0xda, 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa};
    private static final int[] AC_C_BITS = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
    private static final int[] AC_C_VALS = {0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
            0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x35,
            0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a,
            0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4,
            0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6,
            0xd7, 0xd8, 0xd9, 0xda, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa};

    /** Code and length per symbol, built from bits/vals as in Annex C. */
    private static int[][] huff(int[] bits, int[] vals) {
        int[][] t = new int[256][];
        int code = 0, k = 0;
        for (int len = 1; len <= 16; len++) {
            for (int i = 0; i < bits[len - 1]; i++) t[vals[k++]] = new int[]{code++, len};
            code <<= 1;
        }
        return t;
    }

    private static final class Bits {
        final ByteArrayOutputStream o;
        int acc, n;

        Bits(ByteArrayOutputStream o) {
            this.o = o;
        }

        void put(int code, int len) {
            for (int i = len - 1; i >= 0; i--) {
                acc = (acc << 1) | ((code >> i) & 1);
                if (++n == 8) {
                    o.write(acc);
                    if (acc == 0xFF) o.write(0);
                    acc = 0;
                    n = 0;
                }
            }
        }

        void flush() {
            while (n != 0) put(1, 1);
        }
    }

    static byte[] encode(Img im, boolean grey) {
        int quality = 90;
        int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
        int[] ql = new int[64], qc = new int[64];
        for (int i = 0; i < 64; i++) {
            ql[i] = Math.max(1, Math.min(255, (QL[i] * scale + 50) / 100));
            qc[i] = Math.max(1, Math.min(255, (QC[i] * scale + 50) / 100));
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF); o.write(0xD8);
        int nc = grey ? 1 : 3;
        // DQT
        o.write(0xFF); o.write(0xDB);
        int dqtLen = 2 + (grey ? 65 : 130);
        o.write(dqtLen >> 8); o.write(dqtLen & 0xff);
        o.write(0);
        for (int i = 0; i < 64; i++) o.write(ql[ZZ[i]]);
        if (!grey) { o.write(1); for (int i = 0; i < 64; i++) o.write(qc[ZZ[i]]); }
        // SOF0
        o.write(0xFF); o.write(0xC0);
        int sofLen = 8 + 3 * nc;
        o.write(sofLen >> 8); o.write(sofLen & 0xff);
        o.write(8);
        o.write(im.h >> 8); o.write(im.h & 0xff);
        o.write(im.w >> 8); o.write(im.w & 0xff);
        o.write(nc);
        for (int c = 0; c < nc; c++) { o.write(c + 1); o.write(0x11); o.write(c == 0 ? 0 : 1); }
        // DHT
        writeDht(o, 0x00, DC_L_BITS, DC_L_VALS);
        writeDht(o, 0x10, AC_L_BITS, AC_L_VALS);
        if (!grey) { writeDht(o, 0x01, DC_C_BITS, DC_L_VALS); writeDht(o, 0x11, AC_C_BITS, AC_C_VALS); }
        // SOS
        o.write(0xFF); o.write(0xDA);
        int sosLen = 6 + 2 * nc;
        o.write(sosLen >> 8); o.write(sosLen & 0xff);
        o.write(nc);
        for (int c = 0; c < nc; c++) { o.write(c + 1); o.write(c == 0 ? 0x00 : 0x11); }
        o.write(0); o.write(63); o.write(0);

        int[][] dcL = huff(DC_L_BITS, DC_L_VALS), acL = huff(AC_L_BITS, AC_L_VALS);
        int[][] dcC = huff(DC_C_BITS, DC_L_VALS), acC = huff(AC_C_BITS, AC_C_VALS);
        Bits b = new Bits(o);
        int[] prev = new int[3];
        double[] blk = new double[64];
        for (int by = 0; by < im.h; by += 8) {
            for (int bx = 0; bx < im.w; bx += 8) {
                for (int c = 0; c < nc; c++) {
                    for (int y = 0; y < 8; y++) {
                        for (int x = 0; x < 8; x++) {
                            int px = im.get(Math.min(bx + x, im.w - 1), Math.min(by + y, im.h - 1));
                            int r = Img.r(px), g = Img.g(px), bl = Img.b(px);
                            double v;
                            if (c == 0) v = 0.299 * r + 0.587 * g + 0.114 * bl;
                            else if (c == 1) v = -0.168736 * r - 0.331264 * g + 0.5 * bl + 128;
                            else v = 0.5 * r - 0.418688 * g - 0.081312 * bl + 128;
                            blk[y * 8 + x] = v - 128;
                        }
                    }
                    int[] q = fdctQuant(blk, c == 0 ? ql : qc);
                    prev[c] = block(b, q, prev[c], c == 0 ? dcL : dcC, c == 0 ? acL : acC);
                }
            }
        }
        b.flush();
        o.write(0xFF); o.write(0xD9);
        return o.toByteArray();
    }

    private static void writeDht(ByteArrayOutputStream o, int cls, int[] bits, int[] vals) {
        int n = 0;
        for (int x : bits) n += x;
        o.write(0xFF); o.write(0xC4);
        int len = 3 + 16 + n;
        o.write(len >> 8); o.write(len & 0xff);
        o.write(cls);
        for (int x : bits) o.write(x);
        for (int i = 0; i < n; i++) o.write(vals[i]);
    }

    private static final double[] COS = new double[64];

    static {
        for (int x = 0; x < 8; x++) for (int u = 0; u < 8; u++) COS[x * 8 + u] = Math.cos((2 * x + 1) * u * Math.PI / 16);
    }

    private static int[] fdctQuant(double[] blk, int[] q) {
        int[] out = new int[64];
        double[] rows = new double[64];
        // Separable: transform rows, then columns.
        for (int y = 0; y < 8; y++)
            for (int u = 0; u < 8; u++) {
                double s = 0;
                for (int x = 0; x < 8; x++) s += blk[y * 8 + x] * COS[x * 8 + u];
                rows[y * 8 + u] = s;
            }
        for (int v = 0; v < 8; v++) {
            for (int u = 0; u < 8; u++) {
                double s = 0;
                for (int y = 0; y < 8; y++) s += rows[y * 8 + u] * COS[y * 8 + v];
                double cu = u == 0 ? Math.sqrt(0.5) : 1, cv = v == 0 ? Math.sqrt(0.5) : 1;
                out[v * 8 + u] = (int) Math.round(0.25 * cu * cv * s / q[v * 8 + u]);
            }
        }
        return out;
    }

    private static int block(Bits b, int[] q, int prevDc, int[][] dc, int[][] ac) {
        int diff = q[0] - prevDc;
        int cat = category(diff);
        b.put(dc[cat][0], dc[cat][1]);
        if (cat > 0) b.put(bitsOf(diff, cat), cat);
        int run = 0;
        for (int k = 1; k < 64; k++) {
            int v = q[ZZ[k]];
            if (v == 0) { run++; continue; }
            while (run > 15) { b.put(ac[0xF0][0], ac[0xF0][1]); run -= 16; }
            int c = category(v);
            int sym = (run << 4) | c;
            b.put(ac[sym][0], ac[sym][1]);
            b.put(bitsOf(v, c), c);
            run = 0;
        }
        if (run > 0) b.put(ac[0x00][0], ac[0x00][1]);
        return q[0];
    }

    private static int category(int v) {
        int a = Math.abs(v), c = 0;
        while (a > 0) { a >>= 1; c++; }
        return c;
    }

    private static int bitsOf(int v, int cat) {
        return v >= 0 ? v : v + (1 << cat) - 1;
    }
}
