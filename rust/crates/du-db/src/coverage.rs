//! Coverage benchmark aggregation over `genomics.alignment_metadata.coverage`
//! (JSONB), grouped by sequencing lab and test type. The `meanDepth` expression
//! index from migration 0004 accelerates the JSONB extraction.

use crate::DbError;
use du_domain::coverage::{ContigBenchmark, CoverageBenchmark};
use sqlx::PgPool;
use std::collections::HashMap;

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

// ── per-chromosome "Testing Benchmarks" (federated) ──────────────────────────────
//
// The YDNA-Warehouse-style benchmark, generalized to every contig and sourced from
// the FEDERATED coverage cohort (`fed.coverage_summary.metrics->'contigs'`) instead
// of the local `genomics.alignment_metadata` catalog — that catalog is empty on the
// AppView (no local alignments are ingested there). Each published alignment record
// carries a per-contig breakdown (`ContigMetrics`); we unnest it and aggregate per
// vendor / test type / reference build / contig, reporting each metric's average and
// coefficient of variation (CV = σ/µ) across the contributing samples.

/// MSY combined SNP mutation rate (SNPs/bp/year) — mirrors [`crate::age::SNP_RATE`],
/// used to turn a contig's average callable Y loci into an "Est Years/SNP" figure.
const SNP_RATE_PER_BP_YR: f64 = 8.33e-10;

/// Per-chromosome benchmarks across the federated coverage cohort. Rows with only one
/// contributing sample report `None` for every CV (a CV needs a spread). Ordered by
/// vendor, test type, build; contigs within a group are left in insertion order and
/// karyotype-sorted by the caller (SQL can't cheaply express `1..22, X, Y, M`).
pub async fn contig_benchmarks(pool: &PgPool) -> Result<Vec<ContigBenchmark>, DbError> {
    #[derive(sqlx::FromRow)]
    struct Row {
        vendor: Option<String>,
        test_type: Option<String>,
        reference_build: Option<String>,
        contig: String,
        samples: i64,
        callable_avg: Option<f64>,
        callable_sd: Option<f64>,
        depth_avg: Option<f64>,
        depth_sd: Option<f64>,
        poor_avg: Option<f64>,
        poor_sd: Option<f64>,
        total_avg: Option<f64>,
        total_sd: Option<f64>,
    }

    // `c` = one contig entry unnested from a record's metrics.contigs[]. Numeric fields
    // are wire strings (WireF64) or ints; cast defensively via ->> + ::double precision.
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT lab.name AS vendor, \
                sr.test_type AS test_type, \
                cs.reference_build AS reference_build, \
                c->>'contig' AS contig, \
                count(*) AS samples, \
                avg((c->>'callable')::double precision) AS callable_avg, \
                stddev_samp((c->>'callable')::double precision) AS callable_sd, \
                avg((c->>'meanDepth')::double precision) AS depth_avg, \
                stddev_samp((c->>'meanDepth')::double precision) AS depth_sd, \
                avg((c->>'poorMappingQuality')::double precision) AS poor_avg, \
                stddev_samp((c->>'poorMappingQuality')::double precision) AS poor_sd, \
                avg(((c->>'length')::double precision) - (c->>'refN')::double precision) AS total_avg, \
                stddev_samp(((c->>'length')::double precision) - (c->>'refN')::double precision) AS total_sd \
         FROM fed.coverage_summary cs \
         CROSS JOIN LATERAL jsonb_array_elements(cs.metrics->'contigs') AS c \
         LEFT JOIN fed.sequencerun sr ON sr.at_uri = cs.sequence_run_ref \
         LEFT JOIN genomics.sequencer_instrument si ON si.instrument_id = sr.instrument_id \
         LEFT JOIN genomics.sequencing_lab lab ON lab.id = si.lab_id \
         WHERE jsonb_typeof(cs.metrics->'contigs') = 'array' \
           AND c->>'contig' IS NOT NULL \
         GROUP BY lab.name, sr.test_type, cs.reference_build, c->>'contig' \
         ORDER BY lab.name NULLS LAST, sr.test_type NULLS LAST, cs.reference_build NULLS LAST",
    )
    .fetch_all(pool)
    .await?;

    // CV = σ/µ, only meaningful with a spread (n ≥ 2) and a non-zero mean.
    let cv = |avg: Option<f64>, sd: Option<f64>, n: i64| -> Option<f64> {
        match (avg, sd) {
            (Some(a), Some(s)) if n >= 2 && a != 0.0 => Some(s / a),
            _ => None,
        }
    };

    Ok(rows
        .into_iter()
        .map(|r| {
            let is_y = matches!(r.contig.as_str(), "chrY" | "Y" | "chrY_hs1");
            let est_years_per_snp = r
                .callable_avg
                .filter(|&b| is_y && b > 0.0)
                .map(|b| 1.0 / (b * SNP_RATE_PER_BP_YR));
            ContigBenchmark {
                vendor: r.vendor,
                test_type: r.test_type,
                reference_build: r.reference_build,
                contig: r.contig,
                samples: r.samples,
                callable_cv: cv(r.callable_avg, r.callable_sd, r.samples),
                callable_avg: r.callable_avg,
                depth_cv: cv(r.depth_avg, r.depth_sd, r.samples),
                depth_avg: r.depth_avg,
                poor_cv: cv(r.poor_avg, r.poor_sd, r.samples),
                poor_avg: r.poor_avg,
                total_cv: cv(r.total_avg, r.total_sd, r.samples),
                total_avg: r.total_avg,
                est_years_per_snp,
            }
        })
        .collect())
}

