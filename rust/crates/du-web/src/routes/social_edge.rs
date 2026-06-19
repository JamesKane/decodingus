//! Signed Edge (Navigator desktop) social API (`/api/v1/social/*`). The alpha/beta
//! testers' primary client is the Navigator, which cannot hold a cookie session — every
//! call is **signature-authenticated** ([`crate::sig::verify_signed`]) against the
//! caller's registered device key, exactly like the exchange/research Edge endpoints.
//!
//! The DID is bridged into `ident.users` ([`du_db::auth::upsert_user_by_did`]) so the
//! same `social.*` rows serve both the web ([`super::social`]) and the Edge. Writes
//! verify thread ownership; reads are replay-guarded (ts ±5 min). PII-free: only a DID,
//! a signature, and message bodies the user chose to send the team cross the wire.

use crate::error::AppError;
use crate::sig::{ensure_fresh_ts, verify_signed};
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use du_db::social::{self, messages};
use serde::Deserialize;
use serde_json::{json, Value};
use uuid::Uuid;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/v1/social/thread", post(thread_write))
        .route("/api/v1/social/threads", get(my_threads))
        .route("/api/v1/social/thread/:id", get(thread_messages))
        .route("/api/v1/social/post", post(feed_post))
        .route("/api/v1/social/feed", get(feed_read))
        .route("/api/v1/social/notifications", get(notifications_read))
        .route("/api/v1/social/notifications/read", post(notifications_mark))
}

/// Bridge a signature-verified DID into its `ident.users` id (idempotent), awarding the
/// one-time signup bonus on first touch.
async fn uid_of(st: &AppState, did: &str) -> Result<Uuid, AppError> {
    let uid = du_db::auth::upsert_user_by_did(&st.pool, did, None, None).await?.0;
    du_db::reputation::record_once(&st.pool, uid, du_db::reputation::events::NEW_USER_BONUS).await?;
    Ok(uid)
}

// ── support threads ───────────────────────────────────────────────────────────
#[derive(Deserialize)]
struct ThreadBody {
    did: String,
    /// Omit to open a new thread; set to reply to one the caller owns.
    conversation_id: Option<Uuid>,
    subject: Option<String>,
    body: String,
    signature: String,
}

async fn thread_write(State(st): State<AppState>, Json(b): Json<ThreadBody>) -> Result<Json<Value>, AppError> {
    verify_signed(
        &st.pool,
        &b.did,
        &messages::thread(&b.did, b.conversation_id.map(|u| u.to_string()).as_deref()),
        &b.signature,
    )
    .await?;
    let body = b.body.trim();
    if body.is_empty() {
        return Err(AppError::BadRequest("body is required".into()));
    }
    let uid = uid_of(&st, &b.did).await?;
    let conv = match b.conversation_id {
        None => {
            let subject = b.subject.as_deref().map(str::trim).filter(|s| !s.is_empty());
            social::open_support_thread(&st.pool, uid, subject, body).await?
        }
        Some(id) => {
            // Ownership mismatch → 404 (don't reveal another user's thread exists).
            if social::thread_requester(&st.pool, id).await? != Some(uid) {
                return Err(AppError::NotFound(format!("thread {id}")));
            }
            social::post_message(&st.pool, id, uid, body, false).await?;
            id
        }
    };
    Ok(Json(json!({ "conversation_id": conv })))
}

#[derive(Deserialize)]
struct PollQuery {
    did: String,
    ts: i64,
    sig: String,
}

async fn my_threads(State(st): State<AppState>, Query(q): Query<PollQuery>) -> Result<Json<Value>, AppError> {
    ensure_fresh_ts(q.ts)?;
    verify_signed(&st.pool, &q.did, &messages::poll(&q.did, q.ts), &q.sig).await?;
    let uid = uid_of(&st, &q.did).await?;
    let page = social::user_threads(&st.pool, uid, 1, 100).await?;
    let items: Vec<Value> = page
        .items
        .into_iter()
        .map(|t| {
            json!({
                "conversation_id": t.id,
                "subject": t.subject,
                "status": t.status,
                "last_message_at": t.last_message_at,
                "unread": t.user_unread,
            })
        })
        .collect();
    Ok(Json(json!({ "items": items })))
}

#[derive(Deserialize)]
struct ReadQuery {
    did: String,
    ts: i64,
    sig: String,
}

