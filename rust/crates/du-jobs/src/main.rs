//! Background job runner. Two modes:
//!   - `jetstream` — the always-on reporting-mirror consumer (its own service).
//!   - `run-once <job>` — one batch job to completion, driven by systemd timers.
//!
//! The old no-arg interval scheduler is retired: it fired every job on startup and
//! let same-period jobs overlap, spiking DB + memory. Automated jobs now run one at a
//! time via `run-once`, serialized by a Postgres advisory lock (`DU_JOBS_LOCK`).

mod coord_lift;
mod crawl_project;
mod ena;
mod faidx;
mod ftdna_str;
mod gzio;
mod import_kit_identifiers;
mod jetstream;
mod publications;
mod name_private_nodes;
mod topic_prune;
mod ybrowse;
mod yregions;

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
        // Serialize automated runs so jobs NEVER overlap (the concurrent in-process
        // scheduler is retired; cron timers invoke `run-once` one at a time). Held for
        // the whole job, released at process exit:
        //   DU_JOBS_LOCK=wait  → queue behind any running job (nightly batch jobs)
        //   DU_JOBS_LOCK=skip  → skip this tick if a job is already running (frequent pollers)
        //   unset              → no lock (ad-hoc manual runs)
        let _job_lock = match std::env::var("DU_JOBS_LOCK").ok().as_deref() {
            Some("wait") => du_db::job_lock::acquire(&pool, true).await?,
            Some("skip") => match du_db::job_lock::acquire(&pool, false).await? {
                Some(g) => Some(g),
                None => {
                    tracing::info!(job = %job, "another job holds the run lock — skipping this tick");
                    return Ok(());
                }
            },
            _ => None,
        };
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
            // Recompute the Variant Browser's per-physical-variant representative flag
            // (mig 0062) without a full YBrowse reconcile — the backfill after adding the
            // column, and a repair if branch-placement rows changed out of band.
            "variant-representatives" => {
                let changed = du_db::variant::recompute_catalog_representatives(&pool).await?;
                tracing::info!(changed, "variant catalog representatives recomputed");
            }
            // One-time backfill: re-stamp naming_status so a real name = NAMED and only a
            // coordinate placeholder (chr%:%)/NULL = UNNAMED. Batched (short UPDATEs) so it
            // never long-locks the millions-row catalog; ANALYZEs at the end so the Variant
            // Browser's trigram search stays fast. Idempotent — converges to 0.
            "naming-status-normalize" => {
                let fixed = du_db::variant::normalize_naming_status(&pool).await?;
                tracing::info!(fixed, "naming-status-normalize complete");
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
                let replay_guards = du_db::fed::signed_request::purge_expired(&pool).await?;
                tracing::info!(envelopes, sessions, replay_guards, "exchange-expire complete");
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
                    identifier_readjudicated = rep.identifier_readjudicated,
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
                    donors_created = rep.donors_created, identifiers_registered = rep.identifiers_registered,
                    dup_candidates = rep.dup_candidates,
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
            // Backfill real names onto de-novo private placeholder nodes
            // (`<parent>:n<k>`) whose defining sites have since gained a public SNP
            // name (e.g. after a YBrowse resync). Renames each to the lowest
            // natural-sort namable SNP it carries, old id kept as an alias. Previews
            // unless `--apply` (which also bumps the tree revision).
            "name-private-nodes" => {
                let args: Vec<String> = argv.collect();
                name_private_nodes::run(&pool, &name_private_nodes::Config::from_env(&args)).await?;
            }
            // Retroactively apply the discovery topic whitelist (mig 0065) to the
            // pending candidate queue: fetch each pending candidate's OpenAlex primary
            // topic and reject the ones outside the enabled configs' whitelist(s).
            // Cleans up the off-topic flood collected before the whitelist landed.
            // Requires OPENALEX_MAILTO. Previews unless `--apply`.
            "publication-topic-prune" => {
                let cfg = publications::Config::from_env()
                    .ok_or_else(|| anyhow::anyhow!("set OPENALEX_MAILTO"))?;
                let client = du_external::openalex::OpenAlexClient::new(Some(cfg.mailto));
                let args: Vec<String> = argv.collect();
                topic_prune::run(&pool, &client, &topic_prune::Config::from_env(&args)).await?;
            }
            // Crawl an ENA study / NCBI BioProject: enumerate its runs and link
            // each sample's read files (creating biosamples as needed). With an
            // accession arg, crawl just that project; otherwise drain the pending
            // queue populated when a curator attaches a project to a publication.
            "crawl-project" => {
                let ena = du_external::ena::EnaClient::new();
                match argv.next().filter(|a| !a.is_empty()) {
                    Some(acc) => crawl_project::crawl_one_accession(&pool, &ena, &acc).await?,
                    None => crawl_project::crawl_pending(&pool, &ena).await?,
                }
            }
            // External enrichment (formerly scheduled; now nightly run-once). OpenAlex
            // by-DOI refresh, date-sorted discovery, and PubMed by-PMID gap-fill.
            "publication-update" => {
                let cfg = publications::Config::from_env().ok_or_else(|| anyhow::anyhow!("set OPENALEX_MAILTO"))?;
                let client = du_external::openalex::OpenAlexClient::new(Some(cfg.mailto));
                publications::update_all(&pool, &client).await?;
            }
            "publication-discovery" => {
                let cfg = publications::Config::from_env().ok_or_else(|| anyhow::anyhow!("set OPENALEX_MAILTO"))?;
                let client = du_external::openalex::OpenAlexClient::new(Some(cfg.mailto));
                publications::discover(&pool, &client).await?;
            }
            "publication-pubmed-update" => {
                let cfg = publications::NcbiConfig::from_env().ok_or_else(|| anyhow::anyhow!("set NCBI_EMAIL"))?;
                let client = du_external::ncbi::NcbiClient::new(Some(cfg.email), cfg.api_key);
                publications::pubmed_update_all(&pool, &client).await?;
            }
            // Fill study metadata gaps from the public ENA portal (no credentials).
            "ena-study-enrichment" => {
                let client = du_external::ena::EnaClient::new();
                ena::enrich_studies(&pool, &client).await?;
            }
            other => anyhow::bail!(
                "unknown run-once job '{other}' (known: ybrowse, reconcile, variant-representatives, yregions, branch-age, ftdna-str, sequencer-consensus, discovery-consensus, coverage-norms, ibd-discovery-recompute, exchange-expire, tree-samples-recompute, dedup-candidates, consolidate-donors, link-federated-subjects, import-kit-identifiers, mt-rcrs-lift, variant-coord-lift, crawl-project, publication-topic-prune, name-private-nodes, publication-update, publication-discovery, publication-pubmed-update, ena-study-enrichment)"
            ),
        }
        return Ok(());
    }

    // The concurrent in-process interval scheduler is RETIRED: it fired every job on
    // startup and let same-period jobs overlap, spiking DB + memory. Automated jobs now
    // run one at a time via systemd timers calling `run-once <job>`, serialized by the
    // DU_JOBS_LOCK advisory lock (see rust/scripts/nightly-maintenance.sh). The always-on
    // reporting ingest is the separate `jetstream` mode.
    tracing::warn!(
        "no-arg scheduler mode is retired — automated jobs now run via `run-once <job>` \
         (cron/systemd timers) and ingest via the `jetstream` mode. Disable the \
         decodingus-scheduler service; nothing to do here."
    );
    Ok(())
}
