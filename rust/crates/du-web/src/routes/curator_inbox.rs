//! Team-side social orchestration (curator-gated): triage tester→team support
//! threads and broadcast announcements. The "team" is a role, so any curator may
//! reply to any thread ([`du_db::social`], from_team = true). The two-panel inbox
//! mirrors the haplogroup/reconcile-flag HTMX pattern.

use crate::auth::{Curator, NavUser};
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;
use uuid::Uuid;

/// Event the thread list listens for to reload after a reply / status change.
const CHANGED: &str = "thread-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/inbox", get(inbox_page))
        .route("/curator/inbox/fragment", get(inbox_list))
        .route("/curator/inbox/unread-count", get(open_badge))
        .route("/curator/inbox/:id", get(thread_panel))
        .route("/curator/inbox/:id/reply", post(thread_reply))
        .route("/curator/inbox/:id/status", post(thread_status))
        .route("/curator/announcements", get(announce_page).post(announce_create))
        .route("/curator/moderation", get(moderation_page))
        .route("/curator/moderation/:id/resolve", post(moderation_resolve))
}

// ── inbox: thread list ────────────────────────────────────────────────────────
#[derive(Deserialize)]
struct InboxQuery {
    status: Option<String>,
    page: Option<i64>,
}

/// Normalize the status filter to a known value, or `None` for "all".
fn norm_status(s: Option<&str>) -> Option<&str> {
    match s {
        Some("open") => Some("open"),
        Some("replied") => Some("replied"),
        Some("closed") => Some("closed"),
        _ => None,
    }
}

struct ThreadRow {
    id: String,
    subject: String,
    status: String,
    requester: String,
    last_activity: String,
    team_unread: bool,
}

struct InboxView {
    status: String,
    rows: Vec<ThreadRow>,
    page: i64,
    total: i64,
    total_pages: i64,
}

async fn load_inbox(st: &AppState, q: &InboxQuery) -> Result<InboxView, AppError> {
    let status = norm_status(q.status.as_deref());
    let result = du_db::social::team_inbox(&st.pool, status, q.page.unwrap_or(1), 25).await?;
    let rows = result
        .items
        .iter()
        .map(|t| ThreadRow {
            id: t.id.to_string(),
            subject: t.subject.clone().filter(|s| !s.is_empty()).unwrap_or_else(|| "(no subject)".into()),
            status: t.status.clone(),
            requester: t.requester_name.clone().unwrap_or_else(|| "unknown".into()),
            last_activity: t
                .last_message_at
                .map(|d| d.format("%Y-%m-%d %H:%M").to_string())
                .unwrap_or_default(),
            team_unread: t.team_unread,
        })
        .collect();
    Ok(InboxView {
        status: q.status.clone().unwrap_or_default(),
        rows,
        page: result.page,
        total: result.total,
        total_pages: result.total_pages(),
    })
}

#[derive(askama::Template)]
#[template(path = "curator/inbox/page.html")]
struct InboxPageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: InboxView,
}

#[derive(askama::Template)]
#[template(path = "curator/inbox/list.html")]
struct InboxListTemplate {
    t: T,
    list: InboxView,
}

async fn inbox_page(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<InboxQuery>,
) -> Result<Response, AppError> {
    let list = load_inbox(&st, &q).await?;
    Ok(html(&InboxPageTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { display_name: s.display_name, is_curator: true }),
        list,
    }))
}

async fn inbox_list(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<InboxQuery>,
) -> Result<Response, AppError> {
    let list = load_inbox(&st, &q).await?;
    Ok(html(&InboxListTemplate { t: locale.t, list }))
}

// ── feed moderation queue ─────────────────────────────────────────────────────
struct ReportView {
    id: String,
    reporter: String,
    reason: String,
    author: String,
    content: String,
    deleted: bool,
    at: String,
}

#[derive(askama::Template)]
#[template(path = "curator/moderation/page.html")]
struct ModerationTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    reports: Vec<ReportView>,
}

async fn render_moderation(st: &AppState, s: &crate::auth::Session, locale: Locale) -> Result<Response, AppError> {
    let reports = du_db::social::open_reports(&st.pool)
        .await?
        .into_iter()
        .map(|r| ReportView {
            id: r.id.to_string(),
            reporter: r.reporter_name.unwrap_or_else(|| "unknown".into()),
            reason: r.reason.unwrap_or_default(),
            author: r.author_name.unwrap_or_else(|| "unknown".into()),
            content: r.content,
            deleted: r.post_deleted,
            at: r.created_at.format("%Y-%m-%d %H:%M").to_string(),
        })
        .collect();
    Ok(html(&ModerationTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { display_name: s.display_name.clone(), is_curator: true }),
        reports,
    }))
}

async fn moderation_page(Curator(s): Curator, State(st): State<AppState>, locale: Locale) -> Result<Response, AppError> {
    render_moderation(&st, &s, locale).await
}

#[derive(Deserialize)]
struct ResolveForm {
    action: String, // "spam" | "dismiss"
}

async fn moderation_resolve(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<uuid::Uuid>,
    Form(f): Form<ResolveForm>,
) -> Result<Response, AppError> {
    let spam = match f.action.as_str() {
        "spam" => true,
        "dismiss" => false,
        _ => return Err(AppError::BadRequest("bad action".into())),
    };
    du_db::social::resolve_report(&st.pool, id, s.user_id, spam).await?;
    render_moderation(&st, &s, locale).await
}

