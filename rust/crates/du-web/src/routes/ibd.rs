//! Federated IBD suggestions (`/api/v1/ibd/*`) — the D3 read entry point. The AppView
//! *coordinates* IBD: it serves a caller their own **pseudonymous** introduction
//! candidates (mined by `du_db::ibd::recompute_suggestions` from the `fed.*` records
//! Navigator publishes) and brokers a consent request to a chosen candidate **without
//! ever revealing the counterpart's DID** — identity reveal stays Edge-to-Edge over the
//! D1 exchange after mutual consent. Every call is **signature-authenticated** (the
//! caller signs a canonical [`du_db::ibd::messages`] message with its DID key —
//! [`crate::sig::verify_signed`]). Scope is **personal** (the caller's own samples via
//! the `core.biosample.atproto->>'repo_did'` bridge), not project-scoped. Not part of
//! the public OpenAPI document.

use crate::error::AppError;
use crate::sig::verify_signed;
use crate::state::AppState;
use axum::extract::{Query, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use du_db::ibd::{self, messages};
use du_db::exchange;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/v1/ibd/suggestions", get(suggestions))
        .route("/api/v1/ibd/introduce", post(introduce))
        .route("/api/v1/ibd/dismiss", post(dismiss))
        .route("/api/v1/ibd/attest", post(attest))
}

#[derive(Deserialize)]
struct SuggestionsQuery {
    did: String,
    ts: i64,
    sig: String,
}

/// A caller's own ranked candidates — pseudonymous (no counterpart DID).
async fn suggestions(State(st): State<AppState>, Query(q): Query<SuggestionsQuery>) -> Result<Json<Value>, AppError> {
    if (chrono::Utc::now().timestamp() - q.ts).abs() > 300 {
        return Err(AppError::BadRequest("stale timestamp".into()));
    }
    verify_signed(&st.pool, &q.did, &messages::poll(&q.did, q.ts), &q.sig).await?;
    let items: Vec<Value> = ibd::suggestions_for_did(&st.pool, &q.did, 100)
        .await?
        .into_iter()
        .map(|s| json!({
            "suggested_sample_guid": s.suggested_sample_guid,
            "suggestion_type": s.suggestion_type,
            "score": s.score,
            "metadata": s.metadata,
        }))
        .collect();
    Ok(Json(json!({ "items": items })))
}

#[derive(Deserialize)]
struct IntroduceBody {
    did: String,
    suggested_sample_guid: Uuid,
    signature: String,
}

/// Ask the broker to relay a D1 consent request to a chosen candidate. The counterpart
/// DID is resolved server-side and **never returned**; the caller learns it only after
/// mutual consent opens a session (`exchange::pending_for`).
async fn introduce(State(st): State<AppState>, Json(b): Json<IntroduceBody>) -> Result<Json<Value>, AppError> {
    verify_signed(&st.pool, &b.did, &messages::introduce(&b.did, &b.suggested_sample_guid.to_string()), &b.signature).await?;
    // Authorize + pick the exchange purpose from the suggestion's dominant signal (the caller
    // may only introduce to its own genuine candidate).
    let purpose = ibd::introduction_purpose(&st.pool, &b.did, b.suggested_sample_guid)
        .await?
        .ok_or(AppError::Forbidden)?;
    // Resolve the counterpart server-side; never surface it to the caller.
    let counterpart = ibd::owner_did_of_sample(&st.pool, b.suggested_sample_guid)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("candidate {} is not claimable", b.suggested_sample_guid)))?;
    // Opaque, deterministic handle (idempotent per caller+candidate) that does NOT embed
    // the initiator DID — the recipient consents blind, learning the initiator only after
    // mutual consent (symmetric with the caller never seeing the counterpart pre-consent).
    let digest = Sha256::digest(format!("{}:{}", b.did, b.suggested_sample_guid).as_bytes());
    let hex: String = digest.iter().map(|byte| format!("{byte:02x}")).collect();
    let request_uri = format!("urn:ibd:{hex}");
    exchange::create_request(
        &st.pool,
        &exchange::NewRequest {
            request_uri: &request_uri,
            initiator_did: &b.did,
            partner_did: &counterpart,
            purpose: &purpose,
            scope: None,
            details: json!({ "origin": "IBD_SUGGESTION" }),
        },
    )
    .await?;
    // The suggestion became a request → CONVERTED (drops from the active candidate list).
    ibd::mark_converted(&st.pool, &b.did, b.suggested_sample_guid).await?;
    Ok(Json(json!({ "request_uri": request_uri, "status": "PENDING", "purpose": purpose })))
}

