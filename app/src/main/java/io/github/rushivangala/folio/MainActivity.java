package io.github.rushivangala.folio;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import io.github.rushivangala.kit.KitShell;

/**
 * Folio: the web UI in assets/www plus a native bridge (window.Native, see Bridge).
 * Page images and PDFs will reach the page through https://app.local/ routes served here.
 */
public final class MainActivity extends Activity {
    static final String PREFS = "folio";

    WebView web;
    KitShell shell;
    final Handler main = new Handler(Looper.getMainLooper());

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
        shell.load("index.html");
    }

    /** App routes under https://app.local/ (page images, thumbnails). None yet. */
    WebResourceResponse route(Uri u) {
        return KitShell.notFound();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        emit("{\"type\":\"launch\"}");
    }

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
}