/// A pill of the open-thread count for the navbar (lazy-loaded), empty when zero.
async fn open_badge(_c: Curator, State(st): State<AppState>) -> Result<Html<String>, AppError> {
    let n = du_db::social::team_open_count(&st.pool).await?;
    Ok(Html(if n > 0 {
        format!("<span class=\"badge text-bg-danger rounded-pill ms-1\">{n}</span>")
    } else {
        String::new()
    }))
}

// ── inbox: thread detail ──────────────────────────────────────────────────────
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
    requester: String,
    messages: Vec<MsgView>,
}

#[derive(askama::Template)]
#[template(path = "curator/inbox/detail.html")]
struct ThreadDetailTemplate {
    t: T,
    thread: ThreadDetail,
}

/// Build the detail panel for a thread, marking it read on the team side.
async fn render_thread(st: &AppState, t: T, id: Uuid) -> Result<Response, AppError> {
    let thread = du_db::social::get_thread(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("thread {id}")))?;
    du_db::social::mark_read(&st.pool, id, du_db::social::ReadSide::Team).await?;

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

    Ok(html(&ThreadDetailTemplate {
        t,
        thread: ThreadDetail {
            id: id.to_string(),
            subject: thread.subject.unwrap_or_default(),
            status: thread.status,
            requester: thread.requester_name.unwrap_or_else(|| "unknown".into()),
            messages,
        },
    }))
}

async fn thread_panel(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    render_thread(&st, locale.t, id).await
}

#[derive(Deserialize)]
struct ReplyForm {
    body: String,
}

/// On a mutation, return the refreshed panel and trigger the list to reload.
fn changed(body: Response) -> Response {
    (HxHeaders::new().trigger(CHANGED), body).into_response()
}

async fn thread_reply(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<Uuid>,
    Form(f): Form<ReplyForm>,
) -> Result<Response, AppError> {
    let body = f.body.trim();
    if body.is_empty() {
        return Err(AppError::BadRequest("reply body is required".into()));
    }
    du_db::social::post_message(&st.pool, id, s.user_id, body, true).await?;
    Ok(changed(render_thread(&st, locale.t, id).await?))
}

#[derive(Deserialize)]
struct StatusForm {
    status: String,
}

async fn thread_status(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<Uuid>,
    Form(f): Form<StatusForm>,
) -> Result<Response, AppError> {
    let status = norm_status(Some(&f.status)).ok_or_else(|| AppError::BadRequest("bad status".into()))?;
    du_db::social::set_status(&st.pool, id, status).await?;
    Ok(changed(render_thread(&st, locale.t, id).await?))
}

// ── announcements (team broadcast) ────────────────────────────────────────────
struct AnnRow {
    content: String,
    topic: String,
    pinned: bool,
    author: String,
    at: String,
    replies: i64,
}

#[derive(askama::Template)]
#[template(path = "curator/announcements/page.html")]
struct AnnounceTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    rows: Vec<AnnRow>,
    posted: bool,
}

async fn load_announcements(st: &AppState) -> Result<Vec<AnnRow>, AppError> {
    let result = du_db::social::list_feed(&st.pool, Some("ANNOUNCEMENT"), None, 1, 50).await?;
    Ok(result
        .items
        .into_iter()
        .map(|p| AnnRow {
            content: p.content,
            topic: p.topic.unwrap_or_default(),
            pinned: p.pinned,
            author: p.author_name.unwrap_or_else(|| "team".into()),
            at: p.created_at.format("%Y-%m-%d %H:%M").to_string(),
            replies: p.reply_count,
        })
        .collect())
}

async fn announce_page(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
) -> Result<Response, AppError> {
    let rows = load_announcements(&st).await?;
    Ok(html(&AnnounceTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { display_name: s.display_name, is_curator: true }),
        rows,
        posted: false,
    }))
}

#[derive(Deserialize)]
struct AnnounceForm {
    content: String,
    topic: Option<String>,
    pinned: Option<String>,
}

async fn announce_create(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Form(f): Form<AnnounceForm>,
) -> Result<Response, AppError> {
    let content = f.content.trim();
    if content.is_empty() {
        return Err(AppError::BadRequest("announcement body is required".into()));
    }
    let topic = f.topic.map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    let id = du_db::social::create_post(&st.pool, s.user_id, "ANNOUNCEMENT", topic.as_deref(), content, None).await?;
    if f.pinned.as_deref() == Some("on") {
        du_db::social::set_pinned(&st.pool, id, true).await?;
    }
    let rows = load_announcements(&st).await?;
    Ok(html(&AnnounceTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { display_name: s.display_name, is_curator: true }),
        rows,
        posted: true,
    }))
}

#[cfg(test)]
mod tests {
    use axum::body::Body;
    use axum::http::Request;
    use tower::ServiceExt;

    /// The team inbox + announcements are curator-gated: an anonymous request is
    /// redirected to /login rather than served. (Triage/reply behavior itself is
    /// covered by the du_db::social integration tests.)
    #[tokio::test]
    async fn inbox_routes_are_curator_gated() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping inbox gating test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let state = crate::state::AppState { pool: db.pool().clone(), key: tower_cookies::Key::generate(), oauth: None };

        for uri in ["/curator/inbox", "/curator/announcements"] {
            let resp = crate::routes::app(state.clone())
                .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
                .await
                .unwrap();
            assert!(resp.status().is_redirection(), "{uri} should redirect when anonymous");
            assert_eq!(resp.headers().get("location").unwrap(), "/login");
        }
    }
}
