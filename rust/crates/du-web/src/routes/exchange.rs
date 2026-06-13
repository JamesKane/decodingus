//! D1 encrypted-exchange **broker** endpoints (`/api/v1/exchange/*`). The AppView
//! never sees plaintext or session keys — these endpoints record consent, mirror
//! published X25519 keys, and (increment 2) blind-relay ciphertext. Every Edge
//! submission is **signature-authenticated**: the caller signs a canonical message
//! ([`du_db::exchange::messages`]) with its DID's Ed25519 identity key, which the
//! broker verifies (`du_atproto::verify_did_key`; `did:plc/web` resolved first).
//! Not part of the public OpenAPI document (Edge protocol, not the read API).

use crate::error::AppError;
use crate::state::AppState;
use axum::extract::{Query, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use du_db::exchange::{self, messages};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use uuid::Uuid;

/// Max relay envelope size (ciphertext) — back-pressure on the blind buffer.
const MAX_ENVELOPE_BYTES: usize = 1 << 20; // 1 MiB

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/v1/exchange/key", post(publish_key).get(fetch_key))
        .route("/api/v1/exchange/request", post(create_request))
        .route("/api/v1/exchange/consent", post(consent))
        .route("/api/v1/exchange/pending", get(pending))
        .route("/api/v1/exchange/relay", post(relay_post))
        .route("/api/v1/exchange/relay/pull", get(relay_pull))
        .route("/api/v1/exchange/relay/ack", post(relay_ack))
}

/// Verify that `signature` (standard base64 Ed25519) over `message` was produced by
/// `did`'s identity key. `did:key` verifies directly; `did:plc`/`did:web` resolve to
/// their signing key first. A bad signature → 403.
async fn verify_signed(did: &str, message: &str, signature: &str) -> Result<(), AppError> {
    let did_key = if did.starts_with("did:key:") {
        did.to_string()
    } else {
        let parsed = du_atproto::did::Did::parse(did).map_err(|_| AppError::BadRequest("invalid did".into()))?;
        du_atproto::Resolver::new()
            .resolve_did(&parsed)
            .await
            .map_err(|e| AppError::Upstream(format!("did resolution: {e}")))?
            .signing_did_key()
            .ok_or_else(|| AppError::BadRequest("no signing key in did document".into()))?
    };
    du_atproto::verify_did_key(&did_key, message.as_bytes(), signature).map_err(|_| AppError::Forbidden)
}

// ── published X25519 key ──────────────────────────────────────────────────────

#[derive(Deserialize)]
struct KeyBody {
    did: String,
    /// Standard base64 of the 32-byte X25519 public key.
    x25519_pub: String,
    key_uri: Option<String>,
    signature: String,
}

async fn publish_key(State(st): State<AppState>, Json(b): Json<KeyBody>) -> Result<Json<Value>, AppError> {
    verify_signed(&b.did, &messages::publickey(&b.did, &b.x25519_pub, b.key_uri.as_deref()), &b.signature).await?;
    let bytes = STANDARD.decode(b.x25519_pub.trim()).map_err(|_| AppError::BadRequest("x25519_pub base64".into()))?;
    if bytes.len() != 32 {
        return Err(AppError::BadRequest("x25519_pub must be 32 bytes".into()));
    }
    exchange::publish_key(&st.pool, &b.did, &bytes, b.key_uri.as_deref()).await?;
    Ok(Json(json!({ "did": b.did, "status": "published" })))
}

#[derive(Deserialize)]
struct DidQuery {
    did: String,
}

async fn fetch_key(State(st): State<AppState>, Query(q): Query<DidQuery>) -> Result<Json<Value>, AppError> {
    let k = exchange::key_for(&st.pool, &q.did).await?.ok_or_else(|| AppError::NotFound(format!("key for {}", q.did)))?;
    Ok(Json(json!({
        "did": k.did,
        "x25519_pub": STANDARD.encode(&k.x25519_pub),
        "key_uri": k.key_uri,
    })))
}

