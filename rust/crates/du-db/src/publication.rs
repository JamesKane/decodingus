//! Queries for `pubs.publication` (the references listing) + publication jobs
//! (OpenAlex enrichment, discovery candidates).

use crate::{DbError, Page};
use chrono::NaiveDate;
use du_domain::ids::PublicationId;
use du_domain::publication::Publication;
use sqlx::types::chrono::{DateTime, Utc};
use sqlx::PgPool;
use uuid::Uuid;

/// Fields the OpenAlex enrichment job updates (COALESCE'd — nulls don't wipe).
#[derive(Debug, Default, Clone)]
pub struct OpenAlexUpdate {
    pub openalex_id: Option<String>,
    pub journal: Option<String>,
    pub publication_date: Option<NaiveDate>,
    pub cited_by_count: Option<i32>,
    pub open_access_status: Option<String>,
    pub abstract_summary: Option<String>,
}

/// All publications that have a DOI (the enrichment job's work-list).
pub async fn dois(pool: &PgPool) -> Result<Vec<(PublicationId, String)>, DbError> {
    let rows: Vec<(i64, String)> =
        sqlx::query_as("SELECT id, doi FROM pubs.publication WHERE doi IS NOT NULL ORDER BY id")
            .fetch_all(pool)
            .await?;
    Ok(rows.into_iter().map(|(id, doi)| (PublicationId(id), doi)).collect())
}

/// Apply OpenAlex enrichment (only overwrites a column when the new value is set).
pub async fn update_openalex(pool: &PgPool, id: PublicationId, u: &OpenAlexUpdate) -> Result<bool, DbError> {
    let affected = sqlx::query(
        "UPDATE pubs.publication SET \
           open_alex_id = COALESCE($2, open_alex_id), \
           journal = COALESCE($3, journal), \
           publication_date = COALESCE($4, publication_date), \
           cited_by_count = COALESCE($5, cited_by_count), \
           open_access_status = COALESCE($6, open_access_status), \
           abstract_summary = COALESCE($7, abstract_summary), \
           updated_at = now() \
         WHERE id = $1",
    )
    .bind(id.0)
    .bind(&u.openalex_id)
    .bind(&u.journal)
    .bind(u.publication_date)
    .bind(u.cited_by_count)
    .bind(&u.open_access_status)
    .bind(&u.abstract_summary)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(affected > 0)
}

/// Fields the PubMed (NCBI) enrichment job fills. Gap-fill semantics: only
/// populates an empty column (never overwrites curated/OpenAlex values).
#[derive(Debug, Default, Clone)]
pub struct PubMedUpdate {
    pub journal: Option<String>,
    pub publication_date: Option<NaiveDate>,
    pub authors: Option<String>,
    pub doi: Option<String>,
}

/// Publications that have a PMID but still lack journal/authors/date/doi —
/// the PubMed enrichment job's work-list, oldest first, capped at `limit`.
pub async fn pmids_needing_enrichment(
    pool: &PgPool,
    limit: i64,
) -> Result<Vec<(PublicationId, String)>, DbError> {
    let rows: Vec<(i64, String)> = sqlx::query_as(
        "SELECT id, pubmed_id FROM pubs.publication \
         WHERE pubmed_id IS NOT NULL \
           AND (journal IS NULL OR authors IS NULL OR publication_date IS NULL OR doi IS NULL) \
         ORDER BY id LIMIT $1",
    )
    .bind(limit.clamp(1, 500))
    .fetch_all(pool)
    .await?;
    Ok(rows.into_iter().map(|(id, p)| (PublicationId(id), p)).collect())
}

/// Apply PubMed enrichment, filling only empty columns. The DOI is set only when
/// the row has none AND the value isn't already taken (DOI is UNIQUE — this avoids
/// a constraint violation aborting the batch).
pub async fn update_pubmed(pool: &PgPool, id: PublicationId, u: &PubMedUpdate) -> Result<bool, DbError> {
    let affected = sqlx::query(
        "UPDATE pubs.publication SET \
           journal = COALESCE(journal, $2), \
           publication_date = COALESCE(publication_date, $3), \
           authors = COALESCE(authors, $4), \
           doi = CASE WHEN doi IS NULL AND $5 IS NOT NULL \
                        AND NOT EXISTS (SELECT 1 FROM pubs.publication p2 WHERE p2.doi = $5 AND p2.id <> $1) \
                      THEN $5 ELSE doi END, \
           updated_at = now() \
         WHERE id = $1",
    )
    .bind(id.0)
    .bind(&u.journal)
    .bind(u.publication_date)
    .bind(&u.authors)
    .bind(&u.doi)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(affected > 0)
}

