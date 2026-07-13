//! Curator **Variant Naming Authority** UI. Two-panel HTMX screen over
//! `du_db::naming`: the naming queue (left) and a variant panel (right) showing
//! coordinates, current name/aliases, the branch it defines, and any same-coord
//! named variant (dedup) — with **Assign DU name**, **Flag for review**, and
//! **Send back to unnamed** actions. Minting goes through `assign_du_name`.

use crate::auth::{Curator, NavUser};
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use crate::extract::Query;
use axum::extract::{Path, State};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;

const CHANGED: &str = "naming-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/naming", get(page))
        .route("/curator/naming/fragment", get(list))
        .route("/curator/naming/:id/panel", get(panel))
        .route("/curator/naming/:id/assign", post(assign))
        .route("/curator/naming/:id/adopt", post(adopt))
        .route("/curator/naming/:id/status", post(status))
}

// ── helpers ─────────────────────────────────────────────────────────────────

/// Pick the coordinate build to display, **preferring `hs1`** — the platform-native
/// T2T-CHM13 assembly the de-novo catalog is built on — then GRCh38, then whatever
/// build is present. Returns the build name and its coordinate object.
fn pick_build(coords: &serde_json::Value) -> Option<(String, &serde_json::Value)> {
    let obj = coords.as_object()?;
    for b in ["hs1", "GRCh38"] {
        if let Some(v) = obj.get(b) {
            return Some((b.to_string(), v));
        }
    }
    obj.iter().next().map(|(k, v)| (k.clone(), v))
}

/// A JSONB string-or-integer field as a string (positions may be either).
fn field_str(v: &serde_json::Value, key: &str) -> Option<String> {
    v.get(key)
        .and_then(|x| x.as_str().map(str::to_string).or_else(|| x.as_i64().map(|n| n.to_string())))
}

/// "chrY:2781234" from the preferred-build coordinate JSONB (hs1 first), or "—".
fn coord_label(coords: &serde_json::Value) -> String {
    match pick_build(coords) {
        Some((_, g)) => match (g.get("contig").and_then(|v| v.as_str()), field_str(g, "position")) {
            (Some(c), Some(p)) => format!("{c}:{p}"),
            _ => "—".into(),
        },
        None => "—".into(),
    }
}

/// The build name whose coordinate `coord_label` displayed (e.g. "hs1"), or "—".
fn coord_build(coords: &serde_json::Value) -> String {
    pick_build(coords).map(|(b, _)| b).unwrap_or_else(|| "—".into())
}

/// The mutation state "G→A" (ancestral→derived) from the preferred build — what the
/// curator needs to name a variant — or "—".
fn allele_label(coords: &serde_json::Value) -> String {
    match pick_build(coords) {
        Some((_, g)) => match (field_str(g, "ancestral"), field_str(g, "derived")) {
            (Some(a), Some(d)) => format!("{a}→{d}"),
            _ => "—".into(),
        },
        None => "—".into(),
    }
}

fn common_names(aliases: &serde_json::Value) -> Vec<String> {
    aliases
        .get("common_names")
        .and_then(|v| v.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|x| x.as_str())
                // Drop synthetic coordinate placeholders (`chrY:…`) the loader stashed as
                // aliases — they aren't real alternate names.
                .filter(|s| !du_db::naming::is_placeholder_name(s))
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

// ── list ──────────────────────────────────────────────────────────────────────

struct Row {
    id: i64,
    name: String,
    status: String,
    coord: String,
    alleles: String,
    defining: String,
}

struct ListView {
    mode: String,
    rows: Vec<Row>,
    page: i64,
    total: i64,
    total_pages: i64,
}

#[derive(Deserialize)]
struct ListQuery {
    mode: Option<String>,
    page: Option<i64>,
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    let mode = q.mode.clone().unwrap_or_else(|| "needs_name".into());
    let result = du_db::naming::queue(&st.pool, &mode, q.page.unwrap_or(1), 25).await?;
    let (page, total, total_pages) = (result.page, result.total, result.total_pages());
    let rows = result
        .items
        .into_iter()
        .map(|i| {
            // A synthetic coordinate placeholder (`chrY:…`) the loader stamped NAMED is
            // shown as unnamed — it is naming work, not a ratified name.
            let placeholder = i.canonical_name.as_deref().is_some_and(du_db::naming::is_placeholder_name);
            Row {
                id: i.id,
                name: match i.canonical_name {
                    Some(n) if !placeholder => n,
                    _ => "(unnamed)".into(),
                },
                status: if placeholder { "UNNAMED".into() } else { i.naming_status },
                coord: coord_label(&i.coordinates),
                alleles: allele_label(&i.coordinates),
                defining: i.defining.unwrap_or_else(|| "—".into()),
            }
        })
        .collect();
    Ok(ListView { mode, rows, page, total, total_pages })
}

#[derive(askama::Template)]
#[template(path = "curator/naming/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/naming/list.html")]
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
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&ListTemplate { t: locale.t, list }))
}