// ── request + consent ─────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct RequestBody {
    request_uri: String,
    initiator_did: String,
    partner_did: String,
    purpose: String,
    scope: Option<String>,
    details: Option<Value>,
    signature: String,
}

async fn create_request(State(st): State<AppState>, Json(b): Json<RequestBody>) -> Result<Json<Value>, AppError> {
    let msg = messages::request(&b.request_uri, &b.initiator_did, &b.partner_did, &b.purpose, b.scope.as_deref());
    verify_signed(&b.initiator_did, &msg, &b.signature).await?;
    exchange::create_request(
        &st.pool,
        &exchange::NewRequest {
            request_uri: &b.request_uri,
            initiator_did: &b.initiator_did,
            partner_did: &b.partner_did,
            purpose: &b.purpose,
            scope: b.scope.as_deref(),
            details: b.details.unwrap_or_else(|| json!({})),
        },
    )
    .await?;
    Ok(Json(json!({ "request_uri": b.request_uri, "status": "PENDING" })))
}

#[derive(Deserialize)]
struct ConsentBody {
    request_uri: String,
    consenting_did: String,
    consent_given: bool,
    consent_uri: Option<String>,
    signature: String,
}

async fn consent(State(st): State<AppState>, Json(b): Json<ConsentBody>) -> Result<Json<Value>, AppError> {
    verify_signed(&b.consenting_did, &messages::consent(&b.request_uri, &b.consenting_did, b.consent_given), &b.signature).await?;
    let outcome = exchange::record_consent(
        &st.pool,
        &b.request_uri,
        &b.consenting_did,
        b.consent_given,
        b.consent_uri.as_deref(),
        &b.signature,
    )
    .await?;
    match outcome {
        exchange::ConsentOutcome::Unknown => Err(AppError::NotFound(format!("request {}", b.request_uri))),
        exchange::ConsentOutcome::Consented(sid) => Ok(Json(json!({ "status": "CONSENTED", "session_id": sid }))),
        exchange::ConsentOutcome::Declined => Ok(Json(json!({ "status": "DECLINED" }))),
        exchange::ConsentOutcome::Recorded => Ok(Json(json!({ "status": "PENDING" }))),
    }
}

// ── exchange-ready poll (signed) ──────────────────────────────────────────────

#[derive(Deserialize)]
struct PendingQuery {
    did: String,
    /// Unix seconds; must be within ±5 min of now (replay guard).
    ts: i64,
    sig: String,
}

async fn pending(State(st): State<AppState>, Query(q): Query<PendingQuery>) -> Result<Json<Value>, AppError> {
    let now = chrono::Utc::now().timestamp();
    if (now - q.ts).abs() > 300 {
        return Err(AppError::BadRequest("stale timestamp".into()));
    }
    verify_signed(&q.did, &messages::poll(&q.did, q.ts), &q.sig).await?;
    let ready = exchange::pending_for(&st.pool, &q.did).await?;
    let items: Vec<Value> = ready
        .into_iter()
        .map(|r| json!({
            "session_id": r.session_id,
            "request_uri": r.request_uri,
            "purpose": r.purpose,
            "partner_did": r.partner_did,
            "partner_key_uri": r.partner_key_uri,
        }))
        .collect();
    Ok(Json(json!({ "items": items })))
}

// ── blind relay ───────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct RelayBody {
    session_id: Uuid,
    from_did: String,
    to_did: String,
    seq: i32,
    /// Standard base64 of the opaque AES-GCM ciphertext envelope.
    blob: String,
    signature: String,
}

async fn relay_post(State(st): State<AppState>, Json(b): Json<RelayBody>) -> Result<Json<Value>, AppError> {
    let bytes = STANDARD.decode(b.blob.trim()).map_err(|_| AppError::BadRequest("blob base64".into()))?;
    if bytes.is_empty() || bytes.len() > MAX_ENVELOPE_BYTES {
        return Err(AppError::BadRequest("envelope size out of range".into()));
    }
    let hash = STANDARD.encode(Sha256::digest(&bytes));
    let msg = messages::relay(&b.session_id.to_string(), &b.from_did, &b.to_did, b.seq, &hash);
    verify_signed(&b.from_did, &msg, &b.signature).await?;
    let id = exchange::post_envelope(&st.pool, b.session_id, &b.from_did, &b.to_did, b.seq, &bytes).await?;
    Ok(Json(json!({ "id": id })))
}

