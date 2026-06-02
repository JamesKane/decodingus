//! Genomic studies with their linked biosamples. There is no direct study→
//! sample edge in the redesign; samples reach a study through its publications
//! (`pubs.publication_study` → `pubs.publication_biosample` → `core.biosample`).

use crate::DbError;
use sqlx::PgPool;

/// A study plus a brief list of its samples (as a JSONB array of
/// `{sample_guid, accession, source}`).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct StudyWithSamples {
    pub id: i64,
    pub accession: String,
    pub title: Option<String>,
    pub center_name: Option<String>,
    pub samples: serde_json::Value,
}

/// An ENA study that still lacks enriched metadata (title/center).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct EnaCandidate {
    pub id: i64,
    pub accession: String,
}

/// ENA studies missing a title or center name (oldest first), capped at `limit`.
/// The enrichment job fetches these from the ENA portal and fills the gaps.
pub async fn needing_ena_enrichment(pool: &PgPool, limit: i64) -> Result<Vec<EnaCandidate>, DbError> {
    let rows: Vec<EnaCandidate> = sqlx::query_as(
        "SELECT id, accession FROM pubs.genomic_study \
         WHERE source = 'ENA' AND (title IS NULL OR center_name IS NULL) \
         ORDER BY id LIMIT $1",
    )
    .bind(limit.clamp(1, 500))
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// Fill a study's gaps from ENA metadata. `COALESCE` only fills empty columns
/// (never clobbers curated values); `first_public` lands in `details` since it is
/// not the same as `submission_date`. Returns whether a row was updated.
pub async fn apply_ena_metadata(
    pool: &PgPool,
    id: i64,
    title: Option<&str>,
    center_name: Option<&str>,
    first_public: Option<chrono::NaiveDate>,
) -> Result<bool, DbError> {
    let n = sqlx::query(
        "UPDATE pubs.genomic_study SET \
           title = COALESCE(title, $2), \
           center_name = COALESCE(center_name, $3), \
           details = CASE WHEN $4::date IS NOT NULL \
                          THEN jsonb_set(details, '{ena_first_public}', to_jsonb($4::date::text)) \
                          ELSE details END \
         WHERE id = $1",
    )
    .bind(id)
    .bind(title)
    .bind(center_name)
    .bind(first_public)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n > 0)
}

pub async fn with_samples(pool: &PgPool) -> Result<Vec<StudyWithSamples>, DbError> {
    let rows: Vec<StudyWithSamples> = sqlx::query_as(
        "SELECT s.id, s.accession, s.title, s.center_name, \
                COALESCE(jsonb_agg(DISTINCT jsonb_build_object( \
                    'sample_guid', b.sample_guid, 'accession', b.accession, 'source', b.source::text)) \
                  FILTER (WHERE b.sample_guid IS NOT NULL), '[]'::jsonb) AS samples \
         FROM pubs.genomic_study s \
         LEFT JOIN pubs.publication_study ps ON ps.study_id = s.id \
         LEFT JOIN pubs.publication_biosample pb ON pb.publication_id = ps.publication_id \
         LEFT JOIN core.biosample b ON b.sample_guid = pb.sample_guid AND b.deleted = false \
         GROUP BY s.id, s.accession, s.title, s.center_name \
         ORDER BY s.accession",
    )
    .fetch_all(pool)
    .await?;
    Ok(rows)
}
