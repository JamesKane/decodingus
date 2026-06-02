//! Computed-analytics records: genotype summary stats, population/ancestry
//! breakdown, and donor-level haplogroup reconciliation.
//!
//! These are already anonymized computed summaries (no raw genotypes/reads), so
//! the mirror keeps extracted scalar columns for indexed reporting **plus** the
//! computed payload as JSONB (the consumer strips `files` before storing).
//! Ordered+idempotent upsert; deletes go through [`super::delete`].

use super::Common;
use crate::DbError;
use serde_json::Value;
use sqlx::PgPool;

/// Genotype (chip/array) summary statistics.
pub struct Genotype {
    pub common: Common,
    pub biosample_ref: Option<String>,
    pub provider: Option<String>,
    pub test_type_code: Option<String>,
    pub chip_version: Option<String>,
    pub total_markers_called: Option<i32>,
    pub total_markers_possible: Option<i32>,
    pub no_call_rate: Option<f64>,
    pub y_markers_called: Option<i32>,
    pub mt_markers_called: Option<i32>,
    pub autosomal_markers_called: Option<i32>,
    pub het_rate: Option<f64>,
    pub build_version: Option<String>,
    pub y_haplogroup: Option<String>,
    pub mt_haplogroup: Option<String>,
    pub population_breakdown_ref: Option<String>,
    /// Full record minus `files`.
    pub record: Value,
}

pub async fn upsert_genotype(pool: &PgPool, g: &Genotype) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.genotype \
           (did, rkey, at_uri, cid, biosample_ref, provider, test_type_code, chip_version, \
            total_markers_called, total_markers_possible, no_call_rate, y_markers_called, \
            mt_markers_called, autosomal_markers_called, het_rate, build_version, \
            y_haplogroup, mt_haplogroup, population_breakdown_ref, record, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, biosample_ref = EXCLUDED.biosample_ref, \
           provider = EXCLUDED.provider, test_type_code = EXCLUDED.test_type_code, \
           chip_version = EXCLUDED.chip_version, total_markers_called = EXCLUDED.total_markers_called, \
           total_markers_possible = EXCLUDED.total_markers_possible, no_call_rate = EXCLUDED.no_call_rate, \
           y_markers_called = EXCLUDED.y_markers_called, mt_markers_called = EXCLUDED.mt_markers_called, \
           autosomal_markers_called = EXCLUDED.autosomal_markers_called, het_rate = EXCLUDED.het_rate, \
           build_version = EXCLUDED.build_version, y_haplogroup = EXCLUDED.y_haplogroup, \
           mt_haplogroup = EXCLUDED.mt_haplogroup, population_breakdown_ref = EXCLUDED.population_breakdown_ref, \
           record = EXCLUDED.record, record_created_at = EXCLUDED.record_created_at, \
           time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.genotype.time_us",
    )
    .bind(&g.common.did)
    .bind(&g.common.rkey)
    .bind(&g.common.at_uri)
    .bind(&g.common.cid)
    .bind(&g.biosample_ref)
    .bind(&g.provider)
    .bind(&g.test_type_code)
    .bind(&g.chip_version)
    .bind(g.total_markers_called)
    .bind(g.total_markers_possible)
    .bind(g.no_call_rate)
    .bind(g.y_markers_called)
    .bind(g.mt_markers_called)
    .bind(g.autosomal_markers_called)
    .bind(g.het_rate)
    .bind(&g.build_version)
    .bind(&g.y_haplogroup)
    .bind(&g.mt_haplogroup)
    .bind(&g.population_breakdown_ref)
    .bind(&g.record)
    .bind(g.common.record_created_at)
    .bind(g.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}

/// Population/ancestry breakdown (PCA projection → sub-continental percentages).
pub struct PopulationBreakdown {
    pub common: Common,
    pub biosample_ref: Option<String>,
    pub analysis_method: Option<String>,
    pub panel_type: Option<String>,
    pub reference_populations: Option<String>,
    pub snps_analyzed: Option<i32>,
    pub snps_with_genotype: Option<i32>,
    pub snps_missing: Option<i32>,
    pub confidence_level: Option<f64>,
    pub components: Value,
    pub super_population_summary: Value,
    pub pca_coordinates: Option<Value>,
}