#[derive(Deserialize)]
struct DismissBody {
    did: String,
    suggested_sample_guid: Uuid,
    signature: String,
}

/// Dismiss a candidate so the engine stops suggesting it (preserved across recomputes).
async fn dismiss(State(st): State<AppState>, Json(b): Json<DismissBody>) -> Result<Json<Value>, AppError> {
    verify_signed(&st.pool, &b.did, &messages::dismiss(&b.did, &b.suggested_sample_guid.to_string()), &b.signature).await?;
    let n = ibd::dismiss_suggestion(&st.pool, &b.did, b.suggested_sample_guid).await?;
    if n == 0 {
        return Err(AppError::NotFound(format!("no active suggestion for {}", b.suggested_sample_guid)));
    }
    Ok(Json(json!({ "suggested_sample_guid": b.suggested_sample_guid, "status": "DISMISSED" })))
}

#[derive(Deserialize)]
struct AttestBody {
    did: String,
    request_uri: String,
    claimed_sample: Uuid,
    counterpart_sample: Uuid,
    region_type: String,
    #[serde(default)]
    total_shared_cm: Option<f64>,
    #[serde(default)]
    num_segments: Option<i32>,
    #[serde(default = "default_attestation_type")]
    attestation_type: String,
    #[serde(default)]
    notes: Option<String>,
    signature: String,
}

fn default_attestation_type() -> String {
    "INITIAL_REPORT".to_string()
}

/// Report the outcome of a completed Edge-to-Edge comparison. The AppView records it as
/// match state and, once both consented parties report a compatible total, confirms the
/// edge — which is what feeds the shared-match discovery signal. PII-free: the body carries
/// only pseudonymous sample handles + coarse totals, never segment coordinates or genotypes.
async fn attest(State(st): State<AppState>, Json(b): Json<AttestBody>) -> Result<Json<Value>, AppError> {
    // The signed figure binds the claim to the signature (formatted to match the Edge).
    let cm = b.total_shared_cm.map(|c| format!("{c:.1}")).unwrap_or_default();
    let msg = messages::attest(
        &b.did,
        &b.request_uri,
        &b.claimed_sample.to_string(),
        &b.counterpart_sample.to_string(),
        &b.region_type,
        &cm,
    );
    verify_signed(&st.pool, &b.did, &msg, &b.signature).await?;

    let outcome = ibd::record_attestation(
        &st.pool,
        &ibd::Attestation {
            attester_did: &b.did,
            request_uri: &b.request_uri,
            claimed_sample: b.claimed_sample,
            counterpart_sample: b.counterpart_sample,
            region_type: &b.region_type,
            total_shared_cm: b.total_shared_cm,
            num_segments: b.num_segments,
            attestation_type: &b.attestation_type,
            signature: &b.signature,
            notes: b.notes.as_deref(),
        },
    )
    .await?;
    match outcome {
        ibd::AttestationOutcome::Recorded { discovery_index_id, consensus_status, publicly_discoverable } => {
            Ok(Json(json!({
                "discovery_index_id": discovery_index_id,
                "consensus_status": consensus_status,
                "publicly_discoverable": publicly_discoverable,
            })))
        }
        ibd::AttestationOutcome::Rejected(reason) => {
            tracing::warn!(reason, did = %b.did, "ibd attestation rejected");
            Err(AppError::Forbidden)
        }
    }
}

#[cfg(test)]
mod tests {
    use axum::body::{to_bytes, Body};
    use axum::http::{Request, StatusCode};
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;
    use ed25519_dalek::{Signer, SigningKey};
    use serde_json::Value;
    use tower::ServiceExt;
    use uuid::Uuid;

    /// Insert a federated biosample owned by `did`; returns its sample_guid.
    async fn fed_sample(pool: &sqlx::PgPool, did: &str) -> Uuid {
        sqlx::query_scalar(
            "INSERT INTO core.biosample (source, atproto) \
             VALUES ('CITIZEN'::core.biosample_source, jsonb_build_object('uri',$1::text,'repo_did',$2::text)) \
             RETURNING sample_guid",
        )
        .bind(format!("at://{did}/bio"))
        .bind(did)
        .fetch_one(pool)
        .await
        .expect("insert biosample")
    }

