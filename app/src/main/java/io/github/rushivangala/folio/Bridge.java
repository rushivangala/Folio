package io.github.rushivangala.folio;

import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** window.Native: everything the web UI asks of Android. Slow work runs off the UI thread and replies by token. */
public final class Bridge {
    private static final String[] PREF_KEYS = {"onboarded", "format", "filter"};
    private final MainActivity a;

    Bridge(MainActivity a) {
        this.a = a;
    }

    private Session session() {
        return Session.get(a);
    }

    private Library library() {
        return Library.get(a);
    }

    private void bg(final String token, final Job job) {
        a.work.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String extra = job.run();
                    a.emitResult(token, true, extra);
                } catch (Throwable e) {
                    a.emitResult(token, false, "\"error\":" + JSONObject.quote(message(e)));
                }
            }
        });
    }

    private interface Job {
        String run() throws Exception;
    }

    private static String message(Throwable e) {
        if (e instanceof OutOfMemoryError) return "Not enough memory for this page";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    // ---------------------------------------------------------------- status and prefs

    @JavascriptInterface
    public String status() {
        JSONObject o = new JSONObject();
        try {
            o.put("noInternet", noInternet());
            o.put("version", a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName);
            o.put("folder", Library.folderLabel(a, a.prefs().getString("folder", "")));
            o.put("customFolder", !a.prefs().getString("folder", "").isEmpty());
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private boolean noInternet() {
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions == null) return true;
            for (String p : pi.requestedPermissions) if (android.Manifest.permission.INTERNET.equals(p)) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String prefs() {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = a.prefs();
            for (String k : PREF_KEYS) {
                String v = p.getString("p_" + k, null);
                if (v != null) o.put(k, v);
            }
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    @JavascriptInterface
    public void setPref(String k, String v) {
        for (String allowed : PREF_KEYS) {
            if (allowed.equals(k)) {
                a.prefs().edit().putString("p_" + k, v).apply();
                return;
            }
        }
    }

    @JavascriptInterface
    public String takeLaunch() {
        return a.takeLaunch();
    }

    // ---------------------------------------------------------------- folder

    @JavascriptInterface
    public void pickFolder(String token) {
        a.pickFolder(token);
    }

    @JavascriptInterface
    public void resetFolder() {
        String old = a.prefs().getString("folder", "");
        if (!old.isEmpty()) a.releaseFolder(old);
        a.prefs().edit().remove("folder").apply();
    }

    // ---------------------------------------------------------------- the scan in progress

    @JavascriptInterface
    public String pages() {
        return session().json();
    }

    @JavascriptInterface
    public void importPhotos(String token) {
        a.pickPhotos(token);
    }

    @JavascriptInterface
    public void importFiles(String token) {
        a.pickFiles(token);
    }

    @JavascriptInterface
    public void setQuad(final String id, final String json, String token) {
        bg(token, new Job() {
            @Override
            public String run() throws Exception {
                JSONArray q = new JSONArray(json);
                double[] d = new double[8];
                for (int i = 0; i < 8; i++) d[i] = q.getDouble(i);
                if (!session().setQuad(id, d)) throw new IllegalArgumentException("Those corners don’t make a page");
                return null;
            }
        });
    }

    @JavascriptInterface
    public void resetQuad(final String id, String token) {
        bg(token, new Job() {
            @Override
            public String run() {
                Session.Page p = session().page(id);
                if (p != null) session().setQuad(id, Warp.full(p.w, p.h));
                return null;
            }
        });
    }

    @JavascriptInterface
    public void setFilter(final String id, final String filter, final boolean all, String token) {
        bg(token, new Job() {
            @Override
            public String run() {
                if (!session().setFilter(all ? null : id, filter)) throw new IllegalArgumentException("Unknown filter");
                return null;
            }
        });
    }

    @JavascriptInterface
    public void rotate(final String id, final boolean cw, String token) {
        bg(token, new Job() {
            @Override
            public String run() throws Exception {
                session().rotate(id, cw);
                return null;
            }
        });
    }

    @JavascriptInterface
    public void movePage(final String id, final int to, String token) {
        bg(token, new Job() {
            @Override
            public String run() {
                session().move(id, to);
                return null;
            }
        });
    }

    @JavascriptInterface
    public void deletePage(final String id, String token) {
        bg(token, new Job() {
            @Override
            public String run() {
                session().delete(id);
                return null;
            }
        });
    }

    @JavascriptInterface
    public void discard(String token) {
        bg(token, new Job() {
            @Override
            public String run() {
                session().clear();
                return null;
            }
        });
    }

    /** Renders the page now (so the filter Auto chose is known); replies with "used". */
    @JavascriptInterface
    public void prepare(final String id, String token) {
        bg(token, new Job() {
            @Override
            public String run() throws Exception {
                session().preview(id, Session.PREVIEW_MAX);
                Session.Page p = session().page(id);
                return "\"used\":" + JSONObject.quote(p == null ? "" : p.used);
            }
        });
    }

    // ---------------------------------------------------------------- saving

    @JavascriptInterface
    public String defaultName() {
        return Library.defaultName(new java.util.Date());
    }

    @JavascriptInterface
    public void save(final String rawName, final String format, final String collection, String token) {
        bg(token, new Job() {
            @Override
            public String run() throws Exception {
                List<Session.Page> pages = session().pages();
                if (pages.isEmpty()) throw new IllegalStateException("There are no pages to save");
                final String name = Library.cleanName(rawName);
                final boolean pdf = !"jpg".equals(format);
                String folder = a.prefs().getString("folder", "");
                List<byte[]> jpegs = new ArrayList<>();
                List<Bitmap> thumbs = new ArrayList<>();
                for (Session.Page p : pages) {
                    Bitmap b = Pix.toBitmap(session().process(p, Session.SAVE_MAX));
                    jpegs.add(Pix.jpeg(b, 80));
                    double s = 480.0 / Math.max(b.getWidth(), b.getHeight());
                    thumbs.add(Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * s)), Math.max(1, (int) (b.getHeight() * s)), true));
                    b.recycle();
                }
                List<Uri> uris = new ArrayList<>();
                long bytes = 0;
                if (pdf) {
                    final PdfWriter w = new PdfWriter().title(name);
                    for (byte[] j : jpegs) w.add(new PdfWriter.Page(j, null));
                    final byte[] out = w.toBytes();
                    bytes = out.length;
                    uris.add(library().create(folder, name + ".pdf", "application/pdf", new Library.Writer() {
                        @Override
                        public void write(java.io.OutputStream o) throws java.io.IOException {
                            o.write(out);
                        }
                    }));
                } else {
                    for (int i = 0; i < jpegs.size(); i++) {
                        final byte[] j = jpegs.get(i);
                        bytes += j.length;
                        uris.add(library().create(folder, name + (jpegs.size() > 1 ? " " + (i + 1) : "") + ".jpg", "image/jpeg", new Library.Writer() {
                            @Override
                            public void write(java.io.OutputStream o) throws java.io.IOException {
                                o.write(j);
                            }
                        }));
                    }
                }
                JSONObject e = library().add(name, pdf ? "pdf" : "jpg", uris, thumbs, collection, bytes, Library.folderLabel(a, folder));
                for (Bitmap t : thumbs) t.recycle();
                session().clear();
                return "\"entry\":" + e.toString();
            }
        });
    }

    // ---------------------------------------------------------------- library

    @JavascriptInterface
    public String scans() {
        return Library.get(a).json();
    }

    @JavascriptInterface
    public void refreshLibrary(String token) {
        bg(token, new Job() {
            @Override
            public String run() {
                library().refreshMissing();
                return null;
            }
        });
    }

    @JavascriptInterface
    public void renameScan(final String id, final String name, String token) {
        bg(token, new Job() {
            @Override
            public String run() throws Exception {
                library().rename(id, name);
                return null;
            }
        });
    }

    @JavascriptInterface
    public boolean setCollection(String id, String c) {
        return library().setCollection(id, c == null ? "" : c);
    }

    @JavascriptInterface
    public void deleteScan(final String id, String token) {
        bg(token, new Job() {
            @Override
            public String run() throws Exception {
                library().delete(id);
                return null;
            }
        });
    }

    @JavascriptInterface
    public void forgetScan(String id) {
        library().forget(id);
    }

    @JavascriptInterface
    public boolean openScan(String id) {
        try {
            JSONObject e = library().entry(id);
            if (e == null) return false;
            Uri u = Uri.parse(e.getJSONArray("uris").getString(0));
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "pdf".equals(e.optString("format")) ? "application/pdf" : "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, e.optString("name")));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean shareScan(String id) {
        try {
            JSONObject e = library().entry(id);
            if (e == null) return false;
            JSONArray us = e.getJSONArray("uris");
            String mime = "pdf".equals(e.optString("format")) ? "application/pdf" : "image/jpeg";
            ArrayList<Uri> list = new ArrayList<>();
            for (int i = 0; i < us.length(); i++) list.add(Uri.parse(us.getString(i)));
            Intent i;
            if (list.size() == 1) i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, list.get(0));
            else i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, list);
            i.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData clip = ClipData.newRawUri(e.optString("name"), list.get(0));
            for (int k = 1; k < list.size(); k++) clip.addItem(new ClipData.Item(list.get(k)));
            i.setClipData(clip);
            a.startActivity(Intent.createChooser(i, e.optString("name")));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
