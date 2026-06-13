//! D2 ResearchSubject registry endpoints (`/api/v1/research/*`) — **PII-free**. The
//! AppView stores only pseudonymous person nodes + project memberships + a merge
//! audit; identity resolution is Edge-to-Edge over D1/D3. Every call is
//! **signature-authenticated** (the caller signs a canonical message with its DID
//! key — [`crate::sig::verify_signed`]) **and authorized** from existing data:
//! register → project owner; merge → steward of both subjects; custody → the
//! subject's steward. Not part of the public OpenAPI document.

use crate::error::AppError;
use crate::sig::verify_signed;
use crate::state::AppState;
use axum::extract::{Query, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use du_db::research::{self, messages};
use serde::Deserialize;
use serde_json::{json, Value};
use uuid::Uuid;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/v1/research/subject", post(register))
        .route("/api/v1/research/merge", post(merge))
        .route("/api/v1/research/custody", post(custody))
        .route("/api/v1/research/subjects", get(subjects))
        .route("/api/v1/research/project/member", post(add_member))
        .route("/api/v1/research/project/member/revoke", post(revoke_member))
        .route("/api/v1/research/project/members", get(members))
}

#[derive(Deserialize)]
struct RegisterBody {
    steward_did: String,
    project_id: i64,
    /// Present when the subject id was agreed via a D1 id-exchange; omit to mint one.
    subject_id: Option<Uuid>,
    signature: String,
}

async fn register(State(st): State<AppState>, Json(b): Json<RegisterBody>) -> Result<Json<Value>, AppError> {
    verify_signed(
        &b.steward_did,
        &messages::register(&b.steward_did, b.project_id, b.subject_id.map(|u| u.to_string()).as_deref()),
        &b.signature,
    )
    .await?;
    // Authorize (D5 ACL): ADMIN/CO_ADMIN of the project (owner is the founding ADMIN).
    if !research::can(&st.pool, b.project_id, &b.steward_did, research::Capability::ManageSubjects).await? {
        return Err(AppError::Forbidden);
    }
    let sid = research::register_in_project(&st.pool, b.subject_id, b.project_id, &b.steward_did).await?;
    Ok(Json(json!({ "research_subject_id": sid })))
}

#[derive(Deserialize)]
struct MergeBody {
    asserted_by_did: String,
    keep: Uuid,
    retire: Uuid,
    method: String,
    confidence: Option<f64>,
    signature: String,
}

async fn merge(State(st): State<AppState>, Json(b): Json<MergeBody>) -> Result<Json<Value>, AppError> {
    verify_signed(
        &b.asserted_by_did,
        &messages::merge(&b.asserted_by_did, &b.keep.to_string(), &b.retire.to_string(), &b.method),
        &b.signature,
    )
    .await?;
    // Authorize: the asserter must steward BOTH subjects.
    if !research::is_steward_of(&st.pool, &b.asserted_by_did, b.keep).await?
        || !research::is_steward_of(&st.pool, &b.asserted_by_did, b.retire).await?
    {
        return Err(AppError::Forbidden);
    }
    research::merge_subjects(&st.pool, b.keep, b.retire, &b.method, &b.asserted_by_did, b.confidence).await?;
    Ok(Json(json!({ "kept": b.keep, "retired": b.retire })))
}

#[derive(Deserialize)]
struct CustodyBody {
    steward_did: String,
    subject_id: Uuid,
    custody_did: String,
    signature: String,
}

async fn custody(State(st): State<AppState>, Json(b): Json<CustodyBody>) -> Result<Json<Value>, AppError> {
    verify_signed(
        &b.steward_did,
        &messages::custody(&b.steward_did, &b.subject_id.to_string(), &b.custody_did),
        &b.signature,
    )
    .await?;
    // Authorize: only the current steward may transfer custody.
    if !research::is_steward_of(&st.pool, &b.steward_did, b.subject_id).await? {
        return Err(AppError::Forbidden);
    }
    if !research::set_custody(&st.pool, b.subject_id, &b.custody_did).await? {
        return Err(AppError::NotFound(format!("subject {}", b.subject_id)));
    }
    Ok(Json(json!({ "research_subject_id": b.subject_id, "custody_did": b.custody_did })))
}

#[derive(Deserialize)]
struct SubjectsQuery {
    project_id: i64,
    did: String,
    ts: i64,
    sig: String,
}

