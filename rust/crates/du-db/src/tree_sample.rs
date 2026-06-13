//! YFull-style sample placement: attach non-D2C biosamples as leaves under the tree node
//! their published haplogroup call resolves to. A biosample's call is the paper's stated
//! Y/mt haplogroup (`core.biosample.original_haplogroups`); resolution to a `tree.haplogroup`
//! node reuses [`crate::haplogroup::resolve_name_or_variant`] (name → alias → defining-variant
//! → normalization). D2C (`source = 'CITIZEN'`) samples are excluded; an unresolvable call is
//! kept `UNPLACED` for curator triage. Mirrors the advisory-locked declarative-recompute
//! discipline of the coverage/IBD engines.

use crate::{pg_enum_label, DbError};
use du_domain::enums::DnaType;
use serde_json::Value;
use sqlx::PgPool;
use std::collections::HashMap;
use uuid::Uuid;

/// Advisory-lock key guarding concurrent placement recomputes.
const PLACEMENT_ADVISORY_KEY: i64 = 0x4C45_4146_5359; // "LEAFSY"

/// Outcome of [`recompute_placements`].
#[derive(Debug, Default, Clone)]
pub struct PlacementReport {
    pub placed: u64,
    pub unplaced: u64,
}

/// The `original_haplogroups` JSONB keys carrying a DNA type's call (primary, fallback).
fn call_keys(dna_type: DnaType) -> (&'static str, &'static str) {
    match dna_type {
        DnaType::YDna => ("y", "y_result"),
        DnaType::MtDna => ("mt", "mt_result"),
    }
}

/// Recompute leaf placements for `dna_type` from the current non-D2C biosample calls and the
/// current tree. Single-flighted by an advisory lock (a second caller no-ops); unlocks on every
/// path. Declarative (assign placements, prune samples that no longer qualify); bumps
/// `tree_revision` since placements feed the cached tree's per-node count.
pub async fn recompute_placements(pool: &PgPool, dna_type: DnaType) -> Result<PlacementReport, DbError> {
    let mut lock = pool.acquire().await?;
    let locked: bool = sqlx::query_scalar("SELECT pg_try_advisory_lock($1)")
        .bind(PLACEMENT_ADVISORY_KEY)
        .fetch_one(&mut *lock)
        .await?;
    if !locked {
        return Ok(PlacementReport::default());
    }
    let result = recompute_placements_locked(pool, dna_type).await;
    let _ = sqlx::query("SELECT pg_advisory_unlock($1)")
        .bind(PLACEMENT_ADVISORY_KEY)
        .execute(&mut *lock)
        .await;
    result
}

async fn recompute_placements_locked(pool: &PgPool, dna_type: DnaType) -> Result<PlacementReport, DbError> {
    let (primary, fallback) = call_keys(dna_type);

    // Non-D2C, non-deleted samples + their published calls (the leaf candidates).
    let rows: Vec<(Uuid, Value)> = sqlx::query_as(
        "SELECT sample_guid, original_haplogroups FROM core.biosample \
         WHERE source <> 'CITIZEN'::core.biosample_source AND deleted = false",
    )
    .fetch_all(pool)
    .await?;

    // Resolve each sample's call to a node id, caching by call text (calls repeat heavily).
    let mut cache: HashMap<String, Option<i64>> = HashMap::new();
    let mut processed: Vec<Uuid> = Vec::new();
    let mut placements: Vec<(Uuid, Option<i64>, String)> = Vec::new();
    let mut report = PlacementReport::default();
    for (guid, arr) in rows {
        let Some(call) = crate::biosample::pick_original_call(&arr, primary, fallback) else {
            continue; // no published call for this DNA type — not a leaf
        };
        let node_id = match cache.get(&call) {
            Some(id) => *id,
            None => {
                let id = match crate::haplogroup::resolve_name_or_variant(pool, &call, dna_type).await? {
                    Some(name) => crate::haplogroup::get_by_name(pool, &name, dna_type).await?.map(|h| h.id.0),
                    None => None,
                };
                cache.insert(call.clone(), id);
                id
            }
        };
        if node_id.is_some() {
            report.placed += 1;
        } else {
            report.unplaced += 1;
        }
        processed.push(guid);
        placements.push((guid, node_id, call));
    }

    let dna = pg_enum_label(&dna_type)?;
    let mut tx = pool.begin().await?;
    for (guid, node_id, call) in &placements {
        let status = if node_id.is_some() { "PLACED" } else { "UNPLACED" };
        sqlx::query(
            "INSERT INTO tree.haplogroup_sample (sample_guid, dna_type, haplogroup_id, call_text, status, refreshed_at) \
             VALUES ($1, $2::core.dna_type, $3, $4, $5, now()) \
             ON CONFLICT (sample_guid, dna_type) DO UPDATE SET \
               haplogroup_id = EXCLUDED.haplogroup_id, call_text = EXCLUDED.call_text, \
               status = EXCLUDED.status, refreshed_at = now()",
        )
        .bind(guid)
        .bind(&dna)
        .bind(node_id)
        .bind(call)
        .bind(status)
        .execute(&mut *tx)
        .await?;
    }
    // Prune placements whose sample no longer qualifies (deleted / now CITIZEN / lost its call).
    sqlx::query("DELETE FROM tree.haplogroup_sample WHERE dna_type::text = $1 AND sample_guid <> ALL($2)")
        .bind(&dna)
        .bind(&processed)
        .execute(&mut *tx)
        .await?;
    crate::tree_revision::bump(&mut *tx).await?;
    tx.commit().await?;
    Ok(report)
}

