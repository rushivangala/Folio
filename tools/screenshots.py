#!/usr/bin/env python3
"""Renders the web UI the way the phone does and saves screenshots for a visual check.

Serves app/src/main/assets/www at https://app.local/ (the same origin the app uses), replaces
window.Native and window.KitNative with mocks, serves generated page images for the image routes
(/s/ finished page, /o/ original photo, /l/ library thumbnail) and sets Pixel 10 Pro insets.

    python3 tools/screenshots.py [out_dir] [scenario ...]

Needs Python Playwright with Chromium, and Pillow. Each scenario is shot in light (Paper) and
dark (Gruvbox). Console errors, uncaught exceptions and off-host requests fail the run.
"""
import io
import json
import mimetypes
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
WWW = ROOT / "app/src/main/assets/www"
args = [a for a in sys.argv[1:]]
OUT = pathlib.Path(args.pop(0)) if args and not args[0].isidentifier() and "/" in args[0] else ROOT / "build/screenshots"
ONLY = set(args)

# Pixel 10 Pro: 1280 x 2856 px panel, shown here at 412 css px wide.
VIEWPORT = {"width": 412, "height": 918}
INSETS = (52, 24, 0, 0, 0)  # top, bottom, left, right, ime (dp)


# ---------------------------------------------------------------- fixture images

def font(size):
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/dejavu/DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def invoice(w=1240, h=1754):
    im = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(im)
    d.rectangle([90, 90, 190, 190], fill="#1f6fb2")
    d.text((220, 105), "Vattenfall AB", font=font(54), fill="#1a1a1a")
    d.text((220, 170), "169 92 Stockholm", font=font(30), fill="#555")
    d.text((90, 300), "Faktura", font=font(64), fill="#111")
    rows = [("Fakturanummer", "8812 3345"), ("Fakturadatum", "2026-10-12"), ("Förfallodatum", "2026-10-28"),
            ("Bankgiro", "5050-1055"), ("OCR-nummer", "4401 2234 5567 8"), ("Att betala", "1 234,50 kr")]
    for i, (k, v) in enumerate(rows):
        y = 430 + i * 64
        d.text((90, y), k, font=font(34), fill="#333")
        d.text((640, y), v, font=font(34), fill="#111")
    d.line([90, 860, w - 90, 860], fill="#999", width=3)
    for i in range(14):
        y = 900 + i * 52
        d.text((90, y), "Elförbrukning period %02d   %d kWh   %s kr" % (i + 1, 180 + i * 7, 120 + i * 9), font=font(28), fill="#444")
    return im


def receipt(w=900, h=1600):
    im = Image.new("RGB", (w, h), "#fbfaf6")
    d = ImageDraw.Draw(im)
    d.text((300, 80), "ICA Kvantum", font=font(54), fill="#c0262d")
    for i, (k, v) in enumerate([("Mjölk 1,5%", "16,90"), ("Kaffe", "54,95"), ("Bröd", "32,00"), ("Äpplen", "28,40"), ("Moms 12%", "14,39")]):
        d.text((90, 260 + i * 70), k, font=font(40), fill="#222")
        d.text((640, 260 + i * 70), v, font=font(40), fill="#222")
    d.text((90, 700), "Totalt 132,25 kr", font=font(52), fill="#111")
    return im


