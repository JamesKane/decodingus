//! External-identifier index (`core.biosample_identifier`) — the deterministic dedup
//! key that overloaded `accession`/`alias` can't be. One physical donor recurs across
//! vendor namespaces (FTDNA kit, YSEQ #, Dante, FGC, Nebula) and public catalogs (PGP,
//! IGSR/ENA); `(namespace, value)` resolves to exactly one sample.
//!
//! Privacy: vendor-kit rows are `is_public=false` — matched in the background, never on a
//! public read surface. See `proposals/biosample-identifier-dedup.md`.

use crate::DbError;
use sqlx::PgPool;
use std::collections::HashMap;
use uuid::Uuid;

/// A row staged for insertion into `core.biosample_identifier`.
#[derive(Debug, Clone)]
pub struct NewIdentifier {
    pub sample_guid: Uuid,
    pub namespace: String,
    pub value: String,
    pub is_public: bool,
    pub source: String,
}

/// Namespace privacy policy — the single source of truth shared by the manifest backfill,
/// the fed ingest, and the anchor dedup. Public/open-consent catalog ids (PGP is open-consent;
/// IGSR/ENA/SRA/BioSample/HGDP/SGDP are public) are displayable; every vendor kit namespace
/// (FTDNA/YSEQ/Dante/FGC/Nebula/…) is background-only — matched but never on a public surface.
pub fn is_public_namespace(namespace: &str) -> bool {
    matches!(
        namespace.trim().to_ascii_uppercase().as_str(),
        "PGP" | "IGSR" | "1000G" | "ENA" | "SRA" | "BIOSAMPLE" | "HGDP" | "SGDP"
    )
}

/// `accession -> sample_guid` for every live biosample — the manifest join map
/// (`subject_id == accession`). Loaded once; the caller resolves in memory.
pub async fn accession_to_guid(pool: &PgPool) -> Result<HashMap<String, Uuid>, DbError> {
    let rows: Vec<(String, Uuid)> = sqlx::query_as(
        "SELECT accession, sample_guid FROM core.biosample \
         WHERE NOT deleted AND accession IS NOT NULL",
    )
    .fetch_all(pool)
    .await?;
    Ok(rows.into_iter().collect())
}

/// Bulk-insert identifiers, unnested from parallel arrays in a single statement.
/// `ON CONFLICT (namespace, value) DO NOTHING` — idempotent and re-runnable; returns the
/// number of rows actually inserted (existing `(namespace,value)` pairs are skipped).
pub async fn insert_identifiers(pool: &PgPool, rows: &[NewIdentifier]) -> Result<u64, DbError> {
    if rows.is_empty() {
        return Ok(0);
    }
    let guids: Vec<Uuid> = rows.iter().map(|r| r.sample_guid).collect();
    let namespaces: Vec<String> = rows.iter().map(|r| r.namespace.clone()).collect();
    let values: Vec<String> = rows.iter().map(|r| r.value.clone()).collect();
    let publics: Vec<bool> = rows.iter().map(|r| r.is_public).collect();
    let sources: Vec<String> = rows.iter().map(|r| r.source.clone()).collect();
    let n = sqlx::query(
        "INSERT INTO core.biosample_identifier (sample_guid, namespace, value, is_public, source) \
         SELECT * FROM unnest($1::uuid[], $2::text[], $3::text[], $4::bool[], $5::text[]) \
         ON CONFLICT (namespace, value) DO NOTHING",
    )
    .bind(&guids)
    .bind(&namespaces)
    .bind(&values)
    .bind(&publics)
    .bind(&sources)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n)
}

/// Resolve a biosample by an external identifier (the anchor-dedup lookup, Phase 3+).
/// Returns the matched `sample_guid`, or `None`.
pub async fn resolve(pool: &PgPool, namespace: &str, value: &str) -> Result<Option<Uuid>, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT sample_guid FROM core.biosample_identifier WHERE namespace = $1 AND value = $2",
    )
    .bind(namespace)
    .bind(value)
    .fetch_optional(pool)
    .await?)
}