/// An enabled discovery search plus its recency watermark (the max
/// `publication_date` ingested so far; `None` until the first run completes).
#[derive(Debug, Clone)]
pub struct SearchConfig {
    pub id: i64,
    pub query: String,
    pub last_publication_date: Option<NaiveDate>,
}

/// Enabled discovery searches, each with its incremental watermark.
pub async fn enabled_search_configs(pool: &PgPool) -> Result<Vec<SearchConfig>, DbError> {
    let rows: Vec<(i64, String, Option<NaiveDate>)> = sqlx::query_as(
        "SELECT id, search_query, last_publication_date FROM pubs.publication_search_config \
         WHERE enabled = true AND search_query IS NOT NULL ORDER BY id",
    )
    .fetch_all(pool)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(id, query, last_publication_date)| SearchConfig { id, query, last_publication_date })
        .collect())
}

/// Upsert a discovery candidate by OpenAlex id (preserves curator status/review).
/// Returns `true` when the row was newly inserted (vs an update of one already seen).
#[allow(clippy::too_many_arguments)]
pub async fn upsert_candidate(
    pool: &PgPool,
    openalex_id: &str,
    doi: Option<&str>,
    title: Option<&str>,
    abstract_summary: Option<&str>,
    publication_date: Option<NaiveDate>,
    journal_name: Option<&str>,
) -> Result<bool, DbError> {
    // `xmax = 0` distinguishes a fresh INSERT from a DO UPDATE on conflict.
    let inserted: bool = sqlx::query_scalar(
        "INSERT INTO pubs.publication_candidate \
           (openalex_id, doi, title, abstract, publication_date, journal_name, status) \
         VALUES ($1, $2, $3, $4, $5, $6, 'pending') \
         ON CONFLICT (openalex_id) DO UPDATE SET doi = EXCLUDED.doi, title = EXCLUDED.title, \
           abstract = EXCLUDED.abstract, publication_date = EXCLUDED.publication_date, \
           journal_name = EXCLUDED.journal_name \
         RETURNING (xmax = 0)",
    )
    .bind(openalex_id)
    .bind(doi)
    .bind(title)
    .bind(abstract_summary)
    .bind(publication_date)
    .bind(journal_name)
    .fetch_one(pool)
    .await?;
    Ok(inserted)
}

/// Advance a config's watermark after a completed run: bump `last_run_at` and raise
/// `last_publication_date` to the newest date seen (`GREATEST` ignores NULLs, so a
/// run that found nothing new leaves the existing mark intact).
pub async fn advance_search_watermark(
    pool: &PgPool,
    config_id: i64,
    max_publication_date: Option<NaiveDate>,
) -> Result<(), DbError> {
    sqlx::query(
        "UPDATE pubs.publication_search_config \
         SET last_run_at = now(), last_publication_date = GREATEST(last_publication_date, $2) \
         WHERE id = $1",
    )
    .bind(config_id)
    .bind(max_publication_date)
    .execute(pool)
    .await?;
    Ok(())
}

/// Record one discovery run (audit trail; `error` set only on failure).
#[allow(clippy::too_many_arguments)]
pub async fn record_search_run(
    pool: &PgPool,
    config_id: i64,
    from_publication_date: NaiveDate,
    pages_fetched: i32,
    candidates_seen: i32,
    candidates_new: i32,
    error: Option<&str>,
) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO pubs.publication_search_run \
           (config_id, from_publication_date, pages_fetched, candidates_seen, candidates_new, error) \
         VALUES ($1, $2, $3, $4, $5, $6)",
    )
    .bind(config_id)
    .bind(from_publication_date)
    .bind(pages_fetched)
    .bind(candidates_seen)
    .bind(candidates_new)
    .bind(error)
    .execute(pool)
    .await?;
    Ok(())
}

/// Link a biosample to a publication (idempotent). Used by the project crawl to
/// attach a study's imported samples to the paper(s) that study belongs to.
pub async fn link_biosample(
    pool: &PgPool,
    publication_id: PublicationId,
    sample_guid: Uuid,
) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO pubs.publication_biosample (publication_id, sample_guid) VALUES ($1, $2) \
         ON CONFLICT DO NOTHING",
    )
    .bind(publication_id.0)
    .bind(sample_guid)
    .execute(pool)
    .await?;
    Ok(())
}

