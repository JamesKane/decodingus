//! Mirrored citizen instrument→lab observations
//! (`com.decodingus.atmosphere.instrumentObservation`). Each is one citizen's
//! explicit claim that an instrument id belongs to a lab, with a confidence level
//! (KNOWN/INFERRED/GUESSED). [`crate::sequencer::recompute_consensus`] folds these
//! into the proposal set alongside the implicit `fed.sequencerun.center_name`
//! claims. See [`super`] for the shared cursor/delete.

use super::Common;
use crate::DbError;
use chrono::{DateTime, NaiveDate, Utc};
use sqlx::PgPool;

/// A mirrored instrument observation record.
pub struct InstrumentObservation {
    pub common: Common,
    pub instrument_id: Option<String>,
    pub lab_name: Option<String>,
    pub biosample_ref: Option<String>,
    pub platform: Option<String>,
    pub instrument_model: Option<String>,
    pub flowcell_id: Option<String>,
    pub run_date: Option<NaiveDate>,
    pub confidence: Option<String>,
    pub observed_at: Option<DateTime<Utc>>,
}

pub async fn upsert(pool: &PgPool, o: &InstrumentObservation) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.instrument_observation \
           (did, rkey, at_uri, cid, instrument_id, lab_name, biosample_ref, platform, \
            instrument_model, flowcell_id, run_date, confidence, observed_at, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, instrument_id = EXCLUDED.instrument_id, \
           lab_name = EXCLUDED.lab_name, biosample_ref = EXCLUDED.biosample_ref, platform = EXCLUDED.platform, \
           instrument_model = EXCLUDED.instrument_model, flowcell_id = EXCLUDED.flowcell_id, \
           run_date = EXCLUDED.run_date, confidence = EXCLUDED.confidence, observed_at = EXCLUDED.observed_at, \
           record_created_at = EXCLUDED.record_created_at, time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.instrument_observation.time_us",
    )
    .bind(&o.common.did)
    .bind(&o.common.rkey)
    .bind(&o.common.at_uri)
    .bind(&o.common.cid)
    .bind(&o.instrument_id)
    .bind(&o.lab_name)
    .bind(&o.biosample_ref)
    .bind(&o.platform)
    .bind(&o.instrument_model)
    .bind(&o.flowcell_id)
    .bind(o.run_date)
    .bind(&o.confidence)
    .bind(o.observed_at)
    .bind(o.common.record_created_at)
    .bind(o.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}
