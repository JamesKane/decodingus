//! Jetstream reporting-mirror consumer.
//!
//! A long-lived websocket consumer of the public Jetstream firehose (the
//! lightweight JSON variant of `com.atproto.sync.subscribeRepos`). It subscribes
//! to the DecodingUs collections the AppView reports on (`du_db::fed::
//! INGEST_COLLECTIONS`) and mirrors each published **summary** record into its
//! dedicated `fed.*` reporting table, so reports aggregate with local SQL instead
//! of a per-query HTTP fan-out across every PDS.
//!
//! The AppView aggregates and reports — it does not analyze. This mirrors only
//! anonymized computed summaries (never raw reads/files); donor PII in the core
//! container records is dropped at extraction (see `du_db::fed::core`). Resumes
//! from the persisted `time_us` cursor and reconnects with capped backoff; every
//! upsert is idempotent + ordered, so replay overlap on reconnect is harmless.

use du_db::fed::{self, analytics, core, coverage};
use du_db::PgPool;
use futures_util::StreamExt;
use serde::Deserialize;
use serde_json::{json, Value};
use std::time::Duration;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

#[derive(Clone)]
pub struct Config {
    /// Jetstream `subscribe` endpoint, e.g. `wss://jetstream2.us-east.bsky.network/subscribe`.
    pub url: String,
    /// Collections to subscribe to (the firehose filters server-side).
    pub collections: Vec<String>,
}

impl Config {
    pub fn from_env() -> Option<Config> {
        let url = std::env::var("JETSTREAM_URL").ok().filter(|s| !s.is_empty())?;
        // Default to the full reporting ingest set; allow narrowing via env.
        let collections = std::env::var("JETSTREAM_COLLECTIONS")
            .ok()
            .filter(|s| !s.is_empty())
            .map(|s| s.split(',').map(|c| c.trim().to_string()).filter(|c| !c.is_empty()).collect())
            .unwrap_or_else(|| fed::INGEST_COLLECTIONS.iter().map(|c| c.to_string()).collect());
        Some(Config { url, collections })
    }
}

/// A Jetstream event. We only act on `kind == "commit"`; identity/account events
/// still advance the cursor.
#[derive(Deserialize)]
struct Event {
    did: String,
    time_us: i64,
    kind: String,
    #[serde(default)]
    commit: Option<Commit>,
}

#[derive(Deserialize)]
struct Commit {
    operation: String, // create | update | delete
    collection: String,
    rkey: String,
    #[serde(default)]
    cid: Option<String>,
    #[serde(default)]
    record: Option<Value>,
}

/// Run forever: connect, stream, persist cursor, reconnect on drop with capped
/// exponential backoff. Intended to be `tokio::spawn`ed alongside the scheduler.
pub async fn run(pool: PgPool, cfg: Config) {
    let mut backoff = 1u64;
    loop {
        match stream_once(&pool, &cfg).await {
            Ok(()) => {
                tracing::info!("jetstream stream closed cleanly; reconnecting");
                backoff = 1;
            }
            Err(e) => {
                tracing::warn!(error = %e, backoff_s = backoff, "jetstream stream error; reconnecting");
            }
        }
        tokio::time::sleep(Duration::from_secs(backoff)).await;
        backoff = (backoff * 2).min(60);
    }
}

/// Build the subscribe URL with `wantedCollections` filters and an optional
/// resume cursor. NSIDs and the integer cursor need no escaping.
fn build_url(cfg: &Config, cursor: Option<i64>) -> String {
    let mut url = format!("{}?", cfg.url);
    for c in &cfg.collections {
        url.push_str("wantedCollections=");
        url.push_str(c);
        url.push('&');
    }
    if let Some(cur) = cursor {
        url.push_str(&format!("cursor={cur}&"));
    }
    url.pop(); // trailing '?' or '&'
    url
}

