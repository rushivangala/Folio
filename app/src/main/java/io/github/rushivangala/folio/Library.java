package io.github.rushivangala.folio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Saved scans. The files themselves live where the user can see them (Documents/Folio by default,
 * or a folder they picked); Folio keeps an index in files/library.json with page thumbnails so the
 * library opens instantly and works offline from the files.
 */
final class Library {
    static final String DEFAULT_FOLDER = Environment.DIRECTORY_DOCUMENTS + "/Folio";
    static final String[] COLLECTIONS = {"receipts", "bills", "letters", "id", "manuals", "notes"};

    private static Library instance;
    private final Context ctx;
    private final File index, thumbs;
    private final List<JSONObject> entries = new ArrayList<>();
    private static final SecureRandom RND = new SecureRandom();

    static synchronized Library get(Context c) {
        if (instance == null) instance = new Library(c.getApplicationContext());
        return instance;
    }

    private Library(Context c) {
        ctx = c;
        index = new File(c.getFilesDir(), "library.json");
        thumbs = new File(c.getFilesDir(), "thumbs");
        thumbs.mkdirs();
        try {
            if (index.exists()) {
                JSONArray a = new JSONArray(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) entries.add(a.getJSONObject(i));
            }
        } catch (Exception ignored) {
        }
    }

    private void persist() {
        try {
            JSONArray a = new JSONArray();
            for (JSONObject e : entries) a.put(e);
            File tmp = new File(index.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(index)) throw new IOException("rename failed");
        } catch (Exception ignored) {
        }
    }

    synchronized String json() {
        JSONArray a = new JSONArray();
        for (JSONObject e : entries) a.put(e);
        return a.toString();
    }

    synchronized JSONObject entry(String id) {
        for (JSONObject e : entries) if (id.equals(e.optString("id"))) return e;
        return null;
    }

    File thumb(String id, int page) {
        return new File(thumbs, id + "_" + page + ".jpg");
    }