    /// A signed-poll suggestions read returns the caller's own pseudonymous candidates and
    /// nothing for an unrelated DID; introduce brokers a request without leaking the
    /// counterpart DID; introduce to a non-candidate is refused.
    #[tokio::test]
    async fn suggestions_scoped_and_introduce_hides_counterpart() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping ibd endpoint test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();

        let owner = SigningKey::from_bytes(&[41u8; 32]);
        let owner_did = du_atproto::did::did_key_from_ed25519(&owner.verifying_key());
        let target = fed_sample(&pool, &owner_did).await;
        // The counterpart is a did:key so it can sign its own /exchange/incoming poll.
        let counter = SigningKey::from_bytes(&[43u8; 32]);
        let counterpart_did = du_atproto::did::did_key_from_ed25519(&counter.verifying_key());
        let suggested = fed_sample(&pool, &counterpart_did).await;
        // A single ACTIVE candidate: owner's sample → the counterpart's sample.
        sqlx::query(
            "INSERT INTO ibd.match_suggestion (target_sample_guid, suggested_sample_guid, suggestion_type, score, status) \
             VALUES ($1, $2, 'POPULATION_OVERLAP', 0.9, 'ACTIVE')",
        )
        .bind(target)
        .bind(suggested)
        .execute(&pool)
        .await
        .unwrap();
        let state = crate::state::AppState { pool: pool.clone(), key: tower_cookies::Key::generate(), oauth: None };

        // base64 STANDARD sigs carry +,/,= — percent-encode for the query string.
        let enc = |s: &str| s.replace('+', "%2B").replace('/', "%2F").replace('=', "%3D");
        let get = |state: crate::state::AppState, uri: String| async move {
            crate::routes::app(state)
                .oneshot(Request::builder().method("GET").uri(uri).body(Body::empty()).unwrap())
                .await
                .unwrap()
        };
        let post = |state: crate::state::AppState, body: Value| async move {
            crate::routes::app(state)
                .oneshot(Request::builder().method("POST").uri("/api/v1/ibd/introduce")
                    .header("content-type", "application/json").body(Body::from(body.to_string())).unwrap())
                .await
                .unwrap()
        };