/// One connection's lifetime: stream events until the socket closes/errors.
async fn stream_once(pool: &PgPool, cfg: &Config) -> anyhow::Result<()> {
    let cursor = fed::load_cursor(pool).await?;
    let url = build_url(cfg, cursor);
    tracing::info!(%url, "jetstream connecting");
    let (ws, _resp) = connect_async(&url).await?;
    let (_write, mut read) = ws.split();

    while let Some(msg) = read.next().await {
        let text = match msg? {
            Message::Text(t) => t,
            Message::Close(_) => break,
            _ => continue, // ping/pong/binary/frame
        };
        let event: Event = match serde_json::from_str(text.as_str()) {
            Ok(e) => e,
            Err(e) => {
                tracing::debug!(error = %e, "skipping unparsable jetstream event");
                continue;
            }
        };
        if let Err(e) = handle(pool, &event).await {
            tracing::warn!(error = %e, did = %event.did, "failed to mirror event");
        }
        // Advance the cursor. Volume is low (server-side filtered), so persisting
        // per-event is cheap and keeps replay overlap minimal on reconnect.
        fed::save_cursor(pool, event.time_us).await?;
    }
    Ok(())
}

/// Apply one commit event to the matching reporting table: upsert on
/// create/update, remove on delete. Ignores non-commit events and unknown
/// collections.
async fn handle(pool: &PgPool, ev: &Event) -> anyhow::Result<()> {
    if ev.kind != "commit" {
        return Ok(());
    }
    let Some(commit) = &ev.commit else { return Ok(()) };

    if commit.operation == "delete" {
        fed::delete(pool, &commit.collection, &ev.did, &commit.rkey).await?;
        return Ok(());
    }
    // create | update
    let Some(record) = &commit.record else { return Ok(()) };
    let c = common(ev, commit, record);
    match commit.collection.as_str() {
        fed::NS_ALIGNMENT => coverage::upsert(pool, &build_coverage(c, record)).await?,
        fed::NS_BIOSAMPLE => core::upsert_biosample(pool, &build_biosample(c, record)).await?,
        fed::NS_SEQUENCERUN => core::upsert_sequencerun(pool, &build_sequencerun(c, record)).await?,
        fed::NS_PROJECT => core::upsert_project(pool, &build_project(c, record)).await?,
        fed::NS_WORKSPACE => core::upsert_workspace(pool, &build_workspace(c, record)).await?,
        fed::NS_GENOTYPE => analytics::upsert_genotype(pool, &build_genotype(c, record)).await?,
        fed::NS_POPULATION_BREAKDOWN => {
            analytics::upsert_population_breakdown(pool, &build_population_breakdown(c, record)).await?
        }
        fed::NS_HAPLOGROUP_RECONCILIATION => {
            analytics::upsert_reconciliation(pool, &build_reconciliation(c, record)).await?
        }
        other => tracing::debug!(collection = other, "ignoring unwanted collection"),
    }
    Ok(())
}

// ── extraction helpers ──────────────────────────────────────────────────────

fn str_at(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(Value::as_str).map(String::from)
}
fn f64_at(v: &Value, key: &str) -> Option<f64> {
    v.get(key).and_then(Value::as_f64)
}
fn i32_at(v: &Value, key: &str) -> Option<i32> {
    v.get(key).and_then(Value::as_i64).map(|n| n as i32)
}
fn i64_at(v: &Value, key: &str) -> Option<i64> {
    v.get(key).and_then(Value::as_i64)
}
fn arr_len(v: &Value, key: &str) -> i32 {
    v.get(key).and_then(Value::as_array).map(|a| a.len() as i32).unwrap_or(0)
}
/// `haplogroups.<arm>.haplogroupName` (arm = "yDna" | "mtDna").
fn haplogroup_name(v: &Value, container: &str, arm: &str) -> Option<String> {
    v.get(container).and_then(|h| h.get(arm)).and_then(|r| r.get("haplogroupName")).and_then(Value::as_str).map(String::from)
}
/// Clone a record with the `files` array stripped (we never store file metadata).
fn without_files(record: &Value) -> Value {
    let mut v = record.clone();
    if let Some(obj) = v.as_object_mut() {
        obj.remove("files");
    }
    v
}