pub async fn upsert_population_breakdown(pool: &PgPool, p: &PopulationBreakdown) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.population_breakdown \
           (did, rkey, at_uri, cid, biosample_ref, analysis_method, panel_type, reference_populations, \
            snps_analyzed, snps_with_genotype, snps_missing, confidence_level, components, \
            super_population_summary, pca_coordinates, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, biosample_ref = EXCLUDED.biosample_ref, \
           analysis_method = EXCLUDED.analysis_method, panel_type = EXCLUDED.panel_type, \
           reference_populations = EXCLUDED.reference_populations, snps_analyzed = EXCLUDED.snps_analyzed, \
           snps_with_genotype = EXCLUDED.snps_with_genotype, snps_missing = EXCLUDED.snps_missing, \
           confidence_level = EXCLUDED.confidence_level, components = EXCLUDED.components, \
           super_population_summary = EXCLUDED.super_population_summary, pca_coordinates = EXCLUDED.pca_coordinates, \
           record_created_at = EXCLUDED.record_created_at, time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.population_breakdown.time_us",
    )
    .bind(&p.common.did)
    .bind(&p.common.rkey)
    .bind(&p.common.at_uri)
    .bind(&p.common.cid)
    .bind(&p.biosample_ref)
    .bind(&p.analysis_method)
    .bind(&p.panel_type)
    .bind(&p.reference_populations)
    .bind(p.snps_analyzed)
    .bind(p.snps_with_genotype)
    .bind(p.snps_missing)
    .bind(p.confidence_level)
    .bind(&p.components)
    .bind(&p.super_population_summary)
    .bind(&p.pca_coordinates)
    .bind(p.common.record_created_at)
    .bind(p.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}

/// Donor-level multi-run haplogroup reconciliation (consensus call).
pub struct Reconciliation {
    pub common: Common,
    pub specimen_donor_ref: Option<String>,
    pub dna_type: Option<String>,
    pub compatibility_level: Option<String>,
    pub consensus_haplogroup: Option<String>,
    pub confidence: Option<f64>,
    pub branch_compatibility_score: Option<f64>,
    pub snp_concordance: Option<f64>,
    pub run_count: Option<i32>,
    pub record: Value,
}

pub async fn upsert_reconciliation(pool: &PgPool, r: &Reconciliation) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.haplogroup_reconciliation \
           (did, rkey, at_uri, cid, specimen_donor_ref, dna_type, compatibility_level, \
            consensus_haplogroup, confidence, branch_compatibility_score, snp_concordance, \
            run_count, record, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, specimen_donor_ref = EXCLUDED.specimen_donor_ref, \
           dna_type = EXCLUDED.dna_type, compatibility_level = EXCLUDED.compatibility_level, \
           consensus_haplogroup = EXCLUDED.consensus_haplogroup, confidence = EXCLUDED.confidence, \
           branch_compatibility_score = EXCLUDED.branch_compatibility_score, \
           snp_concordance = EXCLUDED.snp_concordance, run_count = EXCLUDED.run_count, \
           record = EXCLUDED.record, record_created_at = EXCLUDED.record_created_at, \
           time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.haplogroup_reconciliation.time_us",
    )
    .bind(&r.common.did)
    .bind(&r.common.rkey)
    .bind(&r.common.at_uri)
    .bind(&r.common.cid)
    .bind(&r.specimen_donor_ref)
    .bind(&r.dna_type)
    .bind(&r.compatibility_level)
    .bind(&r.consensus_haplogroup)
    .bind(r.confidence)
    .bind(r.branch_compatibility_score)
    .bind(r.snp_concordance)
    .bind(r.run_count)
    .bind(&r.record)
    .bind(r.common.record_created_at)
    .bind(r.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}

/// Population-level ancestry report: average super-population percentage across
/// all mirrored breakdowns, with a contributing-sample count. Aggregates over the
/// `super_population_summary` JSONB with a lateral unnest — query-time SQL, no
/// per-PDS fetch.
#[derive(Debug, sqlx::FromRow)]
pub struct SuperPopulationShare {
    pub super_population: Option<String>,
    pub samples: i64,
    pub avg_percentage: Option<f64>,
}

pub async fn super_population_distribution(pool: &PgPool) -> Result<Vec<SuperPopulationShare>, DbError> {
    let rows = sqlx::query_as::<_, SuperPopulationShare>(
        "SELECT sp->>'superPopulation' AS super_population, \
                count(*) AS samples, \
                avg((sp->>'percentage')::double precision) AS avg_percentage \
         FROM fed.population_breakdown pb, \
              jsonb_array_elements(pb.super_population_summary) AS sp \
         GROUP BY sp->>'superPopulation' \
         ORDER BY avg_percentage DESC NULLS LAST",
    )
    .fetch_all(pool)
    .await?;
    Ok(rows)
}
