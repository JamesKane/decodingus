//! Recruitment campaigns (Tier 3c) — the **privacy-preserving** cohort broker. A project
//! admin (D5 `ManageSubjects` + a reputation floor) defines criteria + a message; the
//! AppView computes the cohort from `fed.biosample` and delivers invitations via the
//! notification rail. The researcher **never sees the cohort** — only aggregate counts and
//! the members who opt in. Targets respond from their notification (`/recruitment/:id`).

use crate::auth::{NavUser, User};
use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, State};
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use du_db::research::{self, Capability};
use serde::Deserialize;
use uuid::Uuid;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/projects/:id/recruit", get(recruit_page).post(create_campaign))
        .route("/recruitment/:cid", get(invitation))
        .route("/recruitment/:cid/respond", post(respond))
}

async fn viewer_did(st: &AppState, user_id: Uuid) -> Result<Option<String>, AppError> {
    Ok(du_db::auth::did_of(&st.pool, du_domain::ids::UserId(user_id)).await?)
}

/// Bridge a DID into `ident.users` and drop a SYSTEM notification (the recruitment rail).
async fn notify_did(st: &AppState, did: &str, title: &str, link: &str) -> Result<(), AppError> {
    let uid = du_db::auth::upsert_user_by_did(&st.pool, did, None, None).await?.0;
    du_db::notification::notify_system(&st.pool, uid, title, Some(link), None).await?;
    Ok(())
}

// ── researcher view ───────────────────────────────────────────────────────────
struct CampaignVM {
    title: String,
    haplogroup: String,
    lineage: String,
    cohort_size: i32,
    accepted_count: i64,
    accepted: Vec<String>,
}

#[derive(askama::Template)]
#[template(path = "social/recruitment/recruit.html")]
struct RecruitTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    project_id: i64,
    project_name: String,
    campaigns: Vec<CampaignVM>,
}

/// Resolve the project + assert the viewer is an admin (D5 ManageSubjects) with enough
/// reputation to recruit. Returns (project, viewer_did).
async fn recruiter_ctx(st: &AppState, id: i64, user_id: Uuid) -> Result<(research::ProjectRow, String), AppError> {
    let did = viewer_did(st, user_id).await?.ok_or(AppError::Forbidden)?;
    let project = research::get_project(&st.pool, id).await?.ok_or_else(|| AppError::NotFound(format!("project {id}")))?;
    if !research::can(&st.pool, id, &did, Capability::ManageSubjects).await? {
        return Err(AppError::Forbidden);
    }
    if !du_db::reputation::at_least(&st.pool, user_id, du_db::reputation::RECRUIT_MIN).await? {
        return Err(AppError::Forbidden);
    }
    Ok((project, did))
}

async fn recruit_page(User(s): User, State(st): State<AppState>, locale: Locale, Path(id): Path<i64>) -> Result<Response, AppError> {
    let (project, _did) = recruiter_ctx(&st, id, s.user_id).await?;
    let mut campaigns = Vec::new();
    for c in du_db::recruitment::campaigns_for_project(&st.pool, id).await? {
        let accepted = resolve_names(&st, du_db::recruitment::accepted_dids(&st.pool, c.id).await?).await?;
        campaigns.push(CampaignVM {
            title: c.title,
            haplogroup: c.target_haplogroup,
            lineage: c.lineage,
            cohort_size: c.cohort_size,
            accepted_count: c.accepted_count,
            accepted,
        });
    }
    Ok(html(&RecruitTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        project_id: id,
        project_name: project.project_name,
        campaigns,
    }))
}

/// Resolve a list of DIDs to display names (falling back to the DID).
async fn resolve_names(st: &AppState, dids: Vec<String>) -> Result<Vec<String>, AppError> {
    let mut names = Vec::with_capacity(dids.len());
    for d in dids {
        names.push(du_db::auth::display_name_by_did(&st.pool, &d).await?.unwrap_or(d));
    }
    Ok(names)
}

#[derive(Deserialize)]
struct CampaignForm {
    title: String,
    message: String,
    target_haplogroup: String,
    lineage: String,
}

