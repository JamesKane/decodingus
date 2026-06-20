//! Member-facing social layer: a tester/member opens a support thread to the team
//! and reads/posts to the community feed. Auth = a signed-in session ([`User`]); the
//! identity is the same `ident.users` row a DID is bridged into. A user may only read
//! their own threads. Posting is reputation- and block-gated.

use crate::auth::{NavUser, User};
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, State};
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;
use uuid::Uuid;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/messages", get(messages_page).post(open_thread))
        .route("/messages/fragment", get(threads_fragment))
        .route("/messages/unread-count", get(unread_badge))
        .route("/messages/:id", get(thread_panel))
        .route("/messages/:id/reply", post(thread_reply))
        .route("/feed", get(feed_page).post(create_post))
        .route("/feed/:id/reply", post(feed_reply))
        .route("/feed/:id/vote", post(feed_vote))
        .route("/feed/:id/report", post(feed_report))
        .route("/feed/:id/block", post(feed_block_author))
        .route("/blocks", get(blocks_page))
        .route("/blocks/:user_id/unblock", post(unblock))
        .route("/notifications", get(notifications_page))
        .route("/notifications/unread-count", get(notif_badge))
        .route("/notifications/:id/open", get(notif_open))
        .route("/notifications/read-all", post(notif_read_all))
}

/// A small pill of the caller's unread-reply count, or empty when zero. Lazy-loaded by
/// the navbar (`hx-get` on `load, every 60s`) so page renders stay query-free.
async fn unread_badge(User(s): User, State(st): State<AppState>) -> Result<Html<String>, AppError> {
    let n = du_db::social::user_unread_count(&st.pool, s.user_id).await?;
    Ok(Html(if n > 0 {
        format!("<span class=\"badge text-bg-danger rounded-pill ms-1\">{n}</span>")
    } else {
        String::new()
    }))
}

// ── my messages ───────────────────────────────────────────────────────────────
struct ThreadRow {
    id: String,
    subject: String,
    status: String,
    last_activity: String,
    user_unread: bool,
}

struct ThreadsView {
    rows: Vec<ThreadRow>,
}

async fn load_threads(st: &AppState, user_id: Uuid) -> Result<ThreadsView, AppError> {
    let result = du_db::social::user_threads(&st.pool, user_id, 1, 100).await?;
    let rows = result
        .items
        .iter()
        .map(|t| ThreadRow {
            id: t.id.to_string(),
            subject: t.subject.clone().filter(|s| !s.is_empty()).unwrap_or_else(|| "(no subject)".into()),
            status: t.status.clone(),
            last_activity: t
                .last_message_at
                .map(|d| d.format("%Y-%m-%d %H:%M").to_string())
                .unwrap_or_default(),
            user_unread: t.user_unread,
        })
        .collect();
    Ok(ThreadsView { rows })
}

#[derive(askama::Template)]
#[template(path = "social/messages.html")]
struct MessagesTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ThreadsView,
}

#[derive(askama::Template)]
#[template(path = "social/threads_list.html")]
struct ThreadsListTemplate {
    t: T,
    list: ThreadsView,
}

async fn messages_page(User(s): User, State(st): State<AppState>, locale: Locale) -> Result<Response, AppError> {
    let list = load_threads(&st, s.user_id).await?;
    Ok(html(&MessagesTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        list,
    }))
}

async fn threads_fragment(User(s): User, State(st): State<AppState>, locale: Locale) -> Result<Response, AppError> {
    let list = load_threads(&st, s.user_id).await?;
    Ok(html(&ThreadsListTemplate { t: locale.t, list }))
}

#[derive(Deserialize)]
struct OpenForm {
    subject: Option<String>,
    body: String,
}

async fn open_thread(
    User(s): User,
    State(st): State<AppState>,
    Form(f): Form<OpenForm>,
) -> Result<Response, AppError> {
    let body = f.body.trim();
    if body.is_empty() {
        return Err(AppError::BadRequest("message body is required".into()));
    }
    let subject = f.subject.map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    let id = du_db::social::open_support_thread(&st.pool, s.user_id, subject.as_deref(), body).await?;
    // Full navigation to the new thread (server-driven redirect works for HTMX + plain).
    Ok((HxHeaders::new().redirect(format!("/messages/{id}")), Redirect::to(&format!("/messages/{id}"))).into_response())
}

struct MsgView {
    who: String,
    from_team: bool,
    body: String,
    at: String,
}

struct ThreadDetail {
    id: String,
    subject: String,
    status: String,
    messages: Vec<MsgView>,
}

#[derive(askama::Template)]
#[template(path = "social/thread.html")]
struct ThreadTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    thread: ThreadDetail,
}

