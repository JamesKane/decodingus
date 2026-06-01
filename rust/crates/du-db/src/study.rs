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