async fn thread_messages(
    State(st): State<AppState>,
    Path(id): Path<Uuid>,
    Query(q): Query<ReadQuery>,
) -> Result<Json<Value>, AppError> {
    ensure_fresh_ts(q.ts)?;
    verify_signed(&st.pool, &q.did, &messages::thread_read(&q.did, &id.to_string(), q.ts), &q.sig).await?;
    let uid = uid_of(&st, &q.did).await?;
    if social::thread_requester(&st.pool, id).await? != Some(uid) {
        return Err(AppError::NotFound(format!("thread {id}")));
    }
    social::mark_read(&st.pool, id, social::ReadSide::User).await?;
    let items: Vec<Value> = social::thread_messages(&st.pool, id)
        .await?
        .into_iter()
        .map(|m| json!({ "from_team": m.from_team, "author": m.sender_name, "body": m.body, "at": m.created_at }))
        .collect();
    Ok(Json(json!({ "items": items })))
}

// ── community feed ────────────────────────────────────────────────────────────
#[derive(Deserialize)]
struct PostBody {
    did: String,
    content: String,
    topic: Option<String>,
    /// Set to reply to an existing post.
    parent_post_id: Option<Uuid>,
    signature: String,
}

async fn feed_post(State(st): State<AppState>, Json(b): Json<PostBody>) -> Result<Json<Value>, AppError> {
    verify_signed(
        &st.pool,
        &b.did,
        &messages::post(&b.did, b.parent_post_id.map(|u| u.to_string()).as_deref()),
        &b.signature,
    )
    .await?;
    let content = b.content.trim();
    if content.is_empty() {
        return Err(AppError::BadRequest("content is required".into()));
    }
    let uid = uid_of(&st, &b.did).await?;
    if !du_db::reputation::can_post_to_feed(&st.pool, uid).await? {
        return Err(AppError::Forbidden);
    }
    let topic = b.topic.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let id = social::create_post(&st.pool, uid, "COMMUNITY", topic, content, b.parent_post_id).await?;
    Ok(Json(json!({ "id": id })))
}

fn post_json(p: du_db::social::FeedPost) -> Value {
    json!({
        "id": p.id,
        "kind": p.kind,
        "author": p.author_name,
        "topic": p.topic,
        "content": p.content,
        "pinned": p.pinned,
        "at": p.created_at,
        "reply_count": p.reply_count,
        "score": p.score,
        "parent_post_id": p.parent_post_id,
    })
}

async fn feed_read(State(st): State<AppState>, Query(q): Query<PollQuery>) -> Result<Json<Value>, AppError> {
    ensure_fresh_ts(q.ts)?;
    verify_signed(&st.pool, &q.did, &messages::poll(&q.did, q.ts), &q.sig).await?;
    let announcements: Vec<Value> = social::list_feed(&st.pool, Some("ANNOUNCEMENT"), None, 1, 25)
        .await?
        .items
        .into_iter()
        .map(post_json)
        .collect();
    let community: Vec<Value> = social::list_feed(&st.pool, Some("COMMUNITY"), None, 1, 50)
        .await?
        .items
        .into_iter()
        .map(post_json)
        .collect();
    Ok(Json(json!({ "announcements": announcements, "community": community })))
}

// ── notifications ─────────────────────────────────────────────────────────────
async fn notifications_read(State(st): State<AppState>, Query(q): Query<PollQuery>) -> Result<Json<Value>, AppError> {
    ensure_fresh_ts(q.ts)?;
    verify_signed(&st.pool, &q.did, &du_db::social::messages::poll(&q.did, q.ts), &q.sig).await?;
    let uid = uid_of(&st, &q.did).await?;
    let items: Vec<Value> = du_db::notification::list(&st.pool, uid, 100)
        .await?
        .into_iter()
        .map(|n| {
            json!({
                "id": n.id, "kind": n.kind, "title": n.title, "body": n.body,
                "link": n.link, "actor": n.actor_name, "at": n.created_at, "unread": n.read_at.is_none(),
            })
        })
        .collect();
    let unread = du_db::notification::unread_count(&st.pool, uid).await?;
    Ok(Json(json!({ "items": items, "unread": unread })))
}

#[derive(Deserialize)]
struct NotifReadBody {
    did: String,
    /// Omit to mark all read; set to mark one.
    id: Option<Uuid>,
    ts: i64,
    signature: String,
}

async fn notifications_mark(State(st): State<AppState>, Json(b): Json<NotifReadBody>) -> Result<Json<Value>, AppError> {
    ensure_fresh_ts(b.ts)?;
    verify_signed(
        &st.pool,
        &b.did,
        &du_db::notification::messages::read(&b.did, b.id.map(|u| u.to_string()).as_deref(), b.ts),
        &b.signature,
    )
    .await?;
    let uid = uid_of(&st, &b.did).await?;
    let n = match b.id {
        Some(id) => du_db::notification::mark_read(&st.pool, id, uid).await? as u64,
        None => du_db::notification::mark_all_read(&st.pool, uid).await?,
    };
    Ok(Json(json!({ "marked": n })))
}