/// Load a thread the caller owns, marking it read on the user side. 404 if it isn't
/// theirs (no information leak about other users' threads).
async fn owned_thread(st: &AppState, id: Uuid, user_id: Uuid) -> Result<du_db::social::Thread, AppError> {
    let thread = du_db::social::get_thread(&st.pool, id)
        .await?
        .filter(|t| t.requester_id == Some(user_id))
        .ok_or_else(|| AppError::NotFound(format!("thread {id}")))?;
    Ok(thread)
}

async fn thread_panel(
    User(s): User,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    let thread = owned_thread(&st, id, s.user_id).await?;
    du_db::social::mark_read(&st.pool, id, du_db::social::ReadSide::User).await?;
    let messages = du_db::social::thread_messages(&st.pool, id)
        .await?
        .into_iter()
        .map(|m| MsgView {
            who: m.sender_name.unwrap_or_else(|| "unknown".into()),
            from_team: m.from_team,
            body: m.body,
            at: m.created_at.format("%Y-%m-%d %H:%M").to_string(),
        })
        .collect();
    Ok(html(&ThreadTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        thread: ThreadDetail {
            id: id.to_string(),
            subject: thread.subject.unwrap_or_default(),
            status: thread.status,
            messages,
        },
    }))
}

#[derive(Deserialize)]
struct ReplyForm {
    body: String,
}

async fn thread_reply(
    User(s): User,
    State(st): State<AppState>,
    Path(id): Path<Uuid>,
    Form(f): Form<ReplyForm>,
) -> Result<Response, AppError> {
    owned_thread(&st, id, s.user_id).await?; // authz: must be the requester
    let body = f.body.trim();
    if body.is_empty() {
        return Err(AppError::BadRequest("reply body is required".into()));
    }
    du_db::social::post_message(&st.pool, id, s.user_id, body, false).await?;
    Ok(Redirect::to(&format!("/messages/{id}")).into_response())
}

// ── community feed ────────────────────────────────────────────────────────────
struct PostView {
    id: String,
    kind: String,
    author: String,
    topic: String,
    content: String,
    pinned: bool,
    at: String,
    score: i64,
    up_active: bool,
    down_active: bool,
    can_block: bool,
    /// A read-only post mirrored from a member's PDS (no local vote/reply/block).
    federated: bool,
    replies: Vec<PostView>,
}

struct FeedView {
    posts: Vec<PostView>,
    can_post: bool,
    posted: bool,
}

/// Build a single post's view (no replies), resolving the viewer's current vote so the
/// up/down buttons render their active state. `can_block` offers a block affordance on
/// other members' community posts (never on the team's announcements or your own).
async fn row_view(st: &AppState, viewer: Uuid, p: du_db::social::FeedPost) -> Result<PostView, AppError> {
    let my_vote = du_db::social::user_vote(&st.pool, p.id, viewer).await?;
    let can_block = p.author_id != viewer && p.kind == "COMMUNITY";
    Ok(PostView {
        id: p.id.to_string(),
        kind: p.kind,
        author: p.author_name.unwrap_or_else(|| "member".into()),
        topic: p.topic.unwrap_or_default(),
        content: p.content,
        pinned: p.pinned,
        at: p.created_at.format("%Y-%m-%d %H:%M").to_string(),
        score: p.score,
        up_active: my_vote > 0,
        down_active: my_vote < 0,
        can_block,
        federated: false,
        replies: Vec::new(),
    })
}

/// Build a feed post + its (one level of) replies. When `enforce_blocks` is set, drop the
/// post (and any replies) from/to a user the viewer has blocked — used for community
/// posts but NOT team announcements (which must always be visible).
async fn to_post_view(
    st: &AppState,
    viewer: Uuid,
    p: du_db::social::FeedPost,
    enforce_blocks: bool,
) -> Result<Option<PostView>, AppError> {
    if enforce_blocks && du_db::social::is_blocked_either(&st.pool, viewer, p.author_id).await? {
        return Ok(None);
    }
    let has_replies = p.reply_count > 0;
    let mut view = row_view(st, viewer, p).await?;
    if has_replies {
        let pid: Uuid = view.id.parse().expect("uuid round-trips");
        for r in du_db::social::post_replies(&st.pool, pid).await? {
            if !du_db::social::is_blocked_either(&st.pool, viewer, r.author_id).await? {
                view.replies.push(row_view(st, viewer, r).await?);
            }
        }
    }
    Ok(Some(view))
}

