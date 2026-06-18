//! Test-type taxonomy reads (`genomics.test_type_definition`) joined to the
//! empirical coverage norm (`genomics.test_type_coverage_norm`, see
//! [`crate::coverage`]). Read-only reference data for the Edge/Navigator: the
//! advertised capabilities + the cohort's typical coverage for each test type.

use crate::DbError;
use sqlx::PgPool;

/// A test type's definition plus its empirical coverage norm (when computed).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct TestTypeInfo {
    pub code: String,
    pub display_name: String,
    pub category: String,
    pub vendor: Option<String>,
    pub target_type: Option<String>,
    /// Advertised minimum depth (curator/ETL-set).
    pub expected_min_depth: Option<f64>,
    pub supports_haplogroup_y: bool,
    pub supports_haplogroup_mt: bool,
    pub supports_autosomal_ibd: bool,
    pub supports_ancestry: bool,
    pub typical_file_formats: Vec<String>,
    /// Empirical norm (federated cohort): samples observed + typical depth/coverage.
    pub norm_sample_count: Option<i32>,
    pub norm_median_depth: Option<f64>,
    pub norm_median_pct_30x: Option<f64>,
}

const SELECT: &str = "SELECT ttd.code, ttd.display_name, ttd.category::text AS category, ttd.vendor, \
    ttd.target_type::text AS target_type, ttd.expected_min_depth, ttd.supports_haplogroup_y, \
    ttd.supports_haplogroup_mt, ttd.supports_autosomal_ibd, ttd.supports_ancestry, ttd.typical_file_formats, \
    n.sample_count AS norm_sample_count, n.median_mean_depth AS norm_median_depth, \
    n.median_pct_30x AS norm_median_pct_30x \
    FROM genomics.test_type_definition ttd \
    LEFT JOIN genomics.test_type_coverage_norm n ON n.test_type = ttd.code";

/// All test types, ordered by code.
pub async fn list(pool: &PgPool) -> Result<Vec<TestTypeInfo>, DbError> {
    Ok(sqlx::query_as(&format!("{SELECT} ORDER BY ttd.code")).fetch_all(pool).await?)
}

/// One test type by its code.
pub async fn get(pool: &PgPool, code: &str) -> Result<Option<TestTypeInfo>, DbError> {
    Ok(sqlx::query_as(&format!("{SELECT} WHERE ttd.code = $1"))
        .bind(code)
        .fetch_optional(pool)
        .await?)
}
