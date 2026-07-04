// Tree page: persisted depth preference + SNP off-canvas. Externalized from the
// inline <script> in tree/page.html; idempotent and event-driven so it survives
// hx-boost head-merge + body swaps without script re-execution.
(function () {
  if (window.__duTree) return;
  window.__duTree = true;
  var KEY = 'du.tree.depth', MIN = 1, MAX = 8;

  function storedDepth() {
    var n = parseInt(localStorage.getItem(KEY), 10);
    return (n >= MIN && n <= MAX) ? n : null;
  }

  // The focused root is authoritative in the URL — every re-root / search / depth
  // change pushes `?root=…`. The control bar (search box, depth select, orientation
  // toggle) is rendered OUTSIDE the swapped `#tree-container`, so its server-rendered
  // root goes stale after an HTMX search. Read the live value instead of that stale
  // attribute, so changing depth or orientation keeps the focused node.
  function currentRoot(fallback) {
    var m = /[?&]root=([^&]*)/.exec(window.location.search);
    return m && m[1] ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : (fallback || '');
  }

  // Inject the persisted depth into every tree-window request (re-root, breadcrumb,
  // search, orientation toggle) — but not the SNP sidebar.
  document.addEventListener('htmx:configRequest', function (e) {
    var p = (e.detail && e.detail.path) || '';
    if (p.indexOf('/snp/') === -1 && (p.indexOf('/ytree') === 0 || p.indexOf('/mtree') === 0)) {
      var d = storedDepth();
      if (d) { e.detail.parameters['depth'] = String(d); }
    }
  });

  // Open the off-canvas SNP panel whenever a variant click swaps into it.
  document.addEventListener('htmx:afterSwap', function (e) {
    var t = e.detail && e.detail.target;
    if (t && t.id === 'snpSidebar' && t.innerHTML.trim() !== '') {
      bootstrap.Offcanvas.getOrCreateInstance(document.getElementById('snpOffcanvas')).show();
    }
  });

  // Keep the orientation-toggle links pointed at the live focused root (+ stored depth),
  // so switching orientation doesn't navigate back to the default root. Re-synced after
  // every tree swap (the links sit outside the swapped region, so they persist and are
  // just rewritten in place).
  function syncOrientLinks() {
    var root = currentRoot();
    document.querySelectorAll('a[href*="orient="]').forEach(function (a) {
      var u = new URL(a.getAttribute('href'), window.location.origin);
      if (root) { u.searchParams.set('root', root); }
      var d = storedDepth();
      if (d) { u.searchParams.set('depth', String(d)); }
      a.setAttribute('href', u.pathname + u.search);
    });
  }

  // Per-render: wire the selector and align the view to the stored preference.
  function wireDepth() {
    var sel = document.getElementById('tree-depth');
    if (!sel || sel.dataset.wired) return;
    sel.dataset.wired = '1';
    var base = sel.dataset.base;
    var reload = function (push) {
      // Use the live root, not the (possibly stale) rendered data-root.
      var url = base + '?root=' + encodeURIComponent(currentRoot(sel.dataset.root));
      htmx.ajax('GET', url, { target: '#tree-container', pushUrl: !!push });
    };
    var rendered = parseInt(sel.value, 10);
    var stored = storedDepth();
    // Returning visitor whose preference differs from this (default) render:
    // reload just the tree window at the stored depth.
    if (stored && stored !== rendered) {
      sel.value = String(stored);
      reload(false);
    }
    sel.addEventListener('change', function () {
      localStorage.setItem(KEY, sel.value);
      reload(true);
    });
  }

  function onRender() {
    wireDepth();
    syncOrientLinks();
  }
  document.addEventListener('htmx:load', onRender);
  document.addEventListener('htmx:afterSettle', syncOrientLinks);
  onRender();
})();
