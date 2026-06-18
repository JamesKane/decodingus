//! Public coverage benchmarks: observed sequencing coverage by lab and test
//! type, aggregated from the alignment-metadata coverage JSONB.

use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Query, State};
use axum::response::Response;
use axum::routing::get;
use axum::Router;
use serde::Deserialize;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/coverage-benchmarks", get(benchmarks))
        .route("/coverage/labs", get(labs_page))
        .route("/coverage/labs/fragment", get(labs_fragment))
}

struct BenchRow {
    lab: String,
    test_type: String,
    libraries: i64,
    mean_depth: String,
    cov_10x: String,
    expected: String,
    /// Observed mean depth meets the test type's expected minimum.
    meets: bool,
}

#[derive(askama::Template)]
#[template(path = "coverage/benchmarks.html")]
struct CoverageTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    rows: Vec<BenchRow>,
}

fn fmt_depth(v: Option<f64>) -> String {
    v.map(|d| format!("{d:.1}×")).unwrap_or_else(|| "—".into())
}
fn fmt_pct(v: Option<f64>) -> String {
    v.map(|d| format!("{d:.1}%")).unwrap_or_else(|| "—".into())
}

fn to_bench_row(b: du_domain::coverage::CoverageBenchmark) -> BenchRow {
    BenchRow {
        lab: b.lab.unwrap_or_else(|| "—".into()),
        test_type: b.test_type.unwrap_or_else(|| "—".into()),
        libraries: b.library_count,
        mean_depth: fmt_depth(b.avg_mean_depth),
        cov_10x: fmt_pct(b.avg_cov_10x),
        expected: b.expected_min_depth.map(|d| format!("{d:.0}×")).unwrap_or_else(|| "—".into()),
        meets: matches!(
            (b.avg_mean_depth, b.expected_min_depth),
            (Some(obs), Some(exp)) if obs >= exp
        ),
    }
}

async fn benchmarks(
    State(st): State<AppState>,
    locale: Locale,
    user: crate::auth::MaybeUser,
) -> Result<Response, AppError> {
    let rows = du_db::coverage::benchmarks(&st.pool).await?.into_iter().map(to_bench_row).collect();
    Ok(html(&CoverageTemplate { t: locale.t, next: locale.next, user: user.nav(), rows }))
}

// ── per-lab drill-down ─────────────────────────────────────────────────────────

struct LabRow {
    lab: String,
    libraries: i64,
    test_types: usize,
}

#[derive(askama::Template)]
#[template(path = "coverage/labs.html")]
struct LabsTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    labs: Vec<LabRow>,
}

#[derive(askama::Template)]
#[template(path = "coverage/lab_rows.html")]
struct LabRowsTemplate {
    t: T,
    lab: String,
    rows: Vec<BenchRow>,
}

#[derive(Deserialize)]
struct LabQuery {
    lab: Option<String>,
}

/// Collapse the per-(lab, test-type) benchmarks into one row per lab.
fn lab_summaries(benches: &[du_domain::coverage::CoverageBenchmark]) -> Vec<LabRow> {
    use std::collections::BTreeMap;
    let mut by_lab: BTreeMap<String, (i64, usize)> = BTreeMap::new();
    for b in benches {
        let lab = b.lab.clone().unwrap_or_else(|| "—".into());
        let e = by_lab.entry(lab).or_default();
        e.0 += b.library_count;
        e.1 += 1;
    }
    by_lab.into_iter().map(|(lab, (libraries, test_types))| LabRow { lab, libraries, test_types }).collect()
}

async fn labs_page(
    State(st): State<AppState>,
    locale: Locale,
    user: crate::auth::MaybeUser,
) -> Result<Response, AppError> {
    let labs = lab_summaries(&du_db::coverage::benchmarks(&st.pool).await?);
    Ok(html(&LabsTemplate { t: locale.t, next: locale.next, user: user.nav(), labs }))
}

async fn labs_fragment(
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<LabQuery>,
) -> Result<Response, AppError> {
    let lab = q.lab.unwrap_or_default();
    let rows = du_db::coverage::benchmarks(&st.pool)
        .await?
        .into_iter()
        .filter(|b| b.lab.clone().unwrap_or_else(|| "—".into()) == lab)
        .map(to_bench_row)
        .collect();
    Ok(html(&LabRowsTemplate { t: locale.t, lab, rows }))
}