#[cfg(test)]
mod tests {
    use axum::body::{to_bytes, Body};
    use axum::http::{Request, StatusCode};
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;
    use du_db::social::messages;
    use ed25519_dalek::{Signer, SigningKey};
    use serde_json::{json, Value};
    use tower::ServiceExt;

    /// A did:key tester opens a thread (signed), the team replies, and the tester polls
    /// it back. An unsigned/forged call is rejected; a second DID can't read the first's
    /// thread (404, no leak).
    #[tokio::test]
    async fn edge_thread_open_reply_poll_and_isolation() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping edge social test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();
        let tester = SigningKey::from_bytes(&[71u8; 32]);
        let tester_did = du_atproto::did::did_key_from_ed25519(&tester.verifying_key());
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

        // Open a thread (signed over did + "new").
        let msg = messages::thread(&tester_did, None);
        let sig = STANDARD.encode(tester.sign(msg.as_bytes()).to_bytes());
        let resp = send(state.clone(), "POST", "/api/v1/social/thread".into(),
            json!({ "did": tester_did, "subject": "hi", "body": "first message", "signature": sig })).await;
        assert_eq!(resp.status(), StatusCode::OK);
        let v: Value = serde_json::from_slice(&to_bytes(resp.into_body(), usize::MAX).await.unwrap()).unwrap();
        let conv = v["conversation_id"].as_str().unwrap().to_string();

        // Forged signature → 403.
        let bad = send(state.clone(), "POST", "/api/v1/social/thread".into(),
            json!({ "did": tester_did, "body": "x", "signature": "AAAA" })).await;
        assert_eq!(bad.status(), StatusCode::FORBIDDEN);

        // The team replies (out of band, via the db layer the curator UI uses).
        let curator = du_db::auth::upsert_user_by_did(&pool, "did:test:curator", None, Some("Team")).await.unwrap().0;
        let cid: uuid::Uuid = conv.parse().unwrap();
        du_db::social::post_message(&pool, cid, curator, "we're on it", true).await.unwrap();

        // Tester polls the thread back (replay-guarded read) and sees both messages.
        let ts = chrono::Utc::now().timestamp();
        let rmsg = messages::thread_read(&tester_did, &conv, ts);
        let rsig = STANDARD.encode(tester.sign(rmsg.as_bytes()).to_bytes());
        let read = send(state.clone(), "GET",
            format!("/api/v1/social/thread/{conv}?did={tester_did}&ts={ts}&sig={}", urlencode(&rsig)), Value::Null).await;
        assert_eq!(read.status(), StatusCode::OK);
        let rv: Value = serde_json::from_slice(&to_bytes(read.into_body(), usize::MAX).await.unwrap()).unwrap();
        let items = rv["items"].as_array().unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(items[1]["from_team"], json!(true));

        // The team reply also produced a notification the Navigator can poll (same signed
        // poll proof). It carries the THREAD_REPLY kind and a deep-link.
        let nmsg = messages::poll(&tester_did, ts);
        let nsig = STANDARD.encode(tester.sign(nmsg.as_bytes()).to_bytes());
        let notifs = send(state.clone(), "GET",
            format!("/api/v1/social/notifications?did={tester_did}&ts={ts}&sig={}", urlencode(&nsig)), Value::Null).await;
        assert_eq!(notifs.status(), StatusCode::OK);
        let nv: Value = serde_json::from_slice(&to_bytes(notifs.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(nv["unread"], json!(1));
        assert_eq!(nv["items"][0]["kind"], json!("THREAD_REPLY"));

        // A different DID cannot read that thread (404 — ownership isolation).
        let other = SigningKey::from_bytes(&[72u8; 32]);
        let other_did = du_atproto::did::did_key_from_ed25519(&other.verifying_key());
        let omsg = messages::thread_read(&other_did, &conv, ts);
        let osig = STANDARD.encode(other.sign(omsg.as_bytes()).to_bytes());
        let other_read = send(state, "GET",
            format!("/api/v1/social/thread/{conv}?did={other_did}&ts={ts}&sig={}", urlencode(&osig)), Value::Null).await;
        assert_eq!(other_read.status(), StatusCode::NOT_FOUND);
    }

    /// Minimal percent-encoding for the base64 signature in a query string (+ / =).
    fn urlencode(s: &str) -> String {
        s.replace('+', "%2B").replace('/', "%2F").replace('=', "%3D")
    }
}
