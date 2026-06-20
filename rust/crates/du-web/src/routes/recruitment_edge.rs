//! Signed Edge (Navigator desktop) recruitment API (`/api/v1/recruitment/*`). The
//! response side of Tier 3c: an invited member views their open invitations and
//! accepts/declines them, signature-authenticated exactly like the social/exchange Edge.
//!
//! **Respond-only.** Campaign *creation* is gated to an admin of an AppView `group_project`
//! (D5 `ManageSubjects` + reputation) and stays on the web flow (`super::recruitment`) until
//! the Navigator↔group_project bridge lands. Responding is self-authorized: a target only
//! ever touches its own INVITED row. On an acceptance the campaign owner is notified (the
//! researcher learns the opt-in), mirroring the web `respond` handler. PII-free: a DID, a
//! signature, and the accept/decline choice.

use crate::error::AppError;
use crate::sig::{ensure_fresh_ts, verify_signed};
use crate::state::AppState;
use axum::extract::{Query, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use du_db::recruitment::{self, messages};
use serde::Deserialize;
use serde_json::{json, Value};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/v1/recruitment/invitations", get(invitations_read))
        .route("/api/v1/recruitment/respond", post(respond))
}

#[derive(Deserialize)]
struct PollQuery {
    did: String,
    ts: i64,
    sig: String,
}

/// The caller's open (INVITED) invitations, with campaign + project context.
async fn invitations_read(State(st): State<AppState>, Query(q): Query<PollQuery>) -> Result<Json<Value>, AppError> {
    ensure_fresh_ts(q.ts)?;
    verify_signed(&st.pool, &q.did, &messages::poll(&q.did, q.ts), &q.sig).await?;
    let items: Vec<Value> = recruitment::invitations_for(&st.pool, &q.did)
        .await?
        .into_iter()
        .map(|i| {
            json!({
                "campaign_id": i.campaign_id,
                "title": i.title,
                "message": i.message,
                "project_name": i.project_name,
            })
        })
        .collect();
    Ok(Json(json!({ "items": items })))
}

#[derive(Deserialize)]
struct RespondBody {
    did: String,
    campaign_id: i64,
    accept: bool,
    signature: String,
}

/// Accept/decline an invitation. Idempotent (only flips an INVITED row); on an acceptance the
/// campaign owner is notified. Returns whether the response changed anything.
async fn respond(State(st): State<AppState>, Json(b): Json<RespondBody>) -> Result<Json<Value>, AppError> {
    verify_signed(
        &st.pool,
        &b.did,
        &messages::respond(&b.did, b.campaign_id, b.accept),
        &b.signature,
    )
    .await?;
    let changed = recruitment::respond(&st.pool, b.campaign_id, &b.did, b.accept).await?;
    if changed && b.accept {
        if let Some((owner_uid, project_id)) = recruitment::campaign_owner_project(&st.pool, b.campaign_id).await? {
            du_db::notification::notify_system(
                &st.pool,
                owner_uid,
                "A member accepted your recruitment invitation",
                Some(&format!("/projects/{project_id}/recruit")),
                None,
            )
            .await?;
        }
    }
    Ok(Json(json!({ "changed": changed })))
}

#[cfg(test)]
mod tests {
    use axum::body::{to_bytes, Body};
    use axum::http::{Request, StatusCode};
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;
    use du_db::recruitment::{self, messages};
    use ed25519_dalek::{Signer, SigningKey};
    use serde_json::{json, Value};
    use tower::ServiceExt;

    /// An invited did:key tester polls its invitation (signed), accepts it (signed), and the
    /// target flips to ACCEPTED. A forged signature is rejected (403).
    #[tokio::test]
    async fn edge_invitation_poll_and_accept() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping recruitment edge test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();

        // A researcher + project + campaign; the tester is invited directly (skip cohort compute).
        let researcher = "did:test:researcher";
        let ruid = du_db::auth::upsert_user_by_did(&pool, researcher, None, Some("R")).await.unwrap().0;
        let project = du_db::research::create_project(&pool, "R study", "HAPLOGROUP", Some("Y_DNA"), None, researcher)
            .await
            .unwrap();
        let cid = recruitment::create_campaign(&pool, project, ruid, "Join", "Help us", "R-M269", "Y_DNA")
            .await
            .unwrap();
        let tester = SigningKey::from_bytes(&[71u8; 32]);
        let tester_did = du_atproto::did::did_key_from_ed25519(&tester.verifying_key());
        recruitment::deliver(&pool, cid, &[tester_did.clone()]).await.unwrap();

        let state = crate::state::AppState { pool: pool.clone(), key: tower_cookies::Key::generate(), oauth: None };
        let send = |state: crate::state::AppState, method: &'static str, uri: String, body: Value| async move {
            let mut req = Request::builder().method(method).uri(uri);
            if method == "POST" {
                req = req.header("content-type", "application/json");
            }
            crate::routes::app(state)
                .oneshot(req.body(Body::from(body.to_string())).unwrap())
                .await
                .unwrap()
        };
        let ts = chrono::Utc::now().timestamp();

        // Poll invitations (signed) → the one we delivered.
        let urlencode = |s: &str| s.replace('+', "%2B").replace('/', "%2F").replace('=', "%3D");
        let sig = STANDARD.encode(tester.sign(messages::poll(&tester_did, ts).as_bytes()).to_bytes());
        let uri = format!("/api/v1/recruitment/invitations?did={tester_did}&ts={ts}&sig={}", urlencode(&sig));
        let resp = send(state.clone(), "GET", uri, json!({})).await;
        assert_eq!(resp.status(), StatusCode::OK);
        let body: Value = serde_json::from_slice(&to_bytes(resp.into_body(), 1 << 20).await.unwrap()).unwrap();
        assert_eq!(body["items"].as_array().unwrap().len(), 1);
        assert_eq!(body["items"][0]["campaign_id"].as_i64(), Some(cid));

        // A forged signature is rejected.
        let bad = format!("/api/v1/recruitment/invitations?did={tester_did}&ts={ts}&sig=Zm9v");
        assert_eq!(send(state.clone(), "GET", bad, json!({})).await.status(), StatusCode::FORBIDDEN);

        // Accept (signed) → changed, and the target is now ACCEPTED.
        let rsig = STANDARD.encode(tester.sign(messages::respond(&tester_did, cid, true).as_bytes()).to_bytes());
        let resp = send(
            state.clone(),
            "POST",
            "/api/v1/recruitment/respond".into(),
            json!({ "did": tester_did, "campaign_id": cid, "accept": true, "signature": rsig }),
        )
        .await;
        assert_eq!(resp.status(), StatusCode::OK);
        let body: Value = serde_json::from_slice(&to_bytes(resp.into_body(), 1 << 20).await.unwrap()).unwrap();
        assert_eq!(body["changed"], json!(true));
        assert_eq!(
            recruitment::target_status(&pool, cid, &tester_did).await.unwrap().as_deref(),
            Some("ACCEPTED")
        );
    }
}
