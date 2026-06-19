//! Group-project social surface (Tier 2b). A logged-in member with an AT-Proto DID
//! creates a project (becoming the founding ADMIN, `owner_did`), then the project gets a
//! **members-only feed** (`feed_post kind=PROJECT, topic=project:<id>`) and a roster on
//! top of the **D5 ACL** (`du_db::research`). Session-authed: the viewer's DID is bridged
//! from `ident.users` (projects are DID-keyed; the feed is UUID-keyed — same user, both
//! ids). Web project-create is the bridge until Navigator groupProject PDS ingest exists.

use crate::auth::{NavUser, User};
use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, State};
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use du_db::research::{self, Capability, Role};
use serde::Deserialize;
use uuid::Uuid;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/projects", get(list).post(create))
        .route("/projects/new", get(new_form))
        .route("/projects/:id", get(detail))
        .route("/projects/:id/posts", post(post_to_project))
        .route("/projects/:id/members", post(add_member))
        .route("/projects/:id/members/revoke", post(revoke_member))
}

/// The session user's AT-Proto DID, if linked. Projects are DID-owned, so this is the
/// bridge into the D5 ACL; `None` ⇒ the account can't own/join projects yet.
async fn viewer_did(st: &AppState, user_id: Uuid) -> Result<Option<String>, AppError> {
    Ok(du_db::auth::did_of(&st.pool, du_domain::ids::UserId(user_id)).await?)
}

const PROJECT_TYPES: [&str; 6] = ["HAPLOGROUP", "SURNAME", "GEOGRAPHIC", "ETHNIC", "RESEARCH", "CUSTOM"];

// ── list ──────────────────────────────────────────────────────────────────────
struct ProjectRow {
    id: i64,
    name: String,
    kind: String,
    lineage: String,
}

#[derive(askama::Template)]
#[template(path = "social/projects/list.html")]
struct ListTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    needs_did: bool,
    rows: Vec<ProjectRow>,
}

async fn list(User(s): User, State(st): State<AppState>, locale: Locale) -> Result<Response, AppError> {
    let did = viewer_did(&st, s.user_id).await?;
    let rows = match &did {
        Some(d) => research::projects_for_member(&st.pool, d)
            .await?
            .into_iter()
            .map(|p| ProjectRow {
                id: p.id,
                name: p.project_name,
                kind: p.project_type,
                lineage: p.target_lineage.unwrap_or_default(),
            })
            .collect(),
        None => Vec::new(),
    };
    Ok(html(&ListTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        needs_did: did.is_none(),
        rows,
    }))
}

// ── create ──────────────────────────────────────────────────────────────────
#[derive(askama::Template)]
#[template(path = "social/projects/new.html")]
struct NewTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    types: Vec<&'static str>,
}

async fn new_form(User(s): User, State(st): State<AppState>, locale: Locale) -> Result<Response, AppError> {
    if viewer_did(&st, s.user_id).await?.is_none() {
        return Ok(Redirect::to("/projects").into_response());
    }
    Ok(html(&NewTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        types: PROJECT_TYPES.to_vec(),
    }))
}

#[derive(Deserialize)]
struct CreateForm {
    name: String,
    project_type: String,
    lineage: Option<String>,
    description: Option<String>,
}

async fn create(User(s): User, State(st): State<AppState>, Form(f): Form<CreateForm>) -> Result<Response, AppError> {
    let Some(did) = viewer_did(&st, s.user_id).await? else {
        return Err(AppError::Forbidden);
    };
    let name = f.name.trim();
    if name.is_empty() {
        return Err(AppError::BadRequest("project name is required".into()));
    }
    if !PROJECT_TYPES.contains(&f.project_type.as_str()) {
        return Err(AppError::BadRequest("invalid project type".into()));
    }
    let lineage = f.lineage.as_deref().map(str::trim).filter(|s| matches!(*s, "Y_DNA" | "MT_DNA" | "BOTH"));
    let description = f.description.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let id = research::create_project(&st.pool, name, &f.project_type, lineage, description, &did).await?;
    Ok(Redirect::to(&format!("/projects/{id}")).into_response())
}

// ── detail (feed + roster) ──────────────────────────────────────────────────
struct ProjPost {
    author: String,
    content: String,
    at: String,
    replies: Vec<ProjPost>,
}

struct MemberView {
    did: String,
    name: String,
    role: String,
    removable: bool,
}

#[derive(askama::Template)]
#[template(path = "social/projects/detail.html")]
struct DetailTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    id: i64,
    name: String,
    description: String,
    is_admin: bool,
    posts: Vec<ProjPost>,
    members: Vec<MemberView>,
    roles: Vec<&'static str>,
}

/// Load a project the viewer is a member of, or 404 (no membership-leak). Returns the
/// project + the viewer's DID + whether they're an admin.
async fn member_project(
    st: &AppState,
    id: i64,
    user_id: Uuid,
) -> Result<(research::ProjectRow, String, bool), AppError> {
    let did = viewer_did(st, user_id).await?.ok_or(AppError::NotFound("project".into()))?;
    let project = research::get_project(&st.pool, id).await?.ok_or_else(|| AppError::NotFound(format!("project {id}")))?;
    let role = research::role_of(&st.pool, id, &did).await?;
    let Some(role) = role else { return Err(AppError::NotFound(format!("project {id}"))) };
    Ok((project, did, role == Role::Admin))
}

