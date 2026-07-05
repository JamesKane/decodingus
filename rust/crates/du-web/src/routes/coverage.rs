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
    test_type: String,
    libraries: i64,
    mean_depth: String,
    cov_10x: String,
    expected: String,
    /// Observed mean depth meets the test type's expected minimum.
    meets: bool,
}

fn fmt_depth(v: Option<f64>) -> String {
    v.map(|d| format!("{d:.1}×")).unwrap_or_else(|| "—".into())
}
fn fmt_pct(v: Option<f64>) -> String {
    v.map(|d| format!("{d:.1}%")).unwrap_or_else(|| "—".into())
}

fn to_bench_row(b: du_domain::coverage::CoverageBenchmark) -> BenchRow {
    BenchRow {
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

// ── per-chromosome "Testing Benchmarks" (federated cohort) ───────────────────────

/// One metric's average + coefficient of variation, pre-formatted for the table.
struct Cell {
    avg: String,
    cv: String,
}

/// One contig's row within a vendor/test-type/build section.
struct ContigRow {
    contig: String,
    samples: i64,
    callable: Cell,
    depth: Cell,
    poor: Cell,
    total: Cell,
    years_per_snp: String,
}

/// A vendor × test-type × reference-build section, its contigs beneath it — the
/// YDNA-Warehouse layout generalized to every chromosome.
struct BenchGroup {
    vendor: String,
    test_type: String,
    build: String,
    rows: Vec<ContigRow>,
}

#[derive(askama::Template)]
#[template(path = "coverage/benchmarks.html")]
struct CoverageTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    groups: Vec<BenchGroup>,
}

/// Integer with thousands separators (e.g. `12,345,678`); `—` for `None`.
fn fmt_loci(v: Option<f64>) -> String {
    let Some(v) = v else { return "—".into() };
    let n = v.round() as i64;
    let s = n.abs().to_string();
    let mut out = String::new();
    for (i, ch) in s.chars().enumerate() {
        if i > 0 && (s.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(ch);
    }
    if n < 0 {
        format!("-{out}")
    } else {
        out
    }
}

/// Coefficient of variation as a percentage (`12.3%`); `—` for `None`.
fn fmt_cv(v: Option<f64>) -> String {
    v.map(|c| format!("{:.1}%", c * 100.0)).unwrap_or_else(|| "—".into())
}

fn to_contig_row(b: &du_domain::coverage::ContigBenchmark) -> ContigRow {
    ContigRow {
        contig: b.contig.clone(),
        samples: b.samples,
        callable: Cell { avg: fmt_loci(b.callable_avg), cv: fmt_cv(b.callable_cv) },
        depth: Cell { avg: fmt_depth(b.depth_avg), cv: fmt_cv(b.depth_cv) },
        poor: Cell { avg: fmt_loci(b.poor_avg), cv: fmt_cv(b.poor_cv) },
        total: Cell { avg: fmt_loci(b.total_avg), cv: fmt_cv(b.total_cv) },
        years_per_snp: b
            .est_years_per_snp
            .map(|y| format!("{} yr", y.round() as i64))
            .unwrap_or_else(|| "—".into()),
    }
}

/// Fold the flat per-contig rows (already ordered vendor→test→build→contig by SQL)
/// into vendor/test/build sections, preserving order.
fn group_benchmarks(rows: Vec<du_domain::coverage::ContigBenchmark>) -> Vec<BenchGroup> {
    let mut groups: Vec<BenchGroup> = Vec::new();
    for b in &rows {
        let vendor = b.vendor.clone().unwrap_or_else(|| "—".into());
        let test_type = b.test_type.clone().unwrap_or_else(|| "—".into());
        let build = b.reference_build.clone().unwrap_or_else(|| "—".into());
        let same = groups
            .last()
            .is_some_and(|g| g.vendor == vendor && g.test_type == test_type && g.build == build);
        if !same {
            groups.push(BenchGroup { vendor, test_type, build, rows: Vec::new() });
        }
        groups.last_mut().unwrap().rows.push(to_contig_row(b));
    }
    groups
}

async fn benchmarks(
    State(st): State<AppState>,
    locale: Locale,
    user: crate::auth::MaybeUser,
) -> Result<Response, AppError> {
    let groups = group_benchmarks(du_db::coverage::contig_benchmarks(&st.pool).await?);
    Ok(html(&CoverageTemplate { t: locale.t, next: locale.next, user: user.nav(), groups }))
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
