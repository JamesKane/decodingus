//! Signed Edge (Navigator desktop) recruitment API (`/api/v1/recruitment/*`),
//! signature-authenticated exactly like the social/exchange Edge. Two sides of Tier 3c:
//!
//! **Respond** (member): list open invitations and accept/decline them. Self-authorized — a
//! target only ever touches its own INVITED row. On a fresh acceptance the member is awarded
//! reputation and the campaign owner is notified (the researcher learns the opt-in), mirroring
//! the web `respond` handler.
//!
//! **Create** (researcher): list the projects the caller may recruit for (D5 `ManageSubjects` +
//! reputation, re-checked from the signed DID) and create a campaign — the Navigator-native path
//! that previously required the web flow. The cohort compute/deliver/notify is the shared
//! `super::recruitment::create_and_deliver`, so web and Edge can't drift.
//!
//! PII-free throughout: DIDs, signatures, and the campaign's public targeting fields only.

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
        .route("/api/v1/recruitment/projects", get(projects_read))
        .route("/api/v1/recruitment/campaign", post(create))
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

/// Accept/decline an invitation. Idempotent (only flips an INVITED row); on a fresh acceptance
/// the member is awarded reputation and the campaign owner is notified. Returns whether the
/// response changed anything.
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
        let accepter_uid = du_db::auth::upsert_user_by_did(&st.pool, &b.did, None, None).await?.0;
        super::recruitment::on_acceptance(&st, b.campaign_id, accepter_uid).await?;
    }
    Ok(Json(json!({ "changed": changed })))
}

/// The projects the signed caller may run a campaign in (D5 `ManageSubjects`). The Navigator
/// lists these, then POSTs to `create`.
async fn projects_read(State(st): State<AppState>, Query(q): Query<PollQuery>) -> Result<Json<Value>, AppError> {
    ensure_fresh_ts(q.ts)?;
    verify_signed(&st.pool, &q.did, &messages::projects(&q.did, q.ts), &q.sig).await?;
    let items: Vec<Value> = du_db::research::recruitable_projects(&st.pool, &q.did)
        .await?
        .into_iter()
        .map(|p| {
            json!({
                "project_id": p.id,
                "project_guid": p.project_guid,
                "project_name": p.project_name,
                "lineage": p.target_lineage,
            })
        })
        .collect();
    Ok(Json(json!({ "items": items })))
}

#[derive(Deserialize)]
struct CreateBody {
    did: String,
    project_id: i64,
    title: String,
    message: String,
    target_haplogroup: String,
    lineage: String,
    signature: String,
}