    // ---------------------------------------------------------------- saving

    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (n.startsWith(".")) n = n.substring(1).trim();
        if (n.length() > 120) n = n.substring(0, 120).trim();
        return n.isEmpty() ? defaultName(new Date()) : n;
    }

    static String defaultName(Date d) {
        return "Scan " + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(d);
    }

    interface Writer {
        void write(OutputStream o) throws IOException;
    }

    /** Creates a file in the save folder and writes it. Returns its content URI. */
    Uri create(String folderTree, String displayName, String mime, Writer w) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri uri;
        if (folderTree != null && !folderTree.isEmpty()) {
            Uri tree = Uri.parse(folderTree);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            uri = DocumentsContract.createDocument(cr, parent, mime, displayName);
            if (uri == null) throw new IOException("Couldn’t create a file in the chosen folder");
            try (OutputStream o = cr.openOutputStream(uri, "w")) {
                if (o == null) throw new IOException("Couldn’t write to the chosen folder");
                w.write(o);
            } catch (IOException | RuntimeException e) {
                try { DocumentsContract.deleteDocument(cr, uri); } catch (Exception ignored) { }
                throw e;
            }
            return uri;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        uri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) throw new IOException("Couldn’t create a file in Documents/Folio");
        try (OutputStream o = cr.openOutputStream(uri, "w")) {
            if (o == null) throw new IOException("Couldn’t write to Documents/Folio");
            w.write(o);
        } catch (IOException | RuntimeException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    /** Records a saved scan. Thumbnails are page images (already processed) written at 480 px. */
    synchronized JSONObject add(String name, String format, List<Uri> uris, List<Bitmap> pageThumbs, String collection, long bytes, String folderLabel) throws Exception {
        String id;
        do id = String.format(Locale.ROOT, "%08x", RND.nextInt()); while (entry(id) != null);
        for (int i = 0; i < pageThumbs.size(); i++) Pix.writeJpeg(pageThumbs.get(i), 80, thumb(id, i));
        JSONArray u = new JSONArray();
        for (Uri x : uris) u.put(x.toString());
        JSONObject e = new JSONObject();
        e.put("id", id).put("name", name).put("format", format).put("uris", u).put("pages", pageThumbs.size())
                .put("created", System.currentTimeMillis()).put("collection", collection == null ? "" : collection)
                .put("size", bytes).put("folder", folderLabel);
        entries.add(0, e);
        persist();
        return e;
    }

    // ---------------------------------------------------------------- changes

    synchronized boolean setCollection(String id, String c) {
        JSONObject e = entry(id);
        if (e == null) return false;
        boolean ok = c.isEmpty();
        for (String k : COLLECTIONS) ok |= k.equals(c);
        if (!ok) return false;
        try {
            e.put("collection", c);
        } catch (Exception ex) {
            return false;
        }
        persist();
        return true;
    }

    /** Renames the files too (Documents/Folio or the chosen folder). */
    synchronized void rename(String id, String newName) throws Exception {
        JSONObject e = entry(id);
        if (e == null) throw new IOException("not found");
        String name = cleanName(newName);
        ContentResolver cr = ctx.getContentResolver();
        JSONArray uris = e.getJSONArray("uris"), out = new JSONArray();
        String ext = "pdf".equals(e.optString("format")) ? ".pdf" : ".jpg";
        for (int i = 0; i < uris.length(); i++) {
            Uri u = Uri.parse(uris.getString(i));
            String display = name + (uris.length() > 1 ? " " + (i + 1) : "") + ext;
            if (DocumentsContract.isDocumentUri(ctx, u) && !"media".equals(u.getAuthority())) {
                Uri n = DocumentsContract.renameDocument(cr, u, display);
                out.put((n == null ? u : n).toString());
            } else {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, display);
                cr.update(u, v, null, null);
                out.put(u.toString());
            }
        }
        e.put("name", name).put("uris", out);
        persist();
    }

    /** Deletes the files and forgets the scan. Files already gone are fine. */
    synchronized void delete(String id) throws Exception {
        JSONObject e = entry(id);
        if (e == null) return;
        ContentResolver cr = ctx.getContentResolver();
        JSONArray uris = e.getJSONArray("uris");
        for (int i = 0; i < uris.length(); i++) {
            Uri u = Uri.parse(uris.getString(i));
            if (!exists(u)) continue;
            if (DocumentsContract.isDocumentUri(ctx, u) && !"media".equals(u.getAuthority())) DocumentsContract.deleteDocument(cr, u);
            else cr.delete(u, null, null);
        }
        forget(id);
    }

    synchronized void forget(String id) {
        JSONObject e = entry(id);
        if (e == null) return;
        for (int i = 0; i < e.optInt("pages"); i++) thumb(id, i).delete();
        entries.remove(e);
        persist();
    }

    /** Marks scans whose files were deleted or moved elsewhere (for example in the Files app). */
    void refreshMissing() {
        List<JSONObject> copy;
        synchronized (this) {
            copy = new ArrayList<>(entries);
        }
        boolean changed = false;
        for (JSONObject e : copy) {
            try {
                Uri u = Uri.parse(e.getJSONArray("uris").getString(0));
                boolean missing = !exists(u);
                if (missing != e.optBoolean("missing")) {
                    synchronized (this) {
                        e.put("missing", missing);
                    }
                    changed = true;
                }
            } catch (Exception ignored) {
            }
        }
        if (changed) synchronized (this) {
            persist();
        }
    }

    boolean exists(Uri u) {
        try (Cursor c = ctx.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    /** "Documents/Folio" or the picked folder's name, for display. */
    static String folderLabel(Context c, String tree) {
        if (tree == null || tree.isEmpty()) return DEFAULT_FOLDER;
        try {
            Uri t = Uri.parse(tree);
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
            try (Cursor cur = c.getContentResolver().query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) return cur.getString(0);
            }
        } catch (Exception ignored) {
        }
        return "Chosen folder";
    }
}