/// Render a federated (PDS-mirrored) post — read-only, author resolved where the DID is
/// bridged, else a shortened DID.
fn fed_view(f: du_db::fed::feed::FedPost) -> (chrono::DateTime<chrono::Utc>, PostView) {
    let at = f.created_at;
    let author = f.author_name.unwrap_or_else(|| {
        // did:plc:abcd… — show a short, recognizable handle-less label.
        f.did.split(':').next_back().map(|s| s.chars().take(12).collect()).unwrap_or(f.did.clone())
    });
    let view = PostView {
        id: String::new(),
        kind: "COMMUNITY".into(),
        author,
        topic: f.topic.unwrap_or_default(),
        content: f.text,
        pinned: false,
        at: at.map(|d| d.format("%Y-%m-%d %H:%M").to_string()).unwrap_or_default(),
        score: 0,
        up_active: false,
        down_active: false,
        can_block: false,
        federated: true,
        replies: Vec::new(),
    };
    (at.unwrap_or_else(chrono::Utc::now), view)
}

async fn load_feed(st: &AppState, viewer: Uuid, posted: bool) -> Result<FeedView, AppError> {
    // Team announcements first (always visible)...
    let mut posts = Vec::new();
    for p in du_db::social::list_feed(&st.pool, Some("ANNOUNCEMENT"), None, 1, 25).await?.items {
        if let Some(v) = to_post_view(st, viewer, p, false).await? {
            posts.push(v);
        }
    }
    // ...then the community stream: central posts (block-filtered) + federated posts
    // (PDS-mirrored, read-only) interleaved by recency.
    let mut stream: Vec<(chrono::DateTime<chrono::Utc>, PostView)> = Vec::new();
    for p in du_db::social::list_feed(&st.pool, Some("COMMUNITY"), None, 1, 50).await?.items {
        let ts = p.created_at;
        if let Some(v) = to_post_view(st, viewer, p, true).await? {
            stream.push((ts, v));
        }
    }
    for f in du_db::fed::feed::recent(&st.pool, None, 50).await? {
        stream.push(fed_view(f));
    }
    stream.sort_by_key(|(ts, _)| std::cmp::Reverse(*ts));
    posts.extend(stream.into_iter().map(|(_, v)| v));

    let can_post = du_db::reputation::can_post_to_feed(&st.pool, viewer).await?;
    Ok(FeedView { posts, can_post, posted })
}

#[derive(askama::Template)]
#[template(path = "social/feed.html")]
struct FeedTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    feed: FeedView,
}

async fn feed_page(User(s): User, State(st): State<AppState>, locale: Locale) -> Result<Response, AppError> {
    let feed = load_feed(&st, s.user_id, false).await?;
    Ok(html(&FeedTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        feed,
    }))
}

#[derive(Deserialize)]
struct PostForm {
    content: String,
    topic: Option<String>,
}

async fn create_post(
    User(s): User,
    State(st): State<AppState>,
    locale: Locale,
    Form(f): Form<PostForm>,
) -> Result<Response, AppError> {
    if !du_db::reputation::can_post_to_feed(&st.pool, s.user_id).await? {
        return Err(AppError::Forbidden);
    }
    let content = f.content.trim();
    if content.is_empty() {
        return Err(AppError::BadRequest("post body is required".into()));
    }
    let topic = f.topic.map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    du_db::social::create_post(&st.pool, s.user_id, "COMMUNITY", topic.as_deref(), content, None).await?;
    let feed = load_feed(&st, s.user_id, true).await?;
    Ok(html(&FeedTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        feed,
    }))
}

async fn feed_reply(
    User(s): User,
    State(st): State<AppState>,
    Path(id): Path<Uuid>,
    Form(f): Form<PostForm>,
) -> Result<Response, AppError> {
    if !du_db::reputation::can_post_to_feed(&st.pool, s.user_id).await? {
        return Err(AppError::Forbidden);
    }
    let content = f.content.trim();
    if content.is_empty() {
        return Err(AppError::BadRequest("reply body is required".into()));
    }
    du_db::social::create_post(&st.pool, s.user_id, "COMMUNITY", None, content, Some(id)).await?;
    Ok(Redirect::to("/feed").into_response())
}

#[derive(Deserialize)]
struct VoteForm {
    /// 1 = upvote, -1 = downvote (re-casting the same value clears it).
    value: i16,
}

async fn feed_vote(
    User(s): User,
    State(st): State<AppState>,
    Path(id): Path<Uuid>,
    Form(f): Form<VoteForm>,
) -> Result<Response, AppError> {
    if !matches!(f.value, 1 | -1) {
        return Err(AppError::BadRequest("vote must be 1 or -1".into()));
    }
    let post = du_db::social::get_post(&st.pool, id).await?.ok_or_else(|| AppError::NotFound(format!("post {id}")))?;
    du_db::social::cast_vote(&st.pool, id, s.user_id, post.author_id, f.value).await?;
    Ok(Redirect::to("/feed").into_response())
}

