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
    tracing::info!(
        candidates = candidates.len(),
        enriched,
        "ena-study-enrichment done"
    );
    Ok(())
}

/// Per-run batch cap for the base-count backfill. One ENA call per run, so this bounds both the
/// job's runtime and its politeness footprint; run it repeatedly until it reports zero.
const BACKFILL_BATCH: i64 = 200;

/// Politeness gap between per-run ENA calls, matching the study crawl's.
const BACKFILL_GAP: std::time::Duration = std::time::Duration::from_millis(150);

/// Backfill `genomics.sequence_library.base_count` from ENA for crawled runs that predate the
/// column (migration `0076`).
///
/// A re-crawl cannot do this: `sequence::ingest_libraries` is idempotent at *sample* granularity
/// and skips a sample that already has files, which is the property that keeps re-crawls cheap and
/// which we do not want to weaken for one column. So this fills the column directly, one run at a
/// time — `filereport` filters on whatever accession it is given, so a run accession returns just
/// that run.
///
/// Why it matters: the Grid pays per gigabase realigned (design §6.3), and a unit with no
/// `est_bases` pays the flat base rate — so until this has run, a 90 Gbp realignment earns what a
/// CRAM passthrough earns.
pub async fn backfill_base_counts(pool: &PgPool, client: &EnaClient) -> anyhow::Result<usize> {
    let pending = du_db::sequence::runs_missing_base_count(pool, BACKFILL_BATCH).await?;
    if pending.is_empty() {
        tracing::debug!("ena-base-count: nothing to backfill");
        return Ok(0);
    }
    let (mut filled, mut absent) = (0usize, 0usize);
    for row in &pending {
        match client.run_files(&row.run_accession).await {
            Ok(runs) => {
                // ENA reports `base_count` as a possibly-empty string; an empty one means the
                // submitter never supplied it, which is not an error and not something a retry
                // will fix. Leave the NULL rather than writing a zero that would read as "this
                // run sequenced nothing" and quietly pay a contributor for it.
                let bases = runs
                    .iter()
                    .find(|r| r.run_accession.trim() == row.run_accession)
                    .and_then(|r| super::crawl_project::parse_count(&r.base_count));
                match bases {
                    Some(n) if n > 0 => {
                        if du_db::sequence::set_base_count(pool, row.id, n).await? {
                            filled += 1;
                        }
                    }
                    _ => absent += 1,
                }
            }
            Err(e) => {
                tracing::warn!(run = %row.run_accession, error = %e, "ena-base-count: lookup failed")
            }
        }
        tokio::time::sleep(BACKFILL_GAP).await;
    }
    tracing::info!(
        examined = pending.len(),
        filled,
        absent,
        "ena-base-count: batch done (re-run until examined is 0)"
    );
    Ok(filled)
}