// ── empirical per-test-type coverage norms (D7) ──────────────────────────────────
//
// The cohort norm for each test type, DERIVED from the federated coverage already
// mirrored in `fed.coverage_summary` (joined to `fed.sequencerun.test_type`) plus
// `fed.genotype` marker counts — not hand-curated advertised numbers. Persisted to
// `genomics.test_type_coverage_norm` by a recompute job; read at report-render time
// to compare a sample's actual coverage against what its test type typically
// achieves. Mirrors the sequencer engine's advisory-lock + declarative-recompute
// discipline.

/// Advisory-lock key guarding concurrent norm recomputes.
const NORMS_ADVISORY_KEY: i64 = 0x434F_5645_524E; // "COVERN"

/// One test type's empirical coverage norm.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct CoverageNorm {
    pub test_type: String,
    pub sample_count: i32,
    pub median_mean_depth: Option<f64>,
    pub p25_mean_depth: Option<f64>,
    pub p75_mean_depth: Option<f64>,
    pub median_pct_10x: Option<f64>,
    pub median_pct_20x: Option<f64>,
    pub median_pct_30x: Option<f64>,
    pub typical_y_markers: Option<i32>,
    pub typical_mt_markers: Option<i32>,
}

/// Outcome of [`recompute_norms`].
#[derive(Debug, Default, Clone)]
pub struct NormReport {
    pub test_types: u64,
    pub pruned: u64,
}

/// Every persisted test-type coverage norm, ordered by test type.
pub async fn norms(pool: &PgPool) -> Result<Vec<CoverageNorm>, DbError> {
    Ok(sqlx::query_as(
        "SELECT test_type, sample_count, median_mean_depth, p25_mean_depth, p75_mean_depth, \
                median_pct_10x, median_pct_20x, median_pct_30x, typical_y_markers, typical_mt_markers \
         FROM genomics.test_type_coverage_norm ORDER BY test_type",
    )
    .fetch_all(pool)
    .await?)
}

/// The persisted norm for one test type (report-time conformance lookup).
pub async fn norm_for(pool: &PgPool, test_type: &str) -> Result<Option<CoverageNorm>, DbError> {
    Ok(sqlx::query_as(
        "SELECT test_type, sample_count, median_mean_depth, p25_mean_depth, p75_mean_depth, \
                median_pct_10x, median_pct_20x, median_pct_30x, typical_y_markers, typical_mt_markers \
         FROM genomics.test_type_coverage_norm WHERE test_type = $1",
    )
    .bind(test_type)
    .fetch_optional(pool)
    .await?)
}

/// Recompute the empirical norms from the federated cohort. Single-flighted by an
/// advisory lock (a second caller no-ops); unlocks on every path.
pub async fn recompute_norms(pool: &PgPool) -> Result<NormReport, DbError> {
    let mut lock = pool.acquire().await?;
    let locked: bool = sqlx::query_scalar("SELECT pg_try_advisory_lock($1)")
        .bind(NORMS_ADVISORY_KEY)
        .fetch_one(&mut *lock)
        .await?;
    if !locked {
        return Ok(NormReport::default());
    }
    let result = recompute_norms_locked(pool).await;
    let _ = sqlx::query("SELECT pg_advisory_unlock($1)")
        .bind(NORMS_ADVISORY_KEY)
        .execute(&mut *lock)
        .await;
    result
}

