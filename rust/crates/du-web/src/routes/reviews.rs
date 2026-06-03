//! Curator **merge-review** UI — the two-panel HTMX screen over the `tree.wip_*`
//! staging tables. Left: the worklist of staged items the merge/graft couldn't
//! place confidently (SNP-graft Phase-4 flags + name-collisions + graft-blocked).
//! Right: one item's full context (SNP scatter, anchor strength, source parent)
//! and the resolution form — Accept anchor / Reparent / Merge-into-existing /
//! Defer. Decisions write `wip_resolution`; the change-set apply engine enacts
//! them (so this mirrors the change-sets screen and reuses the tested apply).

use crate::auth::{Curator, NavUser};
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use du_domain::enums::DnaType;
use du_domain::ids::HaplogroupId;
use axum::extract::{Path, Query, State};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;

const CHANGED: &str = "review-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/reviews", get(page))
        .route("/curator/reviews/fragment", get(list))
        .route("/curator/reviews/:wip_id/panel", get(panel))
        .route("/curator/reviews/:wip_id/resolve", post(resolve))
        .route("/curator/reviews/:wip_id/apply", post(apply))
}

fn parse_dna(s: Option<&str>) -> DnaType {
    match s {
        Some("MT_DNA") => DnaType::MtDna,
        _ => DnaType::YDna,
    }
}

// ── list ──────────────────────────────────────────────────────────────────────

struct Row {
    wip_id: i64,
    source: String,
    name: String,
    category: String,
    best_anchor: String,
    resolution: String,
}

struct ListView {
    status: String,
    category: String,
    rows: Vec<Row>,
    page: i64,
    total: i64,
    total_pages: i64,
}

#[derive(Deserialize)]
struct ListQuery {
    status: Option<String>,
    category: Option<String>,
    page: Option<i64>,
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    let status = q.status.as_deref().filter(|s| !s.is_empty());
    let category = q.category.as_deref().filter(|s| !s.is_empty());
    let result = du_db::wip::list(&st.pool, status, category, None, q.page.unwrap_or(1), 20).await?;
    let (page, total, total_pages) = (result.page, result.total, result.total_pages());
    let rows = result
        .items
        .into_iter()
        .map(|r| Row {
            wip_id: r.wip_id,
            source: r.source,
            name: r.name,
            category: r.category.unwrap_or_else(|| "—".into()),
            best_anchor: r.best_anchor.unwrap_or_else(|| "—".into()),
            resolution: r.resolution_type.unwrap_or_default(),
        })
        .collect();
    Ok(ListView {
        status: q.status.clone().unwrap_or_default(),
        category: q.category.clone().unwrap_or_default(),
        rows,
        page,
        total,
        total_pages,
    })
}

#[derive(askama::Template)]
#[template(path = "curator/reviews/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/reviews/list.html")]
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

// ── detail / resolution panel ───────────────────────────────────────────────

struct Candidate {
    node: String,
    hits: i64,
}

struct DetailView {
    wip_id: i64,
    change_set_id: i64,
    cs_status: String,
    source: String,
    name: String,
    category: String,
    reason: String,
    best_anchor: Option<String>,
    anchor_strength_pct: i64,
    candidates: Vec<Candidate>,
    defining_snp_count: i64,
    snps_known: i64,
    source_parent: Option<String>,
    source_parent_status: Option<String>,
    is_backbone: bool,
    tentative_parent: Option<String>,
    preview_children: Vec<String>,
    resolution_type: Option<String>,
    resolution_target: Option<String>,
    resolution_notes: Option<String>,
    open_count: i64,
    resolved_count: i64,
    deferred_count: i64,
    can_apply: bool,
    notice: Option<String>,
}

#[derive(askama::Template)]
#[template(path = "curator/reviews/detail.html")]
struct DetailTemplate {
    t: T,
    item: DetailView,
}

fn jstr(v: &serde_json::Value, k: &str) -> Option<String> {
    v.get(k).and_then(|x| x.as_str()).map(str::to_string)
}
fn jint(v: &serde_json::Value, k: &str) -> i64 {
    v.get(k).and_then(|x| x.as_i64()).unwrap_or(0)
}

