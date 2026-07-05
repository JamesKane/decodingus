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

/// One `<option>` in a filter dropdown, with `selected` precomputed (the template
/// does no string comparison — askama can't compare `&String == String`).
struct FilterOpt {
    value: String,
    selected: bool,
}

/// Turn distinct option values into `FilterOpt`s, marking the current selection.
fn opts(values: Vec<String>, selected: &str) -> Vec<FilterOpt> {
    values
        .into_iter()
        .map(|value| {
            let selected = value == selected;
            FilterOpt { value, selected }
        })
        .collect()
}

#[derive(askama::Template)]
#[template(path = "coverage/benchmarks.html")]
struct CoverageTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    groups: Vec<BenchGroup>,
    // Filter dropdown options, each flagged if currently selected ("" = all).
    vendors: Vec<FilterOpt>,
    test_types: Vec<FilterOpt>,
    builds: Vec<FilterOpt>,
    contigs: Vec<FilterOpt>,
}

/// Filter selections from the query string. Empty strings (the "All" option) are
/// normalized to `None`.
#[derive(Deserialize)]
struct BenchQuery {
    vendor: Option<String>,
    test_type: Option<String>,
    build: Option<String>,
    contig: Option<String>,
}

/// `Some(non-empty)` → itself; `Some("")` / `None` → `None`.
fn nz(v: Option<String>) -> Option<String> {
    v.filter(|s| !s.trim().is_empty())
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

/// Karyotype sort key for a contig label: autosomes `1..22` numerically, then
/// `X`, `Y`, `M`/`MT`, then anything else alphabetically. `chr` prefix optional and
/// case-insensitive (e.g. `chr1`, `CHR12`, `Y`).
fn contig_key(contig: &str) -> (u8, u32, String) {
    let lc = contig.to_ascii_lowercase();
    let bare = lc.strip_prefix("chr").unwrap_or(&lc);
    if let Ok(n) = bare.parse::<u32>() {
        return (0, n, String::new());
    }
    match bare {
        "x" => (1, 0, String::new()),
        "y" => (2, 0, String::new()),
        "m" | "mt" => (3, 0, String::new()),
        other => (4, 0, other.to_string()),
    }
}

/// Fold the flat per-contig rows (ordered vendor→test→build by SQL) into
/// vendor/test/build sections, then karyotype-sort the contigs within each section.
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
    for g in &mut groups {
        g.rows.sort_by(|a, b| contig_key(&a.contig).cmp(&contig_key(&b.contig)));
    }
    groups
}

async fn benchmarks(
    State(st): State<AppState>,
    locale: Locale,
    user: crate::auth::MaybeUser,
    Query(q): Query<BenchQuery>,
) -> Result<Response, AppError> {
    let filter = du_db::coverage::ContigBenchmarkFilter {
        vendor: nz(q.vendor),
        test_type: nz(q.test_type),
        build: nz(q.build),
        contig: nz(q.contig),
    };
    let groups = group_benchmarks(du_db::coverage::contig_benchmarks(&st.pool, &filter).await?);

    let options = du_db::coverage::contig_benchmark_options(&st.pool).await?;
    let mut contigs = options.contigs;
    contigs.sort_by(|a, b| contig_key(a).cmp(&contig_key(b)));

    let sel_vendor = filter.vendor.unwrap_or_default();
    let sel_test_type = filter.test_type.unwrap_or_default();
    let sel_build = filter.build.unwrap_or_default();
    let sel_contig = filter.contig.unwrap_or_default();

    Ok(html(&CoverageTemplate {
        t: locale.t,
        next: locale.next,
        user: user.nav(),
        groups,
        vendors: opts(options.vendors, &sel_vendor),
        test_types: opts(options.test_types, &sel_test_type),
        builds: opts(options.builds, &sel_build),
        contigs: opts(contigs, &sel_contig),
    }))
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

#[cfg(test)]
mod tests {
    use super::contig_key;

    #[test]
    fn contigs_sort_in_karyotype_order() {
        let mut got = vec![
            "chr1", "chr2", "chr9", "chrM", "chrX", "chrY", "chr10", "chr22", "chr21",
        ];
        got.sort_by(|a, b| contig_key(a).cmp(&contig_key(b)));
        assert_eq!(
            got,
            vec!["chr1", "chr2", "chr9", "chr10", "chr21", "chr22", "chrX", "chrY", "chrM"]
        );
    }

    #[test]
    fn contig_key_tolerates_prefix_and_case() {
        assert_eq!(contig_key("chr7"), contig_key("CHR7"));
        assert_eq!(contig_key("Y"), contig_key("chrY"));
        assert_eq!(contig_key("MT"), contig_key("chrM")); // MT and M coalesce
    }
}
