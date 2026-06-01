//! Public references (publications) + per-publication biosample report.
//! Same two-panel HTMX pattern as the variant browser: a searchable/paginated
//! publication list on the left, the selected publication's samples on the right.

use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::response::Response;
use axum::routing::get;
use axum::Router;
use du_domain::ids::PublicationId;
use serde::Deserialize;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/references", get(page))
        .route("/references/list", get(list))
        .route("/references/:id/biosamples", get(biosamples))
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
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&ReferencesPageTemplate { t: locale.t, next: locale.next, list }))
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
