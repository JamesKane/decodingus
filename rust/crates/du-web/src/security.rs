//! Global security-response-header middleware.
//!
//! Applied to every response in [`crate::routes::app`]. Sets the standard browser
//! hardening headers the app previously lacked: a Content-Security-Policy, HSTS,
//! anti-clickjacking, MIME-sniffing protection, referrer and permissions policy.
//!
//! **CSP note:** assets are self-hosted, but the templates still carry a few inline
//! `<script>`/`<style>` blocks and `on*` handlers, so `script-src`/`style-src` allow
//! `'unsafe-inline'` for now (plus the Google reCAPTCHA origins the contact/suggest
//! forms use). Moving the inline scripts to files + a per-request nonce is the
//! follow-up that would let us drop `'unsafe-inline'` from `script-src`.

use axum::extract::Request;
use axum::http::{header, HeaderName, HeaderValue, Method, StatusCode};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use uuid::Uuid;

/// `Permissions-Policy` (no `http::header` constant for it).
const PERMISSIONS_POLICY: HeaderName = HeaderName::from_static("permissions-policy");

/// Content-Security-Policy. Scripts are `'self'` (all externalized to /assets) plus
/// the Google reCAPTCHA origins the public forms load. `style-src` still allows
/// `'unsafe-inline'` (Bootstrap + a few inline `style=` / `<style>` blocks; style
/// injection is far lower-risk than script injection). `img-src` allows the
/// OpenStreetMap tiles the sample-origin / population maps render via Leaflet.
const CSP: &str = "default-src 'self'; \
    script-src 'self' https://www.google.com https://www.gstatic.com; \
    style-src 'self' 'unsafe-inline'; \
    img-src 'self' data: https://*.tile.openstreetmap.org; \
    font-src 'self'; \
    connect-src 'self'; \
    frame-src https://www.google.com; \
    frame-ancestors 'none'; \
    base-uri 'self'; \
    form-action 'self'; \
    object-src 'none'";

/// HSTS. Browsers only honor this over HTTPS (ignored on plain-HTTP dev), so it is
/// safe to send unconditionally; in production the app is fronted by TLS.
const HSTS: &str = "max-age=31536000; includeSubDomains";

const PERMISSIONS: &str = "geolocation=(), camera=(), microphone=(), payment=(), usb=()";

/// Attach the security headers to every response (does not override a header a
/// handler set deliberately).
pub async fn set_security_headers(req: Request, next: Next) -> Response {
    let mut res = next.run(req).await;
    let h = res.headers_mut();
    let mut set = |name: HeaderName, value: &'static str| {
        if !h.contains_key(&name) {
            h.insert(name, HeaderValue::from_static(value));
        }
    };
    set(header::CONTENT_SECURITY_POLICY, CSP);
    set(header::STRICT_TRANSPORT_SECURITY, HSTS);
    set(header::X_FRAME_OPTIONS, "DENY");
    set(header::X_CONTENT_TYPE_OPTIONS, "nosniff");
    set(header::REFERRER_POLICY, "strict-origin-when-cross-origin");
    set(PERMISSIONS_POLICY, PERMISSIONS);
    res
}

// ── CSRF (stateless double-submit) ──────────────────────────────────────────

/// CSRF cookie name (double-submit). Deliberately **not** HttpOnly: the
/// `htmx:configRequest` hook in `base.html` reads it and echoes it as the
/// `X-CSRF-Token` request header on every HTMX request (and, via `hx-boost`,
/// every boosted form submit).
const CSRF_COOKIE: &str = "csrf";

fn is_state_changing(m: &Method) -> bool {
    matches!(*m, Method::POST | Method::PUT | Method::PATCH | Method::DELETE)
}

/// Read the `csrf` cookie value out of the request's `Cookie` header.
fn csrf_cookie(req: &Request) -> Option<String> {
    req.headers()
        .get(header::COOKIE)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.split(';').find_map(|kv| kv.trim().strip_prefix("csrf=").map(str::to_string)))
        .filter(|t| !t.is_empty())
}