/// Create a campaign from the Navigator. Re-checks the signed DID's `ManageSubjects` grant +
/// reputation floor on the project (the same gate the web `recruiter_ctx` enforces), then runs
/// the shared cohort compute/deliver/notify. Returns the new campaign id.
async fn create(State(st): State<AppState>, Json(b): Json<CreateBody>) -> Result<Json<Value>, AppError> {
    verify_signed(
        &st.pool,
        &b.did,
        &messages::create(&b.did, b.project_id, &b.target_haplogroup, &b.lineage),
        &b.signature,
    )
    .await?;
    let (title, message, hg) = (b.title.trim(), b.message.trim(), b.target_haplogroup.trim());
    if title.is_empty() || message.is_empty() || hg.is_empty() {
        return Err(AppError::BadRequest("title, message and haplogroup are required".into()));
    }
    if !matches!(b.lineage.as_str(), "Y_DNA" | "MT_DNA") {
        return Err(AppError::BadRequest("lineage must be Y_DNA or MT_DNA".into()));
    }
    let uid = du_db::auth::upsert_user_by_did(&st.pool, &b.did, None, None).await?.0;
    if !du_db::research::can(&st.pool, b.project_id, &b.did, du_db::research::Capability::ManageSubjects).await? {
        return Err(AppError::Forbidden);
    }
    if !du_db::reputation::at_least(&st.pool, uid, du_db::reputation::RECRUIT_MIN).await? {
        return Err(AppError::Forbidden);
    }
    let cid = super::recruitment::create_and_deliver(&st, b.project_id, uid, &b.did, title, message, hg, &b.lineage).await?;
    Ok(Json(json!({ "campaign_id": cid })))
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
        recruitment::deliver(&pool, cid, std::slice::from_ref(&tester_did)).await.unwrap();

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

    /// A researcher (did:key, project owner ⇒ ManageSubjects) lists its recruitable projects
    /// (signed), then creates a campaign (signed) — the Navigator-native path. The matching
    /// carrier is delivered an invitation, and a forged create signature is rejected (403).
    #[tokio::test]
    async fn edge_list_projects_and_create_campaign() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping recruitment edge create test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();

        // The researcher signs as a did:key; owning the project grants ManageSubjects.
        let researcher = SigningKey::from_bytes(&[42u8; 32]);
        let researcher_did = du_atproto::did::did_key_from_ed25519(&researcher.verifying_key());
        du_db::auth::upsert_user_by_did(&pool, &researcher_did, None, Some("R")).await.unwrap();
        let project = du_db::research::create_project(&pool, "R study", "HAPLOGROUP", Some("Y_DNA"), None, &researcher_did)
            .await
            .unwrap();
        // A matching carrier (exact-name fallback — no tree seeded in the ephemeral DB).
        let cand = "did:test:cand";
        du_db::auth::upsert_user_by_did(&pool, cand, None, Some("C")).await.unwrap();
        sqlx::query("INSERT INTO fed.biosample (did, rkey, at_uri, y_haplogroup, time_us) VALUES ($1,'a',$2,'R-M269',1)")
            .bind(cand)
            .bind(format!("at://{cand}/bs/a"))
            .execute(&pool)
            .await
            .unwrap();

        let state = crate::state::AppState { pool: pool.clone(), key: tower_cookies::Key::generate(), oauth: None };
        let send = |state: crate::state::AppState, method: &'static str, uri: String, body: Value| async move {
            let mut req = Request::builder().method(method).uri(uri);
            if method == "POST" {
                req = req.header("content-type", "application/json");
            }
            crate::routes::app(state).oneshot(req.body(Body::from(body.to_string())).unwrap()).await.unwrap()
        };
        let urlencode = |s: &str| s.replace('+', "%2B").replace('/', "%2F").replace('=', "%3D");
        let ts = chrono::Utc::now().timestamp();

        // List recruitable projects (signed) → our project.
        let psig = STANDARD.encode(researcher.sign(messages::projects(&researcher_did, ts).as_bytes()).to_bytes());
        let uri = format!("/api/v1/recruitment/projects?did={researcher_did}&ts={ts}&sig={}", urlencode(&psig));
        let resp = send(state.clone(), "GET", uri, json!({})).await;
        assert_eq!(resp.status(), StatusCode::OK);
        let body: Value = serde_json::from_slice(&to_bytes(resp.into_body(), 1 << 20).await.unwrap()).unwrap();
        assert_eq!(body["items"].as_array().unwrap().len(), 1);
        assert_eq!(body["items"][0]["project_id"].as_i64(), Some(project));

        // Create a campaign (signed over the structural fields). A forged signature is rejected.
        let csig = STANDARD.encode(researcher.sign(messages::create(&researcher_did, project, "R-M269", "Y_DNA").as_bytes()).to_bytes());
        let make_body = |sig: &str| {
            json!({ "did": researcher_did, "project_id": project, "title": "Join",
                    "message": "Help us", "target_haplogroup": "R-M269", "lineage": "Y_DNA", "signature": sig })
        };
        assert_eq!(
            send(state.clone(), "POST", "/api/v1/recruitment/campaign".into(), make_body("Zm9v")).await.status(),
            StatusCode::FORBIDDEN
        );
        let resp = send(state.clone(), "POST", "/api/v1/recruitment/campaign".into(), make_body(&csig)).await;
        assert_eq!(resp.status(), StatusCode::OK);
        let body: Value = serde_json::from_slice(&to_bytes(resp.into_body(), 1 << 20).await.unwrap()).unwrap();
        let cid = body["campaign_id"].as_i64().expect("campaign_id");

        // The carrier was invited, and got a RECRUITMENT_INVITE notification deep-linking the campaign.
        assert_eq!(recruitment::target_status(&pool, cid, cand).await.unwrap().as_deref(), Some("INVITED"));
        let cand_uid = du_db::auth::upsert_user_by_did(&pool, cand, None, None).await.unwrap().0;
        let notes = du_db::notification::list(&pool, cand_uid, 10).await.unwrap();
        let invite = notes.iter().find(|n| n.kind == du_db::notification::kinds::RECRUITMENT_INVITE).expect("invite notification");
        assert_eq!(invite.link.as_deref(), Some(format!("/recruitment/{cid}").as_str()));
    }
}