def photo_of_page(page):
    """The page lying skewed on a wooden table, with a soft shadow: what an imported photo looks like."""
    w, h = 1500, 2000
    table = Image.new("RGB", (w, h), "#8a6a4a")
    d = ImageDraw.Draw(table)
    for y in range(0, h, 9):
        d.line([0, y, w, y + 30], fill="#80603f" if (y // 9) % 3 else "#957554", width=4)
    quad = [(180, 210), (1310, 260), (1380, 1830), (110, 1760)]
    pw, ph = page.size
    # Perspective transform coefficients mapping output -> input (PIL wants the inverse).
    coeffs = perspective_coeffs(quad, [(0, 0), (pw, 0), (pw, ph), (0, ph)])
    warped = page.transform((w, h), Image.PERSPECTIVE, coeffs, Image.BICUBIC, fillcolor=None)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).polygon(quad, fill=255)
    shade = Image.new("L", (w, h), 0)
    ImageDraw.Draw(shade).polygon([(x + 18, y + 22) for x, y in quad], fill=110)
    table.paste(Image.new("RGB", (w, h), "#2a1d10"), (0, 0), shade.filter(ImageFilter.GaussianBlur(18)))
    table.paste(warped, (0, 0), mask)
    # A shadow falling across the right of the page.
    grad = Image.linear_gradient("L").rotate(90).resize((w, h))
    table = Image.composite(table, Image.blend(table, Image.new("RGB", (w, h), "#000"), 0.35), grad)
    return table


def perspective_coeffs(dst, src):
    import numpy as np  # noqa: local import keeps numpy optional for other uses
    a = []
    for (x, y), (u, v) in zip(dst, src):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    b = [c for p in src for c in p]
    return list(np.linalg.solve(np.array(a, float), np.array(b, float)))


def jpeg(im, maxside):
    im = im.copy()
    im.thumbnail((maxside, maxside))
    bo = io.BytesIO()
    im.save(bo, "JPEG", quality=85)
    return bo.getvalue()


PAGES_IMG = {"a1b2c3d4": invoice(), "e5f60718": receipt()}
ORIGINALS = {"a1b2c3d4": photo_of_page(PAGES_IMG["a1b2c3d4"]), "e5f60718": photo_of_page(PAGES_IMG["e5f60718"])}
THUMBS = {"0badcafe": invoice(), "11223344": receipt(), "55667788": invoice(), "99aabbcc": receipt()}


def image_for(path, query):
    parts = path.strip("/").split("/")
    m = 1400
    for kv in query.split("&"):
        if kv.startswith("m="):
            m = int(kv[2:])
    if parts[0] == "s" and parts[1] in PAGES_IMG:
        return jpeg(PAGES_IMG[parts[1]], m)
    if parts[0] == "o" and parts[1] in ORIGINALS:
        return jpeg(ORIGINALS[parts[1]], m)
    if parts[0] == "l" and parts[1] in THUMBS:
        return jpeg(THUMBS[parts[1]], 480)
    return None


# ---------------------------------------------------------------- mock bridge

PAGES = [
    {"id": "a1b2c3d4", "w": 1500, "h": 2000, "ver": 1, "filter": "auto", "used": "grey", "quad": [180, 210, 1310, 260, 1380, 1830, 110, 1760]},
    {"id": "e5f60718", "w": 1500, "h": 2000, "ver": 1, "filter": "colour", "used": "colour", "quad": [0, 0, 1499, 0, 1499, 1999, 0, 1999]},
]
DAY = 86400000
NOW = 1792252800000  # 2026-10-17
SCANS = [
    {"id": "0badcafe", "name": "Faktura Vattenfall 2026-10-12", "format": "pdf", "pages": 2, "created": NOW - DAY * 2, "collection": "bills", "size": 482133, "folder": "Documents/Folio", "uris": ["content://x/1"]},
    {"id": "11223344", "name": "Kvitto ICA Kvantum", "format": "pdf", "pages": 1, "created": NOW - DAY * 5, "collection": "receipts", "size": 201220, "folder": "Documents/Folio", "uris": ["content://x/2"]},
    {"id": "55667788", "name": "Hyresavtal", "format": "pdf", "pages": 6, "created": NOW - DAY * 40, "collection": "", "size": 2483320, "folder": "Kontrakt", "uris": ["content://x/3"]},
    {"id": "99aabbcc", "name": "Old receipt", "format": "jpg", "pages": 1, "created": NOW - DAY * 90, "collection": "receipts", "size": 90000, "folder": "Documents/Folio", "uris": ["content://x/4"], "missing": True},
]

MOCK = """
(() => {
  const S = %(state)s;
  window.__calls = [];
  const reply = (t, extra) => setTimeout(() => window.app && app.onNative(Object.assign({type: 'result', token: t, ok: true}, extra || {})), 30);
  window.KitNative = {
    setChrome() {}, saveColors() {}, dynamicColors: () => 'null', isNight: () => %(dark)s,
    setCanGoBack() {}, haptic() {}, copy() {}, toast() {}, version: () => S.status.version, fontScale: () => 1,
  };
  const api = {
    status: () => JSON.stringify(S.status), prefs: () => JSON.stringify(S.prefs), setPref(k, v) { S.prefs[k] = v; },
    pages: () => JSON.stringify(S.pages), scans: () => JSON.stringify(S.scans), takeLaunch: () => '', importing: () => false,
    defaultName: () => 'Scan 2026-10-17', setCollection: () => true, openScan: () => true, shareScan: () => true,
    refreshLibrary: (t) => reply(t), prepare: (id, t) => reply(t, {used: 'grey'}),
  };
  window.Native = new Proxy(api, { get(t, k) {
    if (k in t) return t[k];
    return (...a) => { window.__calls.push([k, a]); const last = a[a.length - 1]; if (typeof last === 'string' && /^t\\d+$/.test(last)) reply(last); };
  } });
})();
"""


def state(pages=True, scans=True, prefs=None):
    return {"status": {"noInternet": True, "version": "1.0.0", "folder": "Documents/Folio", "customFolder": False},
            "prefs": prefs or {}, "pages": PAGES if pages else [], "scans": SCANS if scans else []}


SCENARIOS = [
    # name, state, steps (("click", sel) | ("wait", ms) | ("drag", sel...) | ("eval", js))
    ("home-empty", state(False, False), []),
    ("home-library", state(True, True), []),
    ("home-receipts", state(False, True), [("click", '[data-a="coll"][data-v="receipts"]')]),
    ("scan-sheet", state(False, True), [("click", '[data-a="scanSheet"]')]),
    ("review", state(True, True), [("click", '[data-to="review"]'), ("wait", 300)]),
    ("review-filter", state(True, True), [("click", '[data-to="review"]'), ("click", '[data-a="filterTool"]')]),
    ("review-menu", state(True, True), [("click", '[data-to="review"]'), ("click", '[data-a="pageMenu"]')]),
    ("crop", state(True, True), [("click", '[data-to="review"]'), ("click", '[data-a="crop"]'), ("wait", 400)]),
    ("crop-drag", state(True, True), [("click", '[data-to="review"]'), ("click", '[data-a="crop"]'), ("wait", 400), ("dragcorner", 2)]),
    ("save-sheet", state(True, True), [("click", '[data-to="review"]'), ("click", '[data-a="saveSheet"]')]),
    ("detail", state(False, True), [("click", '[data-a="openDetail"][data-id="0badcafe"]'), ("wait", 300)]),
    ("detail-missing", state(False, True), [("click", '[data-a="openDetail"][data-id="99aabbcc"]')]),
    ("rename", state(False, True), [("click", '[data-a="openDetail"][data-id="0badcafe"]'), ("click", '[data-a="renameScan"]')]),
    ("settings", state(False, True), [("click", '[data-to="settings"]'), ("eval", "document.querySelector('.pscroll').scrollTop = 9999")]),
]


def serve(route):
    url = route.request.url.split("app.local/", 1)[1]
    path, _, query = url.partition("?")
    path = path or "index.html"
    img = image_for(path, query) if path[:2] in ("s/", "o/", "l/") else None
    if img is not None:
        route.fulfill(status=200, body=img, content_type="image/jpeg")
        return
    f = (WWW / path).resolve()
    if WWW not in f.parents or not f.is_file():
        route.fulfill(status=404, body="")
        return
    route.fulfill(status=200, body=f.read_bytes(), content_type=mimetypes.guess_type(f.name)[0] or "application/octet-stream")


def drag_corner(page, i):
    box = page.evaluate("""(i) => { const c = document.querySelectorAll('#csvg .hd')[i]; const r = c.getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2]; }""", i)
    page.mouse.move(box[0], box[1])
    page.mouse.down()
    page.mouse.move(box[0] - 30, box[1] - 60, steps=6)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    errors = []
    with sync_playwright() as p:
        browser = p.chromium.launch()
        for theme in ("light", "dark"):
            for name, st, steps in SCENARIOS:
                if ONLY and name not in ONLY:
                    continue
                ctx = browser.new_context(viewport=VIEWPORT, device_scale_factor=2, color_scheme=theme, has_touch=True, is_mobile=True)
                ctx.add_init_script(MOCK % {"state": json.dumps(st), "dark": "true" if theme == "dark" else "false"})
                ctx.route("https://app.local/**", serve)
                ctx.route(lambda url: "app.local" not in url, lambda r: (errors.append("off-host request: " + r.request.url), r.abort()))
                page = ctx.new_page()
                page.on("console", lambda m, n=name: m.type == "error" and errors.append(f"{n}: console: {m.text}"))
                page.on("pageerror", lambda e, n=name: errors.append(f"{n}: {e}"))
                page.goto("https://app.local/index.html")
                page.evaluate("Kit._insets(%d,%d,%d,%d,%d)" % INSETS)
                page.wait_for_timeout(150)
                for step in steps:
                    kind, arg = step
                    if kind == "click":
                        page.click(arg)
                        page.wait_for_timeout(150)
                    elif kind == "wait":
                        page.wait_for_timeout(arg)
                    elif kind == "eval":
                        page.evaluate(arg)
                    elif kind == "dragcorner":
                        drag_corner(page, arg)
                page.wait_for_timeout(350)
                shot = OUT / f"{name}-{theme}.png"
                page.screenshot(path=str(shot))
                print("saved", shot)
                ctx.close()
        browser.close()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
