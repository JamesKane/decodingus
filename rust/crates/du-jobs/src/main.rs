//! Background job runner (replaces the Pekko Quartz scheduler). Registers the
//! scheduled workers and runs them until shutdown (Ctrl-C).
//!
//! The five legacy jobs (publication update + discovery via OpenAlex, YBrowse
//! variant ingest via du-bio, variant export, match discovery) are wired here as
//! their external clients land (du-external) and ingestion is built (du-bio). A
//! DB heartbeat is registered now to exercise the harness end-to-end.

use std::sync::Arc;
use std::time::Duration;

mod coord_lift;
mod ena;
mod faidx;
mod ftdna_str;
mod import_kit_identifiers;
mod jetstream;
mod publications;
mod scheduler;
mod ybrowse;
mod yregions;
use scheduler::{Job, Scheduler};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,du_jobs=debug".into()),
        )
        .init();

    let url = std::env::var("DATABASE_URL")
        .map_err(|_| anyhow::anyhow!("DATABASE_URL is required for the job runner"))?;
    // The scheduler fires several jobs concurrently (esp. the startup burst and the hourly
    // consensus/coverage ticks) alongside the long-lived Jetstream consumer, which acquires a
    // connection per upsert/cursor write. A pool of 4 starves under that; size it (overridable
    // via DU_JOBS_DB_POOL) so jobs + ingest coexist.
    let pool_size = std::env::var("DU_JOBS_DB_POOL")
        .ok()
        .and_then(|s| s.parse().ok())
        .filter(|&n| n > 0)
        .unwrap_or(16);
    let pool = du_db::connect(&url, pool_size).await?;

    let mut argv = std::env::args().skip(1);
    let mode = argv.next();

    // Standalone real-time consumer: `decodingus-jobs jetstream` runs ONLY the Jetstream
    // reporting mirror (no batch scheduler), so ingestion is never starved by — nor does a
    // restart re-trigger — the heavy periodic jobs (branch-age, consensus). Run this as the
    // always-on ingest service; run the batch jobs separately (run-once / a scheduler service).
    if mode.as_deref() == Some("jetstream") {
        let cfg = jetstream::Config::from_env().ok_or_else(|| anyhow::anyhow!("set JETSTREAM_URL"))?;
        tracing::info!(url = %cfg.url, collections = ?cfg.collections, "jetstream consumer (standalone)");
        tokio::select! {
            _ = jetstream::run(pool.clone(), cfg) => {}
            _ = tokio::signal::ctrl_c() => tracing::info!("jetstream consumer shutting down"),
        }
        return Ok(());
    }

    // One-shot mode: `du-jobs run-once <job>` runs a single job to completion and
    // exits, instead of starting the interval scheduler. For ops/backfills (e.g.
    // a full YBrowse GFF3 ingest + reconcile on demand, not on the 24h tick).
    if mode.as_deref() == Some("run-once") {
        let job = argv.next().unwrap_or_default();
        match job.as_str() {
            "ybrowse" => {
                let cfg = ybrowse::Config::from_env()
                    .ok_or_else(|| anyhow::anyhow!("set YBROWSE_GFF (+ optional chain paths)"))?;
                ybrowse::run(&pool, &cfg).await?;
            }
            // Re-derive core.variant from the already-loaded mirror (skips the
            // 770 MB GFF3 re-stream) — e.g. after curator name/merge edits.
            "reconcile" => {
                let rec = du_db::ybrowse::reconcile(&pool).await?;
                tracing::info!(
                    clusters = rec.clusters, created = rec.created, enriched = rec.enriched,
                    flagged = rec.flagged, region_flagged = rec.region_flagged, "reconcile complete"
                );
            }
            // Load the T2T-CHM13v2.0 (hs1) Y structural regions (AZF/DYZ/
            // ampliconic/palindrome/inverted-repeat) into core.genome_region.
            // One-shot: the source BEDs are tiny + versioned, not a daily feed.
            "yregions" => {
                yregions::run(&pool).await?;
            }
            // Recompute STR signatures + the combined branch ages (SNP tree
            // propagation + genealogical + combine) on demand.
            "branch-age" => {
                let s = du_db::ystr::recompute_signatures(&pool).await?;
                let c = du_db::age::recompute_combined_ages(&pool).await?;
                tracing::info!(
                    str_ages = s.age_estimates, snp = c.snp,
                    genealogical = c.genealogical, combined = c.combined,
                    "branch-age recompute complete"
                );
            }
            // Recompute sequencer instrument→lab consensus from the federation
            // (refresh observations → regenerate proposals). Curator-gated; sets
            // sequencer_instrument.lab_id only via accept (or opt-in auto-accept).
            "sequencer-consensus" => {
                let rep = du_db::sequencer::recompute_consensus(&pool, &du_db::sequencer::ConsensusConfig::default()).await?;
                tracing::info!(
                    instruments = rep.instruments, observations = rep.observations_upserted,
                    pruned = rep.observations_pruned, proposals = rep.proposals_active,
                    ready = rep.proposals_ready, conflicts = rep.conflicts,
                    auto_accepted = rep.auto_accepted, "sequencer-consensus complete"
                );
            }
            "discovery-consensus" => {
                let cfg = du_db::discovery::load_config(&pool).await?;
                let rep = du_db::discovery::recompute_consensus(&pool, &cfg).await?;
                tracing::info!(
                    bpv_upserted = rep.bpv_upserted, bpv_pruned = rep.bpv_pruned,
                    unresolved = rep.samples_unresolved, proposals = rep.proposals_active,
                    ready = rep.proposals_ready, split = rep.split_flagged,
                    auto_promoted = rep.auto_promoted, "discovery-consensus complete"
                );
            }
            "coverage-norms" => {
                let rep = du_db::coverage::recompute_norms(&pool).await?;
                tracing::info!(test_types = rep.test_types, pruned = rep.pruned, "coverage-norms complete");
            }
            "ibd-discovery-recompute" => {
                let rep = du_db::ibd::recompute_suggestions(&pool, &du_db::ibd::IbdConfig::default()).await?;
                tracing::info!(
                    samples = rep.samples, blocks = rep.blocks, population = rep.population_pairs,
                    haplogroup = rep.haplogroup_pairs, shared_match = rep.shared_match_pairs,
                    suggestions = rep.suggestions_written, "ibd-discovery-recompute complete"
                );
            }
            "exchange-expire" => {
                let (envelopes, sessions) = du_db::exchange::expire(&pool).await?;
                tracing::info!(envelopes, sessions, "exchange-expire complete");
            }
            // Tier-1 biosample duplicate candidates: block by terminal Y + mt and
            // refine with private-variant Jaccard into dedup.duplicate_candidate.
            // Candidates only — autosomal Tier-2 confirms (never auto-merges).
            "dedup-candidates" => {
                let rep = du_db::dedup::recompute_candidates(&pool, &du_db::dedup::DedupConfig::default()).await?;
                tracing::info!(
                    males = rep.males, multi_blocks = rep.multi_blocks, pairs = rep.pairs_evaluated,
                    written = rep.candidates_written, pruned = rep.candidates_pruned,
                    mt_match = rep.mt_match_pairs, jaccard = rep.jaccard_pairs,
                    "dedup-candidates complete"
                );
            }
            "tree-samples-recompute" => {
                let rep = du_db::tree_sample::recompute_placements(&pool, du_domain::enums::DnaType::YDna).await?;
                tracing::info!(placed = rep.placed, unplaced = rep.unplaced, "tree-samples-recompute complete (Y)");
            }
            // One-shot backfill: link every biosample of one individual — the multiple
            // publication accessions AND the de-novo tree tip — under a single donor,
            // pruning the redundant donor rows. The sample report then resolves the
            // haplogroup at the donor level (surfacing the tip's tree placement on every
            // accession). Grouped by shared panel id (tip.accession == ref.alias).
            // Previews unless `--apply`.
            "consolidate-donors" => {
                let apply = argv.any(|a| a == "--apply");
                let rep = du_db::donor::consolidate_denovo_donors(&pool, apply).await?;
                tracing::info!(
                    apply, groups = rep.groups, biosamples_repointed = rep.biosamples_repointed,
                    donors_pruned = rep.donors_pruned,
                    "consolidate-donors complete{}", if apply { "" } else { " (preview — pass --apply to consolidate)" }
                );
            }
            // Anchor federated subjects: mint a pseudonymous core.biosample (+ donor
            // carrying the published sex) for every fed.biosample lacking a core anchor,
            // linked by the record's at:// URI + repo DID. This is what surfaces a
            // live-published citizen's runs / coverage / ancestry / haplogroups in the
            // unified sample report (all gated on core.biosample.atproto->>'uri').
            // Idempotent; previews unless `--apply`.
            "link-federated-subjects" => {
                let apply = argv.any(|a| a == "--apply");
                let rep = du_db::fed_subject::link_federated_subjects(&pool, apply).await?;
                tracing::info!(
                    apply, unlinked = rep.unlinked, biosamples_created = rep.biosamples_created,
                    donors_created = rep.donors_created,
                    "link-federated-subjects complete{}", if apply { "" } else { " (preview — pass --apply to write)" }
                );
            }
            // Bulk-load FTDNA Y-STR exports into genomics.biosample_str_profile,
            // matched to the cohort by kit→subject_id. Feeds the STR-variance age
            // model (run `branch-age` after). Previews unless `--apply`.
            "ftdna-str" => {
                let args: Vec<String> = argv.collect();
                let cfg = ftdna_str::Config::from_env(&args).ok_or_else(|| {
                    anyhow::anyhow!("set FTDNA_STR_DIR + COHORT_MANIFEST")
                })?;
                ftdna_str::run(&pool, &cfg).await?;
            }
            // Backfill vendor kit identifiers (FTDNA/YSEQ/Dante/FGC/Nebula) from the
            // cohort manifest into core.biosample_identifier — the background dedup key
            // for federated re-publications. subject_id == biosample accession; one
            // (namespace=lab, value=kit) per kit, is_public=false. Does NOT touch the
            // UUID accession or MDKA. Previews unless `--apply`.
            "import-kit-identifiers" => {
                let args: Vec<String> = argv.collect();
                let cfg = import_kit_identifiers::Config::from_env(&args)
                    .ok_or_else(|| anyhow::anyhow!("set COHORT_MANIFEST"))?;
                let rep = import_kit_identifiers::run(&pool, &cfg).await?;
                tracing::info!(
                    apply = cfg.apply, rows_total = rep.rows_total, rows_matched = rep.rows_matched,
                    rows_unmatched = rep.rows_unmatched, multi_lab_rows = rep.multi_lab_rows,
                    staged = rep.staged, inserted = rep.inserted, fallback_ns = rep.fallback_ns,
                    per_namespace = ?rep.per_namespace,
                    dup_pairs = rep.dup_pairs, confirmed = rep.confirmed, disputed = rep.disputed,
                    unplaced = rep.unplaced, candidates_written = rep.candidates_written,
                    "import-kit-identifiers complete{}", if cfg.apply { "" } else { " (preview — pass --apply to write)" }
                );
            }
            // Backfill rCRS (NC_012920.1) coordinates onto the hs1-native mtDNA tree
            // variants, lifting each hs1 chrM position through the shared rotation-aware
            // CHM13 chrM↔rCRS map (du_bio::mt). Gives PhyloTree/MITOMAP-frame positions.
            // Idempotent; previews unless `--apply`.
            "mt-rcrs-lift" => {
                let apply = argv.any(|a| a == "--apply");
                let rep = du_db::variant::backfill_mt_rcrs_coordinates(&pool, apply).await?;
                tracing::info!(
                    apply, total = rep.total, lifted = rep.lifted, unmapped = rep.unmapped,
                    "mt-rcrs-lift complete{}", if apply { "" } else { " (preview — pass --apply to write)" }
                );
            }
            // Backfill missing Y-DNA build coordinates (GRCh37/hs1) by lifting each
            // variant's GRCh38 position via UCSC chains, reverse-complementing alleles
            // on inverted blocks and validating against the target reference base.
            // Chains/refs from ~/.decodingus (DU_LIFTOVER_DIR/DU_REFERENCE_DIR).
            // Previews unless `--apply`.
            "variant-coord-lift" => {
                let args: Vec<String> = argv.collect();
                let cfg = coord_lift::Config::from_env(&args);
                coord_lift::run(&pool, &cfg).await?;
            }
            other => anyhow::bail!(
                "unknown run-once job '{other}' (known: ybrowse, reconcile, yregions, branch-age, ftdna-str, sequencer-consensus, discovery-consensus, coverage-norms, ibd-discovery-recompute, exchange-expire, tree-samples-recompute, dedup-candidates, consolidate-donors, link-federated-subjects, import-kit-identifiers, mt-rcrs-lift, variant-coord-lift)"
            ),
        }
        return Ok(());
    }

    let mut sched = Scheduler::new();

    // DB heartbeat — proves the harness; also a cheap liveness signal.
    {
        let pool = pool.clone();
        sched.register(Job::new("db-heartbeat", Duration::from_secs(300), move || {
            let pool = pool.clone();
            async move {
                let variants = du_db::variant::search(&pool, None, 1, 1).await?.total;
                let publications = du_db::publication::search(&pool, None, 1, 1).await?.total;
                tracing::info!(variants, publications, "heartbeat");
                Ok(())
            }
        }));
    }

    // YBrowse variant ingest (GRCh38 VCF -> lift to GRCh37/hs1 -> core.variant).
    // Registered only when configured (YBROWSE_GFF + chain paths).
    if let Some(cfg) = ybrowse::Config::from_env() {
        let pool = pool.clone();
        sched.register(Job::new("ybrowse-variant-ingest", Duration::from_secs(86_400), move || {
            let pool = pool.clone();
            let cfg = cfg.clone();
            async move { ybrowse::run(&pool, &cfg).await }
        }));
        tracing::info!("ybrowse-variant-ingest registered");
    } else {
        tracing::info!("ybrowse-variant-ingest not configured (set YBROWSE_GFF + chain paths)");
    }

    // Publication jobs (OpenAlex). Enabled when OPENALEX_MAILTO is set.
    if let Some(cfg) = publications::Config::from_env() {
        let client = Arc::new(du_external::openalex::OpenAlexClient::new(Some(cfg.mailto)));
        {
            let (pool, client) = (pool.clone(), client.clone());
            sched.register(Job::new("publication-update", Duration::from_secs(86_400), move || {
                let (pool, client) = (pool.clone(), client.clone());
                async move { publications::update_all(&pool, &client).await }
            }));
        }
        {
            let (pool, client) = (pool.clone(), client.clone());
            sched.register(Job::new("publication-discovery", Duration::from_secs(86_400), move || {
                let (pool, client) = (pool.clone(), client.clone());
                async move { publications::discover(&pool, &client).await }
            }));
        }
        tracing::info!("publication jobs registered (OpenAlex)");
    } else {
        tracing::info!("publication jobs not configured (set OPENALEX_MAILTO)");
    }

    // PubMed (NCBI) enrichment by PMID — complements OpenAlex's by-DOI enrichment.
    if let Some(cfg) = publications::NcbiConfig::from_env() {
        let pool = pool.clone();
        let client = Arc::new(du_external::ncbi::NcbiClient::new(Some(cfg.email), cfg.api_key));
        sched.register(Job::new("publication-pubmed-update", Duration::from_secs(86_400), move || {
            let (pool, client) = (pool.clone(), client.clone());
            async move { publications::pubmed_update_all(&pool, &client).await }
        }));
        tracing::info!("publication-pubmed-update registered (NCBI)");
    } else {
        tracing::info!("publication-pubmed-update not configured (set NCBI_EMAIL)");
    }

    // ENA study enrichment — fills study metadata gaps from the public ENA
    // portal (no credentials needed, so always on).
    {
        let pool = pool.clone();
        let client = Arc::new(du_external::ena::EnaClient::new());
        sched.register(Job::new("ena-study-enrichment", Duration::from_secs(86_400), move || {
            let (pool, client) = (pool.clone(), client.clone());
            async move { ena::enrich_studies(&pool, &client).await }
        }));
        tracing::info!("ena-study-enrichment registered");
    }

    // Branch ages — recompute Y-STR modal signatures + STR-variance ages from the
    // mirrored profiles, then the combined age (STR + SNP-Poisson + genealogical,
    // gap-filling tmrca_ybp). One job to guarantee STR terms exist before the
    // combine. Depends only on the DB; always on.
    {
        let pool = pool.clone();
        sched.register(Job::new("branch-age-recompute", Duration::from_secs(86_400), move || {
            let pool = pool.clone();
            async move {
                let s = du_db::ystr::recompute_signatures(&pool).await?;
                let c = du_db::age::recompute_combined_ages(&pool).await?;
                tracing::info!(
                    haplogroups = s.haplogroups, markers = s.markers, str_ages = s.age_estimates,
                    snp = c.snp, genealogical = c.genealogical, combined = c.combined,
                    "branch-age-recompute done"
                );
                Ok(())
            }
        }));
        tracing::info!("branch-age-recompute registered");
    }

    // Sequencer instrument→lab consensus: refresh observations from the federation
    // and regenerate proposals for curator review. DB-only; always on.
    {
        let pool = pool.clone();
        sched.register(Job::new("sequencer-consensus", Duration::from_secs(3_600), move || {
            let pool = pool.clone();
            async move {
                let rep = du_db::sequencer::recompute_consensus(&pool, &du_db::sequencer::ConsensusConfig::default()).await?;
                tracing::info!(
                    instruments = rep.instruments, observations = rep.observations_upserted,
                    proposals = rep.proposals_active, ready = rep.proposals_ready,
                    conflicts = rep.conflicts, "sequencer-consensus done"
                );
                Ok(())
            }
        }));
        tracing::info!("sequencer-consensus registered");
    }

    // Haplogroup-discovery consensus: materialize federated private variants and pool
    // them into proposed branches by variant-set Jaccard for curator review. DB-only.
    {
        let pool = pool.clone();
        sched.register(Job::new("discovery-consensus", Duration::from_secs(3_600), move || {
            let pool = pool.clone();
            async move {
                let cfg = du_db::discovery::load_config(&pool).await?;
                let rep = du_db::discovery::recompute_consensus(&pool, &cfg).await?;
                tracing::info!(
                    bpv = rep.bpv_upserted, proposals = rep.proposals_active,
                    ready = rep.proposals_ready, split = rep.split_flagged,
                    auto_promoted = rep.auto_promoted, "discovery-consensus done"
                );
                Ok(())
            }
        }));
        tracing::info!("discovery-consensus registered");
    }

    // Empirical per-test-type coverage norms: derive the cohort median depth/coverage
    // (+ typical marker counts) from the federated coverage for conformance checks.
    {
        let pool = pool.clone();
        sched.register(Job::new("coverage-norms", Duration::from_secs(3_600), move || {
            let pool = pool.clone();
            async move {
                let rep = du_db::coverage::recompute_norms(&pool).await?;
                tracing::info!(test_types = rep.test_types, pruned = rep.pruned, "coverage-norms done");
                Ok(())
            }
        }));
        tracing::info!("coverage-norms registered");
    }

    // Federated-subject anchoring: mint a core.biosample anchor for every federated
    // subject the Jetstream consumer mirrored into fed.biosample, so their runs /
    // coverage / ancestry / haplogroups surface in the unified sample report (and
    // feed the downstream IBD / dedup jobs). Hourly; idempotent.
    {
        let pool = pool.clone();
        sched.register(Job::new("link-federated-subjects", Duration::from_secs(3_600), move || {
            let pool = pool.clone();
            async move {
                let rep = du_db::fed_subject::link_federated_subjects(&pool, true).await?;
                tracing::info!(
                    unlinked = rep.unlinked, biosamples_created = rep.biosamples_created,
                    donors_created = rep.donors_created, "link-federated-subjects done"
                );
                Ok(())
            }
        }));
        tracing::info!("link-federated-subjects registered");
    }

    // IBD candidate generation: mine introduction candidates from fed.* ancestry +
    // the match graph into ibd.match_suggestion (block + top-K; no genotypes). Daily.
    {
        let pool = pool.clone();
        sched.register(Job::new("ibd-discovery-recompute", Duration::from_secs(86_400), move || {
            let pool = pool.clone();
            async move {
                let rep = du_db::ibd::recompute_suggestions(&pool, &du_db::ibd::IbdConfig::default()).await?;
                tracing::info!(
                    samples = rep.samples, blocks = rep.blocks, suggestions = rep.suggestions_written,
                    "ibd-discovery-recompute done"
                );
                Ok(())
            }
        }));
        tracing::info!("ibd-discovery-recompute registered");
    }

    // Biosample duplicate candidates (Tier 1): block by terminal Y + mt, refine by
    // private-variant Jaccard into dedup.duplicate_candidate. Candidates only —
    // autosomal Tier-2 confirms (and is what separates duplicates from siblings). Daily.
    {
        let pool = pool.clone();
        sched.register(Job::new("dedup-candidates", Duration::from_secs(86_400), move || {
            let pool = pool.clone();
            async move {
                let rep = du_db::dedup::recompute_candidates(&pool, &du_db::dedup::DedupConfig::default()).await?;
                tracing::info!(
                    males = rep.males, multi_blocks = rep.multi_blocks,
                    written = rep.candidates_written, pruned = rep.candidates_pruned,
                    "dedup-candidates done"
                );
                Ok(())
            }
        }));
        tracing::info!("dedup-candidates registered");
    }

    // YFull-style leaf placement: resolve each non-D2C biosample's published Y call to a tree
    // node into tree.haplogroup_sample (counts + leaf lists). Daily safety net; also run-once
    // after an ETL tree/biosample load. (mt added when the mt tree lands.)
    {
        let pool = pool.clone();
        sched.register(Job::new("tree-samples-recompute", Duration::from_secs(86_400), move || {
            let pool = pool.clone();
            async move {
                let rep = du_db::tree_sample::recompute_placements(&pool, du_domain::enums::DnaType::YDna).await?;
                tracing::info!(placed = rep.placed, unplaced = rep.unplaced, "tree-samples-recompute done (Y)");
                Ok(())
            }
        }));
        tracing::info!("tree-samples-recompute registered");
    }

    // Exchange relay TTL cleanup: drop expired ciphertext envelopes + sessions.
    {
        let pool = pool.clone();
        sched.register(Job::new("exchange-expire", Duration::from_secs(3_600), move || {
            let pool = pool.clone();
            async move {
                let (envelopes, sessions) = du_db::exchange::expire(&pool).await?;
                tracing::info!(envelopes, sessions, "exchange-expire done");
                Ok(())
            }
        }));
        tracing::info!("exchange-expire registered");
    }

    // TODO(jobs): variant-export to a file artifact (the /api/v1/variants/export
    // endpoint already streams CSV live). match-discovery is out of scope (IBD
    // not in production).

    // Jetstream coverage-mirror consumer — a long-lived websocket stream (not an
    // interval job), so it runs as its own task beside the scheduler. Mirrors
    // published alignment coverage summaries into fed.coverage_summary.
    let jetstream = jetstream::Config::from_env().map(|cfg| {
        tracing::info!(url = %cfg.url, collections = ?cfg.collections, "jetstream coverage-mirror consumer registered");
        tokio::spawn(jetstream::run(pool.clone(), cfg))
    });
    if jetstream.is_none() {
        tracing::info!("jetstream consumer not configured (set JETSTREAM_URL)");
    }

    let shutdown = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    sched.run_until(shutdown).await;
    if let Some(handle) = jetstream {
        handle.abort();
    }
    Ok(())
}