// ── detail panel ──────────────────────────────────────────────────────────────

struct Candidate {
    name: String,
}

struct DetailView {
    id: i64,
    name: Option<String>,
    status: String,
    mutation_type: String,
    coord: String,
    coord_build: String,
    alleles: String,
    aliases: Vec<String>,
    defining: Option<String>,
    dedup: Vec<Candidate>,
    can_assign: bool,
    /// Established (non-DU) name to reuse, if this variant is named by definition.
    established: Option<String>,
    notice: Option<String>,
}

#[derive(askama::Template)]
#[template(path = "curator/naming/detail.html")]
struct DetailTemplate {
    t: T,
    v: DetailView,
}

async fn build_detail(st: &AppState, id: i64, notice: Option<String>) -> Result<DetailView, AppError> {
    let i = du_db::naming::get(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("variant {id}")))?;
    let dedup = du_db::naming::dedup_by_site(&st.pool, id)
        .await?
        .into_iter()
        .map(|(_, name)| Candidate { name })
        .collect();
    // A synthetic coordinate placeholder (`chrY:…`) counts as unnamed even though the
    // loader marked it NAMED — the variant is still nameable, and must not display as
    // "already named".
    let placeholder = i.canonical_name.as_deref().is_some_and(du_db::naming::is_placeholder_name);
    let can_assign = i.naming_status != "NAMED" || placeholder;
    // "Named by definition": a real name for this locus + mutation state already exists —
    // on the variant's own aliases, or on the catalog row at the same site. Reuse it rather
    // than mint a DU id. Sourced from `adoptable_name` (not `established_name`) so that the
    // offer is backed by the same lookup as the dedup warning above: a de-novo coordinate
    // row has no aliases, so the aliases-only source left the curator staring at "a named
    // variant already exists" with no button that could reuse it, and Mint DU name — which
    // would fork the marker's identity — as the only action on screen.
    let established = if can_assign && (i.canonical_name.is_none() || placeholder) {
        du_db::naming::adoptable_name(&st.pool, id, &i.aliases).await?
    } else {
        None
    };
    Ok(DetailView {
        id: i.id,
        name: if placeholder { None } else { i.canonical_name.clone() },
        status: if placeholder { "UNNAMED".into() } else { i.naming_status.clone() },
        mutation_type: i.mutation_type,
        coord: coord_label(&i.coordinates),
        coord_build: coord_build(&i.coordinates),
        alleles: allele_label(&i.coordinates),
        aliases: common_names(&i.aliases),
        defining: i.defining,
        dedup,
        can_assign,
        established,
        notice,
    })
}

async fn detail_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let v = build_detail(st, id, notice).await?;
    Ok(html(&DetailTemplate { t, v }))
}

async fn changed_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let body = detail_response(st, t, id, notice).await?;
    Ok((HxHeaders::new().trigger(CHANGED), body).into_response())
}

async fn panel(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    detail_response(&st, locale.t, id, None).await
}

// ── actions ─────────────────────────────────────────────────────────────────

async fn assign(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let notice = match du_db::naming::assign_du_name(&st.pool, id).await {
        Ok(du) => format!("{} {du}", locale.t.get("nm.notice.minted")),
        Err(du_db::DbError::Conflict(m)) => m,
        Err(e) => return Err(e.into()),
    };
    changed_response(&st, locale.t, id, Some(notice)).await
}

/// Reuse the variant's established ISOGG/YBrowse name as its canonical name
/// ("named by definition") instead of minting a new DU identifier.
async fn adopt(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let notice = match du_db::naming::adopt_established_name(&st.pool, id).await {
        Ok(name) => format!("{} {name}", locale.t.get("nm.notice.adopted")),
        Err(du_db::DbError::Conflict(m)) => m,
        Err(e) => return Err(e.into()),
    };
    changed_response(&st, locale.t, id, Some(notice)).await
}

#[derive(Deserialize)]
struct StatusForm {
    /// PENDING_REVIEW | UNNAMED
    status: String,
}

async fn status(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<StatusForm>,
) -> Result<Response, AppError> {
    let new_status = match f.status.as_str() {
        "PENDING_REVIEW" => "PENDING_REVIEW",
        _ => "UNNAMED",
    };
    du_db::naming::set_status(&st.pool, id, new_status).await?;
    changed_response(&st, locale.t, id, None).await
}