fn common(ev: &Event, commit: &Commit, record: &Value) -> fed::Common {
    let record_created_at = record
        .get("meta")
        .and_then(|m| m.get("createdAt"))
        .and_then(Value::as_str)
        .and_then(fed::to_utc);
    fed::Common {
        did: ev.did.clone(),
        rkey: commit.rkey.clone(),
        at_uri: format!("at://{}/{}/{}", ev.did, commit.collection, commit.rkey),
        cid: commit.cid.clone(),
        record_created_at,
        time_us: ev.time_us,
    }
}

// ── per-collection record builders ──────────────────────────────────────────

fn build_coverage(c: fed::Common, record: &Value) -> coverage::CoverageRecord {
    let metrics = record.get("metrics").cloned().unwrap_or_else(|| json!({}));
    coverage::CoverageRecord {
        did: c.did,
        collection: fed::NS_ALIGNMENT.to_string(),
        rkey: c.rkey,
        at_uri: c.at_uri,
        cid: c.cid,
        biosample_ref: str_at(record, "biosampleRef"),
        sequence_run_ref: str_at(record, "sequenceRunRef"),
        reference_build: str_at(record, "referenceBuild"),
        aligner: str_at(record, "aligner"),
        mean_coverage: f64_at(&metrics, "meanCoverage"),
        median_coverage: f64_at(&metrics, "medianCoverage"),
        pct_10x: f64_at(&metrics, "pct10x"),
        pct_20x: f64_at(&metrics, "pct20x"),
        pct_30x: f64_at(&metrics, "pct30x"),
        metrics,
        record_created_at: c.record_created_at,
        time_us: c.time_us,
    }
}

fn build_biosample(c: fed::Common, record: &Value) -> core::Biosample {
    // PII (donorIdentifier / sampleAccession / description) is intentionally
    // never read here — only anonymized/computed fields are mirrored.
    core::Biosample {
        sex: str_at(record, "sex"),
        y_haplogroup: haplogroup_name(record, "haplogroups", "yDna"),
        mt_haplogroup: haplogroup_name(record, "haplogroups", "mtDna"),
        center_name: str_at(record, "centerName"),
        population_breakdown_ref: str_at(record, "populationBreakdownRef"),
        str_profile_ref: str_at(record, "strProfileRef"),
        sequence_run_count: arr_len(record, "sequenceRunRefs"),
        genotype_count: arr_len(record, "genotypeRefs"),
        common: c,
    }
}

fn build_sequencerun(c: fed::Common, record: &Value) -> core::SequenceRun {
    core::SequenceRun {
        biosample_ref: str_at(record, "biosampleRef"),
        platform_name: str_at(record, "platformName"),
        instrument_model: str_at(record, "instrumentModel"),
        instrument_id: str_at(record, "instrumentId"),
        test_type: str_at(record, "testType"),
        library_layout: str_at(record, "libraryLayout"),
        total_reads: i64_at(record, "totalReads"),
        read_length: i32_at(record, "readLength"),
        mean_insert_size: f64_at(record, "meanInsertSize"),
        common: c,
    }
}

fn build_project(c: fed::Common, record: &Value) -> core::Project {
    core::Project {
        project_name: str_at(record, "projectName"),
        administrator_did: str_at(record, "administrator"),
        member_count: arr_len(record, "memberRefs"),
        common: c,
    }
}

fn build_workspace(c: fed::Common, record: &Value) -> core::Workspace {
    core::Workspace {
        sample_count: arr_len(record, "sampleRefs"),
        project_count: arr_len(record, "projectRefs"),
        common: c,
    }
}

