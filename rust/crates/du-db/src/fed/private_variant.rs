//! Mirrored citizen private-variant sets
//! (`com.decodingus.atmosphere.privateVariant`). Each record is one biosample's
//! private variants (mutations beyond its assigned terminal haplogroup) for one
//! DNA arm. [`crate::discovery::recompute_consensus`] materializes these into
//! `tree.biosample_private_variant` and pools them into proposed branches by
//! variant-set Jaccard similarity. See [`super`] for the shared cursor/delete.

use super::Common;
use crate::DbError;
use serde_json::Value;
use sqlx::PgPool;

/// A mirrored private-variant record. `variants` is the lexicon's variant array
/// verbatim (`[{name?, contig, position, ancestral, derived, rsId?}]`).
pub struct PrivateVariant {
    pub common: Common,
    pub biosample_ref: Option<String>,
    pub sequence_run_ref: Option<String>,
    pub dna_type: Option<String>,
    pub terminal_haplogroup: Option<String>,
    pub variants: Value,
}

pub async fn upsert(pool: &PgPool, p: &PrivateVariant) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.private_variant \
           (did, rkey, at_uri, cid, biosample_ref, sequence_run_ref, dna_type, \
            terminal_haplogroup, variants, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, biosample_ref = EXCLUDED.biosample_ref, \
           sequence_run_ref = EXCLUDED.sequence_run_ref, dna_type = EXCLUDED.dna_type, \
           terminal_haplogroup = EXCLUDED.terminal_haplogroup, variants = EXCLUDED.variants, \
           record_created_at = EXCLUDED.record_created_at, time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.private_variant.time_us",
    )
    .bind(&p.common.did)
    .bind(&p.common.rkey)
    .bind(&p.common.at_uri)
    .bind(&p.common.cid)
    .bind(&p.biosample_ref)
    .bind(&p.sequence_run_ref)
    .bind(&p.dna_type)
    .bind(&p.terminal_haplogroup)
    .bind(&p.variants)
    .bind(p.common.record_created_at)
    .bind(p.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}