async fn recompute_norms_locked(pool: &PgPool) -> Result<NormReport, DbError> {
    // Depth/coverage norms from the federated alignment cohort, keyed by test type.
    #[derive(sqlx::FromRow)]
    struct CovAgg {
        test_type: String,
        sample_count: i32,
        median_mean_depth: Option<f64>,
        p25_mean_depth: Option<f64>,
        p75_mean_depth: Option<f64>,
        median_pct_10x: Option<f64>,
        median_pct_20x: Option<f64>,
        median_pct_30x: Option<f64>,
    }
    let cov: Vec<CovAgg> = sqlx::query_as(
        "SELECT sr.test_type AS test_type, count(*)::int AS sample_count, \
                percentile_cont(0.5)  WITHIN GROUP (ORDER BY cs.mean_coverage) AS median_mean_depth, \
                percentile_cont(0.25) WITHIN GROUP (ORDER BY cs.mean_coverage) AS p25_mean_depth, \
                percentile_cont(0.75) WITHIN GROUP (ORDER BY cs.mean_coverage) AS p75_mean_depth, \
                percentile_cont(0.5)  WITHIN GROUP (ORDER BY cs.pct_10x) AS median_pct_10x, \
                percentile_cont(0.5)  WITHIN GROUP (ORDER BY cs.pct_20x) AS median_pct_20x, \
                percentile_cont(0.5)  WITHIN GROUP (ORDER BY cs.pct_30x) AS median_pct_30x \
         FROM fed.coverage_summary cs \
         JOIN fed.sequencerun sr ON sr.at_uri = cs.sequence_run_ref \
         WHERE cs.mean_coverage IS NOT NULL AND sr.test_type IS NOT NULL AND btrim(sr.test_type) <> '' \
         GROUP BY sr.test_type",
    )
    .fetch_all(pool)
    .await?;

    // Typical Y/mt marker counts per test type (for the deferred age weighting).
    #[derive(sqlx::FromRow)]
    struct MarkerAgg {
        test_type: String,
        typical_y_markers: Option<i32>,
        typical_mt_markers: Option<i32>,
    }
    let markers: Vec<MarkerAgg> = sqlx::query_as(
        "SELECT test_type_code AS test_type, \
                percentile_cont(0.5) WITHIN GROUP (ORDER BY y_markers_called::double precision)::int AS typical_y_markers, \
                percentile_cont(0.5) WITHIN GROUP (ORDER BY mt_markers_called::double precision)::int AS typical_mt_markers \
         FROM fed.genotype \
         WHERE test_type_code IS NOT NULL AND btrim(test_type_code) <> '' \
         GROUP BY test_type_code",
    )
    .fetch_all(pool)
    .await?;

    // Merge the two aggregates by test type.
    let mut by_type: HashMap<String, CoverageNorm> = HashMap::new();
    for c in cov {
        by_type.insert(
            c.test_type.clone(),
            CoverageNorm {
                test_type: c.test_type,
                sample_count: c.sample_count,
                median_mean_depth: c.median_mean_depth,
                p25_mean_depth: c.p25_mean_depth,
                p75_mean_depth: c.p75_mean_depth,
                median_pct_10x: c.median_pct_10x,
                median_pct_20x: c.median_pct_20x,
                median_pct_30x: c.median_pct_30x,
                typical_y_markers: None,
                typical_mt_markers: None,
            },
        );
    }
    for m in markers {
        let e = by_type.entry(m.test_type.clone()).or_insert_with(|| CoverageNorm {
            test_type: m.test_type.clone(),
            sample_count: 0,
            median_mean_depth: None,
            p25_mean_depth: None,
            p75_mean_depth: None,
            median_pct_10x: None,
            median_pct_20x: None,
            median_pct_30x: None,
            typical_y_markers: None,
            typical_mt_markers: None,
        });
        e.typical_y_markers = m.typical_y_markers;
        e.typical_mt_markers = m.typical_mt_markers;
    }

    // Declarative upsert (assign, never accumulate) + prune dropped test types.
    let mut tx = pool.begin().await?;
    let mut kept: Vec<String> = Vec::new();
    for n in by_type.into_values() {
        sqlx::query(
            "INSERT INTO genomics.test_type_coverage_norm \
                (test_type, sample_count, median_mean_depth, p25_mean_depth, p75_mean_depth, \
                 median_pct_10x, median_pct_20x, median_pct_30x, typical_y_markers, typical_mt_markers, computed_at) \
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10, now()) \
             ON CONFLICT (test_type) DO UPDATE SET \
                sample_count = EXCLUDED.sample_count, median_mean_depth = EXCLUDED.median_mean_depth, \
                p25_mean_depth = EXCLUDED.p25_mean_depth, p75_mean_depth = EXCLUDED.p75_mean_depth, \
                median_pct_10x = EXCLUDED.median_pct_10x, median_pct_20x = EXCLUDED.median_pct_20x, \
                median_pct_30x = EXCLUDED.median_pct_30x, typical_y_markers = EXCLUDED.typical_y_markers, \
                typical_mt_markers = EXCLUDED.typical_mt_markers, computed_at = now()",
        )
        .bind(&n.test_type)
        .bind(n.sample_count)
        .bind(n.median_mean_depth)
        .bind(n.p25_mean_depth)
        .bind(n.p75_mean_depth)
        .bind(n.median_pct_10x)
        .bind(n.median_pct_20x)
        .bind(n.median_pct_30x)
        .bind(n.typical_y_markers)
        .bind(n.typical_mt_markers)
        .execute(&mut *tx)
        .await?;
        kept.push(n.test_type);
    }
    let pruned = sqlx::query("DELETE FROM genomics.test_type_coverage_norm WHERE test_type <> ALL($1)")
        .bind(&kept)
        .execute(&mut *tx)
        .await?
        .rows_affected();
    tx.commit().await?;
    Ok(NormReport { test_types: kept.len() as u64, pruned })
}
