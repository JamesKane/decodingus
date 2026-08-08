//! Mirrored ancestral-origin records (`com.decodingus.atmosphere.ancestralOrigin`) — one
//! lineage's most distant known ancestor: surname, origin, dates. The locality substrate for the
//! genealogical-era origins icicle. See [`super`] for the shared cursor/delete, and
//! `proposals/ancestral-origin-icicle.md` for the design.
//!
//! **This layer is pure storage.** The privacy gates that make these records publishable —
//! single-token surname, `birth_year <= 1900`, country-only without a birth year, coordinates
//! coarsened — run in the consumer (`du_jobs::jetstream::build_ancestral_origin`) *before* a row
//! reaches here, matching how every other `fed.*` module leaves record-shape extraction to
//! du-jobs. A row in this table has already passed them.

use super::Common;
use crate::DbError;
use serde_json::Value;
use sqlx::PgPool;

/// A mirrored ancestral origin, post-gate. Every field beyond the envelope is optional because
/// the precision ladder legitimately produces a country-only record.
pub struct AncestralOrigin {
    pub common: Common,
    /// at-uri of the parent biosample record — present only for genuinely federated samples.
    pub biosample_ref: Option<String>,
    /// `[{namespace, value}]` verbatim — the join key that actually fires for tree tips.
    pub external_ids: Value,
    pub lineage: Option<String>,
    pub surname: Option<String>,
    pub origin_place: Option<String>,
    pub origin_country: Option<String>,
    pub birth_year: Option<i32>,
    pub death_year: Option<i32>,
    /// Coarsened to 2dp by the consumer. `None` when the record carried no usable coordinate.
    pub lat: Option<f64>,
    pub lon: Option<f64>,
}

pub async fn upsert(pool: &PgPool, o: &AncestralOrigin) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.ancestral_origin \
           (did, rkey, at_uri, cid, biosample_ref, external_ids, lineage, surname, \
            origin_place, origin_country, birth_year, death_year, geocoord, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12, \
                 CASE WHEN $13::float8 IS NULL OR $14::float8 IS NULL THEN NULL \
                      ELSE ST_SetSRID(ST_MakePoint($14::float8, $13::float8), 4326) END, \
                 $15,$16) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, biosample_ref = EXCLUDED.biosample_ref, \
           external_ids = EXCLUDED.external_ids, lineage = EXCLUDED.lineage, \
           surname = EXCLUDED.surname, origin_place = EXCLUDED.origin_place, \
           origin_country = EXCLUDED.origin_country, birth_year = EXCLUDED.birth_year, \
           death_year = EXCLUDED.death_year, geocoord = EXCLUDED.geocoord, \
           record_created_at = EXCLUDED.record_created_at, time_us = EXCLUDED.time_us, \
           indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.ancestral_origin.time_us",
    )
    .bind(&o.common.did)
    .bind(&o.common.rkey)
    .bind(&o.common.at_uri)
    .bind(&o.common.cid)
    .bind(&o.biosample_ref)
    .bind(&o.external_ids)
    .bind(&o.lineage)
    .bind(&o.surname)
    .bind(&o.origin_place)
    .bind(&o.origin_country)
    .bind(o.birth_year)
    .bind(o.death_year)
    .bind(o.lat)
    .bind(o.lon)
    .bind(o.common.record_created_at)
    .bind(o.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}
