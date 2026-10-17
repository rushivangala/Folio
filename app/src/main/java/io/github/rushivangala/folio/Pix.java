package io.github.rushivangala.folio;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Bitmap plumbing: decoding imports, rendering PDF pages, converting to and from Img, JPEG output. */
final class Pix {
    /** Longest side kept for a page original. 4000 px is roughly 450 dpi on A4. */
    static final int ORIGINAL_MAX = 4000;

    private Pix() {
    }

    /** Decodes an image (EXIF orientation applied by ImageDecoder), downscaled to fit maxSide. */
    static Bitmap decode(ContentResolver cr, Uri uri, final int maxSide) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri), new ImageDecoder.OnHeaderDecodedListener() {
            @Override
            public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                d.setMutableRequired(false);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int side = Math.max(w, h);
                if (side > maxSide) {
                    double f = (double) maxSide / side;
                    d.setTargetSize(Math.max(1, (int) Math.round(w * f)), Math.max(1, (int) Math.round(h * f)));
                }
            }
        });
    }

    static Bitmap decodeFile(File f, int maxSide) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), new ImageDecoder.OnHeaderDecodedListener() {
            @Override
            public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int side = Math.max(w, h);
                if (side > maxSide) {
                    double sc = (double) maxSide / side;
                    d.setTargetSize(Math.max(1, (int) Math.round(w * sc)), Math.max(1, (int) Math.round(h * sc)));
                }
            }
        });
    }

    interface PageSink {
        void page(Bitmap b) throws IOException;
    }

    /** Renders each page of a PDF at about 200 dpi on a white background. Returns the page count. */
    static int renderPdf(ContentResolver cr, Uri uri, PageSink sink) throws IOException {
        ParcelFileDescriptor fd = cr.openFileDescriptor(uri, "r");
        if (fd == null) throw new IOException("can't open PDF");
        try (PdfRenderer r = new PdfRenderer(fd)) {
            int n = r.getPageCount();
            for (int i = 0; i < n; i++) {
                try (PdfRenderer.Page p = r.openPage(i)) {
                    double scale = 200.0 / 72.0;
                    int w = (int) Math.round(p.getWidth() * scale), h = (int) Math.round(p.getHeight() * scale);
                    double cap = (double) ORIGINAL_MAX / Math.max(w, h);
                    if (cap < 1) {
                        w = (int) Math.round(w * cap);
                        h = (int) Math.round(h * cap);
                    }
                    Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
                    new Canvas(b).drawColor(Color.WHITE);
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    try {
                        sink.page(b);
                    } finally {
                        b.recycle();
                    }
                }
            }
            return n;
        }
    }

    static Img toImg(Bitmap b) {
        Bitmap src = b.getConfig() == Bitmap.Config.ARGB_8888 ? b : b.copy(Bitmap.Config.ARGB_8888, false);
        Img im = new Img(src.getWidth(), src.getHeight());
        src.getPixels(im.px, 0, im.w, 0, 0, im.w, im.h);
        if (src != b) src.recycle();
        return im;
    }

    static Bitmap toBitmap(Img im) {
        return Bitmap.createBitmap(im.px, im.w, im.h, Bitmap.Config.ARGB_8888);
    }

    static byte[] jpeg(Bitmap b, int quality) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, o);
        return o.toByteArray();
    }

    static void writeJpeg(Bitmap b, int quality, File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            if (!b.compress(Bitmap.CompressFormat.JPEG, quality, o)) throw new IOException("JPEG encoding failed");
        }
        if (!tmp.renameTo(f)) throw new IOException("can't write " + f.getName());
    }

    static List<Uri> uris(android.content.Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data == null) return out;
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        if (out.isEmpty() && data.getData() != null) out.add(data.getData());
        return out;
    }
}