async fn detail(User(s): User, State(st): State<AppState>, locale: Locale, Path(id): Path<i64>) -> Result<Response, AppError> {
    let (project, _did, is_admin) = member_project(&st, id, s.user_id).await?;
    let topic = format!("project:{id}");

    // Members-only feed: top-level PROJECT posts in this topic + their replies.
    let mut posts = Vec::new();
    for p in du_db::social::list_feed(&st.pool, Some("PROJECT"), Some(&topic), 1, 100).await?.items {
        let mut replies = Vec::new();
        if p.reply_count > 0 {
            for r in du_db::social::post_replies(&st.pool, p.id).await? {
                replies.push(ProjPost {
                    author: r.author_name.unwrap_or_else(|| "member".into()),
                    content: r.content,
                    at: r.created_at.format("%Y-%m-%d %H:%M").to_string(),
                    replies: Vec::new(),
                });
            }
        }
        posts.push(ProjPost {
            author: p.author_name.unwrap_or_else(|| "member".into()),
            content: p.content,
            at: p.created_at.format("%Y-%m-%d %H:%M").to_string(),
            replies,
        });
    }

    // Roster (D5 ACL), names resolved where the DID is bridged into ident.users.
    let mut members = Vec::new();
    for m in research::members_of(&st.pool, id).await? {
        let name = du_db::auth::display_name_by_did(&st.pool, &m.member_did).await?.unwrap_or_else(|| m.member_did.clone());
        members.push(MemberView {
            removable: is_admin && m.role != "ADMIN", // can't revoke the founding owner
            did: m.member_did,
            name,
            role: m.role,
        });
    }

    Ok(html(&DetailTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        id,
        name: project.project_name,
        description: project.description.unwrap_or_default(),
        is_admin,
        posts,
        members,
        roles: vec!["CO_ADMIN", "MODERATOR", "CURATOR"],
    }))
}

// ── project feed posting (members) ──────────────────────────────────────────
#[derive(Deserialize)]
struct PostForm {
    content: String,
    parent_post_id: Option<Uuid>,
}

async fn post_to_project(
    User(s): User,
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Form(f): Form<PostForm>,
) -> Result<Response, AppError> {
    // Membership (not reputation) gates a project feed.
    member_project(&st, id, s.user_id).await?;
    let content = f.content.trim();
    if content.is_empty() {
        return Err(AppError::BadRequest("post body is required".into()));
    }
    let topic = format!("project:{id}");
    du_db::social::create_post(&st.pool, s.user_id, "PROJECT", Some(&topic), content, f.parent_post_id).await?;
    Ok(Redirect::to(&format!("/projects/{id}")).into_response())
}

// ── member management (ADMIN, ManageRoles) ──────────────────────────────────
#[derive(Deserialize)]
struct AddMemberForm {
    member_did: String,
    role: String,
}

async fn add_member(
    User(s): User,
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Form(f): Form<AddMemberForm>,
) -> Result<Response, AppError> {
    let did = viewer_did(&st, s.user_id).await?.ok_or(AppError::Forbidden)?;
    if !research::can(&st.pool, id, &did, Capability::ManageRoles).await? {
        return Err(AppError::Forbidden);
    }
    let member_did = f.member_did.trim();
    let role = Role::parse(&f.role).ok_or_else(|| AppError::BadRequest("invalid role".into()))?;
    if member_did.is_empty() || role == Role::Admin {
        return Err(AppError::BadRequest("a member DID and a non-admin role are required".into()));
    }
    research::add_member(&st.pool, id, member_did, role, &[], &did).await?;
    Ok(Redirect::to(&format!("/projects/{id}")).into_response())
}

#[derive(Deserialize)]
struct RevokeForm {
    member_did: String,
}

async fn revoke_member(
    User(s): User,
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Form(f): Form<RevokeForm>,
) -> Result<Response, AppError> {
    let did = viewer_did(&st, s.user_id).await?.ok_or(AppError::Forbidden)?;
    if !research::can(&st.pool, id, &did, Capability::ManageRoles).await? {
        return Err(AppError::Forbidden);
    }
    research::revoke_member(&st.pool, id, f.member_did.trim()).await?;
    Ok(Redirect::to(&format!("/projects/{id}")).into_response())
}

#[cfg(test)]
mod tests {
    use axum::body::Body;
    use axum::http::Request;
    use tower::ServiceExt;

    /// The project routes require a signed-in session (membership/ACL behavior is covered
    /// by the du_db projects integration test).
    #[tokio::test]
    async fn project_routes_require_login() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping project gating test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let state = crate::state::AppState { pool: db.pool().clone(), key: tower_cookies::Key::generate(), oauth: None };

        for uri in ["/projects", "/projects/new", "/projects/1"] {
            let resp = crate::routes::app(state.clone())
                .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
                .await
                .unwrap();
            assert!(resp.status().is_redirection(), "{uri} should redirect when anonymous");
            assert_eq!(resp.headers().get("location").unwrap(), "/login");
        }
    }
}
