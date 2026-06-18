//! Mirrored Y-STR profiles (`com.decodingus.atmosphere.strProfile`). Markers are
//! stored lossless as JSONB; the per-branch modal aggregation lives in
//! [`crate::ystr`]. See [`super`] for the shared cursor/delete.

use super::Common;
use crate::DbError;
use serde_json::Value;
use sqlx::PgPool;

/// A mirrored STR profile. `markers` is the lexicon's `strMarkerValue[]` verbatim.
pub struct StrProfile {
    pub common: Common,
    pub biosample_ref: Option<String>,
    pub sequence_run_ref: Option<String>,
    pub source: Option<String>,
    pub imported_from: Option<String>,
    pub derivation_method: Option<String>,
    pub total_markers: Option<i32>,
    pub markers: Value,
}

pub async fn upsert(pool: &PgPool, p: &StrProfile) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.str_profile \
           (did, rkey, at_uri, cid, biosample_ref, sequence_run_ref, source, imported_from, \
            derivation_method, total_markers, markers, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, biosample_ref = EXCLUDED.biosample_ref, \
           sequence_run_ref = EXCLUDED.sequence_run_ref, source = EXCLUDED.source, \
           imported_from = EXCLUDED.imported_from, derivation_method = EXCLUDED.derivation_method, \
           total_markers = EXCLUDED.total_markers, markers = EXCLUDED.markers, \
           record_created_at = EXCLUDED.record_created_at, time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.str_profile.time_us",
    )
    .bind(&p.common.did)
    .bind(&p.common.rkey)
    .bind(&p.common.at_uri)
    .bind(&p.common.cid)
    .bind(&p.biosample_ref)
    .bind(&p.sequence_run_ref)
    .bind(&p.source)
    .bind(&p.imported_from)
    .bind(&p.derivation_method)
    .bind(p.total_markers)
    .bind(&p.markers)
    .bind(p.common.record_created_at)
    .bind(p.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}
