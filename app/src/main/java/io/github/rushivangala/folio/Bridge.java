package io.github.rushivangala.folio;

import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/** window.Native: everything the web UI asks of Android. Slow work runs off the UI thread and replies by token. */
public final class Bridge {
    private static final String[] PREF_KEYS = {"onboarded"};
    private final MainActivity a;

    Bridge(MainActivity a) {
        this.a = a;
    }

    @JavascriptInterface
    public String status() {
        JSONObject o = new JSONObject();
        try {
            o.put("noInternet", noInternet());
            o.put("version", a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName);
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
}