/// The promoted publication id for a candidate — the row `promote_candidate` would
/// reuse (matched on OpenAlex id or DOI). `None` until the candidate is accepted.
/// Lets the curator attach projects to a paper straight from the review panel.
pub async fn publication_for_candidate(
    pool: &PgPool,
    candidate_id: i64,
) -> Result<Option<PublicationId>, DbError> {
    let id: Option<i64> = sqlx::query_scalar(
        "SELECT p.id FROM pubs.publication_candidate c \
         JOIN pubs.publication p \
           ON p.open_alex_id = c.openalex_id OR (c.doi IS NOT NULL AND p.doi = c.doi) \
         WHERE c.id = $1 LIMIT 1",
    )
    .bind(candidate_id)
    .fetch_optional(pool)
    .await?;
    Ok(id.map(PublicationId))
}

/// Whether a publication with this DOI already exists in the catalog.
pub async fn exists_by_doi(pool: &PgPool, doi: &str) -> Result<bool, DbError> {
    let n: i64 = sqlx::query_scalar("SELECT count(*) FROM pubs.publication WHERE doi = $1")
        .bind(doi)
        .fetch_one(pool)
        .await?;
    Ok(n > 0)
}

// ── discovery candidate review queue ────────────────────────────────────────

/// A discovery candidate awaiting editorial review.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct Candidate {
    pub id: i64,
    pub openalex_id: String,
    pub doi: Option<String>,
    pub title: Option<String>,
    pub abstract_text: Option<String>,
    pub publication_date: Option<NaiveDate>,
    pub journal_name: Option<String>,
    pub relevance_score: Option<f64>,
    pub status: String,
    pub created_at: DateTime<Utc>,
}

const CAND_COLS: &str = "id, openalex_id, doi, title, abstract AS abstract_text, publication_date, \
    journal_name, relevance_score::float8 AS relevance_score, status, created_at";

/// Paginated candidate queue, optionally filtered by status, newest first.
pub async fn list_candidates(
    pool: &PgPool,
    status: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<Candidate>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let status = status.filter(|s| !s.is_empty());
    let where_sql = "WHERE ($1::text IS NULL OR status = $1)";
    let total: i64 =
        sqlx::query_scalar(&format!("SELECT count(*) FROM pubs.publication_candidate {where_sql}"))
            .bind(status)
            .fetch_one(pool)
            .await?;
    let items: Vec<Candidate> = sqlx::query_as(&format!(
        "SELECT {CAND_COLS} FROM pubs.publication_candidate {where_sql} \
         ORDER BY created_at DESC, id DESC LIMIT $2 OFFSET $3"
    ))
    .bind(status)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

pub async fn get_candidate(pool: &PgPool, id: i64) -> Result<Option<Candidate>, DbError> {
    Ok(sqlx::query_as(&format!("SELECT {CAND_COLS} FROM pubs.publication_candidate WHERE id = $1"))
        .bind(id)
        .fetch_optional(pool)
        .await?)
}

/// Set a candidate's review status (`accepted`/`rejected`/`deferred`) and the
/// reviewing curator. Returns whether a row changed.
pub async fn review_candidate(
    pool: &PgPool,
    id: i64,
    status: &str,
    reviewed_by: Uuid,
) -> Result<bool, DbError> {
    let n = sqlx::query("UPDATE pubs.publication_candidate SET status = $2, reviewed_by = $3 WHERE id = $1")
        .bind(id)
        .bind(status)
        .bind(reviewed_by)
        .execute(pool)
        .await?
        .rows_affected();
    Ok(n > 0)
}

/// **Promote** a candidate to a real `pubs.publication`: reuse an existing
/// publication matching the candidate's OpenAlex id or DOI, else create one from
/// the candidate's metadata; then mark the candidate `accepted`. Returns the
/// publication id. Errors if the candidate has no title (publications require one).
pub async fn promote_candidate(pool: &PgPool, id: i64, by: Uuid) -> Result<PublicationId, DbError> {
    let mut tx = pool.begin().await?;
    let c: Candidate = sqlx::query_as(&format!(
        "SELECT {CAND_COLS} FROM pubs.publication_candidate WHERE id = $1 FOR UPDATE"
    ))
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| DbError::Conflict(format!("candidate {id} not found")))?;
    let title = c
        .title
        .as_deref()
        .map(str::trim)
        .filter(|t| !t.is_empty())
        .ok_or_else(|| DbError::Conflict("candidate has no title — cannot promote".into()))?;

    // Reuse an existing publication by OpenAlex id or DOI; else insert.
    let existing: Option<i64> = sqlx::query_scalar(
        "SELECT id FROM pubs.publication WHERE open_alex_id = $1 OR ($2::text IS NOT NULL AND doi = $2) LIMIT 1",
    )
    .bind(&c.openalex_id)
    .bind(c.doi.as_deref())
    .fetch_optional(&mut *tx)
    .await?;
    let pub_id: i64 = match existing {
        Some(pid) => pid,
        None => {
            sqlx::query_scalar(
                "INSERT INTO pubs.publication (open_alex_id, doi, title, journal, publication_date, abstract_summary) \
                 VALUES ($1, $2, $3, $4, $5, $6) RETURNING id",
            )
            .bind(&c.openalex_id)
            .bind(c.doi.as_deref())
            .bind(title)
            .bind(c.journal_name.as_deref())
            .bind(c.publication_date)
            .bind(c.abstract_text.as_deref())
            .fetch_one(&mut *tx)
            .await?
        }
    };

    sqlx::query("UPDATE pubs.publication_candidate SET status = 'accepted', reviewed_by = $2 WHERE id = $1")
        .bind(id)
        .bind(by)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(PublicationId(pub_id))
}