async fn subjects(State(st): State<AppState>, Query(q): Query<SubjectsQuery>) -> Result<Json<Value>, AppError> {
    if (chrono::Utc::now().timestamp() - q.ts).abs() > 300 {
        return Err(AppError::BadRequest("stale timestamp".into()));
    }
    verify_signed(&q.did, &messages::poll(&q.did, q.ts), &q.sig).await?;
    if !research::is_team_member(&st.pool, q.project_id, &q.did).await? {
        return Err(AppError::Forbidden);
    }
    let rows = research::subjects_in_project(&st.pool, q.project_id).await?;
    let items: Vec<Value> = rows
        .into_iter()
        .map(|r| json!({ "research_subject_id": r.research_subject_id, "steward_did": r.steward_did }))
        .collect();
    Ok(Json(json!({ "items": items })))
}

// ── collaborator-team management (D5, ADMIN-gated) ────────────────────────────

#[derive(Deserialize)]
struct AddMemberBody {
    actor_did: String,
    project_id: i64,
    member_did: String,
    role: String,
    permissions: Option<Vec<String>>,
    signature: String,
}

async fn add_member(State(st): State<AppState>, Json(b): Json<AddMemberBody>) -> Result<Json<Value>, AppError> {
    verify_signed(&b.actor_did, &messages::add_member(&b.actor_did, b.project_id, &b.member_did, &b.role), &b.signature).await?;
    if !research::can(&st.pool, b.project_id, &b.actor_did, research::Capability::ManageRoles).await? {
        return Err(AppError::Forbidden);
    }
    let role = research::Role::parse(&b.role).ok_or_else(|| AppError::BadRequest("invalid role".into()))?;
    research::add_member(&st.pool, b.project_id, &b.member_did, role, &b.permissions.unwrap_or_default(), &b.actor_did).await?;
    Ok(Json(json!({ "project_id": b.project_id, "member_did": b.member_did, "role": role.as_str() })))
}

#[derive(Deserialize)]
struct RevokeMemberBody {
    actor_did: String,
    project_id: i64,
    member_did: String,
    signature: String,
}

async fn revoke_member(State(st): State<AppState>, Json(b): Json<RevokeMemberBody>) -> Result<Json<Value>, AppError> {
    verify_signed(&b.actor_did, &messages::revoke_member(&b.actor_did, b.project_id, &b.member_did), &b.signature).await?;
    if !research::can(&st.pool, b.project_id, &b.actor_did, research::Capability::ManageRoles).await? {
        return Err(AppError::Forbidden);
    }
    if !research::revoke_member(&st.pool, b.project_id, &b.member_did).await? {
        return Err(AppError::NotFound(format!("live member {}", b.member_did)));
    }
    Ok(Json(json!({ "project_id": b.project_id, "member_did": b.member_did, "status": "REVOKED" })))
}

async fn members(State(st): State<AppState>, Query(q): Query<SubjectsQuery>) -> Result<Json<Value>, AppError> {
    if (chrono::Utc::now().timestamp() - q.ts).abs() > 300 {
        return Err(AppError::BadRequest("stale timestamp".into()));
    }
    verify_signed(&q.did, &messages::poll(&q.did, q.ts), &q.sig).await?;
    if !research::is_team_member(&st.pool, q.project_id, &q.did).await? {
        return Err(AppError::Forbidden);
    }
    let items: Vec<Value> = research::members_of(&st.pool, q.project_id)
        .await?
        .into_iter()
        .map(|m| json!({ "member_did": m.member_did, "role": m.role }))
        .collect();
    Ok(Json(json!({ "items": items })))
}

#[cfg(test)]
mod tests {
    use axum::body::{to_bytes, Body};
    use axum::http::{Request, StatusCode};
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;
    use ed25519_dalek::{Signer, SigningKey};
    use tower::ServiceExt;

    /// The project owner can register a subject; a valid signature from a non-owner
    /// is rejected by the authorization gate (403), and a tampered signature 403s.
    #[tokio::test]
    async fn register_is_owner_gated_and_signed() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping research endpoint test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();
        let owner = SigningKey::from_bytes(&[11u8; 32]);
        let owner_did = du_atproto::did::did_key_from_ed25519(&owner.verifying_key());

