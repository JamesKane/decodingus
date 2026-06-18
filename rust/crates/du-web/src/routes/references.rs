//! Public references (publications) + per-publication biosample report.
//! Same two-panel HTMX pattern as the variant browser: a searchable/paginated
//! publication list on the left, the selected publication's samples on the right.

use crate::auth::{MaybeUser, NavUser};
use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::routes::pages::{recaptcha_secret, recaptcha_site_key, verify_recaptcha};
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::response::Response;
use axum::routing::get;
use axum::{Form, Router};
use du_domain::ids::PublicationId;
use serde::Deserialize;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/references", get(page))
        .route("/references/list", get(list))
        .route("/references/:id/biosamples", get(biosamples))
        .route("/references/submit", get(submit_form).post(submit))
}

#[derive(Deserialize)]
struct ListQuery {
    query: Option<String>,
    page: Option<i64>,
    page_size: Option<i64>,
}

#[derive(Deserialize)]
struct PageQuery {
    page: Option<i64>,
    page_size: Option<i64>,
}

struct PubRow {
    id: i64,
    title: String,
    journal: String,
    year: String,
    citations: Option<i32>,
}

struct PubListView {
    query: String,
    rows: Vec<PubRow>,
    page: i64,
    page_size: i64,
    total: i64,
    total_pages: i64,
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<PubListView, AppError> {
    let result =
        du_db::publication::search(&st.pool, q.query.as_deref(), q.page.unwrap_or(1), q.page_size.unwrap_or(20))
            .await?;
    let rows = result
        .items
        .iter()
        .map(|p| PubRow {
            id: p.id.0,
            title: p.title.clone(),
            journal: p.journal.clone().unwrap_or_default(),
            year: p.publication_date.map(|d| d.format("%Y").to_string()).unwrap_or_default(),
            citations: p.cited_by_count,
        })
        .collect();
    Ok(PubListView {
        query: q.query.clone().unwrap_or_default(),
        rows,
        page: result.page,
        page_size: result.page_size,
        total: result.total,
        total_pages: result.total_pages(),
    })
}

#[derive(askama::Template)]
#[template(path = "references/page.html")]
struct ReferencesPageTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    list: PubListView,
}

#[derive(askama::Template)]
#[template(path = "references/list.html")]
struct PubListTemplate {
    t: T,
    list: PubListView,
}

struct BioRow {
    source: String,
    accession: String,
    alias: String,
    description: String,
}

#[derive(askama::Template)]
#[template(path = "references/biosamples.html")]
struct BiosamplesTemplate {
    t: T,
    pub_id: i64,
    pub_title: String,
    pub_doi: Option<String>,
    rows: Vec<BioRow>,
    page: i64,
    page_size: i64,
    total: i64,
    total_pages: i64,
}

async fn page(
    State(st): State<AppState>,
    locale: Locale,
    user: crate::auth::MaybeUser,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&ReferencesPageTemplate { t: locale.t, next: locale.next, user: user.nav(), list }))
}

async fn list(
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&PubListTemplate { t: locale.t, list }))
}

async fn biosamples(
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Query(q): Query<PageQuery>,
) -> Result<Response, AppError> {
    let pub_id = PublicationId(id);
    let publication = du_db::publication::get_by_id(&st.pool, pub_id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("publication {id}")))?;
    let result =
        du_db::biosample::for_publication(&st.pool, pub_id, q.page.unwrap_or(1), q.page_size.unwrap_or(25))
            .await?;
    let rows = result
        .items
        .iter()
        .map(|b| BioRow {
            source: b.source.label().to_string(),
            accession: b.accession.clone().unwrap_or_default(),
            alias: b.alias.clone().unwrap_or_default(),
            description: b.description.clone().unwrap_or_default(),
        })
        .collect();

    Ok(html(&BiosamplesTemplate {
        t: locale.t,
        pub_id: id,
        pub_title: publication.title,
        pub_doi: publication.doi,
        rows,
        page: result.page,
        page_size: result.page_size,
        total: result.total,
        total_pages: result.total_pages(),
    }))
}