fn build_genotype(c: fed::Common, record: &Value) -> analytics::Genotype {
    analytics::Genotype {
        biosample_ref: str_at(record, "biosampleRef"),
        provider: str_at(record, "provider"),
        test_type_code: str_at(record, "testTypeCode"),
        chip_version: str_at(record, "chipVersion"),
        total_markers_called: i32_at(record, "totalMarkersCalled"),
        total_markers_possible: i32_at(record, "totalMarkersPossible"),
        no_call_rate: f64_at(record, "noCallRate"),
        y_markers_called: i32_at(record, "yMarkersCalled"),
        mt_markers_called: i32_at(record, "mtMarkersCalled"),
        autosomal_markers_called: i32_at(record, "autosomalMarkersCalled"),
        het_rate: f64_at(record, "hetRate"),
        build_version: str_at(record, "buildVersion"),
        y_haplogroup: haplogroup_name(record, "derivedHaplogroups", "yDna"),
        mt_haplogroup: haplogroup_name(record, "derivedHaplogroups", "mtDna"),
        population_breakdown_ref: str_at(record, "populationBreakdownRef"),
        record: without_files(record),
        common: c,
    }
}

fn build_population_breakdown(c: fed::Common, record: &Value) -> analytics::PopulationBreakdown {
    analytics::PopulationBreakdown {
        biosample_ref: str_at(record, "biosampleRef"),
        analysis_method: str_at(record, "analysisMethod"),
        panel_type: str_at(record, "panelType"),
        reference_populations: str_at(record, "referencePopulations"),
        snps_analyzed: i32_at(record, "snpsAnalyzed"),
        snps_with_genotype: i32_at(record, "snpsWithGenotype"),
        snps_missing: i32_at(record, "snpsMissing"),
        confidence_level: f64_at(record, "confidenceLevel"),
        components: record.get("components").cloned().unwrap_or_else(|| json!([])),
        super_population_summary: record.get("superPopulationSummary").cloned().unwrap_or_else(|| json!([])),
        pca_coordinates: record.get("pcaCoordinates").cloned(),
        common: c,
    }
}

