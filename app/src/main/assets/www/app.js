/* Folio — interface. Built on the shared kit (kit/kit.js, kit/kit.css), the same look as the
   rest of the suite. Talks to Android through window.Native (Bridge.java). Page images come
   from https://app.local/ routes: /s/ID finished page, /o/ID original, /l/ID/N library thumbnail. */
(function () {
  'use strict';
  var N = window.Native || null;
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var esc = Kit.esc, haptic = Kit.haptic;

  // ---------------------------------------------------------------- icons (kit set + a few of our own)
  var P = {
    scan: 'M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M8 10h8M8 14h5',
    nonet: 'M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0M5.6 5.6l12.8 12.8',
    text: 'M5 5h14M12 5v14M8 19h8',
    tag: 'M3 12V4h8l10 10-8 8zM7.5 8h.01',
    crop: 'M6 2v14a2 2 0 0 0 2 2h14M2 6h14a2 2 0 0 1 2 2v14',
    rotR: 'M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5',
    rotL: 'M4 12a8 8 0 1 0 2.3-5.7M4 4v5h5',
    filter: 'M12 3a9 9 0 1 0 0 18zM12 3a9 9 0 0 1 0 18',
    camera: 'M4 8h3l2-3h6l2 3h3v11H4zM9 13a3 3 0 1 0 6 0a3 3 0 1 0 -6 0',
    photos: 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9h.01',
    pdf: 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h6M9 17h4',
    left: 'M15 6l-6 6 6 6', right: 'M9 6l6 6-6 6',
    open: 'M14 4h6v6M20 4l-9 9M18 14v6H4V6h6',
    addp: 'M6 3h9l4 4v14H6zM14 3v5h5M12 11v6M9 14h6',
    reset: 'M4 4h6M4 4v6M20 20h-6M20 20v-6M4 20l6-6M20 4l-6 6',
  };
  function ic(name, cls) { return Kit.icon(P[name] || name, cls); }

  var COLLECTIONS = [['receipts', 'Receipts'], ['bills', 'Bills'], ['letters', 'Letters'], ['id', 'ID'], ['manuals', 'Manuals'], ['notes', 'Notes']];
  var COLL_NAME = {}; COLLECTIONS.forEach(function (c) { COLL_NAME[c[0]] = c[1]; });
  var FILTERS = [['auto', 'Auto'], ['colour', 'Colour'], ['grey', 'Greyscale'], ['bw', 'Black & white'], ['photo', 'Photo']];
  var FILTER_NAME = {}; FILTERS.forEach(function (f) { FILTER_NAME[f[0]] = f[1]; });

  // ---------------------------------------------------------------- state
  var S = {
    nav: [{ s: 'home' }], prefs: {}, status: {}, collection: 'all',
    scans: [], pages: [], cur: 0, tool: null, busy: null,
    sheet: null, modal: null, snack: null, crop: null,
  };
  function top() { return S.nav[S.nav.length - 1]; }
  function nat(fn) { try { return N && N[fn] ? N[fn].apply(N, [].slice.call(arguments, 1)) : undefined; } catch (e) { return undefined; } }
  function plural(n, one, many) { return n.toLocaleString('en-GB') + ' ' + (n === 1 ? one : (many || one + 's')); }
  function refreshStatus() { try { S.status = JSON.parse(nat('status') || '{}'); } catch (e) { S.status = {}; } }
  function loadPages() { try { S.pages = JSON.parse(nat('pages') || '[]'); } catch (e) { S.pages = []; } if (S.cur >= S.pages.length) S.cur = Math.max(0, S.pages.length - 1); }
  function loadScans() { try { S.scans = JSON.parse(nat('scans') || '[]'); } catch (e) { S.scans = []; } }
  function setPref(k, v) { S.prefs[k] = String(v); nat('setPref', k, String(v)); }
  function page() { return S.pages[S.cur]; }
  function scanById(id) { return S.scans.filter(function (s) { return s.id === id; })[0]; }

  // ---------------------------------------------------------------- native calls that answer later
  var waiting = {}, tokenN = 0;
  function call(fn, args) {
    return new Promise(function (resolve) {
      if (!N || !N[fn]) { resolve({ ok: false, error: 'unavailable' }); return; }
      var t = 't' + (++tokenN);
      waiting[t] = resolve;
      try { N[fn].apply(N, (args || []).concat([t])); } catch (e) { delete waiting[t]; resolve({ ok: false, error: String(e) }); }
    });
  }

  // ---------------------------------------------------------------- snackbar and dialogs
  var snackT;
  function snack(msg, action) {
    S.snack = { msg: msg, action: action || null };
    clearTimeout(snackT);
    snackT = setTimeout(function () { S.snack = null; renderOverlay(); }, action ? 5000 : 3000);
    renderOverlay();
  }
  function confirmBox(o) { S.modal = o; renderOverlay(); }
  function busy(msg) { S.busy = msg; renderOverlay(); }

  // ---------------------------------------------------------------- navigation and back
  function go(s, extra) { var e = extra || {}; e.s = s; S.nav.push(e); S.tool = null; render(); }
  function home() { S.nav = [{ s: 'home' }]; S.tool = null; loadScans(); render(); }
  function back() {
    if (S.modal) { S.modal = null; renderOverlay(); return true; }
    if (S.sheet) { S.sheet = null; renderOverlay(); return true; }
    if (top().s === 'review' && S.tool) { S.tool = null; render(); return true; }
    if (S.nav.length > 1) { S.nav.pop(); S.tool = null; if (top().s === 'home') loadScans(); render(); return true; }
    if (S.collection !== 'all') { S.collection = 'all'; render(); return true; }
    return false;
  }
  function canBack() { return !!(S.modal || S.sheet || S.nav.length > 1 || S.collection !== 'all'); }
  Kit.onBack(back, canBack);

  window.app = {
    back: back,
    onNative: function (ev) {
      if (ev.type === 'resume') {
        refreshStatus();
        if (top().s === 'home') { call('refreshLibrary').then(function () { loadScans(); if (top().s === 'home') render(); }); }
        else if (top().s === 'settings') render();
        return;
      }
      if (ev.type === 'launch') { handleLaunch(); return; }
      if (ev.type === 'importing') { busy(ev.count > 1 ? 'Adding ' + ev.count + ' files…' : 'Adding pages…'); return; }
      if (ev.token && waiting[ev.token]) { var f = waiting[ev.token]; delete waiting[ev.token]; f(ev); return; }
      if (ev.type === 'imported') afterImport(ev);
    },
    state: S,
  };

  function handleLaunch() {
    var l = nat('takeLaunch');
    if (!l) return;
    var o; try { o = JSON.parse(l); } catch (e) { return; }
    if (o.open === 'review') {
      S.sheet = null; S.modal = null;
      if (o.importing) {
        busy('Adding pages…');
        var poll = function () { if (nat('importing')) setTimeout(poll, 400); else { busy(null); loadPages(); render(); } };
        setTimeout(poll, 400);
      }
      loadPages();
      S.nav = [{ s: 'home' }, { s: 'review' }];
      render();
    }
  }

  function afterImport(ev) {
    busy(null);
    var before = S.pages.length;
    loadPages();
    if (ev.added) {
      S.cur = Math.min(before, S.pages.length - 1);
      if (top().s !== 'review') go('review'); else render();
    } else render();
    if (ev.failed) snack(plural(ev.failed, 'file') + ' couldn’t be read');
    else if (!ev.added && !ev.cancelled) snack('Nothing to add');
  }

  // ---------------------------------------------------------------- shared pieces
  function header(t, sub, actions) {
    return '<header class="k-head"><div class="k-ht"><h1>' + esc(t) + '</h1>' + (sub ? '<div class="k-hs">' + esc(sub) + '</div>' : '') + '</div><div class="acts">' + (actions || '') + '</div></header>';
  }
  function subHeader(t, sub, actions) {
    return '<header class="k-head k-small"><button type="button" class="k-icb" data-a="back" aria-label="Back">' + Kit.icon('back') + '</button><div class="k-ht"><h1>' + esc(t) + '</h1>' + (sub ? '<div class="k-hs">' + esc(sub) + '</div>' : '') + '</div><div class="acts">' + (actions || '') + '</div></header>';
  }
  function icb(a, icon, label, extra) { return '<button type="button" class="k-icb" data-a="' + a + '"' + (extra || '') + ' aria-label="' + esc(label) + '">' + icon + '</button>'; }
  function capRow(t, right) { return '<div class="k-cap pri"><span>' + esc(t) + '</span><span class="k-sp"></span>' + (right ? '<span class="k-n">' + esc(right) + '</span>' : '') + '</div>'; }
  function row(o) {
    // o: {a, attrs, lead (html), icon (html), img, t, sub, val, chev, btn, cls}
    var tag = o.a ? 'button type="button" data-a="' + o.a + '"' + (o.attrs || '') : 'div';
    return '<' + tag + ' class="k-row' + (o.cls ? ' ' + o.cls : '') + '">' +
      (o.img ? '<span class="thumbl">' + o.img + '</span>' : o.lead ? '<span class="k-lead">' + o.lead + '</span>' : o.icon ? o.icon : '') +
      '<span class="k-rt"><span class="k-t">' + esc(o.t) + '</span>' + (o.sub != null ? '<small>' + esc(o.sub) + '</small>' : '') + '</span>' +
      (o.val ? '<span class="k-val">' + esc(o.val) + '</span>' : '') + (o.btn || '') + (o.chev ? Kit.icon('chevR', 'k-chev') : '') +
      '</' + (o.a ? 'button' : 'div') + '>';
  }
  function sw(on, a, label) { return '<button type="button" class="k-switch' + (on ? ' on' : '') + '" role="switch" aria-checked="' + !!on + '" data-a="' + a + '" aria-label="' + esc(label) + '"></button>'; }
  function seg(label, opts, val, action) {
    return '<div class="k-seg" role="radiogroup" aria-label="' + esc(label) + '">' + opts.map(function (o) {
      var on = o[0] === val;
      return '<button type="button" role="radio" aria-checked="' + on + '" data-a="' + action + '" data-v="' + o[0] + '">' + (on ? Kit.icon('check') : '') + esc(o[1]) + '</button>';
    }).join('') + '</div>';
  }
  function chipRow(list, val, action, label) {
    return '<div class="k-chips" role="radiogroup" aria-label="' + esc(label) + '">' + list.map(function (c) {
      var on = c[0] === val;
      return '<button type="button" class="k-chip" role="radio" aria-checked="' + on + '" aria-pressed="' + on + '" data-a="' + action + '" data-v="' + c[0] + '">' + (on ? Kit.icon('check') : '') + esc(c[1]) + '</button>';
    }).join('') + '</div>';
  }
  function dateText(ms) { return new Date(ms).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }); }
  function sizeText(b) { return b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' kB'; }
  function pageUrl(p, m) { return 'https://app.local/s/' + p.id + '?v=' + p.ver + '&m=' + m; }
  function thumbUrl(s, n) { return 'https://app.local/l/' + s.id + '/' + (n || 0) + '?v=1'; }

  // ---------------------------------------------------------------- home: the library
  function visibleScans() {
    return S.scans.filter(function (s) { return S.collection === 'all' || s.collection === S.collection; });
  }
  function homeView() {
    var list = visibleScans();
    var sub = S.scans.length ? plural(S.scans.length, 'scan') : 'No scans yet';
    var h = header('Folio', sub, icb('go', Kit.icon('search'), 'Search', ' data-to="search"') + icb('go', Kit.icon('tune'), 'Settings', ' data-to="settings"'));
    h += '<div class="scroll" id="sc">' + chipRow([['all', 'All']].concat(COLLECTIONS), S.collection, 'coll', 'Collections');
    if (S.pages.length) {
      h += '<div class="k-group resume">' + row({ a: 'go', attrs: ' data-to="review"', img: '<img alt="" src="' + pageUrl(S.pages[0], 240) + '">', t: 'Scan in progress', sub: plural(S.pages.length, 'page') + ' · not saved yet', chev: true }) + '</div>';
    }
    if (list.length) {
      h += '<div class="k-group scans">' + list.map(function (s) {
        var meta = [dateText(s.created), plural(s.pages, 'page'), s.format === 'pdf' ? 'PDF' : 'JPEG'];
        if (s.collection && S.collection === 'all') meta.push(COLL_NAME[s.collection] || '');
        return row({ a: 'openDetail', attrs: ' data-id="' + s.id + '"', cls: s.missing ? 'missing' : '', img: '<img alt="" loading="lazy" src="' + thumbUrl(s) + '">', t: s.name, sub: s.missing ? 'File not found' : meta.join(' · '), chev: true });
      }).join('') + '</div>';
    } else if (!S.pages.length || S.collection !== 'all') {
      var name = (COLL_NAME[S.collection] || '').toLowerCase();
      h += '<div class="empty"><div class="appic">' + APPICON + '</div>' +
        '<b>' + (S.collection === 'all' ? 'Make paper disappear' : 'No ' + esc(name) + ' yet') + '</b>' +
        '<p>' + (S.collection === 'all' ? 'Scan a page and Folio turns it into a named, searchable PDF in ' + esc(S.status.folder || 'Documents/Folio') + '.' : 'Scans you put in ' + esc(name) + ' show up here.') + '</p></div>';
      if (S.collection === 'all') {
        h += '<div class="k-group hints">' +
          row({ lead: ic('photos'), t: 'Bring in what you have', sub: 'Photos of pages, screenshots and PDFs, or “Scan with Folio” from any app' }) +
          row({ lead: ic('crop'), t: 'Straight, clean pages', sub: 'Drag the corners to flatten a page; filters remove shadows' }) +
          row({ lead: ic('nonet'), t: 'Stays on this phone', sub: 'Folio has no internet permission' }) + '</div>';
      }
    }
    h += '<div class="pad-b"></div></div>';
    h += '<button type="button" class="k-fab ext" data-a="scanSheet">' + ic('scan') + 'Scan</button>';
    return h;
  }

  // ---------------------------------------------------------------- review: the scan in progress
  function reviewView() {
    var p = page();
    if (!p) {
      return subHeader('New scan') + '<div class="scroll"><div class="empty"><b>No pages yet</b><p>Add photos of pages or a PDF to start.</p>' +
        '<div class="btnrow col"><button type="button" class="k-btn filled" data-a="importPhotos">' + ic('photos') + 'From Photos</button><button type="button" class="k-btn tonal" data-a="importFiles">' + ic('pdf') + 'From Files</button></div></div></div>';
    }
    var used = p.filter === 'auto' && p.used ? 'Auto · ' + (FILTER_NAME[p.used] || p.used) : FILTER_NAME[p.filter];
    var h = subHeader('Review', 'Page ' + (S.cur + 1) + ' of ' + S.pages.length + ' · ' + used, icb('pageMenu', Kit.icon('more'), 'More'));
    h += '<div class="stage" id="stage"><img id="pimg" alt="Page ' + (S.cur + 1) + '" src="' + pageUrl(p, 1400) + '"><div class="spin" id="spin"></div></div>';
    if (S.tool === 'filter') {
      h += '<div class="tray">' + chipRow(FILTERS, p.filter, 'filter', 'Filter') +
        '<div class="trayacts"><button type="button" class="k-btn sm" data-a="filterAll">Use on all pages</button><button type="button" class="k-btn sm" data-a="toolDone">Done</button></div></div>';
    } else {
      h += '<div class="strip" id="strip" role="listbox" aria-label="Pages">' + S.pages.map(function (q, i) {
        return '<button type="button" class="pg' + (i === S.cur ? ' on' : '') + '" role="option" aria-selected="' + (i === S.cur) + '" data-a="pageSel" data-i="' + i + '" data-id="' + q.id + '"><img alt="" src="' + pageUrl(q, 320) + '"><span>' + (i + 1) + '</span></button>';
      }).join('') + '<button type="button" class="pg add" data-a="addSheet" aria-label="Add pages">' + Kit.icon('plus') + '</button></div>';
      h += '<div class="tools">' +
        tool('crop', 'crop', 'Crop') + tool('rotate', 'rotR', 'Rotate') + tool('filterTool', 'filter', 'Filter') + tool('delPage', 'trash', 'Delete') +
        '</div>';
    }
    h += '<div class="savebar"><button type="button" class="k-btn filled wide" data-a="saveSheet">' + Kit.icon('check') + 'Save ' + (S.pages.length > 1 ? plural(S.pages.length, 'page') : '') + '</button></div>';
    return h;
  }
  function tool(a, icon, label) { return '<button type="button" class="tool" data-a="' + a + '">' + ic(icon) + '<span>' + label + '</span></button>'; }

  // ---------------------------------------------------------------- crop: drag the four corners
  function cropView() {
    var p = page();
    if (!p) return reviewView();
    return '<div class="cropwrap">' + subHeader('Crop', 'Drag the corners to the page edges', icb('cropReset', ic('reset'), 'Whole image')) +
      '<div class="cstage" id="cstage"><img id="cimg" alt="" draggable="false" src="https://app.local/o/' + p.id + '?v=' + p.ver + '&m=1600"><svg id="csvg" aria-hidden="true"></svg><div class="loupe" id="loupe"></div></div>' +
      '<div class="cropbar"><button type="button" class="k-btn outlined" data-a="back">Cancel</button><button type="button" class="k-btn filled" data-a="cropDone">' + Kit.icon('check') + 'Done</button></div></div>';
  }

  function setupCrop() {
    var p = page(), img = $('#cimg'), stage = $('#cstage'), svg = $('#csvg'), loupe = $('#loupe');
    if (!p || !img) return;
    var q = S.crop && S.crop.id === p.id ? S.crop.q : p.quad.slice();
    S.crop = { id: p.id, q: q };
    var box = null;
    function layout() {
      var r = img.getBoundingClientRect(), sr = stage.getBoundingClientRect();
      if (!r.width) return;
      box = { x: r.left - sr.left, y: r.top - sr.top, s: r.width / p.w, w: r.width, h: r.height };
      draw();
    }
    function pt(i) { return [box.x + q[i * 2] * box.s, box.y + q[i * 2 + 1] * box.s]; }
    function draw() {
      if (!box) return;
      var pts = [0, 1, 2, 3].map(pt);
      var poly = pts.map(function (a) { return a[0].toFixed(1) + ',' + a[1].toFixed(1); }).join(' ');
      var ok = convex(q);
      var h = '<path class="shade" fill-rule="evenodd" d="M0 0H' + stage.clientWidth + 'V' + stage.clientHeight + 'H0Z M' + pts.map(function (a) { return a[0] + ' ' + a[1]; }).join(' L') + 'Z"/>' +
        '<polygon class="edge' + (ok ? '' : ' bad') + '" points="' + poly + '"/>';
      pts.forEach(function (a, i) { h += '<circle class="hd" data-i="' + i + '" cx="' + a[0] + '" cy="' + a[1] + '" r="14"/>'; });
      svg.setAttribute('viewBox', '0 0 ' + stage.clientWidth + ' ' + stage.clientHeight);
      svg.innerHTML = h;
    }
    var drag = null;
    stage.addEventListener('pointerdown', function (e) {
      if (!box) return;
      var sr = stage.getBoundingClientRect(), x = e.clientX - sr.left, y = e.clientY - sr.top, best = -1, bd = 48 * 48;
      for (var i = 0; i < 4; i++) { var a = pt(i), d = (a[0] - x) * (a[0] - x) + (a[1] - y) * (a[1] - y); if (d < bd) { bd = d; best = i; } }
      if (best < 0) return;
      e.preventDefault();
      stage.setPointerCapture(e.pointerId);
      var a0 = pt(best);
      drag = { i: best, dx: a0[0] - x, dy: a0[1] - y };
      haptic('tick');
      move(e);
    });
    function move(e) {
      if (!drag) return;
      var sr = stage.getBoundingClientRect();
      var x = e.clientX - sr.left + drag.dx, y = e.clientY - sr.top + drag.dy;
      q[drag.i * 2] = Math.max(0, Math.min(p.w - 1, (x - box.x) / box.s));
      q[drag.i * 2 + 1] = Math.max(0, Math.min(p.h - 1, (y - box.y) / box.s));
      draw();
      // Magnifier: 2.5x around the corner, shown away from the finger.
      var a = pt(drag.i), z = 2.5, size = 112;
      loupe.style.display = 'block';
      loupe.style.backgroundImage = 'url("' + img.src + '")';
      loupe.style.backgroundSize = (box.w * z) + 'px ' + (box.h * z) + 'px';
      loupe.style.backgroundPosition = (size / 2 - (a[0] - box.x) * z) + 'px ' + (size / 2 - (a[1] - box.y) * z) + 'px';
      var above = a[1] - size - 40 > 0;
      loupe.style.left = Math.max(0, Math.min(stage.clientWidth - size, a[0] - size / 2)) + 'px';
      loupe.style.top = (above ? a[1] - size - 40 : a[1] + 40) + 'px';
    }
    stage.addEventListener('pointermove', move);
    function end() { drag = null; loupe.style.display = 'none'; }
    stage.addEventListener('pointerup', end);
    stage.addEventListener('pointercancel', end);
    if (img.complete && img.naturalWidth) layout(); else img.addEventListener('load', layout);
    window.addEventListener('resize', layout);
    S.crop.layout = layout;
  }

  /** Same rule as Warp.valid: convex, clockwise, not tiny. */
  function convex(q) {
    var sign = 0;
    for (var i = 0; i < 4; i++) {
      var a = i * 2, b = ((i + 1) % 4) * 2, c = ((i + 2) % 4) * 2;
      var cross = (q[b] - q[a]) * (q[c + 1] - q[b + 1]) - (q[b + 1] - q[a + 1]) * (q[c] - q[b]);
      var s = cross > 0 ? 1 : cross < 0 ? -1 : 0;
      if (!s) return false;
      if (!sign) sign = s; else if (s !== sign) return false;
    }
    var area = 0;
    for (var k = 0; k < 4; k++) { var j = (k + 1) % 4; area += q[k * 2] * q[j * 2 + 1] - q[j * 2] * q[k * 2 + 1]; }
    return sign > 0 && Math.abs(area) / 2 > 64;
  }

  // ---------------------------------------------------------------- scan details
  function detailView() {
    var s = scanById(top().id);
    if (!s) return subHeader('Scan') + '<div class="k-empty"><b>Not found</b>This scan is no longer in the library.</div>';
    var h = subHeader(s.name, dateText(s.created), icb('renameScan', Kit.icon('edit'), 'Rename'));
    h += '<div class="scroll">';
    if (s.missing) {
      h += '<div class="k-note warn" style="margin-top:8px">' + Kit.icon('warn') + '<span>The file was moved or deleted outside Folio. <button type="button" class="linkb" data-a="forgetScan">Remove from library</button></span></div>';
    }
    h += '<div class="pages">' + Array.apply(null, Array(Math.min(s.pages, 60))).map(function (_, i) {
      return '<button type="button" class="pthumb" data-a="openScan" aria-label="Open page ' + (i + 1) + '"><img alt="" loading="lazy" src="' + thumbUrl(s, i) + '"><span>' + (i + 1) + '</span></button>';
    }).join('') + '</div>';
    h += '<div class="btnrow detail"><button type="button" class="k-btn filled" data-a="openScan"' + (s.missing ? ' disabled' : '') + '>' + ic('open') + 'Open</button><button type="button" class="k-btn tonal" data-a="shareScan"' + (s.missing ? ' disabled' : '') + '>' + Kit.icon('share') + 'Share</button></div>';
    h += capRow('Collection') + chipRow([['', 'None']].concat(COLLECTIONS), s.collection || '', 'setColl', 'Collection');
    h += capRow('Details') + '<div class="k-group">' +
      row({ icon: ic('pdf'), t: s.format === 'pdf' ? 'PDF' : (s.pages > 1 ? 'JPEG images' : 'JPEG image'), sub: plural(s.pages, 'page') + ' · ' + sizeText(s.size || 0) }) +
      row({ icon: Kit.icon('folder'), t: s.folder || 'Documents/Folio', sub: 'Saved in' }) +
      row({ icon: Kit.icon('calendar'), t: new Date(s.created).toLocaleString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' }), sub: 'Scanned' }) + '</div>';
    h += '<div class="btnrow danger"><button type="button" class="k-btn text-danger" data-a="deleteScan">' + Kit.icon('trash') + 'Delete scan</button></div>';
    return h + '<div class="pad-b"></div></div>';
  }

  function searchView() {
    return subHeader('Search') + '<div class="scroll"><div class="k-empty"><b>Search comes with text recognition</b>Soon Folio reads the text of every scan, and understands amounts and dates like “receipts over 500 kr”.</div></div>';
  }

  function settingsView() {
    var st = S.status, pr = S.prefs;
    var o = subHeader('Settings') + '<div class="pscroll">';
    o += '<div id="kitapp">' + Kit.appearanceHtml() + '</div>';
    o += capRow('Scanning') + '<div class="k-group">' +
      '<div class="k-row k-col"><div class="k-rt"><span class="k-t">Filter for new pages</span><small>Auto keeps photos as they are and cleans up documents</small></div>' + chipRow(FILTERS, pr.filter || 'auto', 'defFilter', 'Filter for new pages').replace('k-chips', 'k-chips flush') + '</div>' +
      '<div class="k-row k-col"><div class="k-rt"><span class="k-t">Save as</span></div>' + seg('Save as', [['pdf', 'PDF'], ['jpg', 'JPEG']], pr.format || 'pdf', 'defFormat') + '</div>' +
      row({ icon: Kit.icon('folder'), t: 'Save to', sub: st.folder || 'Documents/Folio', btn: (st.customFolder ? '<button type="button" class="k-btn sm" data-a="resetFolder">Reset</button>' : '') + '<button type="button" class="k-btn tonal sm" data-a="pickFolder">Change</button>' }) + '</div>';
    o += capRow('Privacy') + '<div class="k-group">' +
      row({ icon: '<span class="dot" style="background:' + (st.noInternet === false ? 'var(--err)' : 'var(--ok)') + '"></span>', t: st.noInternet === false ? 'Internet permission present' : 'No internet permission', sub: 'Filters, page flattening and saving all happen on this phone' }) + '</div>';
    o += '<p class="k-about">Folio ' + esc(st.version || '') + '<br>Part of a suite of offline apps · themes from karui</p>';
    return o + '</div>';
  }

  // ---------------------------------------------------------------- render
  function render() {
    var s = top().s, html;
    if (s === 'settings') html = settingsView();
    else if (s === 'search') html = searchView();
    else if (s === 'review') html = reviewView();
    else if (s === 'crop') html = cropView();
    else if (s === 'scan') html = detailView();
    else html = homeView();
    $('#app').innerHTML = '<div class="screen s-' + s + '" data-screen="' + s + '">' + html + '</div>';
    after(s);
    renderOverlay();
    Kit.syncBack();
  }

  function after(s) {
    if (s === 'settings') Kit.bindAppearance($('#kitapp'), function () { render(); });
    // Crop is always dark: give it dark system bars, and hand the bars back to the theme afterwards.
    if (s === 'crop') { setupCrop(); if (!S.darkChrome) { S.darkChrome = true; try { Kit.native && Kit.native.setChrome('#111111', true); } catch (e) {} } }
    else if (S.darkChrome) { S.darkChrome = false; Kit.apply(); }
    if (s === 'review') {
      var img = $('#pimg'), spin = $('#spin');
      if (img) {
        var done = function () { if (spin) spin.style.display = 'none'; img.classList.add('ok'); };
        if (img.complete && img.naturalWidth) done(); else { img.addEventListener('load', done); img.addEventListener('error', done); }
        swipe($('#stage'));
      }
      var on = document.querySelector('.strip .pg.on');
      if (on) on.scrollIntoView({ block: 'nearest', inline: 'center' });
      var p = page();
      if (p && p.filter === 'auto' && !p.used) call('prepare', [p.id]).then(function (r) { if (r.ok && r.used) { p.used = r.used; var hs = document.querySelector('.k-hs'); if (hs && top().s === 'review') hs.textContent = 'Page ' + (S.cur + 1) + ' of ' + S.pages.length + ' · Auto · ' + (FILTER_NAME[r.used] || r.used); } });
      stripDrag($('#strip'));
    }
  }

  /** Horizontal swipe on the page to move between pages. */
  function swipe(el) {
    if (!el) return;
    var x0 = null, y0 = 0;
    el.addEventListener('pointerdown', function (e) { if (e.target.closest('button')) return; x0 = e.clientX; y0 = e.clientY; });
    el.addEventListener('pointerup', function (e) {
      if (x0 === null) return;
      var dx = e.clientX - x0, dy = e.clientY - y0; x0 = null;
      if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) pageGo(dx < 0 ? 1 : -1);
    });
  }
  function pageGo(d) { var n = S.cur + d; if (n < 0 || n >= S.pages.length) return; S.cur = n; S.tool = null; haptic('tick'); render(); }

  /** Long-press a page in the strip, then drag it sideways to reorder. */
  var lpFired = false;
  function stripDrag(strip) {
    if (!strip) return;
    var timer = null, item = null, startX = 0, ghostX = 0, moved = false;
    strip.addEventListener('pointerdown', function (e) {
      var b = e.target.closest('.pg[data-id]');
      if (!b) return;
      startX = e.clientX; moved = false;
      clearTimeout(timer);
      timer = setTimeout(function () {
        item = b; lpFired = true; haptic('long');
        b.classList.add('lift'); strip.classList.add('sorting');
        try { strip.setPointerCapture(e.pointerId); } catch (er) {}
      }, 380);
    });
    strip.addEventListener('pointermove', function (e) {
      if (!item) { if (Math.abs(e.clientX - startX) > 8) clearTimeout(timer); return; }
      e.preventDefault();
      moved = true;
      ghostX = e.clientX - startX;
      item.style.transform = 'translateX(' + ghostX + 'px) scale(1.08)';
    });
    function drop() {
      clearTimeout(timer);
      if (!item) return;
      var id = item.dataset.id, from = +item.dataset.i, w = item.offsetWidth + 8;
      var to = Math.max(0, Math.min(S.pages.length - 1, from + Math.round(ghostX / w)));
      item.classList.remove('lift'); item.style.transform = ''; strip.classList.remove('sorting');
      item = null;
      setTimeout(function () { lpFired = false; }, 50);
      if (moved && to !== from) {
        var p = S.pages.splice(from, 1)[0]; S.pages.splice(to, 0, p); S.cur = to;
        haptic('confirm'); render();
        call('movePage', [id, to]).then(function () { loadPages(); });
      }
    }
    strip.addEventListener('pointerup', drop);
    strip.addEventListener('pointercancel', drop);
    strip.addEventListener('contextmenu', function (e) { e.preventDefault(); });
  }

  // ---------------------------------------------------------------- overlays: sheets, dialogs, snackbar
  function renderOverlay() {
    var h = '';
    if (S.sheet) h += '<button type="button" class="k-scrim" data-a="sheetClose" aria-label="Close"></button><div class="k-sheet" role="dialog" aria-label="' + esc(S.sheet.title) + '"><div class="k-grab"></div><h3>' + esc(S.sheet.title) + '</h3>' + S.sheet.body() + '</div>';
    if (S.modal) {
      var m = S.modal;
      h += '<button type="button" class="k-scrim" data-a="modalNo" aria-label="Cancel"></button><div class="k-modal" role="alertdialog" aria-label="' + esc(m.title) + '"><h3>' + esc(m.title) + '</h3>' + (m.body ? '<p>' + esc(m.body) + '</p>' : '') + (m.field ? '<input class="k-field" id="mfield" value="' + esc(m.field) + '" maxlength="120" autocomplete="off" spellcheck="false">' : '') +
        '<div class="k-acts"><button type="button" class="k-btn sm" data-a="modalNo">Cancel</button><button type="button" class="k-btn ' + (m.danger ? 'danger' : 'filled') + ' sm" data-a="modalOk">' + esc(m.okLabel || 'OK') + '</button></div></div>';
    }
    if (S.busy) h += '<div class="busy" role="status"><div class="spin"></div><span>' + esc(S.busy) + '</span></div>';
    if (S.snack) h += '<div class="k-snack' + (document.querySelector('.k-fab,.savebar,.cropbar') ? ' high' : '') + '" role="status"><span>' + esc(S.snack.msg) + '</span>' + (S.snack.action ? '<button type="button" data-a="snackAct">' + esc(S.snack.action.label) + '</button>' : '') + '</div>';
    var o = $('#overlay'), hadField = !!$('#mfield');
    o.innerHTML = h;
    var f = $('#mfield'); if (f && !hadField) { f.focus(); f.select(); }
    var nm = $('#sname'); if (nm && S.sheet && S.sheet.focus) { S.sheet.focus = false; nm.focus(); }
    Kit.syncBack();
  }

  function scanSheet() {
    S.sheet = { title: 'Scan', body: function () {
      return '<div class="k-group">' +
        row({ lead: ic('camera'), t: 'Camera', sub: 'Live page finder arrives in the next update', cls: 'soon' }) +
        row({ a: 'importPhotos', lead: ic('photos'), t: 'From Photos', sub: 'Pick photos or screenshots of pages', chev: true }) +
        row({ a: 'importFiles', lead: ic('pdf'), t: 'From Files', sub: 'Images or PDFs; every PDF page becomes a page', chev: true }) + '</div>';
    } };
    renderOverlay();
  }

  function saveSheet() {
    var st = S.sheet = { title: 'Save scan', name: S.saveName || nat('defaultName') || 'Scan', format: S.prefs.format || 'pdf', coll: '', focus: false, body: function () {
      return '<label class="flabel" for="sname">Name</label><input class="k-field" id="sname" value="' + esc(st.name) + '" maxlength="120" autocomplete="off" spellcheck="false">' +
        '<div class="flabel">Format</div>' + seg('Format', [['pdf', 'PDF'], ['jpg', 'JPEG']], st.format, 'saveFormat') +
        '<div class="flabel">Collection</div>' + chipRow([['', 'None']].concat(COLLECTIONS), st.coll, 'saveColl', 'Collection').replace('k-chips', 'k-chips flush') +
        '<div class="sum">' + esc(plural(S.pages.length, 'page') + ' · ' + (st.format === 'pdf' ? 'one PDF' : S.pages.length > 1 ? S.pages.length + ' JPEG images' : 'a JPEG image') + ' in ' + (S.status.folder || 'Documents/Folio')) + '</div>' +
        '<button type="button" class="k-btn filled wide" data-a="saveGo"' + (st.saving ? ' disabled' : '') + '>' + (st.saving ? 'Saving…' : Kit.icon('check') + 'Save') + '</button>';
    } };
    renderOverlay();
  }
  function keepName() { var f = $('#sname'); if (f && S.sheet) S.sheet.name = f.value; }

  function addSheet() {
    S.sheet = { title: 'Add pages', body: function () {
      return '<div class="k-group">' +
        row({ a: 'importPhotos', lead: ic('photos'), t: 'From Photos', chev: true }) +
        row({ a: 'importFiles', lead: ic('pdf'), t: 'From Files', sub: 'Images or PDFs', chev: true }) + '</div>';
    } };
    renderOverlay();
  }

  function pageMenu() {
    S.sheet = { title: 'Page ' + (S.cur + 1), body: function () {
      var h = '<div class="k-group">';
      if (S.cur > 0) h += row({ a: 'movePage', attrs: ' data-d="-1"', icon: ic('left'), t: 'Move earlier' });
      if (S.cur < S.pages.length - 1) h += row({ a: 'movePage', attrs: ' data-d="1"', icon: ic('right'), t: 'Move later' });
      h += row({ a: 'rotateL', icon: ic('rotL'), t: 'Rotate left' }) + row({ a: 'addSheet', icon: ic('addp'), t: 'Add pages' }) + '</div>';
      h += '<div class="k-group" style="margin-top:12px">' + row({ a: 'discard', icon: Kit.icon('trash'), t: 'Discard this scan', sub: 'Removes all ' + plural(S.pages.length, 'page') + ' without saving', cls: 'danger' }) + '</div>';
      return h;
    } };
    renderOverlay();
  }

  // ---------------------------------------------------------------- actions
  function pageOp(fn, args, msg) {
    var p = page();
    if (!p) return Promise.resolve({ ok: false });
    busy(msg || null);
    return call(fn, [p.id].concat(args || [])).then(function (r) {
      busy(null);
      loadPages();
      if (!r.ok && !r.cancelled) snack(r.error || 'Something went wrong');
      render();
      return r;
    });
  }
  function importFrom(fn) {
    S.sheet = null; renderOverlay();
    call(fn).then(function (r) {
      // Pickers answer twice: once picked ("imported" with this token), or once cancelled.
      if (r.type === 'imported') afterImport(r);
      else if (!r.ok && !r.cancelled) snack(r.error || 'Couldn’t open the picker');
    });
  }

  var A = {
    back: function () { back(); },
    go: function (d) { if (d.to === 'review') loadPages(); go(d.to); },
    coll: function (d) { if (S.collection !== d.v) { S.collection = d.v; haptic('tick'); render(); } },
    scanSheet: function () { haptic('tap'); scanSheet(); },
    addSheet: function () { addSheet(); },
    sheetClose: function () { S.sheet = null; renderOverlay(); },
    importPhotos: function () { importFrom('importPhotos'); },
    importFiles: function () { importFrom('importFiles'); },
    openDetail: function (d) { go('scan', { id: d.id }); },
    pageSel: function (d) { if (lpFired) return; S.cur = +d.i; S.tool = null; render(); },
    pageMenu: function () { pageMenu(); },
    crop: function () { S.crop = null; go('crop'); },
    cropReset: function () {
      var p = page(); if (!p || !S.crop) return;
      var q = [0, 0, p.w - 1, 0, p.w - 1, p.h - 1, 0, p.h - 1];
      for (var i = 0; i < 8; i++) S.crop.q[i] = q[i];
      haptic('tick'); if (S.crop.layout) S.crop.layout();
    },
    cropDone: function () {
      var p = page(); if (!p || !S.crop) return;
      if (!convex(S.crop.q)) { haptic('reject'); snack('The corners need to outline the page'); return; }
      var q = S.crop.q.map(function (v) { return Math.round(v * 10) / 10; });
      S.nav.pop(); S.crop = null;
      busy('Flattening…');
      call('setQuad', [p.id, JSON.stringify(q)]).then(function (r) { busy(null); loadPages(); if (!r.ok) snack(r.error || 'Couldn’t crop'); render(); });
    },
    rotate: function () { haptic('tap'); pageOp('rotate', [true]); },
    rotateL: function () { S.sheet = null; pageOp('rotate', [false]); },
    filterTool: function () { S.tool = 'filter'; render(); },
    toolDone: function () { S.tool = null; render(); },
    filter: function (d) { var p = page(); if (!p || p.filter === d.v) return; haptic('tick'); p.filter = d.v; p.used = ''; pageOp('setFilter', [d.v, false]).then(function () { S.tool = 'filter'; render(); }); },
    filterAll: function () {
      var p = page(); if (!p) return;
      call('setFilter', [p.id, p.filter, true]).then(function (r) { loadPages(); render(); if (r.ok) snack(FILTER_NAME[p.filter] + ' on all ' + plural(S.pages.length, 'page')); });
    },
    movePage: function (d) {
      var p = page(), to = S.cur + (+d.d); S.sheet = null;
      if (!p || to < 0 || to >= S.pages.length) { renderOverlay(); return; }
      call('movePage', [p.id, to]).then(function () { loadPages(); S.cur = to; render(); });
    },
    delPage: function () {
      var p = page(); if (!p) return;
      var only = S.pages.length === 1;
      confirmBox({ title: only ? 'Delete the only page?' : 'Delete page ' + (S.cur + 1) + '?', body: only ? 'This scan will be empty.' : 'The other pages stay.', okLabel: 'Delete', danger: true, ok: function () {
        call('deletePage', [p.id]).then(function () { loadPages(); if (!S.pages.length) { home(); return; } render(); });
      } });
    },
    discard: function () {
      S.sheet = null;
      confirmBox({ title: 'Discard this scan?', body: plural(S.pages.length, 'page') + ' will be removed. Nothing has been saved yet.', okLabel: 'Discard', danger: true, ok: function () {
        call('discard').then(function () { loadPages(); home(); });
      } });
    },
    saveSheet: function () { saveSheet(); },
    saveFormat: function (d) { keepName(); S.sheet.format = d.v; renderOverlay(); },
    saveColl: function (d) { keepName(); S.sheet.coll = d.v; renderOverlay(); },
    saveGo: function () {
      keepName();
      var st = S.sheet; if (!st || st.saving) return;
      st.saving = true; renderOverlay();
      call('save', [st.name, st.format, st.coll]).then(function (r) {
        S.sheet = null;
        if (!r.ok) { snack('Couldn’t save: ' + (r.error || 'unknown error')); renderOverlay(); return; }
        haptic('confirm');
        loadPages(); loadScans(); refreshStatus();
        S.nav = [{ s: 'home' }];
        render();
        var e = r.entry || {};
        snack('Saved to ' + (e.folder || 'Documents/Folio'), { label: 'Open', fn: function () { if (!nat('openScan', e.id)) snack('No app can open it'); } });
      });
    },
    snackAct: function () { var s = S.snack; S.snack = null; renderOverlay(); if (s && s.action) s.action.fn(); },
    openScan: function () { var s = scanById(top().id); if (s && !nat('openScan', s.id)) snack('No app on this phone can open it'); },
    shareScan: function () { var s = scanById(top().id); if (s && !nat('shareScan', s.id)) snack('Couldn’t share'); },
    setColl: function (d) { var s = scanById(top().id); if (!s) return; if (nat('setCollection', s.id, d.v)) { s.collection = d.v; haptic('tick'); render(); } },
    renameScan: function () {
      var s = scanById(top().id); if (!s) return;
      confirmBox({ title: 'Rename', field: s.name, okLabel: 'Rename', ok: function (v) {
        v = (v || '').trim(); if (!v || v === s.name) return;
        call('renameScan', [s.id, v]).then(function (r) { loadScans(); render(); snack(r.ok ? 'Renamed' : 'Couldn’t rename: ' + (r.error || '')); });
      } });
    },
    deleteScan: function () {
      var s = scanById(top().id); if (!s) return;
      confirmBox({ title: 'Delete “' + s.name + '”?', body: 'The ' + (s.format === 'pdf' ? 'PDF' : 'image' + (s.pages > 1 ? 's' : '')) + ' in ' + (s.folder || 'Documents/Folio') + ' will be deleted too.', okLabel: 'Delete', danger: true, ok: function () {
        call('deleteScan', [s.id]).then(function (r) { if (!r.ok) { snack('Couldn’t delete: ' + (r.error || '')); return; } loadScans(); S.nav.pop(); render(); snack('Deleted'); });
      } });
    },
    forgetScan: function () { var s = scanById(top().id); if (!s) return; nat('forgetScan', s.id); loadScans(); S.nav.pop(); render(); },
    defFilter: function (d) { setPref('filter', d.v); render(); },
    defFormat: function (d) { setPref('format', d.v); render(); },
    pickFolder: function () { call('pickFolder').then(function (r) { refreshStatus(); render(); if (r.ok) snack('Saving to ' + r.folder); else if (r.error) snack(r.error); }); },
    resetFolder: function () { nat('resetFolder'); refreshStatus(); render(); snack('Saving to Documents/Folio'); },
    modalNo: function () { S.modal = null; renderOverlay(); },
    modalOk: function () { var m = S.modal, f = $('#mfield'), v = f ? f.value : undefined; S.modal = null; renderOverlay(); if (m && m.ok) m.ok(v); },
  };
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-a]');
    if (!b || b.disabled) return;
    var fn = A[b.dataset.a];
    if (fn) fn(b.dataset, b);
  });
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    if (e.target.id === 'mfield') A.modalOk();
    else if (e.target.id === 'sname') A.saveGo();
  });

  // ---------------------------------------------------------------- app icon (same artwork as the launcher icon)
  var APPICON = '<svg aria-hidden="true" viewBox="0 0 108 108"><rect width="108" height="108" fill="#b8bb26"/><path d="M30 40v-6a4 4 0 0 1 4 -4h6M68 30h6a4 4 0 0 1 4 4v6M78 68v6a4 4 0 0 1 -4 4h-6M40 78h-6a4 4 0 0 1 -4 -4v-6" fill="none" stroke="#1d2021" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M42 46h24M42 54h24M42 62h14" fill="none" stroke="#1d2021" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  // ---------------------------------------------------------------- start
  function start() {
    try { S.prefs = JSON.parse(nat('prefs') || '{}') || {}; } catch (e) { S.prefs = {}; }
    Kit.apply();
    Kit.onTheme(function () { render(); });
    refreshStatus();
    loadPages();
    loadScans();
    render();
    handleLaunch();
    call('refreshLibrary').then(function () { loadScans(); if (top().s === 'home') render(); });
  }
  start();
})();
