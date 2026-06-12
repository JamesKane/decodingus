//! Background job runner (replaces the Pekko Quartz scheduler). Registers the
//! scheduled workers and runs them until shutdown (Ctrl-C).
//!
//! The five legacy jobs (publication update + discovery via OpenAlex, YBrowse
//! variant ingest via du-bio, variant export, match discovery) are wired here as
//! their external clients land (du-external) and ingestion is built (du-bio). A
//! DB heartbeat is registered now to exercise the harness end-to-end.

use std::sync::Arc;
use std::time::Duration;

mod ena;
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
    let pool = du_db::connect(&url, 4).await?;

    // One-shot mode: `du-jobs run-once <job>` runs a single job to completion and
    // exits, instead of starting the interval scheduler. For ops/backfills (e.g.
    // a full YBrowse GFF3 ingest + reconcile on demand, not on the 24h tick).
    let mut argv = std::env::args().skip(1);
    if argv.next().as_deref() == Some("run-once") {
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
            other => anyhow::bail!(
                "unknown run-once job '{other}' (known: ybrowse, reconcile, yregions, branch-age, sequencer-consensus)"
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
