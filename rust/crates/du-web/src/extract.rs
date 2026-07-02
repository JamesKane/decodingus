//! Shared Axum extractors.
//!
//! [`Query`] is a **duplicate-tolerant** replacement for `axum::extract::Query`.
//! Axum's own `Query` deserializes with `serde_urlencoded`, which rejects a query
//! string that repeats a key (`?status=X&page=2&status=X`) with `400 Bad Request`
//! (`serde_html_form` / axum-extra behave the same for scalar fields — a repeated
//! key only collapses when the target field is a `Vec`). That 400 is easy to hit
//! with HTMX: a paginated fragment whose container carries `hx-include="#filter"`
//! auto-sends the filter's value, so if a pagination link *also* hard-codes that
//! same param the key arrives twice (see the naming/regions/publications pagers).
//!
//! This extractor first splits the raw query into ordered `(key, value)` pairs —
//! which tolerates repeats, since that's a *sequence*, not a map — then collapses
//! duplicate keys keeping the **last** occurrence (an `hx-include` value appended
//! after a hard-coded URL param wins, matching HTML-form semantics), and finally
//! deserializes the deduped pairs into `T`. The API mirrors `axum::extract::Query`,
//! so handlers get the resilient behaviour by importing `crate::extract::Query`.
//! Emitting the duplicate in the first place is still a template bug worth fixing;
//! this just keeps the whole class from ever surfacing as a 400 again.

use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::de::DeserializeOwned;

/// Duplicate-tolerant query extractor. See module docs.
pub struct Query<T>(pub T);

fn bad_request(msg: impl std::fmt::Display) -> Response {
    (StatusCode::BAD_REQUEST, format!("invalid query string: {msg}")).into_response()
}

#[axum::async_trait]
impl<T, S> FromRequestParts<S> for Query<T>
where
    T: DeserializeOwned,
    S: Send + Sync,
{
    type Rejection = Response;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        let raw = parts.uri.query().unwrap_or("");
        // Parse into ordered pairs. As a *sequence* this accepts repeated keys
        // (unlike deserializing straight into a struct/map, which would 400).
        let pairs: Vec<(String, String)> = serde_urlencoded::from_str(raw).map_err(bad_request)?;
        // Collapse duplicates, keeping the last value seen for each key.
        let mut deduped: Vec<(String, String)> = Vec::with_capacity(pairs.len());
        for (k, v) in pairs {
            match deduped.iter_mut().find(|(ek, _)| *ek == k) {
                Some(slot) => slot.1 = v,
                None => deduped.push((k, v)),
            }
        }
        let encoded = serde_urlencoded::to_string(&deduped).map_err(bad_request)?;
        let value = serde_urlencoded::from_str(&encoded).map_err(bad_request)?;
        Ok(Query(value))
    }
}

#[cfg(test)]
mod tests {
    use super::Query;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use axum::routing::get;
    use axum::Router;
    use serde::Deserialize;
    use tower::ServiceExt;

    #[derive(Deserialize)]
    struct ListQ {
        status: Option<String>,
        page: Option<i64>,
    }

    async fn echo(Query(q): Query<ListQ>) -> String {
        format!("{}-{}", q.status.unwrap_or_default(), q.page.unwrap_or(0))
    }

    async fn call(uri: &str) -> (StatusCode, String) {
        let resp = Router::new()
            .route("/", get(echo))
            .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
            .await
            .unwrap();
        let status = resp.status();
        let bytes = axum::body::to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        (status, String::from_utf8(bytes.to_vec()).unwrap())
    }

    /// The whole point: a duplicated query key (what HTMX's hx-include produces on
    /// top of a hard-coded pagination param) must NOT 400. Last value wins.
    #[tokio::test]
    async fn tolerates_duplicate_keys_last_wins() {
        // The exact shape that used to 400 the curator pagers.
        let (status, body) = call("/?status=&page=2&status=open").await;
        assert_eq!(status, StatusCode::OK, "duplicate key must not 400");
        assert_eq!(body, "open-2", "last value of a repeated scalar wins");
    }

    /// A plain, non-duplicated query still deserializes normally.
    #[tokio::test]
    async fn plain_query_still_works() {
        let (status, body) = call("/?status=closed&page=3").await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, "closed-3");
    }

    /// An empty query string yields all-defaults (Options are None).
    #[tokio::test]
    async fn empty_query_is_defaults() {
        let (status, body) = call("/").await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, "-0");
    }
}
