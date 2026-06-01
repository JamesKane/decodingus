//! Coverage benchmark reporting type — observed sequencing coverage aggregated
//! by lab and test type, compared against the test type's expected depth.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CoverageBenchmark {
    pub lab: Option<String>,
    pub test_type: Option<String>,
    pub library_count: i64,
    /// Mean of per-file mean depth (from `alignment_metadata.coverage->>'meanDepth'`).
    pub avg_mean_depth: Option<f64>,
    /// Mean of per-file `percent_coverage_at_10x`.
    pub avg_cov_10x: Option<f64>,
    /// The test type's configured expected minimum depth, for comparison.
    pub expected_min_depth: Option<f64>,
}