async fn feed_report(
    User(s): User,
    State(st): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    du_db::social::report_post(&st.pool, id, s.user_id, None).await?;
    Ok(Redirect::to("/feed").into_response())
}

/// Block the author of a post (community posts only; never yourself or the team).
async fn feed_block_author(
    User(s): User,
    State(st): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    let post = du_db::social::get_post(&st.pool, id).await?.ok_or_else(|| AppError::NotFound(format!("post {id}")))?;
    if post.author_id != s.user_id && post.kind == "COMMUNITY" {
        du_db::social::block(&st.pool, s.user_id, post.author_id, None).await?;
    }
    Ok(Redirect::to("/feed").into_response())
}

struct BlockRow {
    user_id: String,
    name: String,
    at: String,
}

#[derive(askama::Template)]
#[template(path = "social/blocks.html")]
struct BlocksTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    blocks: Vec<BlockRow>,
}

async fn blocks_page(User(s): User, State(st): State<AppState>, locale: Locale) -> Result<Response, AppError> {
    let blocks = du_db::social::list_blocks(&st.pool, s.user_id)
        .await?
        .into_iter()
        .map(|b| BlockRow {
            user_id: b.blocked_id.to_string(),
            name: b.blocked_name.unwrap_or_else(|| "member".into()),
            at: b.created_at.format("%Y-%m-%d").to_string(),
        })
        .collect();
    Ok(html(&BlocksTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        blocks,
    }))
}

async fn unblock(User(s): User, State(st): State<AppState>, Path(user_id): Path<Uuid>) -> Result<Response, AppError> {
    du_db::social::unblock(&st.pool, s.user_id, user_id).await?;
    Ok(Redirect::to("/blocks").into_response())
}

// ── notifications ─────────────────────────────────────────────────────────────
struct NotifView {
    id: String,
    actor: String,
    title: String,
    at: String,
    unread: bool,
}

#[derive(askama::Template)]
#[template(path = "social/notifications.html")]
struct NotificationsTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    notifications: Vec<NotifView>,
}

async fn notifications_page(User(s): User, State(st): State<AppState>, locale: Locale) -> Result<Response, AppError> {
    let notifications = du_db::notification::list(&st.pool, s.user_id, 100)
        .await?
        .into_iter()
        .map(|n| NotifView {
            id: n.id.to_string(),
            actor: n.actor_name.unwrap_or_default(),
            title: n.title,
            at: n.created_at.format("%Y-%m-%d %H:%M").to_string(),
            unread: n.read_at.is_none(),
        })
        .collect();
    Ok(html(&NotificationsTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        notifications,
    }))
}

/// Lazy bell badge (navbar), empty when zero. Loaded async so renders stay query-free.
async fn notif_badge(User(s): User, State(st): State<AppState>) -> Result<Html<String>, AppError> {
    let n = du_db::notification::unread_count(&st.pool, s.user_id).await?;
    Ok(Html(if n > 0 {
        format!("<span class=\"badge text-bg-danger rounded-pill ms-1\">{n}</span>")
    } else {
        String::new()
    }))
}

/// Mark one notification read and navigate to its target (GET so it's a plain link).
async fn notif_open(User(s): User, State(st): State<AppState>, Path(id): Path<Uuid>) -> Result<Response, AppError> {
    du_db::notification::mark_read(&st.pool, id, s.user_id).await?;
    // Resolve the destination from the (recipient-scoped) notification; fall back to list.
    let dest = du_db::notification::list(&st.pool, s.user_id, 200)
        .await?
        .into_iter()
        .find(|n| n.id == id)
        .and_then(|n| n.link)
        .unwrap_or_else(|| "/notifications".into());
    Ok(Redirect::to(&dest).into_response())
}

async fn notif_read_all(User(s): User, State(st): State<AppState>) -> Result<Response, AppError> {
    du_db::notification::mark_all_read(&st.pool, s.user_id).await?;
    Ok(Redirect::to("/notifications").into_response())
}

#[cfg(test)]
mod tests {
    use axum::body::Body;
    use axum::http::Request;
    use tower::ServiceExt;

    /// The member messages + feed routes require a signed-in session: an anonymous
    /// request is redirected to /login. (Thread/feed behavior is covered by the
    /// du_db::social integration tests; cross-user thread isolation by `owned_thread`.)
    #[tokio::test]
    async fn member_routes_require_login() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping member-route gating test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let state = crate::state::AppState { pool: db.pool().clone(), key: tower_cookies::Key::generate(), oauth: None };

        for uri in ["/messages", "/feed"] {
            let resp = crate::routes::app(state.clone())
                .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
                .await
                .unwrap();
            assert!(resp.status().is_redirection(), "{uri} should redirect when anonymous");
            assert_eq!(resp.headers().get("location").unwrap(), "/login");
        }
    }
}
