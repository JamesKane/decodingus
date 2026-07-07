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

// ── project crawl (curator attaches a study/project to a publication) ─────────

/// A study queued for a run crawl.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct StudyCrawl {
    pub id: i64,
    pub accession: String,
}

/// The `pubs.study_source` for an accession: NCBI BioProjects are `PRJNA…`,
/// everything else (ENA `PRJEB…`/`ERP…`, INSDC) is treated as `ENA` — which is
/// also the repo we crawl through (ENA mirrors INSDC).
pub fn source_for_accession(accession: &str) -> &'static str {
    if accession.trim().to_ascii_uppercase().starts_with("PRJNA") {
        "NCBI_BIOPROJECT"
    } else {
        "ENA"
    }
}

/// Ensure a study row exists for `accession`; returns its id. Existing metadata is
/// left untouched (insert-or-get, no dead tuple on the hot conflict path).
pub async fn upsert_by_accession(pool: &PgPool, accession: &str, source: &str) -> Result<i64, DbError> {
    let acc = accession.trim();
    let id: i64 = sqlx::query_scalar(
        "WITH ins AS ( \
             INSERT INTO pubs.genomic_study (accession, source) \
             VALUES ($1, $2::pubs.study_source) ON CONFLICT (accession) DO NOTHING RETURNING id \
         ) \
         SELECT id FROM ins \
         UNION ALL SELECT id FROM pubs.genomic_study WHERE accession = $1 LIMIT 1",
    )
    .bind(acc)
    .bind(source)
    .fetch_one(pool)
    .await?;
    Ok(id)
}

/// Link a study to a publication (idempotent).
pub async fn link_publication(pool: &PgPool, publication_id: i64, study_id: i64) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO pubs.publication_study (publication_id, study_id) VALUES ($1, $2) \
         ON CONFLICT DO NOTHING",
    )
    .bind(publication_id)
    .bind(study_id)
    .execute(pool)
    .await?;
    Ok(())
}

/// Queue a study for crawling (re-queues a previously crawled study — used when a
/// study is attached to a new publication so that paper picks up the samples).
pub async fn request_crawl(pool: &PgPool, study_id: i64) -> Result<(), DbError> {
    sqlx::query(
        "UPDATE pubs.genomic_study \
         SET crawl_status = 'pending', crawl_requested_at = now(), crawl_error = NULL \
         WHERE id = $1",
    )
    .bind(study_id)
    .execute(pool)
    .await?;
    Ok(())
}

/// Studies awaiting a crawl (oldest request first), capped at `limit`.
pub async fn pending_crawls(pool: &PgPool, limit: i64) -> Result<Vec<StudyCrawl>, DbError> {
    let rows: Vec<StudyCrawl> = sqlx::query_as(
        "SELECT id, accession FROM pubs.genomic_study \
         WHERE crawl_status = 'pending' ORDER BY crawl_requested_at NULLS FIRST, id LIMIT $1",
    )
    .bind(limit.clamp(1, 1000))
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// The publications currently linked to a study (crawl links their samples to each).
pub async fn publications_for_study(pool: &PgPool, study_id: i64) -> Result<Vec<i64>, DbError> {
    let ids: Vec<i64> =
        sqlx::query_scalar("SELECT publication_id FROM pubs.publication_study WHERE study_id = $1")
            .bind(study_id)
            .fetch_all(pool)
            .await?;
    Ok(ids)
}

/// Record a crawl's terminal state: `done` (with counts) or `error` (with message).
pub async fn mark_crawled(
    pool: &PgPool,
    study_id: i64,
    sample_count: i32,
    run_count: i32,
    error: Option<&str>,
) -> Result<(), DbError> {
    sqlx::query(
        "UPDATE pubs.genomic_study \
         SET crawl_status = CASE WHEN $4::text IS NULL THEN 'done' ELSE 'error' END, \
             crawled_at = now(), sample_count = $2, run_count = $3, crawl_error = $4 \
         WHERE id = $1",
    )
    .bind(study_id)
    .bind(sample_count)
    .bind(run_count)
    .bind(error)
    .execute(pool)
    .await?;
    Ok(())
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
