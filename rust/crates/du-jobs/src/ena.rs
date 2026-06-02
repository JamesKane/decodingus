//! ENA study-enrichment job: fill gaps in `pubs.genomic_study` (title, center,
//! first-public) from the public ENA portal. Replaces the legacy Quartz
//! `EnaStudyEnrichment` worker. The ENA portal needs no credentials, so the job
//! always registers; it processes a bounded batch per run and is idempotent
//! (COALESCE only fills empty columns).

use du_db::PgPool;
use du_external::ena::EnaClient;

/// Per-run batch cap — keeps each daily run polite to the ENA portal.
const BATCH: i64 = 50;

pub async fn enrich_studies(pool: &PgPool, client: &EnaClient) -> anyhow::Result<()> {
    let candidates = du_db::study::needing_ena_enrichment(pool, BATCH).await?;
    if candidates.is_empty() {
        tracing::debug!("ena-study-enrichment: nothing to enrich");
        return Ok(());
    }
    let mut enriched = 0usize;
    for c in &candidates {
        match client.study(&c.accession).await {
            Ok(Some(meta)) => {
                du_db::study::apply_ena_metadata(
                    pool,
                    c.id,
                    meta.title.as_deref(),
                    meta.center_name.as_deref(),
                    meta.first_public,
                )
                .await?;
                enriched += 1;
            }
            Ok(None) => tracing::debug!(accession = %c.accession, "ena: no study found"),
            Err(e) => tracing::warn!(accession = %c.accession, error = %e, "ena fetch failed"),
        }
    }
    tracing::info!(candidates = candidates.len(), enriched, "ena-study-enrichment done");
    Ok(())
}
