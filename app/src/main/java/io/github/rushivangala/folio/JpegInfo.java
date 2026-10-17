package io.github.rushivangala.folio;

import java.io.IOException;

/** Reads a JPEG's size and number of colour components from its frame header. Pure Java. */
public final class JpegInfo {
    public final int width, height, components;
    /** An Adobe APP14 marker is present (CMYK JPEGs from Adobe tools store inverted values). */
    public final boolean adobe;

    JpegInfo(int width, int height, int components, boolean adobe) {
        this.width = width;
        this.height = height;
        this.components = components;
        this.adobe = adobe;
    }

    public static JpegInfo read(byte[] b) throws IOException {
        if (b == null || b.length < 4 || (b[0] & 0xff) != 0xFF || (b[1] & 0xff) != 0xD8) throw new IOException("not a JPEG");
        int i = 2;
        boolean adobe = false;
        while (i + 4 <= b.length) {
            if ((b[i] & 0xff) != 0xFF) throw new IOException("bad JPEG marker at " + i);
            int m = b[i + 1] & 0xff;
            if (m == 0xFF) { i++; continue; }          // fill byte
            if (m == 0xD8 || (m >= 0xD0 && m <= 0xD7) || m == 0x01) { i += 2; continue; }
            if (m == 0xD9 || m == 0xDA) break;         // end of image / start of scan before a frame
            int len = ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
            if (len < 2 || i + 2 + len > b.length) throw new IOException("truncated JPEG");
            if (m == 0xEE && len >= 7 && b[i + 4] == 'A' && b[i + 5] == 'd' && b[i + 6] == 'o' && b[i + 7] == 'b' && b[i + 8] == 'e') adobe = true;
            boolean sof = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
            if (sof) {
                if (len < 8) throw new IOException("short frame header");
                int h = ((b[i + 5] & 0xff) << 8) | (b[i + 6] & 0xff);
                int w = ((b[i + 7] & 0xff) << 8) | (b[i + 8] & 0xff);
                int nc = b[i + 9] & 0xff;
                if (w <= 0 || h <= 0 || (nc != 1 && nc != 3 && nc != 4)) throw new IOException("unsupported JPEG frame");
                return new JpegInfo(w, h, nc, adobe);
            }
            i += 2 + len;
        }
        throw new IOException("no JPEG frame header");
    }
}
