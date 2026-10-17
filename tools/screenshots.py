#!/usr/bin/env python3
"""Renders the web UI the way the phone does and saves screenshots for a visual check.

Serves app/src/main/assets/www at https://app.local/ (the same origin the app uses), replaces
window.Native and window.KitNative with mocks, and sets system-bar insets like a Pixel 10 Pro.

    python3 tools/screenshots.py [out_dir]

Needs Python Playwright with Chromium. Each scenario is shot in light (Paper) and dark (Gruvbox).
Console errors and uncaught exceptions fail the run.
"""
import json
import mimetypes
import pathlib
import sys

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
WWW = ROOT / "app/src/main/assets/www"
OUT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ROOT / "build/screenshots")

# Pixel 10 Pro: 1280 x 2856 px panel, shown here at 412 css px wide.
VIEWPORT = {"width": 412, "height": 918}
INSETS = (52, 24, 0, 0, 0)  # top, bottom, left, right, ime (dp)

MOCK = """
(() => {
  const status = %(status)s, prefs = %(prefs)s;
  window.__calls = [];
  const log = (n, a) => window.__calls.push([n, [...a]]);
  window.KitNative = {
    setChrome() {}, saveColors() {}, dynamicColors: () => 'null', isNight: () => %(dark)s,
    setCanGoBack() {}, haptic() {}, copy() {}, toast() {}, version: () => status.version, fontScale: () => 1,
  };
  window.Native = new Proxy({
    status: () => JSON.stringify(status),
    prefs: () => JSON.stringify(prefs),
    setPref(k, v) { prefs[k] = v; },
  }, { get(t, k) { if (k in t) return t[k]; return (...a) => { log(k, a); return undefined; }; } });
})();
"""

SCENARIOS = [
    # name, clicks (CSS selectors, in order), status
    ("home", [], {}),
    ("home-receipts", ['[data-a="coll"][data-v="receipts"]'], {}),
    ("scan-tapped", ['[data-a="scan"]'], {}),
    ("search", ['[data-to="search"]'], {}),
    ("settings", ['[data-to="settings"]'], {}),
]


def serve(route):
    path = route.request.url.split("app.local/", 1)[1].split("?", 1)[0] or "index.html"
    f = (WWW / path).resolve()
    if WWW not in f.parents or not f.is_file():
        route.fulfill(status=404, body="")
        return
    route.fulfill(status=200, body=f.read_bytes(), content_type=mimetypes.guess_type(f.name)[0] or "application/octet-stream")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    errors = []
    with sync_playwright() as p:
        browser = p.chromium.launch()
        for theme in ("light", "dark"):
            for name, clicks, extra in SCENARIOS:
                status = {"noInternet": True, "version": "1.0.0"}
                status.update(extra)
                ctx = browser.new_context(viewport=VIEWPORT, device_scale_factor=2, color_scheme=theme, has_touch=True, is_mobile=True)
                ctx.add_init_script(MOCK % {"status": json.dumps(status), "prefs": "{}", "dark": "true" if theme == "dark" else "false"})
                ctx.route("https://app.local/**", serve)
                ctx.route(lambda url: "app.local" not in url, lambda r: (errors.append("off-host request: " + r.request.url), r.abort()))
                page = ctx.new_page()
                page.on("console", lambda m, n=name: m.type == "error" and errors.append(f"{n}: console: {m.text}"))
                page.on("pageerror", lambda e, n=name: errors.append(f"{n}: {e}"))
                page.goto("https://app.local/index.html")
                page.evaluate("Kit._insets(%d,%d,%d,%d,%d)" % INSETS)
                page.wait_for_timeout(150)
                for sel in clicks:
                    page.click(sel)
                    page.wait_for_timeout(120)
                page.wait_for_timeout(300)
                shot = OUT / f"{name}-{theme}.png"
                page.screenshot(path=str(shot))
                print("saved", shot.relative_to(ROOT) if ROOT in shot.parents else shot)
                ctx.close()
        browser.close()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
