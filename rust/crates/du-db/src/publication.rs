//! Queries for `pubs.publication` (the references listing).

use crate::{DbError, Page};
use du_domain::ids::PublicationId;
use du_domain::publication::Publication;
use sqlx::PgPool;

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