fn build_reconciliation(c: fed::Common, record: &Value) -> analytics::Reconciliation {
    let status = record.get("status").cloned().unwrap_or_else(|| json!({}));
    analytics::Reconciliation {
        specimen_donor_ref: str_at(record, "specimenDonorRef"),
        dna_type: str_at(record, "dnaType"),
        compatibility_level: str_at(&status, "compatibilityLevel"),
        consensus_haplogroup: str_at(&status, "consensusHaplogroup"),
        confidence: f64_at(&status, "confidence"),
        branch_compatibility_score: f64_at(&status, "branchCompatibilityScore"),
        snp_concordance: f64_at(&status, "snpConcordance"),
        run_count: i32_at(&status, "runCount"),
        record: record.clone(),
        common: c,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mk_common() -> fed::Common {
        fed::Common {
            did: "did:plc:abc".into(),
            rkey: "rk1".into(),
            at_uri: "at://did:plc:abc/c/rk1".into(),
            cid: Some("bafy".into()),
            record_created_at: None,
            time_us: 1_725_000_000_000_000,
        }
    }

    #[test]
    fn build_url_includes_all_collections_and_cursor() {
        let cfg = Config { url: "wss://jet/subscribe".into(), collections: vec!["a.b.c".into(), "d.e.f".into()] };
        assert_eq!(build_url(&cfg, None), "wss://jet/subscribe?wantedCollections=a.b.c&wantedCollections=d.e.f");
        assert_eq!(build_url(&cfg, Some(7)), "wss://jet/subscribe?wantedCollections=a.b.c&wantedCollections=d.e.f&cursor=7");
    }

    #[test]
    fn coverage_extracts_metrics_and_refs() {
        let record = json!({
            "biosampleRef": "at://x/bs/1",
            "referenceBuild": "GRCh38",
            "metrics": { "meanCoverage": 31.5, "pct30x": 88.0, "contigs": [{"contigName":"chr1"}] }
        });
        let r = build_coverage(mk_common(), &record);
        assert_eq!(r.reference_build.as_deref(), Some("GRCh38"));
        assert_eq!(r.mean_coverage, Some(31.5));
        assert_eq!(r.pct_30x, Some(88.0));
        assert!(r.metrics.get("contigs").is_some());
    }

    #[test]
    fn biosample_drops_pii_and_extracts_haplogroups() {
        let record = json!({
            "donorIdentifier": "SECRET-DONOR-123",
            "sampleAccession": "ACC-999",
            "description": "free text about the donor",
            "sex": "Male",
            "centerName": "Acme Sequencing",
            "haplogroups": { "yDna": { "haplogroupName": "R-M269", "score": 0.9 }, "mtDna": { "haplogroupName": "H1a" } },
            "sequenceRunRefs": ["a", "b"],
            "genotypeRefs": ["g"]
        });
        let b = build_biosample(mk_common(), &record);
        assert_eq!(b.sex.as_deref(), Some("Male"));
        assert_eq!(b.y_haplogroup.as_deref(), Some("R-M269"));
        assert_eq!(b.mt_haplogroup.as_deref(), Some("H1a"));
        assert_eq!(b.center_name.as_deref(), Some("Acme Sequencing"));
        assert_eq!(b.sequence_run_count, 2);
        assert_eq!(b.genotype_count, 1);
        // Biosample struct has no field that could carry donorIdentifier/accession/
        // description — PII is structurally impossible to mirror.
    }

    #[test]
    fn genotype_strips_files_from_stored_record() {
        let record = json!({
            "provider": "23andMe",
            "totalMarkersCalled": 600000,
            "noCallRate": 0.02,
            "derivedHaplogroups": { "yDna": { "haplogroupName": "R-M269" } },
            "files": [{ "fileName": "genome.txt", "location": "/Users/secret/path" }]
        });
        let g = build_genotype(mk_common(), &record);
        assert_eq!(g.provider.as_deref(), Some("23andMe"));
        assert_eq!(g.total_markers_called, Some(600000));
        assert_eq!(g.y_haplogroup.as_deref(), Some("R-M269"));
        assert!(g.record.get("files").is_none(), "files (incl. local paths) must be stripped");
        assert!(g.record.get("provider").is_some());
    }

    #[test]
    fn population_breakdown_keeps_summary_arrays() {
        let record = json!({
            "analysisMethod": "PCA_PROJECTION_GMM",
            "panelType": "genome-wide",
            "confidenceLevel": 0.95,
            "components": [{ "populationCode": "CEU", "percentage": 60.0 }],
            "superPopulationSummary": [{ "superPopulation": "European", "percentage": 85.0 }]
        });
        let p = build_population_breakdown(mk_common(), &record);
        assert_eq!(p.panel_type.as_deref(), Some("genome-wide"));
        assert_eq!(p.confidence_level, Some(0.95));
        assert_eq!(p.super_population_summary.as_array().map(|a| a.len()), Some(1));
    }

    #[test]
    fn reconciliation_extracts_status_consensus() {
        let record = json!({
            "specimenDonorRef": "at://x/donor/1",
            "dnaType": "Y_DNA",
            "status": { "compatibilityLevel": "COMPATIBLE", "consensusHaplogroup": "R-BY18291", "confidence": 0.97, "runCount": 3 }
        });
        let r = build_reconciliation(mk_common(), &record);
        assert_eq!(r.dna_type.as_deref(), Some("Y_DNA"));
        assert_eq!(r.compatibility_level.as_deref(), Some("COMPATIBLE"));
        assert_eq!(r.consensus_haplogroup.as_deref(), Some("R-BY18291"));
        assert_eq!(r.run_count, Some(3));
    }
}
