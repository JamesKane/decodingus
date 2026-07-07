//! Publication jobs: OpenAlex enrichment (by DOI) and discovery (by search
//! config). Rate-limited under OpenAlex's 10 req/s limit. Enabled only when
//! `OPENALEX_MAILTO` is set (the polite-pool identifier).

use chrono::{Duration as Days, NaiveDate, Utc};
use du_db::publication::{OpenAlexUpdate, SearchConfig};
use du_db::PgPool;
use du_external::openalex::{OpenAlexClient, WorkMeta};
use std::time::Duration;

/// ~6.7 req/s, comfortably under OpenAlex's 10/s.
const REQUEST_GAP: Duration = Duration::from_millis(150);

/// On a config's first run (no watermark yet), how far back to backfill.
const INITIAL_BACKFILL_DAYS: i64 = 365;
/// Overlap re-query window below the watermark, to catch papers OpenAlex indexed
/// late (published before the mark but added after the last run). Dedup by
/// `openalex_id` makes the re-scan harmless.
const WATERMARK_LOOKBACK_DAYS: i64 = 30;
/// OpenAlex's max page size.
const PER_PAGE: u32 = 200;
/// Safety bound on pages per config per run (200 × 40 = 8000 works — far beyond
/// any real daily delta; prevents a runaway loop on a pathological cursor).
const MAX_PAGES: usize = 40;

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

/// Run each enabled discovery search incrementally: paginate by publication date
/// (newest first) from the config's watermark, upserting candidates. Sorting by
/// date rather than relevance is the fix for missed new papers — OpenAlex relevance
/// is citation-weighted, so a fresh, uncited paper never ranks into a top page.
pub async fn discover(pool: &PgPool, client: &OpenAlexClient) -> anyhow::Result<()> {
    let configs = du_db::publication::enabled_search_configs(pool).await?;
    let today = Utc::now().date_naive();
    let mut total_new = 0usize;
    for cfg in configs {
        let from = match cfg.last_publication_date {
            Some(d) => d - Days::days(WATERMARK_LOOKBACK_DAYS),
            None => today - Days::days(INITIAL_BACKFILL_DAYS),
        };
        match run_search(pool, client, &cfg, from).await {
            Ok(o) => {
                // Advance the watermark only on a fully-scanned run, so a mid-run
                // failure can't skip the older (lower-page) results we never reached.
                du_db::publication::advance_search_watermark(pool, cfg.id, o.max_date).await?;
                du_db::publication::record_search_run(
                    pool, cfg.id, from, o.pages as i32, o.seen as i32, o.new as i32, None,
                )
                .await?;
                total_new += o.new;
                tracing::info!(config = %cfg.query, pages = o.pages, seen = o.seen, new = o.new, "discovery config done");
            }
            Err(e) => {
                tracing::warn!(query = %cfg.query, error = %e, "openalex search failed");
                du_db::publication::record_search_run(pool, cfg.id, from, 0, 0, 0, Some(&e.to_string())).await?;
            }
        }
        tokio::time::sleep(REQUEST_GAP).await;
    }
    tracing::info!(total_new, "publication-discovery done");
    Ok(())
}

struct SearchOutcome {
    pages: usize,
    seen: usize,
    new: usize,
    /// Newest publication_date seen this run (folds in the prior watermark).
    max_date: Option<NaiveDate>,
}

/// Cursor-paginate one config's search from `from` and upsert every candidate.
/// Returns on the first page error (bubbles up so the watermark isn't advanced).
async fn run_search(
    pool: &PgPool,
    client: &OpenAlexClient,
    cfg: &SearchConfig,
    from: NaiveDate,
) -> anyhow::Result<SearchOutcome> {
    let mut cursor = "*".to_string();
    let (mut pages, mut seen, mut new) = (0usize, 0usize, 0usize);
    let mut max_date = cfg.last_publication_date;
    loop {
        let page = client.search_page(&cfg.query, from, PER_PAGE, &cursor).await?;
        for c in &page.candidates {
            let inserted = du_db::publication::upsert_candidate(
                pool,
                &c.openalex_id,
                c.doi.as_deref(),
                c.title.as_deref(),
                c.abstract_summary.as_deref(),
                c.publication_date,
                c.journal.as_deref(),
            )
            .await?;
            seen += 1;
            if inserted {
                new += 1;
            }
            if let Some(d) = c.publication_date {
                max_date = Some(max_date.map_or(d, |m| m.max(d)));
            }
        }
        pages += 1;
        match page.next_cursor {
            Some(nc) if !nc.is_empty() && !page.candidates.is_empty() && pages < MAX_PAGES => {
                cursor = nc;
                tokio::time::sleep(REQUEST_GAP).await;
            }
            _ => {
                if pages >= MAX_PAGES {
                    tracing::warn!(config = %cfg.query, "discovery hit MAX_PAGES; older results deferred to next run");
                }
                break;
            }
        }
    }
    Ok(SearchOutcome { pages, seen, new, max_date })
}

// ── NCBI / PubMed enrichment (by PMID) ───────────────────────────────────────

/// ~3 req/s — NCBI's unauthenticated limit (an api_key raises it to 10/s).
const NCBI_GAP: Duration = Duration::from_millis(350);

pub struct NcbiConfig {
    pub email: String,
    pub api_key: Option<String>,
}

impl NcbiConfig {
    pub fn from_env() -> Option<NcbiConfig> {
        let email = std::env::var("NCBI_EMAIL").ok().filter(|s| !s.is_empty())?;
        Some(NcbiConfig { email, api_key: std::env::var("NCBI_API_KEY").ok().filter(|s| !s.is_empty()) })
    }
}

fn to_pubmed_update(m: du_external::ncbi::PubMedMeta) -> du_db::publication::PubMedUpdate {
    du_db::publication::PubMedUpdate {
        journal: m.journal,
        publication_date: m.publication_date,
        authors: m.authors,
        doi: m.doi,
    }
}

/// Fill metadata gaps (journal/authors/date/doi) for publications that have a
/// PMID, from PubMed. Complements `update_all` (which enriches by DOI).
pub async fn pubmed_update_all(pool: &PgPool, client: &du_external::ncbi::NcbiClient) -> anyhow::Result<()> {
    let pmids = du_db::publication::pmids_needing_enrichment(pool, 100).await?;
    let total = pmids.len();
    let (mut updated, mut missing, mut failed) = (0usize, 0usize, 0usize);
    for (id, pmid) in pmids {
        match client.pubmed_summary(&pmid).await {
            Ok(Some(meta)) => {
                du_db::publication::update_pubmed(pool, id, &to_pubmed_update(meta)).await?;
                updated += 1;
            }
            Ok(None) => missing += 1,
            Err(e) => {
                tracing::warn!(%pmid, error = %e, "pubmed fetch failed");
                failed += 1;
            }
        }
        tokio::time::sleep(NCBI_GAP).await;
    }
    tracing::info!(total, updated, missing, failed, "publication-pubmed-update done");
    Ok(())
}
