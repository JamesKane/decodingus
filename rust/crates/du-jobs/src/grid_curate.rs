//! Grid curation: publish the claimable work list.
//!
//! This job turns samples the ENA crawl already resolved into `grid.work_unit` rows. It makes **no
//! network calls at all** — `crawl_project` did that, and the file URLs, md5s and sizes are already
//! in `genomics.sequence_file`. Curating centrally is also what keeps a volunteer fleet off the ENA
//! portal: a node is handed a finished manifest and never goes discovering files for itself.
//!
//! Design: `documents/design/distributed-compute-grid.md` §4.5 in the DUNavigator repo.
//!
//! Idempotent. A refresh updates a unit's manifest and sizes but never its `state`,
//! `required_replicas` or canonical digest — curation describes the *input*, validation owns the
//! *lifecycle*, and a re-crawl must not un-canonicalise a finished unit.

use du_db::grid::{self, NewWorkUnit};
use du_db::PgPool;

/// Per-run cap. Curation is cheap (one query, then one upsert per sample), but a bounded batch
/// keeps a first run over a large catalogue from holding the job lock for an unbounded time.
const BATCH: i64 = 500;

/// What one curation pass did.
pub struct CurateOutcome {
    pub examined: usize,
    pub published: usize,
    /// Units that carry no `est_bases`. The per-Gbp credit term has nothing to weigh these by;
    /// see [`grid::curation_candidates`] for why, and `documents/…/distributed-compute-grid.md`
    /// §12.4 for the fix.
    pub without_est_bases: usize,
    pub cram: usize,
    pub fastq: usize,
}

/// Publish work units for samples that do not have one yet.
///
/// `only_new = false` re-projects every eligible sample, which refreshes manifests after a
/// re-crawl. That is the ops path; the timer runs the incremental one.
pub async fn curate(pool: &PgPool, only_new: bool) -> anyhow::Result<CurateOutcome> {
    let candidates = grid::curation_candidates(pool, only_new, BATCH).await?;
    let mut out = CurateOutcome {
        examined: candidates.len(),
        published: 0,
        without_est_bases: 0,
        cram: 0,
        fastq: 0,
    };
    if candidates.is_empty() {
        tracing::debug!("grid-curate: nothing to publish");
        return Ok(out);
    }

    for c in &candidates {
        if c.est_bases.is_none() {
            out.without_est_bases += 1;
        }
        match c.data_kind.as_str() {
            "CRAM" => out.cram += 1,
            _ => out.fastq += 1,
        }
        let unit = NewWorkUnit {
            sample_accession: c.sample_accession.clone(),
            study_accession: c.study_accession.clone(),
            data_kind: c.data_kind.clone(),
            manifest: c.manifest.clone(),
            est_bases: c.est_bases,
            total_bytes: c.total_bytes,
        };
        // One bad sample must not sink the batch: the catalogue is a best-effort projection, and a
        // sample that fails to publish is simply picked up by the next run.
        match grid::upsert_work_unit(pool, &unit).await {
            Ok(_) => out.published += 1,
            Err(e) => {
                tracing::warn!(sample = %c.sample_accession, error = %e, "grid-curate: upsert failed")
            }
        }
    }

    tracing::info!(
        examined = out.examined,
        published = out.published,
        cram = out.cram,
        fastq = out.fastq,
        without_est_bases = out.without_est_bases,
        "grid-curate: done"
    );
    // Say the quiet part out loud rather than leaving a silent hole in the credit formula.
    if out.without_est_bases > 0 {
        tracing::warn!(
            count = out.without_est_bases,
            "grid-curate: units published with no est_bases — the per-Gbp credit term cannot weigh \
             them. ENA exposes base_count; du-external's RUN_FIELDS does not request it."
        );
    }
    Ok(out)
}