async fn build_detail(st: &AppState, wip_id: i64, notice: Option<String>) -> Result<DetailView, AppError> {
    let d = du_db::wip::get(&st.pool, wip_id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("review item {wip_id}")))?;
    let r = &d.review;
    let candidates = r
        .get("candidates")
        .and_then(|c| c.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|c| Some(Candidate { node: jstr(c, "node")?, hits: jint(c, "hits") }))
                .collect()
        })
        .unwrap_or_default();
    let strength = r.get("anchor_strength").and_then(|x| x.as_f64()).unwrap_or(0.0);

    // Preview: where it would land — the tentative parent's current children.
    let mut preview_children = Vec::new();
    if let Some(pid) = d.tentative_parent_id {
        let kids = du_db::haplogroup::children(&st.pool, HaplogroupId(pid)).await?;
        preview_children = kids.into_iter().take(10).map(|h| h.name).collect();
    }

    let (open_count, resolved_count, deferred_count) = du_db::wip::counts(&st.pool, d.change_set_id).await?;
    let can_apply = resolved_count > 0 && matches!(d.cs_status.as_str(), "DRAFT" | "READY_FOR_REVIEW" | "UNDER_REVIEW");

    let resolution_target = d
        .resolution
        .as_ref()
        .and_then(|res| res.new_parent_name.clone().or_else(|| res.merge_target_name.clone()));

    Ok(DetailView {
        wip_id: d.wip_id,
        change_set_id: d.change_set_id,
        cs_status: d.cs_status,
        source: d.source,
        name: d.name,
        category: jstr(r, "category").unwrap_or_else(|| "—".into()),
        reason: jstr(r, "reason").unwrap_or_default(),
        best_anchor: jstr(r, "best_anchor"),
        anchor_strength_pct: (strength * 100.0).round() as i64,
        candidates,
        defining_snp_count: jint(r, "defining_snp_count"),
        snps_known: jint(r, "snps_known_to_foundation"),
        source_parent: jstr(r, "source_parent"),
        source_parent_status: jstr(r, "source_parent_status"),
        is_backbone: r.get("is_backbone").and_then(|x| x.as_bool()).unwrap_or(false),
        tentative_parent: d.tentative_parent_name,
        preview_children,
        resolution_type: d.resolution.as_ref().map(|res| res.resolution_type.clone()),
        resolution_target,
        resolution_notes: d.resolution.as_ref().and_then(|res| res.notes.clone()),
        open_count,
        resolved_count,
        deferred_count,
        can_apply,
        notice,
    })
}

async fn detail_response(st: &AppState, t: T, wip_id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let item = build_detail(st, wip_id, notice).await?;
    Ok(html(&DetailTemplate { t, item }))
}

async fn changed_response(st: &AppState, t: T, wip_id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let body = detail_response(st, t, wip_id, notice).await?;
    Ok((HxHeaders::new().trigger(CHANGED), body).into_response())
}

async fn panel(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(wip_id): Path<i64>,
) -> Result<Response, AppError> {
    detail_response(&st, locale.t, wip_id, None).await
}

// ── actions ─────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct ResolveForm {
    /// REPARENT | MERGE_EXISTING | DEFER
    action: String,
    /// Target node name (parent for REPARENT, merge target for MERGE_EXISTING).
    target: Option<String>,
    notes: Option<String>,
}

async fn resolve(
    cur: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(wip_id): Path<i64>,
    Form(f): Form<ResolveForm>,
) -> Result<Response, AppError> {
    let d = du_db::wip::get(&st.pool, wip_id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("review item {wip_id}")))?;
    let dna = parse_dna(d.cs_dna.as_deref());
    let notes = f.notes.as_deref().map(str::trim).filter(|s| !s.is_empty());

    // Resolve the (optional) target node name → id.
    let target_name = f.target.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let target_id = match target_name {
        Some(n) => match du_db::haplogroup::get_by_name(&st.pool, n, dna).await? {
            Some(h) => Some(h.id.0),
            None => {
                let notice = format!("{}: {n}", locale.t.get("rev.notice.unknown_node"));
                return changed_response(&st, locale.t, wip_id, Some(notice)).await;
            }
        },
        None => None,
    };

    let (kind, new_parent_id, merge_target_id) = match f.action.as_str() {
        "REPARENT" => {
            if target_id.is_none() {
                let notice = locale.t.get("rev.notice.need_parent").to_string();
                return changed_response(&st, locale.t, wip_id, Some(notice)).await;
            }
            ("REPARENT", target_id, None)
        }
        "MERGE_EXISTING" => {
            if target_id.is_none() {
                let notice = locale.t.get("rev.notice.need_target").to_string();
                return changed_response(&st, locale.t, wip_id, Some(notice)).await;
            }
            ("MERGE_EXISTING", None, target_id)
        }
        _ => ("DEFER", None, None),
    };

    du_db::wip::resolve(&st.pool, wip_id, kind, new_parent_id, merge_target_id, notes, &cur.0.display_name).await?;
    let notice = Some(format!("{} {kind}", locale.t.get("rev.notice.resolved")));
    changed_response(&st, locale.t, wip_id, notice).await
}

async fn apply(
    cur: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(wip_id): Path<i64>,
) -> Result<Response, AppError> {
    let d = du_db::wip::get(&st.pool, wip_id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("review item {wip_id}")))?;
    // DRAFT/READY_FOR_REVIEW → UNDER_REVIEW so apply's status gate passes.
    du_db::change_set::start_review(&st.pool, d.change_set_id).await?;
    match du_db::change_set::apply(&st.pool, d.change_set_id, &cur.0.display_name).await {
        Ok(r) => {
            let notice = Some(format!(
                "{}: +{} ✎{} (skip {})",
                locale.t.get("rev.notice.applied"),
                r.created,
                r.variant_edits,
                r.skipped,
            ));
            changed_response(&st, locale.t, wip_id, notice).await
        }
        Err(du_db::DbError::Conflict(msg)) => changed_response(&st, locale.t, wip_id, Some(msg)).await,
        Err(e) => Err(e.into()),
    }
}
