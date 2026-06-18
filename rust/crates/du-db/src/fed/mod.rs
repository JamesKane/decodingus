//! Federated reporting mirror (atmosphere "Record Status Summary" — the legacy
//! `✅ AppView Complete` ingest set).
//!
//! The AppView does NOT analyze — it **aggregates and reports**. Navigator
//! computes anonymized per-sample SUMMARIES at the edge and publishes them as
//! public PDS records; a single Jetstream consumer (`du-jobs`) mirrors each
//! supported collection into a dedicated `fed.*` reporting table here, and
//! reports aggregate with local SQL. This is the summary ingestion the atmosphere
//! v2.1 "scope reduction" wrongly dropped — NOT the raw-data network mirror
//! (summaries only, no raw reads/files, donor PII never stored).
//!
//! One module/table per collection; the consumer drives them and shares the
//! cursor defined here. Storage structs are plain data — the consumer (du-jobs)
//! owns record-shape extraction, keeping this layer pure storage.

use crate::DbError;
use chrono::{DateTime, Utc};
use sqlx::PgPool;

pub mod analytics;
pub mod core;
pub mod coverage;
pub mod device_key;
pub mod instrument_observation;
pub mod private_variant;
pub mod str_profile;

// Collection NSIDs the AppView ingests for reporting. The records Navigator computes +
// publishes are defined in the shared `du-domain::fed` module, so the NSIDs for those
// collections are sourced from there — publisher and consumer cannot drift. (project /
// workspace / genotype / strProfile have no shared record contract yet, so they stay
// local until one lands.)
pub const NS_ALIGNMENT: &str = du_domain::fed::NS_ALIGNMENT;
pub const NS_BIOSAMPLE: &str = du_domain::fed::NS_BIOSAMPLE;
pub const NS_SEQUENCERUN: &str = du_domain::fed::NS_SEQUENCERUN;
pub const NS_PROJECT: &str = "com.decodingus.atmosphere.project";
pub const NS_WORKSPACE: &str = "com.decodingus.atmosphere.workspace";
pub const NS_GENOTYPE: &str = "com.decodingus.atmosphere.genotype";
pub const NS_POPULATION_BREAKDOWN: &str = du_domain::fed::NS_POPULATION_BREAKDOWN;
pub const NS_HAPLOGROUP_RECONCILIATION: &str = du_domain::fed::NS_HAPLOGROUP_RECONCILIATION;
pub const NS_STR_PROFILE: &str = "com.decodingus.atmosphere.strProfile";
pub const NS_INSTRUMENT_OBSERVATION: &str = "com.decodingus.atmosphere.instrumentObservation";
pub const NS_PRIVATE_VARIANT: &str = "com.decodingus.atmosphere.privateVariant";
pub const NS_DEVICE_KEY: &str = "com.decodingus.atmosphere.deviceKey";

/// Every collection mirrored for reporting (the consumer's `wantedCollections`).
pub const INGEST_COLLECTIONS: &[&str] = &[
    NS_ALIGNMENT,
    NS_BIOSAMPLE,
    NS_SEQUENCERUN,
    NS_PROJECT,
    NS_WORKSPACE,
    NS_GENOTYPE,
    NS_POPULATION_BREAKDOWN,
    NS_HAPLOGROUP_RECONCILIATION,
    NS_STR_PROFILE,
    NS_INSTRUMENT_OBSERVATION,
    NS_PRIVATE_VARIANT,
    NS_DEVICE_KEY,
];

/// The `fed.*` reporting table backing a collection, or `None` if unsupported.
fn table_for(collection: &str) -> Option<&'static str> {
    Some(match collection {
        NS_ALIGNMENT => "fed.coverage_summary",
        NS_BIOSAMPLE => "fed.biosample",
        NS_SEQUENCERUN => "fed.sequencerun",
        NS_PROJECT => "fed.project",
        NS_WORKSPACE => "fed.workspace",
        NS_GENOTYPE => "fed.genotype",
        NS_POPULATION_BREAKDOWN => "fed.population_breakdown",
        NS_HAPLOGROUP_RECONCILIATION => "fed.haplogroup_reconciliation",
        NS_STR_PROFILE => "fed.str_profile",
        NS_INSTRUMENT_OBSERVATION => "fed.instrument_observation",
        NS_PRIVATE_VARIANT => "fed.private_variant",
        NS_DEVICE_KEY => "fed.device_key",
        _ => return None,
    })
}

/// Pointer/provenance fields common to every mirrored record.
#[derive(Clone)]
pub struct Common {
    pub did: String,
    pub rkey: String,
    pub at_uri: String,
    pub cid: Option<String>,
    pub record_created_at: Option<DateTime<Utc>>,
    /// Jetstream cursor (`time_us`) of the event that produced this row.
    pub time_us: i64,
}

/// Remove a mirrored record (its source was deleted on the PDS). The table is
/// resolved from a fixed NSID map — never interpolated from untrusted input.
/// Returns `false` for an unsupported collection.
pub async fn delete(pool: &PgPool, collection: &str, did: &str, rkey: &str) -> Result<bool, DbError> {
    let Some(table) = table_for(collection) else { return Ok(false) };
    let sql = format!("DELETE FROM {table} WHERE did = $1 AND rkey = $2");
    sqlx::query(&sql).bind(did).bind(rkey).execute(pool).await?;
    Ok(true)
}

/// Last persisted Jetstream cursor (`time_us`), if the consumer has run before.
pub async fn load_cursor(pool: &PgPool) -> Result<Option<i64>, DbError> {
    let cursor: Option<i64> =
        sqlx::query_scalar("SELECT time_us FROM fed.jetstream_cursor WHERE id")
            .fetch_optional(pool)
            .await?;
    Ok(cursor)
}

/// Persist the Jetstream cursor (singleton row) so the consumer resumes here.
pub async fn save_cursor(pool: &PgPool, time_us: i64) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.jetstream_cursor (id, time_us) VALUES (true, $1) \
         ON CONFLICT (id) DO UPDATE SET time_us = EXCLUDED.time_us, updated_at = now()",
    )
    .bind(time_us)
    .execute(pool)
    .await?;
    Ok(())
}

/// Shared helper for the `Utc` conversion the consumer needs when building rows.
pub fn to_utc(s: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(s).ok().map(|dt| dt.with_timezone(&Utc))
}