#[derive(sqlx::FromRow)]
struct PublicationRow {
    id: i64,
    title: String,
    doi: Option<String>,
    pubmed_id: Option<String>,
    journal: Option<String>,
    publication_date: Option<chrono::NaiveDate>,
    authors: Option<String>,
    abstract_summary: Option<String>,
    url: Option<String>,
    cited_by_count: Option<i32>,
    open_access_status: Option<String>,
}

impl From<PublicationRow> for Publication {
    fn from(r: PublicationRow) -> Self {
        Publication {
            id: PublicationId(r.id),
            title: r.title,
            doi: r.doi,
            pubmed_id: r.pubmed_id,
            journal: r.journal,
            publication_date: r.publication_date,
            authors: r.authors,
            abstract_summary: r.abstract_summary,
            url: r.url,
            cited_by_count: r.cited_by_count,
            open_access_status: r.open_access_status,
        }
    }
}

const SELECT: &str = "SELECT id, title, doi, pubmed_id, journal, publication_date, authors, \
    abstract_summary, url, cited_by_count, open_access_status FROM pubs.publication";

pub async fn get_by_id(pool: &PgPool, id: PublicationId) -> Result<Option<Publication>, DbError> {
    let row: Option<PublicationRow> = sqlx::query_as(&format!("{SELECT} WHERE id = $1"))
        .bind(id.0)
        .fetch_optional(pool)
        .await?;
    Ok(row.map(Into::into))
}

/// Paginated list, optionally filtered by title/journal/DOI substring, newest first.
pub async fn search(
    pool: &PgPool,
    query: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<Publication>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let term = query.map(str::trim).filter(|q| !q.is_empty());

    const FILTER: &str = "WHERE title ILIKE $1 OR journal ILIKE $1 OR doi ILIKE $1";
    const ORDER: &str = "ORDER BY publication_date DESC NULLS LAST, id DESC";

    let (total, rows): (i64, Vec<PublicationRow>) = if let Some(t) = term {
        let like = format!("%{t}%");
        let total: i64 =
            sqlx::query_scalar(&format!("SELECT count(*) FROM pubs.publication {FILTER}"))
                .bind(&like)
                .fetch_one(pool)
                .await?;
        let rows = sqlx::query_as(&format!("{SELECT} {FILTER} {ORDER} LIMIT $2 OFFSET $3"))
            .bind(&like)
            .bind(limit)
            .bind(offset)
            .fetch_all(pool)
            .await?;
        (total, rows)
    } else {
        let total: i64 = sqlx::query_scalar("SELECT count(*) FROM pubs.publication")
            .fetch_one(pool)
            .await?;
        let rows = sqlx::query_as(&format!("{SELECT} {ORDER} LIMIT $1 OFFSET $2"))
            .bind(limit)
            .bind(offset)
            .fetch_all(pool)
            .await?;
        (total, rows)
    };

    Ok(Page {
        items: rows.into_iter().map(Into::into).collect(),
        total,
        page: page.max(1),
        page_size: limit,
    })
}
