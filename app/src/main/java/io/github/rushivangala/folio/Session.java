package io.github.rushivangala.folio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The scan in progress: an ordered list of pages, each an original image plus its corners,
 * filter and rotation. Kept in files/session so it survives the app being closed mid-scan.
 * Corners are in the original's pixels; rotating a page rotates the original itself, so what the
 * crop screen shows is always upright.
 */
final class Session {
    static final int PREVIEW_MAX = 1400, SAVE_MAX = 3000;

    static final class Page {
        String id;
        int w, h, ver;
        double[] quad;
        String filter, used = "";

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id).put("w", w).put("h", h).put("ver", ver).put("filter", filter).put("used", used);
            JSONArray q = new JSONArray();
            for (double d : quad) q.put(Math.round(d * 10) / 10.0);
            o.put("quad", q);
            return o;
        }

        static Page from(JSONObject o) throws Exception {
            Page p = new Page();
            p.id = o.getString("id");
            p.w = o.getInt("w");
            p.h = o.getInt("h");
            p.ver = o.optInt("ver");
            p.filter = o.optString("filter", Filters.AUTO);
            p.used = o.optString("used");
            JSONArray q = o.getJSONArray("quad");
            p.quad = new double[8];
            for (int i = 0; i < 8; i++) p.quad[i] = q.getDouble(i);
            return p;
        }
    }

    private static Session instance;
    private final Context ctx;
    private final File dir, renders;
    private final List<Page> pages = new ArrayList<>();
    private static final SecureRandom RND = new SecureRandom();

    static synchronized Session get(Context c) {
        if (instance == null) instance = new Session(c.getApplicationContext());
        return instance;
    }

    private Session(Context c) {
        ctx = c;
        dir = new File(c.getFilesDir(), "session");
        renders = new File(c.getCacheDir(), "render");
        dir.mkdirs();
        renders.mkdirs();
        load();
    }

    // ---------------------------------------------------------------- persistence

    private void load() {
        try {
            File f = new File(dir, "session.json");
            if (!f.exists()) return;
            JSONArray a = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getJSONArray("pages");
            for (int i = 0; i < a.length(); i++) {
                Page p = Page.from(a.getJSONObject(i));
                if (original(p).exists()) pages.add(p);
            }
        } catch (Exception e) {
            pages.clear();
        }
    }

    private void persist() {
        try {
            JSONArray a = new JSONArray();
            for (Page p : pages) a.put(p.json());
            File tmp = new File(dir, "session.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(new JSONObject().put("pages", a).toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(dir, "session.json"))) throw new IOException("rename failed");
        } catch (Exception ignored) {
        }
    }

    File original(Page p) {
        return new File(dir, "o_" + p.id + ".jpg");
    }

    synchronized String json() {
        try {
            JSONArray a = new JSONArray();
            for (Page p : pages) a.put(p.json());
            return a.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    synchronized int size() {
        return pages.size();
    }

    synchronized List<Page> pages() {
        return new ArrayList<>(pages);
    }

    synchronized Page page(String id) {
        for (Page p : pages) if (p.id.equals(id)) return p;
        return null;
    }

    // ---------------------------------------------------------------- adding pages

    /** Adds an upright original (already downscaled to ORIGINAL_MAX). Returns the new page. */
    Page add(Bitmap b, String filter) throws IOException {
        Page p = new Page();
        synchronized (this) {
            p.id = newId();
        }
        p.w = b.getWidth();
        p.h = b.getHeight();
        p.quad = Warp.full(p.w, p.h);
        p.filter = filter == null ? Filters.AUTO : filter;
        Pix.writeJpeg(b, 92, original(p));
        synchronized (this) {
            pages.add(p);
            persist();
        }
        return p;
    }

    private String newId() {
        String id;
        do {
            id = String.format(Locale.ROOT, "%08x", RND.nextInt());
        } while (page(id) != null);
        return id;
    }

    // ---------------------------------------------------------------- edits

    synchronized boolean setQuad(String id, double[] q) {
        Page p = page(id);
        if (p == null || !Warp.valid(q)) return false;
        for (int i = 0; i < 8; i++) q[i] = Math.max(0, Math.min(i % 2 == 0 ? p.w - 1 : p.h - 1, q[i]));
        p.quad = q;
        p.ver++;
        persist();
        return true;
    }

    synchronized boolean setFilter(String id, String f) {
        if (!known(f)) return false;
        if (id == null) {
            for (Page p : pages) { p.filter = f; p.ver++; }
        } else {
            Page p = page(id);
            if (p == null) return false;
            p.filter = f;
            p.ver++;
        }
        persist();
        return true;
    }

    static boolean known(String f) {
        for (String k : Filters.ALL) if (k.equals(f)) return true;
        return false;
    }

    synchronized boolean delete(String id) {
        Page p = page(id);
        if (p == null) return false;
        pages.remove(p);
        original(p).delete();
        persist();
        return true;
    }

    synchronized boolean move(String id, int to) {
        Page p = page(id);
        if (p == null) return false;
        pages.remove(p);
        pages.add(Math.max(0, Math.min(pages.size(), to)), p);
        persist();
        return true;
    }

    /** Rotates the page's original a quarter turn (clockwise when cw), corners included. */
    boolean rotate(String id, boolean cw) throws IOException {
        Page p;
        synchronized (this) {
            p = page(id);
        }
        if (p == null) return false;
        Bitmap src = Pix.decodeFile(original(p), Pix.ORIGINAL_MAX);
        Matrix m = new Matrix();
        m.postRotate(cw ? 90 : -90);
        Bitmap r = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        src.recycle();
        Pix.writeJpeg(r, 92, original(p));
        synchronized (this) {
            int w = p.w, h = p.h;
            double[] q = p.quad, n = new double[8];
            // Rotate each corner, then re-label so the first corner is the new top-left.
            int[] order = cw ? new int[]{3, 0, 1, 2} : new int[]{1, 2, 3, 0};
            for (int i = 0; i < 4; i++) {
                double x = q[order[i] * 2], y = q[order[i] * 2 + 1];
                n[i * 2] = cw ? h - 1 - y : y;
                n[i * 2 + 1] = cw ? x : w - 1 - x;
            }
            p.w = r.getWidth();
            p.h = r.getHeight();
            p.quad = n;
            p.ver++;
            persist();
        }
        r.recycle();
        return true;
    }

    synchronized void clear() {
        for (Page p : pages) original(p).delete();
        pages.clear();
        persist();
        File[] old = renders.listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    // ---------------------------------------------------------------- rendering

    /** The finished page: flattened, filtered, as an Img no larger than maxSide. */
    Img process(Page p, int maxSide) throws IOException {
        double[] quad;
        int pw, ph;
        String filter;
        synchronized (this) {
            quad = p.quad.clone();
            pw = p.w;
            ph = p.h;
            filter = p.filter;
        }
        boolean full = Warp.isFull(quad, pw, ph);
        // Decode a bit larger than the output when flattening, so resampling keeps detail.
        Bitmap b = Pix.decodeFile(original(p), full ? maxSide : Math.min(Pix.ORIGINAL_MAX, (int) (maxSide * 1.3)));
        Img src = Pix.toImg(b);
        b.recycle();
        Img page;
        if (full) page = src;
        else {
            double s = (double) src.w / pw;
            double[] q = new double[8];
            for (int i = 0; i < 8; i++) q[i] = quad[i] * s;
            page = Warp.flatten(src, q, maxSide);
        }
        Filters.Result r = Filters.apply(page.fit(maxSide), filter);
        synchronized (this) {
            if (!r.used.equals(p.used)) {
                p.used = r.used;
                persist();
            }
        }
        return r.img;
    }

    /** A JPEG of the finished page for the screen, cached per page version. */
    byte[] preview(String id, int maxSide) throws IOException {
        Page p = page(id);
        if (p == null) return null;
        File f = new File(renders, p.id + "_" + p.ver + "_" + maxSide + ".jpg");
        if (f.exists()) return Files.readAllBytes(f.toPath());
        Bitmap b = Pix.toBitmap(process(p, maxSide));
        Pix.writeJpeg(b, 85, f);
        b.recycle();
        return Files.readAllBytes(f.toPath());
    }

    /** The upright original, downscaled, for the crop screen. */
    byte[] originalPreview(String id, int maxSide) throws IOException {
        Page p = page(id);
        if (p == null) return null;
        File f = new File(renders, p.id + "_o" + p.ver + "_" + maxSide + ".jpg");
        if (f.exists()) return Files.readAllBytes(f.toPath());
        Bitmap b = Pix.decodeFile(original(p), maxSide);
        Pix.writeJpeg(b, 85, f);
        b.recycle();
        return Files.readAllBytes(f.toPath());
    }
}
