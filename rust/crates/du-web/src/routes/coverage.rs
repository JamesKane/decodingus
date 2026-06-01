//! Public coverage benchmarks: observed sequencing coverage by lab and test
//! type, aggregated from the alignment-metadata coverage JSONB.

use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::State;
use axum::response::Response;
use axum::routing::get;
use axum::Router;

pub fn router() -> Router<AppState> {
    Router::new().route("/coverage-benchmarks", get(benchmarks))
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

async fn benchmarks(
    State(st): State<AppState>,
    locale: Locale,
    user: crate::auth::MaybeUser,
) -> Result<Response, AppError> {
    let rows = du_db::coverage::benchmarks(&st.pool)
        .await?
        .into_iter()
        .map(|b| BenchRow {
            lab: b.lab.unwrap_or_else(|| "—".into()),
            test_type: b.test_type.unwrap_or_else(|| "—".into()),
            libraries: b.library_count,
            mean_depth: fmt_depth(b.avg_mean_depth),
            cov_10x: fmt_pct(b.avg_cov_10x),
            expected: b
                .expected_min_depth
                .map(|d| format!("{d:.0}×"))
                .unwrap_or_else(|| "—".into()),
            meets: matches!(
                (b.avg_mean_depth, b.expected_min_depth),
                (Some(obs), Some(exp)) if obs >= exp
            ),
        })
        .collect();

    Ok(html(&CoverageTemplate { t: locale.t, next: locale.next, user: user.nav(), rows }))
}
