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
use axum::http::{header, HeaderName, HeaderValue};
use axum::middleware::Next;
use axum::response::Response;

/// `Permissions-Policy` (no `http::header` constant for it).
const PERMISSIONS_POLICY: HeaderName = HeaderName::from_static("permissions-policy");

/// Content-Security-Policy. `'self'` for everything, with two narrow exceptions:
/// `'unsafe-inline'` for the remaining inline scripts/styles, and the Google
/// reCAPTCHA origins (script + frame) used by the public forms.
const CSP: &str = "default-src 'self'; \
    script-src 'self' 'unsafe-inline' https://www.google.com https://www.gstatic.com; \
    style-src 'self' 'unsafe-inline'; \
    img-src 'self' data:; \
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
}
