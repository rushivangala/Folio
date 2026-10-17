package io.github.rushivangala.folio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A small PDF writer for scans. Each page is one JPEG drawn full-page (DCTDecode, passed through
 * untouched) plus an optional invisible text layer: words in text render mode 3 (Helvetica,
 * WinAnsiEncoding so å ä ö work), each stretched over its box on the page, so any viewer can search
 * and copy the text. Pure Java.
 */
public final class PdfWriter {
    /** A4 width in points; pages keep the scan's aspect ratio. */
    public static final double PAGE_WIDTH = 595.276;

    /** A recognised word, in image pixels. */
    public static final class Word {
        public final String text;
        public final double left, top, right, bottom;

        public Word(String text, double left, double top, double right, double bottom) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    public static final class Page {
        final byte[] jpeg;
        final JpegInfo info;
        final List<Word> words;

        public Page(byte[] jpeg, List<Word> words) throws IOException {
            this.jpeg = jpeg;
            this.info = JpegInfo.read(jpeg);
            this.words = words == null ? new ArrayList<Word>() : words;
        }
    }

    private final List<Page> pages = new ArrayList<>();
    private String title = "";
    private Date created = new Date();

    public PdfWriter title(String t) {
        title = t == null ? "" : t;
        return this;
    }

    public PdfWriter created(Date d) {
        created = d;
        return this;
    }

    public PdfWriter add(Page p) {
        pages.add(p);
        return this;
    }

    public int pageCount() {
        return pages.size();
    }

    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        write(bo);
        return bo.toByteArray();
    }

    // ---------------------------------------------------------------- writing

    private static final class Out {
        final OutputStream os;
        long pos;
        final List<Long> offsets = new ArrayList<>();

        Out(OutputStream os) {
            this.os = os;
        }

        void bytes(byte[] b) throws IOException {
            os.write(b);
            pos += b.length;
        }

        void s(String str) throws IOException {
            bytes(str.getBytes(StandardCharsets.ISO_8859_1));
        }

        /** Starts object n (objects must be written in order 1, 2, 3 …). */
        void obj(int n) throws IOException {
            if (n != offsets.size() + 1) throw new IllegalStateException("object " + n + " out of order");
            offsets.add(pos);
            s(n + " 0 obj\n");
        }
    }

    public void write(OutputStream os) throws IOException {
        if (pages.isEmpty()) throw new IllegalStateException("no pages");
        Out o = new Out(os);
        o.s("%PDF-1.7\n");
        o.bytes(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});

        // 1 catalog, 2 page tree, 3 font, 4 info, then per page: page, image, contents.
        int n = pages.size();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(pageObj(i)).append(" 0 R ");

        o.obj(1);
        o.s("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        o.obj(2);
        o.s("<< /Type /Pages /Count " + n + " /Kids [ " + kids + "] >>\nendobj\n");
        o.obj(3);
        o.s("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n");
        o.obj(4);
        o.s("<< /Producer (Folio) /Creator (Folio) /CreationDate " + pdfDate(created) + (title.isEmpty() ? "" : " /Title " + textString(title)) + " >>\nendobj\n");

        for (int i = 0; i < n; i++) {
            Page p = pages.get(i);
            double pw = PAGE_WIDTH, ph = PAGE_WIDTH * p.info.height / p.info.width;
            byte[] content = content(p, pw, ph);
            o.obj(pageObj(i));
            o.s("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(pw) + " " + num(ph) + "] /Resources << /XObject << /Im0 " + (pageObj(i) + 1) + " 0 R >> /Font << /F1 3 0 R >> >> /Contents " + (pageObj(i) + 2) + " 0 R >>\nendobj\n");
            o.obj(pageObj(i) + 1);
            o.s("<< /Type /XObject /Subtype /Image /Width " + p.info.width + " /Height " + p.info.height
                    + " /ColorSpace " + (p.info.components == 1 ? "/DeviceGray" : p.info.components == 4 ? "/DeviceCMYK" : "/DeviceRGB")
                    + (p.info.components == 4 && p.info.adobe ? " /Decode [1 0 1 0 1 0 1 0]" : "")
                    + " /BitsPerComponent 8 /Filter /DCTDecode /Length " + p.jpeg.length + " >>\nstream\n");
            o.bytes(p.jpeg);
            o.s("\nendstream\nendobj\n");
            o.obj(pageObj(i) + 2);
            o.s("<< /Length " + content.length + " >>\nstream\n");
            o.bytes(content);
            o.s("\nendstream\nendobj\n");
        }

        long xref = o.pos;
        int count = o.offsets.size() + 1;
        StringBuilder x = new StringBuilder("xref\n0 " + count + "\n0000000000 65535 f \n");
        for (long off : o.offsets) x.append(String.format(Locale.ROOT, "%010d 00000 n \n", off));
        o.s(x.toString());
        o.s("trailer\n<< /Size " + count + " /Root 1 0 R /Info 4 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        os.flush();
    }

    private static int pageObj(int i) {
        return 5 + i * 3;
    }

    private static byte[] content(Page p, double pw, double ph) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        StringBuilder s = new StringBuilder();
        s.append("q ").append(num(pw)).append(" 0 0 ").append(num(ph)).append(" 0 0 cm /Im0 Do Q\n");
        if (!p.words.isEmpty()) {
            double sx = pw / p.info.width, sy = ph / p.info.height;
            s.append("BT 3 Tr\n");
            for (int i = 0; i < p.words.size(); i++) {
                Word w = p.words.get(i);
                String text = w.text == null ? "" : w.text.trim();
                if (text.isEmpty()) continue;
                // A space after a word that continues on the same line, so copied text keeps word breaks.
                Word next = i + 1 < p.words.size() ? p.words.get(i + 1) : null;
                boolean sameLine = next != null && next.left > w.left && Math.abs((next.top + next.bottom) - (w.top + w.bottom)) < (w.bottom - w.top);
                byte[] enc = winAnsi(sameLine ? text + " " : text);
                double boxW = (w.right - w.left) * sx, boxH = (w.bottom - w.top) * sy;
                if (boxW <= 0 || boxH <= 0) continue;
                double size = boxH;
                double natural = width(winAnsi(text)) / 1000.0 * size;
                double tz = natural > 0 ? 100.0 * boxW / natural : 100;
                double x = w.left * sx, baseline = ph - w.bottom * sy + boxH * 0.2;
                s.append("/F1 ").append(num(size)).append(" Tf ").append(num(tz)).append(" Tz 1 0 0 1 ").append(num(x)).append(' ').append(num(baseline)).append(" Tm ");
                writeAscii(b, s);
                b.write('(');
                escape(b, enc);
                writeAscii(b, s.append(") Tj\n"));
            }
            s.append("ET\n");
        }
        writeAscii(b, s);
        byte[] out = b.toByteArray();
        // Drop the trailing newline; the stream keyword adds its own.
        return out.length > 0 && out[out.length - 1] == '\n' ? java.util.Arrays.copyOf(out, out.length - 1) : out;
    }

