/* Folio — interface. Built on the shared kit (kit/kit.js, kit/kit.css), the same look as the
   rest of the suite. Talks to Android through window.Native (Bridge.java). */
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
  };
  function ic(name, cls) { return Kit.icon(P[name] || name, cls); }

  // Collections Folio fills automatically (editable later).
  var COLLECTIONS = [['all', 'All'], ['receipts', 'Receipts'], ['bills', 'Bills'], ['letters', 'Letters'], ['id', 'ID'], ['manuals', 'Manuals'], ['notes', 'Notes']];

  // ---------------------------------------------------------------- state
  var S = { nav: [{ s: 'home' }], prefs: {}, status: {}, collection: 'all', scans: [], snack: null, modal: null };
  function top() { return S.nav[S.nav.length - 1]; }
  function nat(fn) { try { return N && N[fn] ? N[fn].apply(N, [].slice.call(arguments, 1)) : undefined; } catch (e) { return undefined; } }
  function refreshStatus() { try { S.status = JSON.parse(nat('status') || '{}'); } catch (e) { S.status = {}; } }

  var snackT;
  function snack(msg) {
    S.snack = { msg: msg };
    clearTimeout(snackT);
    snackT = setTimeout(function () { S.snack = null; renderOverlay(); }, 3000);
    renderOverlay();
  }

  // ---------------------------------------------------------------- navigation and back
  function go(s) { S.nav.push({ s: s }); render(); }
  function back() {
    if (S.modal) { S.modal = null; renderOverlay(); return true; }
    if (S.nav.length > 1) { S.nav.pop(); render(); return true; }
    if (S.collection !== 'all') { S.collection = 'all'; render(); return true; }
    return false;
  }
  function canBack() { return !!(S.modal || S.nav.length > 1 || S.collection !== 'all'); }
  Kit.onBack(back, canBack);

  window.app = {
    back: back,
    onNative: function (ev) {
      if (ev.type === 'resume') { refreshStatus(); if (top().s === 'settings') render(); }
    },
    state: S,
  };

  // ---------------------------------------------------------------- shared pieces
  function header(t, sub, actions) {
    return '<header class="k-head"><div class="k-ht"><h1>' + esc(t) + '</h1>' + (sub ? '<div class="k-hs">' + esc(sub) + '</div>' : '') + '</div><div class="acts">' + (actions || '') + '</div></header>';
  }
  function subHeader(t) {
    return '<header class="k-head k-small"><button type="button" class="k-icb" data-a="back" aria-label="Back">' + Kit.icon('back') + '</button><div class="k-ht"><h1>' + esc(t) + '</h1></div></header>';
  }
  function icb(a, icon, label, extra) { return '<button type="button" class="k-icb" data-a="' + a + '"' + (extra || '') + ' aria-label="' + esc(label) + '">' + icon + '</button>'; }
  function capRow(t) { return '<div class="k-cap pri"><span>' + esc(t) + '</span></div>'; }
  function row(o) {
    // o: {a, attrs, lead (html), icon (html), t, sub, val, chev, btn}
    var tag = o.a ? 'button type="button" data-a="' + o.a + '"' + (o.attrs || '') : 'div';
    return '<' + tag + ' class="k-row">' +
      (o.lead ? '<span class="k-lead">' + o.lead + '</span>' : o.icon ? o.icon : '') +
      '<span class="k-rt"><span class="k-t">' + esc(o.t) + '</span>' + (o.sub != null ? '<small>' + esc(o.sub) + '</small>' : '') + '</span>' +
      (o.val ? '<span class="k-val">' + esc(o.val) + '</span>' : '') + (o.btn || '') + (o.chev ? Kit.icon('chevR', 'k-chev') : '') +
      '</' + (o.a ? 'button' : 'div') + '>';
  }
  function chips() {
    return '<div class="k-chips" role="tablist" aria-label="Collections">' + COLLECTIONS.map(function (c) {
      var on = S.collection === c[0];
      return '<button type="button" class="k-chip" role="tab" aria-selected="' + on + '" aria-pressed="' + on + '" data-a="coll" data-v="' + c[0] + '">' + (on ? Kit.icon('check') : '') + esc(c[1]) + '</button>';
    }).join('') + '</div>';
  }

  // ---------------------------------------------------------------- screens
  function homeView() {
    var sub = S.scans.length ? S.scans.length + (S.scans.length === 1 ? ' scan' : ' scans') : 'No scans yet';
    var h = header('Folio', sub, icb('go', Kit.icon('search'), 'Search', ' data-to="search"') + icb('go', Kit.icon('tune'), 'Settings', ' data-to="settings"'));
    h += '<div class="scroll" id="sc">' + chips();
    var name = COLLECTIONS.filter(function (c) { return c[0] === S.collection; })[0][1];
    h += '<div class="empty"><div class="appic">' + APPICON + '</div>' +
      '<b>' + (S.collection === 'all' ? 'Make paper disappear' : 'No ' + esc(name.toLowerCase()) + ' yet') + '</b>' +
      '<p>' + (S.collection === 'all' ? 'Scan a page and Folio turns it into a named, searchable PDF in Documents/Folio.' : 'Scans Folio recognises as ' + esc(name.toLowerCase()) + ' show up here.') + '</p></div>';
    if (S.collection === 'all') {
      h += '<div class="k-group hints">' +
        row({ lead: ic('text'), t: 'Searchable text', sub: 'Every page is read on the phone, in English and Swedish' }) +
        row({ lead: ic('tag'), t: 'Knows what it is', sub: 'Receipts, invoices and letters are named and sorted for you' }) +
        row({ lead: ic('nonet'), t: 'Stays on this phone', sub: 'Folio has no internet permission' }) + '</div>';
    }
    h += '<div class="pad-b"></div></div>';
    h += '<button type="button" class="k-fab ext" data-a="scan">' + ic('scan') + 'Scan</button>';
    return h;
  }

  function searchView() {
    return subHeader('Search') + '<div class="scroll"><div class="k-empty"><b>Nothing to search yet</b>Search reads the text of every scan, and understands amounts and dates like “receipts over 500 kr”.</div></div>';
  }

  function settingsView() {
    var st = S.status;
    var o = subHeader('Settings') + '<div class="pscroll">';
    o += '<div id="kitapp">' + Kit.appearanceHtml() + '</div>';
    o += capRow('Privacy') + '<div class="k-group">' +
      row({ icon: '<span class="dot" style="background:' + (st.noInternet === false ? 'var(--err)' : 'var(--ok)') + '"></span>', t: st.noInternet === false ? 'Internet permission present' : 'No internet permission', sub: 'Text recognition, sorting and search are worked out on this phone' }) + '</div>';
    o += '<p class="k-about">Folio ' + esc(st.version || '') + '<br>Part of a suite of offline apps · themes from karui</p>';
    return o + '</div>';
  }

  function render() {
    var s = top().s, html;
    if (s === 'settings') html = settingsView();
    else if (s === 'search') html = searchView();
    else html = homeView();
    $('#app').innerHTML = '<div class="screen s-' + s + '" data-screen="' + s + '">' + html + '</div>';
    if (s === 'settings') Kit.bindAppearance($('#kitapp'), function () { render(); });
    renderOverlay();
    Kit.syncBack();
  }

  function renderOverlay() {
    var h = '';
    if (S.snack) h += '<div class="k-snack" role="status"><span>' + esc(S.snack.msg) + '</span></div>';
    $('#overlay').innerHTML = h;
    Kit.syncBack();
  }

  // ---------------------------------------------------------------- actions
  var A = {
    back: function () { back(); },
    go: function (d) { go(d.to); },
    coll: function (d) { if (S.collection !== d.v) { S.collection = d.v; haptic('tick'); render(); } },
    scan: function () { haptic('tap'); snack('Scanning arrives in the next update'); },
  };
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-a]');
    if (!b) return;
    var fn = A[b.dataset.a];
    if (fn) fn(b.dataset, b);
  });

  // ---------------------------------------------------------------- app icon (same artwork as the launcher icon)
  var APPICON = '<svg aria-hidden="true" viewBox="0 0 108 108"><rect width="108" height="108" fill="#b8bb26"/><path d="M30 40v-6a4 4 0 0 1 4 -4h6M68 30h6a4 4 0 0 1 4 4v6M78 68v6a4 4 0 0 1 -4 4h-6M40 78h-6a4 4 0 0 1 -4 -4v-6" fill="none" stroke="#1d2021" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M42 46h24M42 54h24M42 62h14" fill="none" stroke="#1d2021" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  // ---------------------------------------------------------------- start
  function start() {
    try { S.prefs = JSON.parse(nat('prefs') || '{}') || {}; } catch (e) { S.prefs = {}; }
    Kit.apply();
    Kit.onTheme(function () { render(); });
    refreshStatus();
    render();
  }
  start();
})();