async fn create_campaign(
    User(s): User,
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Form(f): Form<CampaignForm>,
) -> Result<Response, AppError> {
    let (_project, did) = recruiter_ctx(&st, id, s.user_id).await?;
    let (title, message, hg) = (f.title.trim(), f.message.trim(), f.target_haplogroup.trim());
    if title.is_empty() || message.is_empty() || hg.is_empty() {
        return Err(AppError::BadRequest("title, message and haplogroup are required".into()));
    }
    if !matches!(f.lineage.as_str(), "Y_DNA" | "MT_DNA") {
        return Err(AppError::BadRequest("lineage must be Y_DNA or MT_DNA".into()));
    }
    let cid = du_db::recruitment::create_campaign(&st.pool, id, s.user_id, title, message, hg, &f.lineage).await?;
    // Compute + deliver to the matching cohort (the researcher never sees these DIDs).
    let cohort = du_db::recruitment::compute_cohort(&st.pool, hg, &f.lineage, Some(&did)).await?;
    let fresh = du_db::recruitment::deliver(&st.pool, cid, &cohort).await?;
    let link = format!("/recruitment/{cid}");
    for target in &fresh {
        notify_did(&st, target, "A research project is recruiting members like you", &link).await?;
    }
    Ok(Redirect::to(&format!("/projects/{id}/recruit")).into_response())
}

// ── target invitation ─────────────────────────────────────────────────────────
#[derive(askama::Template)]
#[template(path = "social/recruitment/invitation.html")]
struct InvitationTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    cid: i64,
    title: String,
    message: String,
    project_name: String,
    status: String,
}

async fn invitation(User(s): User, State(st): State<AppState>, locale: Locale, Path(cid): Path<i64>) -> Result<Response, AppError> {
    let did = viewer_did(&st, s.user_id).await?.ok_or_else(|| AppError::NotFound("invitation".into()))?;
    // Authorization: the viewer must be a target of this campaign (no membership-leak).
    let status = du_db::recruitment::target_status(&st.pool, cid, &did)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("invitation {cid}")))?;
    let c = du_db::recruitment::get_campaign(&st.pool, cid).await?.ok_or_else(|| AppError::NotFound(format!("campaign {cid}")))?;
    let project_name = research::get_project(&st.pool, project_of(&st, cid).await?)
        .await?
        .map(|p| p.project_name)
        .unwrap_or_default();
    Ok(html(&InvitationTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { is_curator: s.is_curator(), display_name: s.display_name }),
        cid,
        title: c.title,
        message: c.message,
        project_name,
        status,
    }))
}

async fn project_of(st: &AppState, cid: i64) -> Result<i64, AppError> {
    Ok(du_db::recruitment::campaign_owner_project(&st.pool, cid).await?.map(|(_, pid)| pid).unwrap_or(0))
}

#[derive(Deserialize)]
struct RespondForm {
    action: String, // accept | decline
}

async fn respond(
    User(s): User,
    State(st): State<AppState>,
    Path(cid): Path<i64>,
    Form(f): Form<RespondForm>,
) -> Result<Response, AppError> {
    let did = viewer_did(&st, s.user_id).await?.ok_or(AppError::Forbidden)?;
    let accept = match f.action.as_str() {
        "accept" => true,
        "decline" => false,
        _ => return Err(AppError::BadRequest("bad action".into())),
    };
    let changed = du_db::recruitment::respond(&st.pool, cid, &did, accept).await?;
    // On an acceptance, the researcher learns this opt-in — alert them.
    if changed && accept {
        if let Some((owner_uid, project_id)) = du_db::recruitment::campaign_owner_project(&st.pool, cid).await? {
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
    Ok(Redirect::to(&format!("/recruitment/{cid}")).into_response())
}

#[cfg(test)]
mod tests {
    use axum::body::Body;
    use axum::http::Request;
    use tower::ServiceExt;

    /// Recruitment routes require login (cohort/opt-in privacy is covered by the du_db
    /// recruitment integration test).
    #[tokio::test]
    async fn recruitment_routes_require_login() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping recruitment gating test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let state = crate::state::AppState { pool: db.pool().clone(), key: tower_cookies::Key::generate(), oauth: None };

        for uri in ["/projects/1/recruit", "/recruitment/1"] {
            let resp = crate::routes::app(state.clone())
                .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
                .await
                .unwrap();
            assert!(resp.status().is_redirection(), "{uri} should redirect when anonymous");
            assert_eq!(resp.headers().get("location").unwrap(), "/login");
        }
    }
}