/// Stateless double-submit CSRF protection for the cookie-session browser routes.
///
/// Issues a random `csrf` cookie on first contact, then on every state-changing
/// method requires the `X-CSRF-Token` header to equal that cookie. An attacker on
/// another origin can neither read the victim's cookie (to forge the header) nor
/// set the header on a cross-site form post, so the two never match.
///
/// **Exempt:** `/api/v1/*` — the JSON API is read-only or Ed25519-signature-authed
/// (a forged request can't produce a valid signature), so CSRF does not apply.
pub async fn csrf_protect(req: Request, next: Next) -> Response {
    let cookie_token = csrf_cookie(&req);
    let exempt = req.uri().path().starts_with("/api/v1/");

    if is_state_changing(req.method()) && !exempt {
        let header_token = req.headers().get("x-csrf-token").and_then(|v| v.to_str().ok());
        let valid = matches!((&cookie_token, header_token), (Some(c), Some(h)) if c == h);
        if !valid {
            return (StatusCode::FORBIDDEN, "CSRF validation failed").into_response();
        }
    }

    let issue = cookie_token.is_none();
    let mut res = next.run(req).await;
    if issue {
        let token = Uuid::new_v4().simple().to_string();
        if let Ok(v) = HeaderValue::from_str(&format!("{CSRF_COOKIE}={token}; Path=/; SameSite=Strict")) {
            res.headers_mut().append(header::SET_COOKIE, v);
        }
    }
    res
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request as HttpRequest, StatusCode};
    use axum::routing::get;
    use axum::Router;
    use tower::ServiceExt;

    #[tokio::test]
    async fn headers_present_on_every_response() {
        let app = Router::new()
            .route("/", get(|| async { "ok" }))
            .layer(axum::middleware::from_fn(set_security_headers));
        let res = app
            .oneshot(HttpRequest::builder().uri("/").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let h = res.headers();
        assert!(h.get(header::CONTENT_SECURITY_POLICY).unwrap().to_str().unwrap().contains("default-src 'self'"));
        assert_eq!(h.get(header::X_FRAME_OPTIONS).unwrap(), "DENY");
        assert_eq!(h.get(header::X_CONTENT_TYPE_OPTIONS).unwrap(), "nosniff");
        assert_eq!(h.get(header::REFERRER_POLICY).unwrap(), "strict-origin-when-cross-origin");
        assert!(h.contains_key(header::STRICT_TRANSPORT_SECURITY));
        assert!(h.contains_key("permissions-policy"));
    }

    #[tokio::test]
    async fn does_not_override_handler_set_header() {
        async fn custom() -> Response {
            let mut r = Response::new(Body::from("x"));
            r.headers_mut().insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("SAMEORIGIN"));
            r
        }
        let app = Router::new().route("/", get(custom)).layer(axum::middleware::from_fn(set_security_headers));
        let res = app
            .oneshot(HttpRequest::builder().uri("/").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.headers().get(header::X_FRAME_OPTIONS).unwrap(), "SAMEORIGIN");
    }

    fn csrf_app() -> Router {
        Router::new()
            .route("/form", get(|| async { "ok" }).post(|| async { "posted" }))
            .route("/api/v1/thing", axum::routing::post(|| async { "api" }))
            .layer(axum::middleware::from_fn(csrf_protect))
    }

    async fn send(app: Router, method: &str, uri: &str, cookie: Option<&str>, token: Option<&str>) -> Response {
        let mut b = HttpRequest::builder().method(method).uri(uri);
        if let Some(c) = cookie {
            b = b.header(header::COOKIE, format!("csrf={c}"));
        }
        if let Some(t) = token {
            b = b.header("x-csrf-token", t);
        }
        app.oneshot(b.body(Body::empty()).unwrap()).await.unwrap()
    }

    #[tokio::test]
    async fn get_issues_token_cookie() {
        let res = send(csrf_app(), "GET", "/form", None, None).await;
        assert_eq!(res.status(), StatusCode::OK);
        let sc = res.headers().get(header::SET_COOKIE).unwrap().to_str().unwrap();
        assert!(sc.starts_with("csrf=") && sc.contains("SameSite=Strict"), "issues a csrf cookie: {sc}");
    }

    #[tokio::test]
    async fn post_requires_matching_token() {
        // No cookie/header → blocked.
        assert_eq!(send(csrf_app(), "POST", "/form", None, None).await.status(), StatusCode::FORBIDDEN);
        // Cookie but no header → blocked.
        assert_eq!(send(csrf_app(), "POST", "/form", Some("abc"), None).await.status(), StatusCode::FORBIDDEN);
        // Mismatched header → blocked.
        assert_eq!(send(csrf_app(), "POST", "/form", Some("abc"), Some("xyz")).await.status(), StatusCode::FORBIDDEN);
        // Matching cookie + header → allowed.
        assert_eq!(send(csrf_app(), "POST", "/form", Some("abc"), Some("abc")).await.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn api_v1_is_exempt() {
        // The Ed25519-signed / read-only JSON API does not require a CSRF token.
        assert_eq!(send(csrf_app(), "POST", "/api/v1/thing", None, None).await.status(), StatusCode::OK);
    }
}
