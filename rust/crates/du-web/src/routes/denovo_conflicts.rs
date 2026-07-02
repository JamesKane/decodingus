//! Curator review of **de-novo tree conflicts** — reference clades (ISOGG /
//! PhyloTree) whose de-novo placement disagrees: foreign tips inside the clade's
//! home node and/or clade members scattered elsewhere. Read-only triage queue
//! (worst magnitude first), filterable by lineage. Populated by the de-novo loader.

use crate::auth::{Curator, NavUser};
use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use crate::extract::Query;
use axum::extract::State;
use axum::response::Response;
use axum::routing::get;
use axum::Router;
use du_domain::enums::DnaType;
use serde::Deserialize;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/denovo-conflicts", get(page))
        .route("/curator/denovo-conflicts/fragment", get(list))
}

struct Row {
    lineage: &'static str,
    clade: String,
    label: String,
    n_tips: i32,
    magnitude: i32,
    home_node: String,
    foreign_in: i32,
    members_away: i32,
}

struct ListView {
    rows: Vec<Row>,
    page: i64,
    total: i64,
    total_pages: i64,
    dna: String,
}

#[derive(Deserialize)]
struct ListQuery {
    page: Option<i64>,
    #[serde(rename = "type")]
    dna: Option<String>,
}

fn parse_dna(s: Option<&str>) -> Option<DnaType> {
    match s {
        Some("Y" | "Y_DNA") => Some(DnaType::YDna),
        Some("MT" | "MT_DNA") => Some(DnaType::MtDna),
        _ => None,
    }
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    let dna = parse_dna(q.dna.as_deref());
    let result = du_db::denovo::list_conflicts(&st.pool, dna, q.page.unwrap_or(1), 25).await?;
    let (page, total, total_pages) = (result.page, result.total, result.total_pages());
    let rows = result
        .items
        .into_iter()
        .map(|c| Row {
            lineage: if c.dna_type == "Y_DNA" { "Y" } else { "mt" },
            clade: c.haplogroup,
            label: c.label.unwrap_or_default(),
            n_tips: c.n_tips,
            magnitude: c.magnitude,
            home_node: c.home_node.unwrap_or_default(),
            foreign_in: c.foreign_in,
            members_away: c.members_away,
        })
        .collect();
    Ok(ListView { rows, page, total, total_pages, dna: q.dna.clone().unwrap_or_default() })
}

#[derive(askama::Template)]
#[template(path = "curator/denovo-conflicts/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/denovo-conflicts/list.html")]
struct ListTemplate {
    t: T,
    list: ListView,
}

async fn page(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&PageTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { display_name: s.display_name, is_curator: true }),
        list,
    }))
}

async fn list(
    Curator(_): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&ListTemplate { t: locale.t, list }))
}