        let project_id: i64 = sqlx::query_scalar(
            "INSERT INTO social.group_project (project_name, project_type, owner_did) VALUES ('P','RESEARCH',$1) RETURNING id",
        )
        .bind(&owner_did)
        .fetch_one(&pool)
        .await
        .expect("project");
        let state = crate::state::AppState { pool, key: tower_cookies::Key::generate(), oauth: None };

        let post = |state: crate::state::AppState, body: serde_json::Value| async move {
            crate::routes::app(state)
                .oneshot(Request::builder().method("POST").uri("/api/v1/research/subject")
                    .header("content-type", "application/json").body(Body::from(body.to_string())).unwrap())
                .await
                .unwrap()
        };

        // Owner-signed → 200 + a minted subject.
        let msg = du_db::research::messages::register(&owner_did, project_id, None);
        let sig = STANDARD.encode(owner.sign(msg.as_bytes()).to_bytes());
        let ok = post(state.clone(), serde_json::json!({ "steward_did": owner_did, "project_id": project_id, "signature": sig })).await;
        assert_eq!(ok.status(), StatusCode::OK);
        let v: serde_json::Value = serde_json::from_slice(&to_bytes(ok.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert!(v["research_subject_id"].as_str().is_some());

        // A non-owner with a VALID signature is rejected by the owner gate (403).
        let other = SigningKey::from_bytes(&[12u8; 32]);
        let other_did = du_atproto::did::did_key_from_ed25519(&other.verifying_key());
        let omsg = du_db::research::messages::register(&other_did, project_id, None);
        let osig = STANDARD.encode(other.sign(omsg.as_bytes()).to_bytes());
        let r403 = post(state.clone(), serde_json::json!({ "steward_did": other_did, "project_id": project_id, "signature": osig })).await;
        assert_eq!(r403.status(), StatusCode::FORBIDDEN);

        // A tampered signature (owner did, wrong sig) → 403.
        let bad = post(state, serde_json::json!({ "steward_did": owner_did, "project_id": project_id, "signature": "AAAA" })).await;
        assert_eq!(bad.status(), StatusCode::FORBIDDEN);
    }

    /// Adding a team member is ADMIN-gated: the owner (founding ADMIN) succeeds; a
    /// non-admin DID with a valid signature is rejected (403).
    #[tokio::test]
    async fn add_member_is_admin_gated() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping add-member test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();
        let owner = SigningKey::from_bytes(&[21u8; 32]);
        let owner_did = du_atproto::did::did_key_from_ed25519(&owner.verifying_key());
        let project_id: i64 = sqlx::query_scalar(
            "INSERT INTO social.group_project (project_name, project_type, owner_did) VALUES ('T','RESEARCH',$1) RETURNING id",
        )
        .bind(&owner_did)
        .fetch_one(&pool)
        .await
        .unwrap();
        let state = crate::state::AppState { pool, key: tower_cookies::Key::generate(), oauth: None };

        let post = |state: crate::state::AppState, body: serde_json::Value| async move {
            crate::routes::app(state)
                .oneshot(Request::builder().method("POST").uri("/api/v1/research/project/member")
                    .header("content-type", "application/json").body(Body::from(body.to_string())).unwrap())
                .await
                .unwrap()
        };

        // Owner (ADMIN) adds a CO_ADMIN → 200.
        let m = du_db::research::messages::add_member(&owner_did, project_id, "did:key:zNew", "CO_ADMIN");
        let sig = STANDARD.encode(owner.sign(m.as_bytes()).to_bytes());
        let ok = post(state.clone(), serde_json::json!({
            "actor_did": owner_did, "project_id": project_id, "member_did": "did:key:zNew", "role": "CO_ADMIN", "signature": sig,
        })).await;
        assert_eq!(ok.status(), StatusCode::OK);

        // A non-admin (valid signature, but no ManageRoles) → 403.
        let outsider = SigningKey::from_bytes(&[22u8; 32]);
        let out_did = du_atproto::did::did_key_from_ed25519(&outsider.verifying_key());
        let m2 = du_db::research::messages::add_member(&out_did, project_id, "did:key:zEvil", "ADMIN");
        let sig2 = STANDARD.encode(outsider.sign(m2.as_bytes()).to_bytes());
        let r403 = post(state, serde_json::json!({
            "actor_did": out_did, "project_id": project_id, "member_did": "did:key:zEvil", "role": "ADMIN", "signature": sig2,
        })).await;
        assert_eq!(r403.status(), StatusCode::FORBIDDEN);
    }
}