        // Owner-signed poll → its one candidate, pseudonymous.
        let ts = chrono::Utc::now().timestamp();
        let sig = STANDARD.encode(owner.sign(du_db::ibd::messages::poll(&owner_did, ts).as_bytes()).to_bytes());
        let resp = get(state.clone(), format!("/api/v1/ibd/suggestions?did={owner_did}&ts={ts}&sig={}", enc(&sig))).await;
        assert_eq!(resp.status(), StatusCode::OK);
        let v: Value = serde_json::from_slice(&to_bytes(resp.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(v["items"].as_array().unwrap().len(), 1);
        assert_eq!(v["items"][0]["suggested_sample_guid"].as_str().unwrap(), suggested.to_string());
        // The pseudonymous row carries no counterpart DID.
        assert!(!v["items"][0].to_string().contains(counterpart_did.as_str()));

        // An unrelated DID (valid signature) sees none of the owner's candidates.
        let other = SigningKey::from_bytes(&[42u8; 32]);
        let other_did = du_atproto::did::did_key_from_ed25519(&other.verifying_key());
        let osig = STANDARD.encode(other.sign(du_db::ibd::messages::poll(&other_did, ts).as_bytes()).to_bytes());
        let r2 = get(state.clone(), format!("/api/v1/ibd/suggestions?did={other_did}&ts={ts}&sig={}", enc(&osig))).await;
        let v2: Value = serde_json::from_slice(&to_bytes(r2.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(v2["items"].as_array().unwrap().len(), 0);

        // A stale timestamp → 422.
        let stale = get(state.clone(), format!("/api/v1/ibd/suggestions?did={owner_did}&ts={}&sig={}", ts - 10_000, enc(&sig))).await;
        assert_eq!(stale.status(), StatusCode::UNPROCESSABLE_ENTITY);

        // Introduce to the genuine candidate → brokers a PENDING request, counterpart hidden.
        let im = du_db::ibd::messages::introduce(&owner_did, &suggested.to_string());
        let isig = STANDARD.encode(owner.sign(im.as_bytes()).to_bytes());
        let intro = post(state.clone(), serde_json::json!({
            "did": owner_did, "suggested_sample_guid": suggested, "signature": isig,
        })).await;
        assert_eq!(intro.status(), StatusCode::OK);
        let iv: Value = serde_json::from_slice(&to_bytes(intro.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(iv["status"].as_str(), Some("PENDING"));
        // Purpose routed from the suggestion's signal (POPULATION_OVERLAP → autosomal).
        assert_eq!(iv["purpose"].as_str(), Some("IBD_AUTOSOMAL"));
        assert!(!iv.to_string().contains(counterpart_did.as_str()), "introduce response must not leak the counterpart DID");
        let request_uri = iv["request_uri"].as_str().unwrap().to_string();
        assert!(!request_uri.contains(&owner_did), "the opaque handle must not embed the initiator DID");
        // The broker row carries the resolved counterpart as partner_did (server-side only).
        let partner: String = sqlx::query_scalar("SELECT partner_did FROM exchange.exchange_request WHERE initiator_did = $1")
            .bind(&owner_did)
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(partner, counterpart_did);

        // The loop closes: the counterpart DISCOVERS the request via /exchange/incoming —
        // symmetric-blind (gets the handle + purpose, NOT the initiator DID).
        let cts = chrono::Utc::now().timestamp();
        let csig = STANDARD.encode(counter.sign(du_db::exchange::messages::poll(&counterpart_did, cts).as_bytes()).to_bytes());
        let inc = get(state.clone(), format!("/api/v1/exchange/incoming?did={counterpart_did}&ts={cts}&sig={}", enc(&csig))).await;
        assert_eq!(inc.status(), StatusCode::OK);
        let incv: Value = serde_json::from_slice(&to_bytes(inc.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(incv["items"].as_array().unwrap().len(), 1);
        assert_eq!(incv["items"][0]["request_uri"].as_str(), Some(request_uri.as_str()));
        assert_eq!(incv["items"][0]["purpose"].as_str(), Some("IBD_AUTOSOMAL"));
        assert!(!incv.to_string().contains(&owner_did), "incoming must not reveal the initiator pre-consent");

        // Introduce to a sample that is NOT the caller's candidate → 403.
        let stranger = fed_sample(&pool, "did:plc:stranger").await;
        let bm = du_db::ibd::messages::introduce(&owner_did, &stranger.to_string());
        let bsig = STANDARD.encode(owner.sign(bm.as_bytes()).to_bytes());
        let bad = post(state.clone(), serde_json::json!({
            "did": owner_did, "suggested_sample_guid": stranger, "signature": bsig,
        })).await;
        assert_eq!(bad.status(), StatusCode::FORBIDDEN);

        // Dismiss a fresh ACTIVE candidate → 200; it then no longer shows in /suggestions.
        let other = fed_sample(&pool, "did:plc:other").await;
        sqlx::query(
            "INSERT INTO ibd.match_suggestion (target_sample_guid, suggested_sample_guid, suggestion_type, score, status) \
             VALUES ($1, $2, 'POPULATION_OVERLAP', 0.7, 'ACTIVE')",
        )
        .bind(target)
        .bind(other)
        .execute(&pool)
        .await
        .unwrap();
        let dm = du_db::ibd::messages::dismiss(&owner_did, &other.to_string());
        let dsig = STANDARD.encode(owner.sign(dm.as_bytes()).to_bytes());
        let dres = crate::routes::app(state.clone())
            .oneshot(Request::builder().method("POST").uri("/api/v1/ibd/dismiss")
                .header("content-type", "application/json")
                .body(Body::from(serde_json::json!({ "did": owner_did, "suggested_sample_guid": other, "signature": dsig }).to_string())).unwrap())
            .await
            .unwrap();
        assert_eq!(dres.status(), StatusCode::OK);
        let dts = chrono::Utc::now().timestamp();
        let dpoll = STANDARD.encode(owner.sign(du_db::ibd::messages::poll(&owner_did, dts).as_bytes()).to_bytes());
        let listed = get(state, format!("/api/v1/ibd/suggestions?did={owner_did}&ts={dts}&sig={}", enc(&dpoll))).await;
        let lv: Value = serde_json::from_slice(&to_bytes(listed.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert!(!lv.to_string().contains(&other.to_string()), "dismissed candidate is gone from /suggestions");
    }

    /// The attest endpoint records a signed report (200/PENDING for one side), rejects a
    /// tampered signature (403) and a non-consented exchange (403).
    #[tokio::test]
    async fn attest_endpoint_signed_and_gated() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping ibd attest endpoint test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();

        let alice = SigningKey::from_bytes(&[61u8; 32]);
        let alice_did = du_atproto::did::did_key_from_ed25519(&alice.verifying_key());
        let bob = SigningKey::from_bytes(&[62u8; 32]);
        let bob_did = du_atproto::did::did_key_from_ed25519(&bob.verifying_key());
        let sa = fed_sample(&pool, &alice_did).await;
        let sb = fed_sample(&pool, &bob_did).await;
        sqlx::query(
            "INSERT INTO exchange.exchange_request (request_uri, initiator_did, partner_did, purpose, status) \
             VALUES ('urn:web:ab', $1, $2, 'IBD_AUTOSOMAL', 'CONSENTED')",
        )
        .bind(&alice_did)
        .bind(&bob_did)
        .execute(&pool)
        .await
        .unwrap();
        let state = crate::state::AppState { pool: pool.clone(), key: tower_cookies::Key::generate(), oauth: None };

        let post = |state: crate::state::AppState, body: Value| async move {
            crate::routes::app(state)
                .oneshot(Request::builder().method("POST").uri("/api/v1/ibd/attest")
                    .header("content-type", "application/json").body(Body::from(body.to_string())).unwrap())
                .await
                .unwrap()
        };

        // Signed happy path: alice's first report → 200, PENDING (awaiting bob).
        let cm = format!("{:.1}", 250.0);
        let msg = du_db::ibd::messages::attest(&alice_did, "urn:web:ab", &sa.to_string(), &sb.to_string(), "AUTOSOMAL", &cm);
        let sig = STANDARD.encode(alice.sign(msg.as_bytes()).to_bytes());
        let ok = post(state.clone(), serde_json::json!({
            "did": alice_did, "request_uri": "urn:web:ab", "claimed_sample": sa, "counterpart_sample": sb,
            "region_type": "AUTOSOMAL", "total_shared_cm": 250.0, "signature": sig,
        })).await;
        assert_eq!(ok.status(), StatusCode::OK);
        let ov: Value = serde_json::from_slice(&to_bytes(ok.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(ov["consensus_status"].as_str(), Some("PENDING"));
        assert_eq!(ov["publicly_discoverable"].as_bool(), Some(false));

        // Tampered signature → 403.
        let bad = post(state.clone(), serde_json::json!({
            "did": alice_did, "request_uri": "urn:web:ab", "claimed_sample": sa, "counterpart_sample": sb,
            "region_type": "AUTOSOMAL", "total_shared_cm": 250.0, "signature": "AAAA",
        })).await;
        assert_eq!(bad.status(), StatusCode::FORBIDDEN);

        // A correctly-signed report against a non-consented exchange → 403.
        sqlx::query(
            "INSERT INTO exchange.exchange_request (request_uri, initiator_did, partner_did, purpose, status) \
             VALUES ('urn:web:pending', $1, $2, 'IBD_AUTOSOMAL', 'PENDING')",
        )
        .bind(&alice_did)
        .bind(&bob_did)
        .execute(&pool)
        .await
        .unwrap();
        let pmsg = du_db::ibd::messages::attest(&alice_did, "urn:web:pending", &sa.to_string(), &sb.to_string(), "AUTOSOMAL", &cm);
        let psig = STANDARD.encode(alice.sign(pmsg.as_bytes()).to_bytes());
        let pending = post(state, serde_json::json!({
            "did": alice_did, "request_uri": "urn:web:pending", "claimed_sample": sa, "counterpart_sample": sb,
            "region_type": "AUTOSOMAL", "total_shared_cm": 250.0, "signature": psig,
        })).await;
        assert_eq!(pending.status(), StatusCode::FORBIDDEN);
    }
}