// ── public "suggest a paper" (DOI) form ───────────────────────────────────────

#[derive(askama::Template)]
#[template(path = "references/submit.html")]
struct SubmitTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    /// reCAPTCHA site key — renders the widget when present.
    site_key: Option<String>,
    doi: String,
    /// Success: the candidate was queued (carries the resolved title).
    queued: Option<String>,
    error: Option<String>,
}

#[derive(Deserialize)]
struct SubmitForm {
    doi: String,
    #[serde(rename = "g-recaptcha-response")]
    recaptcha: Option<String>,
}

/// Strip common DOI prefixes so OpenAlex's `/works/doi:` lookup gets a bare DOI.
fn normalize_doi(raw: &str) -> String {
    let d = raw.trim();
    let d = d.strip_prefix("https://doi.org/").or_else(|| d.strip_prefix("http://doi.org/")).unwrap_or(d);
    let d = d.strip_prefix("doi:").unwrap_or(d);
    d.trim().to_string()
}

async fn submit_form(locale: Locale, user: MaybeUser) -> Response {
    html(&SubmitTemplate {
        t: locale.t,
        next: locale.next,
        user: user.nav(),
        site_key: recaptcha_site_key(),
        doi: String::new(),
        queued: None,
        error: None,
    })
}

/// Public: look a submitted DOI up in OpenAlex and queue it as a pending
/// `publication_candidate` for curator review (never a published reference
/// directly). Idempotent on the work's OpenAlex id.
async fn submit(
    State(st): State<AppState>,
    locale: Locale,
    user: MaybeUser,
    Form(f): Form<SubmitForm>,
) -> Result<Response, AppError> {
    let doi = normalize_doi(&f.doi);
    let render = |doi: String, queued: Option<String>, error: Option<String>| {
        html(&SubmitTemplate {
            t: locale.t,
            next: locale.next.clone(),
            user: user.nav(),
            site_key: recaptcha_site_key(),
            doi,
            queued,
            error,
        })
    };

    if doi.is_empty() {
        return Ok(render(doi, None, Some(locale.t.get("submit.error.empty").to_string())));
    }
    // reCAPTCHA enforced only when configured (dev works without).
    if let Some(secret) = recaptcha_secret() {
        let token = f.recaptcha.as_deref().unwrap_or("");
        if token.is_empty() || !verify_recaptcha(&secret, token).await {
            return Ok(render(doi, None, Some(locale.t.get("submit.error.captcha").to_string())));
        }
    }
    // Already in the catalog?
    if du_db::publication::exists_by_doi(&st.pool, &doi).await? {
        return Ok(render(doi, None, Some(locale.t.get("submit.error.exists").to_string())));
    }

    // Resolve via OpenAlex; only queue works it can identify (need an OpenAlex id).
    let client = du_external::openalex::OpenAlexClient::new(std::env::var("OPENALEX_MAILTO").ok());
    let meta = match client.work_by_doi(&doi).await {
        Ok(Some(m)) => m,
        Ok(None) => return Ok(render(doi, None, Some(locale.t.get("submit.error.notfound").to_string()))),
        Err(_) => return Ok(render(doi, None, Some(locale.t.get("submit.error.lookup").to_string()))),
    };
    let Some(openalex_id) = meta.openalex_id.as_deref() else {
        return Ok(render(doi, None, Some(locale.t.get("submit.error.notfound").to_string())));
    };

    du_db::publication::upsert_candidate(
        &st.pool,
        openalex_id,
        Some(&doi),
        meta.title.as_deref(),
        meta.abstract_summary.as_deref(),
        meta.publication_date,
        meta.journal.as_deref(),
    )
    .await?;

    let title = meta.title.clone().unwrap_or_else(|| doi.clone());
    Ok(render(String::new(), Some(title), None))
}