/// Direct PLACED sample counts per haplogroup node id (the serving rollup input — callers
/// sum cumulatively over the subtree).
pub async fn counts_by_node(pool: &PgPool, dna_type: DnaType) -> Result<HashMap<i64, i64>, DbError> {
    let rows: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT haplogroup_id, count(*)::bigint FROM tree.haplogroup_sample \
         WHERE dna_type::text = $1 AND status = 'PLACED' AND haplogroup_id IS NOT NULL \
         GROUP BY haplogroup_id",
    )
    .bind(pg_enum_label(&dna_type)?)
    .fetch_all(pool)
    .await?;
    Ok(rows.into_iter().collect())
}

/// A placed leaf sample under a node (pseudonymous-safe: accession/alias + optional citation).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct LeafSample {
    pub sample_guid: Uuid,
    pub accession: Option<String>,
    pub alias: Option<String>,
    pub source: String,
    pub pub_title: Option<String>,
    pub pub_doi: Option<String>,
    pub pub_url: Option<String>,
}

/// The placed samples at-or-below a node (the YFull "open a clade → see its samples" list),
/// with each sample's most recent linked publication when present.
pub async fn samples_under(pool: &PgPool, node_name: &str, dna_type: DnaType) -> Result<Vec<LeafSample>, DbError> {
    Ok(sqlx::query_as(
        "WITH RECURSIVE sub AS ( \
            SELECT id FROM tree.haplogroup \
            WHERE name = $1 AND haplogroup_type::text = $2 AND valid_until IS NULL \
            UNION ALL \
            SELECT r.child_haplogroup_id FROM tree.haplogroup_relationship r \
            JOIN sub ON r.parent_haplogroup_id = sub.id \
            WHERE r.valid_until IS NULL \
         ) \
         SELECT b.sample_guid, b.accession, b.alias, b.source::text AS source, \
                p.title AS pub_title, p.doi AS pub_doi, p.url AS pub_url \
         FROM tree.haplogroup_sample hs \
         JOIN sub ON sub.id = hs.haplogroup_id \
         JOIN core.biosample b ON b.sample_guid = hs.sample_guid \
         LEFT JOIN LATERAL ( \
            SELECT pub.title, pub.doi, pub.url FROM pubs.publication_biosample pb \
            JOIN pubs.publication pub ON pub.id = pb.publication_id \
            WHERE pb.sample_guid = b.sample_guid \
            ORDER BY pub.publication_date DESC NULLS LAST LIMIT 1 \
         ) p ON true \
         WHERE hs.dna_type::text = $2 AND hs.status = 'PLACED' AND b.deleted = false \
         ORDER BY b.accession NULLS LAST, b.sample_guid",
    )
    .bind(node_name)
    .bind(pg_enum_label(&dna_type)?)
    .fetch_all(pool)
    .await?)
}
