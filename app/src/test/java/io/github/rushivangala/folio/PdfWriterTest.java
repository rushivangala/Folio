package io.github.rushivangala.folio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PdfWriterTest {

    static PdfWriter.Page page(int w, int h, PdfWriter.Word... words) throws Exception {
        return new PdfWriter.Page(TestImages.jpeg(TestImages.blank(w, h)), Arrays.asList(words));
    }

    static String latin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    @Test
    public void writesValidStructureWithCorrectXref() throws Exception {
        byte[] pdf = new PdfWriter().title("Kvitto ICA").created(new Date(0))
                .add(page(400, 600, new PdfWriter.Word("Total", 10, 10, 90, 40)))
                .add(page(600, 400))
                .toBytes();
        String s = latin1(pdf);
        assertTrue(s.startsWith("%PDF-1.7\n"));
        assertTrue(s.endsWith("%%EOF\n"));
        assertTrue(s.contains("/Count 2"));
        assertTrue(s.contains("/Filter /DCTDecode"));
        assertTrue(s.contains("/BaseFont /Helvetica /Encoding /WinAnsiEncoding"));

        // Every xref entry must point at "n 0 obj".
        int xref = Integer.parseInt(s.substring(s.lastIndexOf("startxref\n") + 10, s.lastIndexOf("\n%%EOF")).trim());
        assertTrue(s.startsWith("xref\n", xref));
        Matcher m = Pattern.compile("(\\d{10}) 00000 n ").matcher(s.substring(xref));
        int obj = 1;
        while (m.find()) {
            int off = Integer.parseInt(m.group(1));
            assertTrue("object " + obj + " at " + off, s.startsWith(obj + " 0 obj\n", off));
            obj++;
        }
        assertEquals(4 + 2 * 3 + 1, obj); // 4 shared objects + 3 per page
        assertTrue(s.contains("/Size " + obj));
    }

    @Test
    public void pageKeepsImageAspectAtA4Width() throws Exception {
        String s = latin1(new PdfWriter().add(page(1000, 1414)).toBytes());
        assertTrue(s, s.contains("/MediaBox [0 0 595.276 841.72"));
    }

    @Test
    public void textLayerIsInvisibleAndEncodesSwedishLetters() throws Exception {
        String s = latin1(new PdfWriter()
                .add(page(1000, 1000,
                        new PdfWriter.Word("Förfallodatum", 100, 100, 400, 130),
                        new PdfWriter.Word("2026-10-28", 420, 100, 600, 130),
                        new PdfWriter.Word("Å(ä)\\ö", 100, 200, 300, 230)))
                .toBytes());
        assertTrue(s.contains("BT 3 Tr"));
        // ö is 0xF6 in WinAnsi, written as an octal escape; a trailing space joins words on one line.
        assertTrue(s, s.contains("(F\\366rfallodatum ) Tj"));
        assertTrue(s, s.contains("(2026-10-28) Tj"));
        // Parentheses and backslashes are escaped; Å=0xC5, ä=0xE4.
        assertTrue(s, s.contains("(\\305\\(\\344\\)\\\\\\366) Tj"));
    }

    @Test
    public void unsupportedCharactersBecomeQuestionMarks() {
        assertEquals("a?b", latin1(PdfWriter.winAnsi("a中b")));
        assertEquals("€", new String(PdfWriter.winAnsi("€"), java.nio.charset.Charset.forName("windows-1252")));
    }

    @Test
    public void wordIsStretchedToItsBox() throws Exception {
        // "MM" in Helvetica is 1.666 em; a 100x50 px box on a 595.276 pt page that is 595.276 px wide
        // means size 50 and natural width 83.3, so Tz is 120.
        String s = latin1(new PdfWriter().add(page(595, 595, new PdfWriter.Word("MM", 0, 0, 100, 50))).toBytes());
        Matcher m = Pattern.compile("/F1 ([0-9.]+) Tf ([0-9.]+) Tz").matcher(s);
        assertTrue(m.find());
        assertEquals(50.0, Double.parseDouble(m.group(1)), 0.1);
        assertEquals(120.0, Double.parseDouble(m.group(2)), 0.5);
    }

    @Test
    public void nonAsciiTitleIsUtf16() throws Exception {
        String s = latin1(new PdfWriter().title("Faktura Göteborg").add(page(10, 10)).toBytes());
        assertTrue(s.contains("/Title <FEFF"));
        assertTrue(s.contains("00F6")); // ö
    }

    @Test
    public void greyJpegUsesDeviceGray() throws Exception {
        byte[] jpeg = TestImages.jpegGrey(TestImages.blank(20, 30));
        assertEquals(1, JpegInfo.read(jpeg).components);
        String s = latin1(new PdfWriter().add(new PdfWriter.Page(jpeg, null)).toBytes());
        assertTrue(s.contains("/ColorSpace /DeviceGray"));
    }

    @Test
    public void writesSampleForInspection() throws Exception {
        // Leaves build/test-output/sample.pdf so the text layer can be checked with a PDF reader.
        File dir = new File("build/test-output");
        dir.mkdirs();
        Img im = TestImages.blank(1240, 1754);
        TestImages.fillRect(im, 100, 100, 900, 160, 0xff202020);
        try (FileOutputStream f = new FileOutputStream(new File(dir, "sample.pdf"))) {
            new PdfWriter().title("Faktura Vattenfall").add(new PdfWriter.Page(TestImages.jpeg(im), Arrays.asList(
                    new PdfWriter.Word("Faktura", 100, 100, 330, 160),
                    new PdfWriter.Word("Vattenfall", 350, 100, 640, 160),
                    new PdfWriter.Word("Förfallodatum:", 100, 300, 420, 340),
                    new PdfWriter.Word("2026-10-28", 440, 300, 640, 340),
                    new PdfWriter.Word("Att", 100, 400, 170, 440),
                    new PdfWriter.Word("betala:", 180, 400, 330, 440),
                    new PdfWriter.Word("1 234,50 kr", 340, 400, 600, 440),
                    new PdfWriter.Word("Räksmörgås", 100, 500, 380, 540)))).write(f);
        }
    }
}
