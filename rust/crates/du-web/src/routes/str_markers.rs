//! Public Y-STR marker report: the observed range (min / modal / max) of every
//! marker across the whole corpus, with its repeat motif and mutation rate where
//! we hold them.
//!
//! This is the horizontal view of the STR data. `du_db::ystr` otherwise only ever
//! reads it per-haplogroup (modal signatures, ASR, branch ages), so there was no
//! way to ask what the corpus says about a marker. The shape mirrors the MIN/MAX/
//! MODE summary a vendor project page publishes, over a larger cohort.
//!
//! Two columns exist to keep the numbers honest rather than merely tidy:
//! null alleles (a reported `0`, which is a deleted locus, not a zero repeat
//! count) are excluded from the range and counted separately, and each marker
//! states how it enters the age model — most markers have no published rate and
//! are scored at `du_db::ystr::DEFAULT_STR_RATE`.

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
    Router::new().route("/str-markers", get(page))
}

/// How a marker enters the STR age model, as a rendered badge. Kept as a small
/// enum-ish trio of flags because askama can't compare strings in the template.
struct AgeStatus {
    label: String,
    /// Bootstrap contextual class for the badge.
    css: &'static str,
}

/// One marker's row, fully pre-formatted — the template does no formatting or
/// comparison (this crate's convention: display logic lives in the route module).
struct MarkerRow {
    marker: String,
    multi_copy: bool,
    motif: String,
    period: String,
    /// Min / modal / max, already rendered: repeat counts for simple markers,
    /// copy vectors (e.g. `11-15`) for multi-copy ones.
    min: String,
    modal: String,
    max: String,
    observations: String,
    samples: String,
    distinct_values: String,
    /// Compact caveat cell, e.g. `2 null · 1 partial`; empty when clean.
    notes: String,
    rate: String,
    rate_ci: String,
    rate_source: String,
    age_status: AgeStatus,
}

/// Corpus totals for the summary line above the table.
struct Totals {
    markers: String,
    observations: String,
    with_motif: String,
    with_rate: String,
}

#[derive(Deserialize)]
struct MarkerQuery {
    /// Case-insensitive substring filter on the marker name.
    q: Option<String>,
    /// `observations` (default), `name`, `motif`, or `range`.
    sort: Option<String>,
}

#[derive(askama::Template)]
#[template(path = "str/markers.html")]
struct MarkersTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    rows: Vec<MarkerRow>,
    totals: Totals,
    q: String,
    sort: String,
    /// The rate used where a marker has none, shown in the legend.
    default_rate: String,
    /// When the precomputed statistics were last refreshed; empty if never.
    refreshed_at: String,
}

