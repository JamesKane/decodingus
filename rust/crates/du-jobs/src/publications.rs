//! Publication jobs: OpenAlex enrichment (by DOI) and discovery (by search
//! config). Rate-limited under OpenAlex's 10 req/s limit. Enabled only when
//! `OPENALEX_MAILTO` is set (the polite-pool identifier).

use du_db::publication::OpenAlexUpdate;
use du_db::PgPool;
use du_external::openalex::{OpenAlexClient, WorkMeta};
use std::time::Duration;

/// ~6.7 req/s, comfortably under OpenAlex's 10/s.
const REQUEST_GAP: Duration = Duration::from_millis(150);

pub struct Config {
    pub mailto: String,
}

impl Config {
    pub fn from_env() -> Option<Config> {
        std::env::var("OPENALEX_MAILTO").ok().filter(|s| !s.is_empty()).map(|mailto| Config { mailto })
    }
}

fn to_update(m: WorkMeta) -> OpenAlexUpdate {
    OpenAlexUpdate {
        openalex_id: m.openalex_id,
        journal: m.journal,
        publication_date: m.publication_date,
        cited_by_count: m.cited_by_count,
        open_access_status: m.open_access_status,
        abstract_summary: m.abstract_summary,
    }
}

/// Refresh OpenAlex metadata for every publication with a DOI.
pub async fn update_all(pool: &PgPool, client: &OpenAlexClient) -> anyhow::Result<()> {
    let dois = du_db::publication::dois(pool).await?;
    let total = dois.len();
    let (mut updated, mut missing, mut failed) = (0usize, 0usize, 0usize);
    for (id, doi) in dois {
        match client.work_by_doi(&doi).await {
            Ok(Some(meta)) => {
                du_db::publication::update_openalex(pool, id, &to_update(meta)).await?;
                updated += 1;
            }
            Ok(None) => missing += 1,
            Err(e) => {
                tracing::warn!(%doi, error = %e, "openalex fetch failed");
                failed += 1;
            }
        }
        tokio::time::sleep(REQUEST_GAP).await;
    }
    tracing::info!(total, updated, missing, failed, "publication-update done");
    Ok(())
}

/// Run each enabled discovery search and upsert the resulting candidates.
pub async fn discover(pool: &PgPool, client: &OpenAlexClient) -> anyhow::Result<()> {
    let configs = du_db::publication::enabled_search_configs(pool).await?;
    let mut candidates = 0usize;
    for query in configs {
        match client.search(&query, 50).await {
            Ok(cands) => {
                for c in cands {
                    du_db::publication::upsert_candidate(
                        pool,
                        &c.openalex_id,
                        c.doi.as_deref(),
                        c.title.as_deref(),
                        c.abstract_summary.as_deref(),
                        c.publication_date,
                        c.journal.as_deref(),
                    )
                    .await?;
                    candidates += 1;
                }
            }
            Err(e) => tracing::warn!(%query, error = %e, "openalex search failed"),
        }
        tokio::time::sleep(REQUEST_GAP).await;
    }
    tracing::info!(candidates, "publication-discovery done");
    Ok(())
}
