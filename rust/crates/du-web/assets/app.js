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

  // "Cancel" buttons that clear an htmx-loaded detail panel (replaces inline onclick).
  document.addEventListener('click', function (e) {
    var btn = e.target.closest && e.target.closest('[data-clear-target]');
    if (!btn) return;
    var el = document.getElementById(btn.getAttribute('data-clear-target'));
    if (el) { el.innerHTML = ''; }
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
