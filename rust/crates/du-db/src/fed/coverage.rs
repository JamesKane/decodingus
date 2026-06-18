//! Alignment coverage summaries (`com.decodingus.atmosphere.alignment`) — the
//! first mirrored collection (migration 0011, `fed.coverage_summary`). QC metrics
//! only, never raw reads. See [`super`] for the shared cursor/delete.

use crate::DbError;
use chrono::{DateTime, Utc};
use serde_json::Value;
use sqlx::PgPool;

/// One published alignment summary record. Scalars are extracted from
/// `metrics` (alignmentMetrics) for indexed aggregation; `metrics` keeps the
/// authoritative copy (incl. per-contig stats).
pub struct CoverageRecord {
    pub did: String,
    pub collection: String,
    pub rkey: String,
    pub at_uri: String,
    pub cid: Option<String>,
    pub biosample_ref: Option<String>,
    pub sequence_run_ref: Option<String>,
    pub reference_build: Option<String>,
    pub aligner: Option<String>,
    pub mean_coverage: Option<f64>,
    pub median_coverage: Option<f64>,
    pub pct_10x: Option<f64>,
    pub pct_20x: Option<f64>,
    pub pct_30x: Option<f64>,
    pub metrics: Value,
    pub record_created_at: Option<DateTime<Utc>>,
    pub time_us: i64,
}

/// Upsert a mirrored summary record. Idempotent and ordered: a row is only
/// overwritten by an event with a `time_us` at least as new, so replays after a
/// reconnect and out-of-order deliveries can't resurrect stale state.
pub async fn upsert(pool: &PgPool, r: &CoverageRecord) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.coverage_summary \
           (did, collection, rkey, at_uri, cid, biosample_ref, sequence_run_ref, \
            reference_build, aligner, mean_coverage, median_coverage, \
            pct_10x, pct_20x, pct_30x, metrics, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17) \
         ON CONFLICT (did, collection, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, \
           biosample_ref = EXCLUDED.biosample_ref, sequence_run_ref = EXCLUDED.sequence_run_ref, \
           reference_build = EXCLUDED.reference_build, aligner = EXCLUDED.aligner, \
           mean_coverage = EXCLUDED.mean_coverage, median_coverage = EXCLUDED.median_coverage, \
           pct_10x = EXCLUDED.pct_10x, pct_20x = EXCLUDED.pct_20x, pct_30x = EXCLUDED.pct_30x, \
           metrics = EXCLUDED.metrics, record_created_at = EXCLUDED.record_created_at, \
           time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.coverage_summary.time_us",
    )
    .bind(&r.did)
    .bind(&r.collection)
    .bind(&r.rkey)
    .bind(&r.at_uri)
    .bind(&r.cid)
    .bind(&r.biosample_ref)
    .bind(&r.sequence_run_ref)
    .bind(&r.reference_build)
    .bind(&r.aligner)
    .bind(r.mean_coverage)
    .bind(r.median_coverage)
    .bind(r.pct_10x)
    .bind(r.pct_20x)
    .bind(r.pct_30x)
    .bind(&r.metrics)
    .bind(r.record_created_at)
    .bind(r.time_us)
    .execute(pool)
    .await?;
    Ok(())
}

/// A population coverage aggregate over the mirror, grouped by reference build —
/// the cheap query-time path the mirror exists to enable.
#[derive(Debug, sqlx::FromRow)]
pub struct BuildCoverage {
    pub reference_build: Option<String>,
    pub samples: i64,
    pub mean_coverage: Option<f64>,
    pub mean_pct_30x: Option<f64>,
}

/// Aggregate mirrored summaries by reference build (sample count + averaged
/// depth/30x), most-sampled build first.
pub async fn aggregate_by_build(pool: &PgPool) -> Result<Vec<BuildCoverage>, DbError> {
    let rows = sqlx::query_as::<_, BuildCoverage>(
        "SELECT reference_build, count(*) AS samples, \
                avg(mean_coverage) AS mean_coverage, avg(pct_30x) AS mean_pct_30x \
         FROM fed.coverage_summary \
         GROUP BY reference_build \
         ORDER BY samples DESC",
    )
    .fetch_all(pool)
    .await?;
    Ok(rows)
}
