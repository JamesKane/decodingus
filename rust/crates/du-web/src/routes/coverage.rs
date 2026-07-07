//! Public per-chromosome "Testing Benchmarks": sequencing coverage aggregated
//! across the federated cohort (`fed.coverage_summary.metrics->'contigs'`) by
//! vendor / test type / reference build / chromosome, filterable in the UI.

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
    Router::new().route("/coverage-benchmarks", get(benchmarks))
}

fn fmt_depth(v: Option<f64>) -> String {
    v.map(|d| format!("{d:.1}×")).unwrap_or_else(|| "—".into())
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
        g.rows.sort_by_key(|a| contig_key(&a.contig));
    }
    groups
}

async fn benchmarks(
    State(st): State<AppState>,
    locale: Locale,
    user: crate::auth::MaybeUser,
    Query(q): Query<BenchQuery>,
) -> Result<Response, AppError> {
    let options = du_db::coverage::contig_benchmark_options(&st.pool).await?;
    let mut contigs = options.contigs.clone();
    contigs.sort_by_key(|a| contig_key(a));

    // A first, param-less visit lands on a small, relevant slice (chrY on the CHM13
    // build — the Y-tree's reference) rather than every chromosome × lab × build. Once
    // the user touches any filter, the form submits all four keys, so we honor exactly
    // what's given (including empty = "All"). A missing key ⇒ untouched ⇒ default.
    let untouched = q.vendor.is_none() && q.test_type.is_none() && q.build.is_none() && q.contig.is_none();
    let (vendor, test_type, build, contig) = if untouched {
        let contig = options.contigs.iter().find(|c| contig_key(c) == contig_key("chrY")).cloned();
        let build = options.builds.iter().find(|b| b.to_ascii_lowercase().contains("chm13")).cloned();
        (None, None, build, contig)
    } else {
        (nz(q.vendor), nz(q.test_type), nz(q.build), nz(q.contig))
    };

    let filter = du_db::coverage::ContigBenchmarkFilter {
        vendor: vendor.clone(),
        test_type: test_type.clone(),
        build: build.clone(),
        contig: contig.clone(),
    };
    let groups = group_benchmarks(du_db::coverage::contig_benchmarks(&st.pool, &filter).await?);

    Ok(html(&CoverageTemplate {
        t: locale.t,
        next: locale.next,
        user: user.nav(),
        groups,
        vendors: opts(options.vendors, &vendor.unwrap_or_default()),
        test_types: opts(options.test_types, &test_type.unwrap_or_default()),
        builds: opts(options.builds, &build.unwrap_or_default()),
        contigs: opts(contigs, &contig.unwrap_or_default()),
    }))
}

#[cfg(test)]
mod tests {
    use super::contig_key;

    #[test]
    fn contigs_sort_in_karyotype_order() {
        let mut got = vec![
            "chr1", "chr2", "chr9", "chrM", "chrX", "chrY", "chr10", "chr22", "chr21",
        ];
        got.sort_by_key(|a| contig_key(a));
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
