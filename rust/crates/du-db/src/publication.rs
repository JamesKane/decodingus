//! Queries for `pubs.publication` (the references listing) + publication jobs
//! (OpenAlex enrichment, discovery candidates).

use crate::{DbError, Page};
use chrono::NaiveDate;
use du_domain::ids::PublicationId;
use du_domain::publication::Publication;
use sqlx::PgPool;

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

/// Enabled discovery search queries.
pub async fn enabled_search_configs(pool: &PgPool) -> Result<Vec<String>, DbError> {
    let rows: Vec<String> = sqlx::query_scalar(
        "SELECT search_query FROM pubs.publication_search_config WHERE enabled = true AND search_query IS NOT NULL",
    )
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// Upsert a discovery candidate by OpenAlex id (preserves curator status/review).
#[allow(clippy::too_many_arguments)]
pub async fn upsert_candidate(
    pool: &PgPool,
    openalex_id: &str,
    doi: Option<&str>,
    title: Option<&str>,
    abstract_summary: Option<&str>,
    publication_date: Option<NaiveDate>,
    journal_name: Option<&str>,
) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO pubs.publication_candidate \
           (openalex_id, doi, title, abstract, publication_date, journal_name, status) \
         VALUES ($1, $2, $3, $4, $5, $6, 'pending') \
         ON CONFLICT (openalex_id) DO UPDATE SET doi = EXCLUDED.doi, title = EXCLUDED.title, \
           abstract = EXCLUDED.abstract, publication_date = EXCLUDED.publication_date, \
           journal_name = EXCLUDED.journal_name",
    )
    .bind(openalex_id)
    .bind(doi)
    .bind(title)
    .bind(abstract_summary)
    .bind(publication_date)
    .bind(journal_name)
    .execute(pool)
    .await?;
    Ok(())
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