#[derive(Deserialize)]
struct PullQuery {
    session_id: Uuid,
    did: String,
    ts: i64,
    sig: String,
}

async fn relay_pull(State(st): State<AppState>, Query(q): Query<PullQuery>) -> Result<Json<Value>, AppError> {
    if (chrono::Utc::now().timestamp() - q.ts).abs() > 300 {
        return Err(AppError::BadRequest("stale timestamp".into()));
    }
    verify_signed(&q.did, &messages::poll(&q.did, q.ts), &q.sig).await?;
    let envs = exchange::pull_envelopes(&st.pool, q.session_id, &q.did).await?;
    let items: Vec<Value> = envs
        .into_iter()
        .map(|e| json!({ "id": e.id, "from_did": e.from_did, "seq": e.seq, "blob": STANDARD.encode(&e.blob) }))
        .collect();
    Ok(Json(json!({ "items": items })))
}

#[derive(Deserialize)]
struct AckBody {
    envelope_id: i64,
    did: String,
    signature: String,
}

async fn relay_ack(State(st): State<AppState>, Json(b): Json<AckBody>) -> Result<Json<Value>, AppError> {
    verify_signed(&b.did, &messages::ack(&b.did, b.envelope_id), &b.signature).await?;
    if exchange::ack_envelope(&st.pool, b.envelope_id, &b.did).await? {
        Ok(Json(json!({ "status": "acked" })))
    } else {
        Err(AppError::NotFound(format!("envelope {}", b.envelope_id)))
    }
}

#[cfg(test)]
mod tests {
    use axum::body::{to_bytes, Body};
    use axum::http::{Request, StatusCode};
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;
    use ed25519_dalek::{Signer, SigningKey};
    use tower::ServiceExt;

    /// did:key + a signed request verifies and is recorded; a tampered signature
    /// is rejected with 403. (Exercises the broker's signature-auth gate offline.)
    #[tokio::test]
    async fn signed_request_verifies_and_tamper_is_rejected() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping exchange auth test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let state = crate::state::AppState { pool: db.pool().clone(), key: tower_cookies::Key::generate(), oauth: None };

        let sk = SigningKey::from_bytes(&[3u8; 32]);
        let did = du_atproto::did::did_key_from_ed25519(&sk.verifying_key());
        let partner = "did:key:zPartnerPlaceholder";
        let req_uri = "at://did:key:z.../com.decodingus.exchange.request/1";
        let msg = du_db::exchange::messages::request(req_uri, &did, partner, "GENEALOGY_PII", None);
        let sig = STANDARD.encode(sk.sign(msg.as_bytes()).to_bytes());

        let body = serde_json::json!({
            "request_uri": req_uri, "initiator_did": did, "partner_did": partner,
            "purpose": "GENEALOGY_PII", "signature": sig,
        });
        let app = crate::routes::app(state.clone());
        let r = app
            .oneshot(Request::builder().method("POST").uri("/api/v1/exchange/request")
                .header("content-type", "application/json").body(Body::from(body.to_string())).unwrap())
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        let v: serde_json::Value = serde_json::from_slice(&to_bytes(r.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(v["status"], "PENDING");

        // Tampered signature → 403.
        let mut bad = body.clone();
        bad["partner_did"] = serde_json::json!("did:key:zSomeoneElse");
        let app = crate::routes::app(state);
        let r2 = app
            .oneshot(Request::builder().method("POST").uri("/api/v1/exchange/request")
                .header("content-type", "application/json").body(Body::from(bad.to_string())).unwrap())
            .await
            .unwrap();
        assert_eq!(r2.status(), StatusCode::FORBIDDEN);
    }
}
