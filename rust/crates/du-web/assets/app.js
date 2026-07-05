// DecodingUs global client behaviors. Loaded once in the base layout; reacts to
// content (re)rendered by hx-boost / HTMX via the htmx:load event. Externalized
// from inline <script> so the CSP can drop script-src 'unsafe-inline'.
(function () {
  if (window.__duApp) return;
  window.__duApp = true;

  function csrfToken() {
    var m = document.cookie.match(/(?:^|;\s*)csrf=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : '';
  }

  // CSRF double-submit: echo the `csrf` cookie as a header on every HTMX request
  // (hx-boost routes all form submits + links through HTMX, so this covers them).
  document.addEventListener('htmx:configRequest', function (e) {
    var t = csrfToken();
    if (t) { e.detail.headers['X-CSRF-Token'] = t; }
  });

  // Native (hx-boost="false") POST forms — e.g. login/logout — can't send a custom
  // header, so mirror the `csrf` cookie into a hidden `csrf_token` field the server
  // accepts as the double-submit token. Capture phase so it runs before submission.
  document.addEventListener('submit', function (e) {
    var f = e.target;
    if (!f || String(f.method || '').toLowerCase() !== 'post') return;
    if (f.querySelector('input[name="csrf_token"]')) return;
    var t = csrfToken();
    if (!t) return;
    var i = document.createElement('input');
    i.type = 'hidden';
    i.name = 'csrf_token';
    i.value = t;
    f.appendChild(i);
  }, true);

  // AT Protocol sign-in: the GET /login/atproto form redirects to an external
  // authorization server (bsky.app or any PDS), which CSP `form-action 'self'` blocks
  // (it governs form-submission redirect targets). Navigate via JS instead — location
  // changes aren't governed by form-action — so the server can still PAR + redirect to
  // any auth server. Bubble phase (after the csrf capture handler, which ignores GET).
  document.addEventListener('submit', function (e) {
    var f = e.target;
    if (!f || f.getAttribute('action') !== '/login/atproto') return;
    var input = f.querySelector('input[name="handle"]');
    var handle = input && input.value.trim();
    if (!handle) return; // let the browser's `required` validation handle empties
    e.preventDefault();
    window.location.assign('/login/atproto?handle=' + encodeURIComponent(handle));
  });

  // "Cancel" buttons that clear an htmx-loaded detail panel (replaces inline onclick).
  document.addEventListener('click', function (e) {
    var btn = e.target.closest && e.target.closest('[data-clear-target]');
    if (!btn) return;
    var el = document.getElementById(btn.getAttribute('data-clear-target'));
    if (el) { el.innerHTML = ''; }
  });

  // Auto-submit a form when a `data-autosubmit` <select> changes (CSP-safe
  // replacement for inline onchange). The submit button is the no-JS fallback.
  document.addEventListener('change', function (e) {
    var sel = e.target.closest && e.target.closest('select[data-autosubmit]');
    if (sel && sel.form) { sel.form.submit(); }
  });

  // GDPR cookie banner: show + wire it when present and no consent cookie is set.
  function wireBanner() {
    if (document.cookie.split('; ').some(function (c) { return c.indexOf('du_consent=') === 0; })) return;
    var b = document.getElementById('cookie-banner');
    if (!b) return;
    b.classList.remove('d-none');
    b.querySelectorAll('button[data-consent]:not([data-wired])').forEach(function (btn) {
      btn.setAttribute('data-wired', '1');
      btn.addEventListener('click', function () {
        fetch('/cookie-consent', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-CSRF-Token': csrfToken() },
          body: new URLSearchParams({ consent: btn.getAttribute('data-consent') })
        }).then(function () { b.classList.add('d-none'); });
      });
    });
  }
  document.addEventListener('htmx:load', wireBanner);
  wireBanner();
})();
