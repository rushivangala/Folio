package io.github.rushivangala.folio;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.rushivangala.kit.KitShell;

/**
 * Folio: the web UI in assets/www plus a native bridge (window.Native, see Bridge).
 * Page images reach the page through https://app.local/ routes served here:
 * /s/ID finished page (flattened and filtered), /o/ID upright original for cropping,
 * /l/ID/N library page thumbnail.
 */
public final class MainActivity extends Activity {
    static final String PREFS = "folio";
    static final int REQ_PHOTOS = 10, REQ_FILES = 11, REQ_FOLDER = 12;

    WebView web;
    KitShell shell;
    final Handler main = new Handler(Looper.getMainLooper());
    /** One worker for scan work, so page edits apply in the order they were made. */
    final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Map<Integer, String> requestTokens = new HashMap<>();
    private volatile String launch;
    /** Imports still running (the page may load after one finishes and miss its event). */
    final java.util.concurrent.atomic.AtomicInteger importsRunning = new java.util.concurrent.atomic.AtomicInteger();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);
        shell = new KitShell(this, web).install();
        web.addJavascriptInterface(new Bridge(this), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse res = shell.serveAsset(r);
                if (res != null) return res;
                try {
                    return route(r.getUrl());
                } catch (Throwable e) {
                    return KitShell.notFound();
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                shell.pageFinished();
            }
        });
        if (state == null) readLaunch(getIntent());
        shell.load("index.html");
    }

    // ---------------------------------------------------------------- launches (share sheet)

    private void readLaunch(Intent i) {
        if (i == null) return;
        String a = i.getAction();
        if (Intent.ACTION_SEND.equals(a) || Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            final List<Uri> uris = new ArrayList<>();
            if (Intent.ACTION_SEND.equals(a)) {
                Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                if (u != null) uris.add(u);
            } else {
                ArrayList<Uri> list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
                if (list != null) uris.addAll(list);
            }
            if (uris.isEmpty() && i.getClipData() != null) uris.addAll(Pix.uris(i));
            if (uris.isEmpty()) return;
            launch = "{\"open\":\"review\",\"importing\":true}";
            importUris(uris, null);
        }
    }

    String takeLaunch() {
        String s = launch;
        launch = null;
        return s == null ? "" : s;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readLaunch(intent);
        emit("{\"type\":\"launch\"}");
    }

    // ---------------------------------------------------------------- importing

    /** Adds images and PDFs as pages; replies with an "imported" event (token may be null). */
    void importUris(final List<Uri> uris, final String token) {
        final String filter = prefs().getString("p_filter", Filters.AUTO);
        importsRunning.incrementAndGet();
        work.execute(new Runnable() {
            @Override
            public void run() {
                int added = 0, failed = 0;
                try {
                    Session s = Session.get(MainActivity.this);
                    for (Uri u : uris) {
                        try {
                            String mime = getContentResolver().getType(u);
                            if (mime != null && mime.toLowerCase(Locale.ROOT).equals("application/pdf")) {
                                final int[] n = {0};
                                Pix.renderPdf(getContentResolver(), u, new Pix.PageSink() {
                                    @Override
                                    public void page(Bitmap b) throws java.io.IOException {
                                        s.add(b, filter);
                                        n[0]++;
                                    }
                                });
                                added += n[0];
                            } else {
                                Bitmap b = Pix.decode(getContentResolver(), u, Pix.ORIGINAL_MAX);
                                s.add(b, filter);
                                b.recycle();
                                added++;
                            }
                        } catch (Throwable e) {
                            failed++;
                        }
                    }
                } finally {
                    importsRunning.decrementAndGet();
                }
                emit("{\"type\":\"imported\",\"added\":" + added + ",\"failed\":" + failed + (token == null ? "" : ",\"token\":" + JSONObject.quote(token)) + "}");
            }
        });
    }

    void pickPhotos(String token) {
        Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(50, MediaStore.getPickImagesMaxLimit()));
        start(i, REQ_PHOTOS, token);
    }

    void pickFiles(String token) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        start(i, REQ_FILES, token);
    }

    void pickFolder(String token) {
        start(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), REQ_FOLDER, token);
    }

    private void start(final Intent i, final int req, final String token) {
        main.post(new Runnable() {
            @Override
            public void run() {
                requestTokens.put(req, token);
                try {
                    startActivityForResult(i, req);
                } catch (Exception e) {
                    requestTokens.remove(req);
                    emitResult(token, false, "\"error\":\"No app on this phone can do that\"");
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        String token = requestTokens.remove(req);
        if (token == null) return;
        if (result != RESULT_OK || data == null) {
            emitResult(token, false, "\"cancelled\":true");
            return;
        }
        if (req == REQ_FOLDER) {
            Uri tree = data.getData();
            if (tree == null) {
                emitResult(token, false, null);
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                emitResult(token, false, "\"error\":\"That folder can’t be used\"");
                return;
            }
            String old = prefs().getString("folder", "");
            if (!old.isEmpty() && !old.equals(tree.toString())) releaseFolder(old);
            prefs().edit().putString("folder", tree.toString()).apply();
            emitResult(token, true, "\"folder\":" + JSONObject.quote(Library.folderLabel(this, tree.toString())));
            return;
        }
        List<Uri> uris = Pix.uris(data);
        if (uris.isEmpty()) {
            emitResult(token, false, "\"cancelled\":true");
            return;
        }
        emit("{\"type\":\"importing\",\"count\":" + uris.size() + "}");
        importUris(uris, token);
    }

    void releaseFolder(String tree) {
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        shell.resumed();
        emit("{\"type\":\"resume\"}");
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        shell.configurationChanged(c);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    void emit(final String json) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web != null && shell.isReady()) web.evaluateJavascript("window.app&&app.onNative(" + json + ")", null);
            }
        });
    }

    void emitResult(String token, boolean ok, String extra) {
        emit("{\"type\":\"result\",\"token\":" + JSONObject.quote(token == null ? "" : token) + ",\"ok\":" + ok + (extra == null ? "" : "," + extra) + "}");
    }

    // ---------------------------------------------------------------- image routes

    WebResourceResponse route(Uri u) throws Exception {
        String path = u.getPath() == null ? "/" : u.getPath();
        String[] parts = path.split("/");
        if (parts.length < 3 || !parts[2].matches("[0-9a-f]{8}")) return KitShell.notFound();
        byte[] b = null;
        Session s = Session.get(this);
        switch (parts[1]) {
            case "s":
                b = s.preview(parts[2], size(u.getQueryParameter("m"), Session.PREVIEW_MAX));
                break;
            case "o":
                b = s.originalPreview(parts[2], size(u.getQueryParameter("m"), 1600));
                break;
            case "l":
                int page = parts.length > 3 ? Integer.parseInt(parts[3]) : 0;
                File f = Library.get(this).thumb(parts[2], page);
                if (f.exists()) b = java.nio.file.Files.readAllBytes(f.toPath());
                break;
            default:
                break;
        }
        // Versioned URLs (?v=) never change, so the WebView may keep them.
        return b == null ? KitShell.notFound() : KitShell.response("image/jpeg", new ByteArrayInputStream(b), u.getQueryParameter("v") != null);
    }

    private static int size(String s, int def) {
        try {
            return Math.max(160, Math.min(2400, Integer.parseInt(s)));
        } catch (Exception e) {
            return def;
        }
    }
}