/// Integer with thousands separators (e.g. `12,345`).
fn fmt_count(n: i64) -> String {
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

/// A mutation rate in the scale it is actually read at — per generation, and small
/// enough that plain decimal notation is unreadable below ~1e-4.
fn fmt_rate(v: Option<f64>) -> String {
    match v {
        Some(r) if r >= 0.001 => format!("{r:.5}"),
        Some(r) if r > 0.0 => format!("{r:.2e}"),
        _ => "—".into(),
    }
}

/// Pick the rendered value for one end of the range: simple markers carry a
/// repeat count, multi-copy markers a copy vector. Exactly one is ever set.
fn range_cell(value: Option<i32>, combination: &Option<String>) -> String {
    match (value, combination) {
        (Some(v), _) => v.to_string(),
        (None, Some(c)) => c.clone(),
        _ => "—".into(),
    }
}

/// Null-allele and partial-repeat counts folded into one cell, so two columns
/// that are empty for nearly every marker don't widen the table.
fn notes(null_alleles: i64, complex: i64, t: &T) -> String {
    let mut parts = Vec::new();
    if null_alleles > 0 {
        parts.push(format!("{null_alleles} {}", t.get("str.markers.note.null")));
    }
    if complex > 0 {
        parts.push(format!("{complex} {}", t.get("str.markers.note.partial")));
    }
    parts.join(" · ")
}

fn age_status(status: &str, t: &T) -> AgeStatus {
    match status {
        "MEASURED_RATE" => {
            AgeStatus { label: t.get("str.markers.status.measured").to_string(), css: "text-bg-success" }
        }
        "EXCLUDED" => {
            AgeStatus { label: t.get("str.markers.status.excluded").to_string(), css: "text-bg-secondary" }
        }
        _ => AgeStatus { label: t.get("str.markers.status.default").to_string(), css: "text-bg-warning" },
    }
}

fn to_row(m: du_db::ystr::MarkerStat, t: &T) -> MarkerRow {
    let ci = match (m.rate_ci_low, m.rate_ci_high) {
        (Some(lo), Some(hi)) => format!("{}–{}", fmt_rate(Some(lo)), fmt_rate(Some(hi))),
        _ => "—".into(),
    };
    MarkerRow {
        marker: m.marker_name,
        multi_copy: m.multi_copy,
        motif: m.motif.unwrap_or_else(|| "—".into()),
        period: m.period.map(|p| p.to_string()).unwrap_or_else(|| "—".into()),
        min: range_cell(m.min_value, &m.min_combination),
        modal: range_cell(m.modal_value, &m.modal_combination),
        max: range_cell(m.max_value, &m.max_combination),
        observations: fmt_count(m.observations),
        samples: fmt_count(m.samples),
        distinct_values: fmt_count(m.distinct_values),
        notes: notes(m.null_alleles, m.complex_count, t),
        rate: fmt_rate(m.mutation_rate),
        rate_ci: ci,
        rate_source: m.rate_source.unwrap_or_default(),
        age_status: age_status(&m.age_model_status, t),
    }
}

/// Sort key for a marker name: `DYS<number>` numerically (so DYS19 precedes
/// DYS385 rather than following DYS1xx alphabetically), everything else after it
/// by name.
fn name_key(marker: &str) -> (u8, u32, String) {
    if let Some(rest) = marker.strip_prefix("DYS") {
        let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        if !digits.is_empty() {
            if let Ok(n) = digits.parse::<u32>() {
                // Suffixed variants (DYS389I/II) sort after the bare number.
                return (0, n, rest[digits.len()..].to_string());
            }
        }
    }
    (1, 0, marker.to_string())
}

async fn page(
    State(st): State<AppState>,
    locale: Locale,
    user: crate::auth::MaybeUser,
    Query(query): Query<MarkerQuery>,
) -> Result<Response, AppError> {
    let all = du_db::ystr::marker_stats(&st.pool).await?;
    // These figures are precomputed (du-jobs run-once str-marker-stats), so the
    // page states its own age rather than implying it is live.
    let refreshed_at = du_db::ystr::marker_stats_refreshed_at(&st.pool)
        .await?
        .map(|t| t.format("%Y-%m-%d %H:%M UTC").to_string())
        .unwrap_or_default();

    // Totals describe the whole corpus, not the filtered view — they are the
    // reference-coverage headline (how much of what we observe we can explain).
    let totals = Totals {
        markers: fmt_count(all.len() as i64),
        observations: fmt_count(all.iter().map(|m| m.observations).sum()),
        with_motif: fmt_count(all.iter().filter(|m| m.motif.is_some()).count() as i64),
        with_rate: fmt_count(
            all.iter().filter(|m| m.age_model_status == "MEASURED_RATE").count() as i64
        ),
    };

    let q = query.q.unwrap_or_default();
    let needle = q.trim().to_ascii_lowercase();
    let mut rows: Vec<du_db::ystr::MarkerStat> = all
        .into_iter()
        .filter(|m| needle.is_empty() || m.marker_name.to_ascii_lowercase().contains(&needle))
        .collect();

    // SQL already returns observations-descending; re-sort only for other keys.
    let sort = query.sort.unwrap_or_default();
    match sort.as_str() {
        "name" => rows.sort_by_key(|m| name_key(&m.marker_name)),
        // Motif-less markers last, so the sort surfaces what we can explain.
        "motif" => {
            rows.sort_by_key(|m| (m.motif.is_none(), m.period, name_key(&m.marker_name)))
        }
        // Widest observed spread first — the most variable markers in the corpus.
        "range" => rows.sort_by_key(|m| std::cmp::Reverse(m.distinct_values)),
        _ => {}
    }

    let t = &locale.t;
    let rows = rows.into_iter().map(|m| to_row(m, t)).collect();

    Ok(html(&MarkersTemplate {
        t: locale.t,
        next: locale.next,
        user: user.nav(),
        rows,
        totals,
        q,
        sort,
        default_rate: fmt_rate(Some(du_db::ystr::DEFAULT_STR_RATE)),
        refreshed_at,
    }))
}

#[cfg(test)]
mod tests {
    use super::{fmt_count, fmt_rate, name_key, range_cell};

    #[test]
    fn markers_sort_numerically_within_dys() {
        let mut got = vec!["DYS448", "DYS19", "CDY", "DYS389II", "DYS389I", "DYS385", "Y-GATA-H4"];
        got.sort_by_key(|m| name_key(m));
        // DYS19 before DYS385 (numeric, not lexical); non-DYS names after.
        assert_eq!(
            got,
            vec!["DYS19", "DYS385", "DYS389I", "DYS389II", "DYS448", "CDY", "Y-GATA-H4"]
        );
    }

    #[test]
    fn range_cell_picks_whichever_shape_the_marker_has() {
        assert_eq!(range_cell(Some(13), &None), "13");
        assert_eq!(range_cell(None, &Some("11-15".to_string())), "11-15");
        assert_eq!(range_cell(None, &None), "—");
    }

    #[test]
    fn rates_stay_readable_at_both_scales() {
        assert_eq!(fmt_rate(Some(0.00278)), "0.00278");
        assert_eq!(fmt_rate(Some(9.74635e-05)), "9.75e-5");
        assert_eq!(fmt_rate(None), "—");
    }

    #[test]
    fn counts_get_thousands_separators() {
        assert_eq!(fmt_count(875), "875");
        assert_eq!(fmt_count(508227), "508,227");
    }
}
