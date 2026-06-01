//! HTMX request/response plumbing for server-driven hypermedia (plan §4).

use axum::extract::FromRequestParts;
use axum::http::header::{HeaderName, HeaderValue};
use axum::http::request::Parts;
use axum::response::{IntoResponseParts, ResponseParts};
use std::convert::Infallible;

/// Whether the request came from HTMX, and whether it is a history-restore.
///
/// `wants_fragment()` is the negotiation rule: serve just the inner fragment for
/// HTMX-driven swaps, but serve the FULL page for normal navigations AND for
/// htmx history restoration (back/forward), which expects the whole document.
pub struct HxRequest {
    pub is_htmx: bool,
    pub is_history_restore: bool,
    /// The `HX-Target` element id, if the swap names one.
    pub target: Option<String>,
}

impl HxRequest {
    /// Serve the inner fragment only when this is an HTMX swap aimed at the
    /// given target id — NOT for boosted full-page navigations (which target the
    /// body and expect a whole document) nor history restoration.
    pub fn wants_fragment_for(&self, target_id: &str) -> bool {
        self.is_htmx
            && !self.is_history_restore
            && self.target.as_deref() == Some(target_id)
    }
}

#[axum::async_trait]
impl<S: Send + Sync> FromRequestParts<S> for HxRequest {
    type Rejection = Infallible;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, Self::Rejection> {
        let has = |name: &str| parts.headers.get(name).is_some_and(|v| v == "true");
        let target = parts
            .headers
            .get("hx-target")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        Ok(HxRequest {
            is_htmx: has("hx-request"),
            is_history_restore: has("hx-history-restore-request"),
            target,
        })
    }
}

/// Builder for HTMX response headers, so state transitions are server-driven
/// (HX-Push-Url / HX-Trigger / HX-Redirect / HX-Location / HX-Reswap).
#[derive(Default)]
pub struct HxHeaders {
    push_url: Option<String>,
    trigger: Option<String>,
    redirect: Option<String>,
    location: Option<String>,
    reswap: Option<String>,
}

// Builder surface kept complete for upcoming write flows (curator CRUD, forms);
// only push_url is exercised by the read-only slice so far.
#[allow(dead_code)]
impl HxHeaders {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn push_url(mut self, url: impl Into<String>) -> Self {
        self.push_url = Some(url.into());
        self
    }
    pub fn trigger(mut self, event: impl Into<String>) -> Self {
        self.trigger = Some(event.into());
        self
    }
    pub fn redirect(mut self, url: impl Into<String>) -> Self {
        self.redirect = Some(url.into());
        self
    }
    pub fn location(mut self, url: impl Into<String>) -> Self {
        self.location = Some(url.into());
        self
    }
    pub fn reswap(mut self, spec: impl Into<String>) -> Self {
        self.reswap = Some(spec.into());
        self
    }
}

impl IntoResponseParts for HxHeaders {
    type Error = Infallible;

    fn into_response_parts(self, mut res: ResponseParts) -> Result<ResponseParts, Self::Error> {
        let headers = res.headers_mut();
        let mut set = |name: &'static str, val: Option<String>| {
            if let Some(v) = val {
                if let Ok(hv) = HeaderValue::from_str(&v) {
                    headers.insert(HeaderName::from_static(name), hv);
                }
            }
        };
        set("hx-push-url", self.push_url);
        set("hx-trigger", self.trigger);
        set("hx-redirect", self.redirect);
        set("hx-location", self.location);
        set("hx-reswap", self.reswap);
        Ok(res)
    }
}
