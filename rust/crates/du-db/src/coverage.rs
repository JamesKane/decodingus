//! Coverage benchmark aggregation over `genomics.alignment_metadata.coverage`
//! (JSONB), grouped by sequencing lab and test type. The `meanDepth` expression
//! index from migration 0004 accelerates the JSONB extraction.

use crate::DbError;
use du_domain::coverage::CoverageBenchmark;
use sqlx::PgPool;

pub async fn benchmarks(pool: &PgPool) -> Result<Vec<CoverageBenchmark>, DbError> {
    #[derive(sqlx::FromRow)]
    struct Row {
        lab: Option<String>,
        test_type: Option<String>,
        library_count: i64,
        avg_mean_depth: Option<f64>,
        avg_cov_10x: Option<f64>,
        expected_min_depth: Option<f64>,
    }

    let rows: Vec<Row> = sqlx::query_as(
        "SELECT l.name AS lab, \
                ttd.display_name AS test_type, \
                count(DISTINCT sl.id) AS library_count, \
                avg((am.coverage->>'meanDepth')::double precision) AS avg_mean_depth, \
                avg((am.coverage->>'percent_coverage_at_10x')::double precision) AS avg_cov_10x, \
                ttd.expected_min_depth AS expected_min_depth \
         FROM genomics.alignment_metadata am \
         JOIN genomics.sequence_file sf ON sf.id = am.sequence_file_id \
         JOIN genomics.sequence_library sl ON sl.id = sf.library_id \
         LEFT JOIN genomics.sequencing_lab l ON l.id = sl.lab_id \
         LEFT JOIN genomics.test_type_definition ttd ON ttd.id = sl.test_type_id \
         WHERE am.metric_level = 'CONTIG_OVERALL' \
         GROUP BY l.name, ttd.display_name, ttd.expected_min_depth \
         ORDER BY l.name NULLS LAST, ttd.display_name NULLS LAST",
    )
    .fetch_all(pool)
    .await?;

    Ok(rows
        .into_iter()
        .map(|r| CoverageBenchmark {
            lab: r.lab,
            test_type: r.test_type,
            library_count: r.library_count,
            avg_mean_depth: r.avg_mean_depth,
            avg_cov_10x: r.avg_cov_10x,
            expected_min_depth: r.expected_min_depth,
        })
        .collect())
}
