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

  // Per-render: wire the selector and align the view to the stored preference.
  function wireDepth() {
    var sel = document.getElementById('tree-depth');
    if (!sel || sel.dataset.wired) return;
    sel.dataset.wired = '1';
    var base = sel.dataset.base, root = sel.dataset.root;
    var url = base + '?root=' + encodeURIComponent(root);
    var rendered = parseInt(sel.value, 10);
    var stored = storedDepth();
    // Returning visitor whose preference differs from this (default) render:
    // reload just the tree window at the stored depth.
    if (stored && stored !== rendered) {
      sel.value = String(stored);
      htmx.ajax('GET', url, { target: '#tree-container' });
    }
    sel.addEventListener('change', function () {
      localStorage.setItem(KEY, sel.value);
      htmx.ajax('GET', url, { target: '#tree-container', pushUrl: true });
    });
  }
  document.addEventListener('htmx:load', wireDepth);
  wireDepth();
})();
