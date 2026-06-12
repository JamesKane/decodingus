//! Sequencer-lab lookup — resolve a sequencing instrument id (from BAM/CRAM `@RG`
//! headers, e.g. `A00123`) to its sequencing laboratory, for the Edge analyzer.
//!
//! Resolves via the **preseeded** direct association
//! (`genomics.sequencer_instrument.lab_id` → `genomics.sequencing_lab`). The
//! consensus/curation path (`instrument_observation` → `instrument_association_
//! proposal` → accept) is not live yet; when it is, accepting a proposal sets
//! `lab_id` and this lookup is unchanged.

use crate::DbError;
use sqlx::PgPool;

/// A resolved instrument → lab association.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct LabLookup {
    pub instrument_id: String,
    pub lab_name: String,
    pub is_d2c: bool,
    pub website_url: Option<String>,
    pub manufacturer: Option<String>,
    pub model_name: Option<String>,
}

/// Resolve a single instrument id to its lab. `None` when the instrument is
/// unknown or has no preseeded lab association.
pub async fn lookup_lab(pool: &PgPool, instrument_id: &str) -> Result<Option<LabLookup>, DbError> {
    Ok(sqlx::query_as::<_, LabLookup>(
        "SELECT si.instrument_id, sl.name AS lab_name, sl.is_d2c, sl.website_url, \
                si.manufacturer, si.model_name \
         FROM genomics.sequencer_instrument si \
         JOIN genomics.sequencing_lab sl ON sl.id = si.lab_id \
         WHERE si.instrument_id = $1",
    )
    .bind(instrument_id)
    .fetch_optional(pool)
    .await?)
}

/// Every preseeded instrument → lab association (the Edge's bulk cache seed),
/// ordered by instrument id.
pub async fn lab_instruments(pool: &PgPool) -> Result<Vec<LabLookup>, DbError> {
    Ok(sqlx::query_as::<_, LabLookup>(
        "SELECT si.instrument_id, sl.name AS lab_name, sl.is_d2c, sl.website_url, \
                si.manufacturer, si.model_name \
         FROM genomics.sequencer_instrument si \
         JOIN genomics.sequencing_lab sl ON sl.id = si.lab_id \
         ORDER BY si.instrument_id",
    )
    .fetch_all(pool)
    .await?)
}