    private static void writeAscii(ByteArrayOutputStream b, StringBuilder s) {
        byte[] x = s.toString().getBytes(StandardCharsets.ISO_8859_1);
        b.write(x, 0, x.length);
        s.setLength(0);
    }

    private static void escape(ByteArrayOutputStream b, byte[] enc) {
        for (byte by : enc) {
            int v = by & 0xff;
            if (v == '(' || v == ')' || v == '\\') {
                b.write('\\');
                b.write(v);
            } else if (v < 32 || v > 126) {
                String oct = Integer.toOctalString(v);
                b.write('\\');
                for (int k = oct.length(); k < 3; k++) b.write('0');
                for (char c : oct.toCharArray()) b.write(c);
            } else b.write(v);
        }
    }

    private static final Charset CP1252 = Charset.forName("windows-1252");

    /** Encodes to WinAnsi (Windows-1252); characters it can't hold become '?'. */
    static byte[] winAnsi(String s) {
        CharsetEncoder enc = CP1252.newEncoder();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            String one = new String(Character.toChars(cp));
            if (cp >= 32 && enc.canEncode(one)) {
                byte[] x = one.getBytes(CP1252);
                b.write(x, 0, x.length);
            } else b.write('?');
        }
        return b.toByteArray();
    }

    /** Helvetica advance widths for WinAnsi codes 32..255 (thousandths of an em), from the standard AFM. */
    private static final short[] HELVETICA = {
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556,
            556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556,
            556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, 278,
            556, 278, 222, 556, 333, 1000, 556, 556, 333, 1000, 667, 333, 1000, 278, 611, 278, 278, 222, 222, 333, 333, 350, 556, 1000,
            333, 1000, 500, 333, 944, 278, 500, 667, 278, 333, 556, 556, 556, 556, 260, 556, 333, 737, 370, 556, 584, 333, 737, 333,
            400, 584, 333, 333, 333, 556, 537, 278, 333, 333, 365, 556, 834, 834, 834, 611, 667, 667, 667, 667, 667, 667, 1000, 722,
            667, 667, 667, 667, 278, 278, 278, 278, 722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
            556, 556, 556, 556, 556, 556, 889, 500, 556, 556, 556, 556, 278, 278, 278, 278, 556, 556, 556, 556, 556, 556, 556, 584,
            611, 556, 556, 556, 556, 500, 556, 500};

    static int width(byte[] enc) {
        int w = 0;
        for (byte b : enc) {
            int v = b & 0xff;
            w += v >= 32 ? HELVETICA[v - 32] : 278;
        }
        return w;
    }

    /** A PDF text string: plain when it is printable ASCII, otherwise UTF-16BE with a byte-order mark. */
    static String textString(String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) { ascii = false; break; }
        }
        if (ascii) return "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")";
        StringBuilder h = new StringBuilder("<FEFF");
        for (byte b : s.getBytes(StandardCharsets.UTF_16BE)) h.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        return h.append('>').toString();
    }

    static String pdfDate(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "(D:" + f.format(d) + "Z)";
    }

    static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return Long.toString(Math.round(v));
        String s = String.format(Locale.ROOT, "%.3f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.equals("-0") ? "0" : s;
    }
}
