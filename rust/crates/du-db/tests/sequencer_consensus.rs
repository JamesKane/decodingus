//! Live-DB test for the sequencer-lab consensus engine
//! (`du_db::sequencer::recompute_consensus` + proposal lifecycle). Seeds federated
//! sequenceruns/biosamples, aggregates, accepts, and rejects. Skips when
//! DATABASE_URL is unset.

use du_db::sequencer::{self, ConsensusConfig};
use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// A curator user — accept/reject now write an audit row (FK to ident.users).
async fn test_user(pool: &PgPool) -> Uuid {
    sqlx::query_scalar(
        "INSERT INTO ident.users (handle, display_name) VALUES ('testseq-curator', 'Test Curator') \
         ON CONFLICT (handle) DO UPDATE SET display_name = EXCLUDED.display_name RETURNING id",
    )
    .fetch_one(pool)
    .await
    .expect("test user")
}

/// One federated biosample (the lab claim is `center_name`).
async fn biosample(pool: &PgPool, did: &str, at_uri: &str, center: Option<&str>, t: i64) {
    sqlx::query(
        "INSERT INTO fed.biosample (did, rkey, at_uri, center_name, time_us) VALUES ($1,$2,$3,$4,$5)",
    )
    .bind(did)
    .bind(format!("bio-{t}"))
    .bind(at_uri)
    .bind(center)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert biosample");
}

/// One federated sequencerun carrying an instrument id, referencing a biosample.
async fn run(pool: &PgPool, did: &str, run_uri: &str, biosample_ref: &str, instrument: &str, t: i64) {
    sqlx::query(
        "INSERT INTO fed.sequencerun (did, rkey, at_uri, biosample_ref, instrument_id, platform_name, instrument_model, time_us) \
         VALUES ($1,$2,$3,$4,$5,'ILLUMINA','NovaSeq 6000',$6)",
    )
    .bind(did)
    .bind(format!("run-{t}"))
    .bind(run_uri)
    .bind(biosample_ref)
    .bind(instrument)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert run");
}

/// One federated sequencerun carrying an instrument id AND a published
/// `sequencing_facility` — the contributor's known lab, with no biosample/center_name
/// and (deliberately) no seeded instrument→lab mapping (e.g. a PacBio serial).
async fn run_with_facility(pool: &PgPool, did: &str, run_uri: &str, instrument: &str, facility: &str, t: i64) {
    sqlx::query(
        "INSERT INTO fed.sequencerun (did, rkey, at_uri, instrument_id, sequencing_facility, platform_name, instrument_model, time_us) \
         VALUES ($1,$2,$3,$4,$5,'PACBIO_SMRT','Revio',$6)",
    )
    .bind(did)
    .bind(format!("frun-{t}"))
    .bind(run_uri)
    .bind(instrument)
    .bind(facility)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert facility run");
}

/// One explicit citizen instrumentObservation (mirrored from the lexicon record),
/// observed `now()` with the given confidence level.
async fn observation(pool: &PgPool, did: &str, at_uri: &str, instrument: &str, lab: &str, confidence: &str, t: i64) {
    sqlx::query(
        "INSERT INTO fed.instrument_observation \
           (did, rkey, at_uri, instrument_id, lab_name, biosample_ref, platform, instrument_model, confidence, observed_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,'ILLUMINA','NovaSeq 6000',$7, now(), $8)",
    )
    .bind(did)
    .bind(format!("obs-{t}"))
    .bind(at_uri)
    .bind(instrument)
    .bind(lab)
    .bind(format!("at://{did}/bio"))
    .bind(confidence)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert observation");
}

/// Explicit instrumentObservation records (KNOWN, fresh) drive a proposal on their
/// own — no sequencerun needed — and score higher than the implicit INFERRED path.
#[tokio::test]
async fn explicit_observations_drive_consensus() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping explicit-observation test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let curator = test_user(&pool).await;

    // Three citizens, five KNOWN observations claiming "YSEQ" for instrument B00100.
    let mut t = 5000i64;
    for (did, n_obs) in [("did:ex:ann", 2), ("did:ex:ben", 2), ("did:ex:cat", 1)] {
        for r in 0..n_obs {
            observation(&pool, did, &format!("at://{did}/obs/{r}"), "B00100", "YSEQ", "KNOWN", t).await;
            t += 1;
        }
    }

    let cfg = ConsensusConfig::default();
    let rep = sequencer::recompute_consensus(&pool, &cfg).await.expect("recompute");
    assert!(rep.observations_upserted >= 5);

    let page = sequencer::list_proposals(&pool, None, 1, 50).await.expect("list");
    let p = page.items.iter().find(|p| p.instrument_id == "B00100").expect("B00100 proposal");
    assert_eq!(p.proposed_lab_name.as_deref(), Some("YSEQ"));
    assert_eq!(p.observation_count, 5);
    assert_eq!(p.distinct_citizen_count, 3);
    assert_eq!(p.status, "READY_FOR_REVIEW");
    // KNOWN (conf_level 1.0) + fresh (recency 1.0) → high score.
    assert!(p.confidence_score.unwrap() >= 0.78, "KNOWN+recent score: {:?}", p.confidence_score);

    let (_pv, obs) = sequencer::proposal_detail(&pool, p.id).await.expect("detail").expect("found");
    assert_eq!(obs.len(), 5);
    assert!(obs.iter().all(|o| o.confidence.as_deref() == Some("KNOWN")));

    // Accept closes the loop to the lookup.
    sequencer::accept_proposal(&pool, p.id, curator, "YSEQ", None, None, Some(false)).await.expect("accept");
    assert!(sequencer::lookup_lab(&pool, "B00100").await.expect("lookup").is_some());
}

/// The published `sequencingFacility` alone (no center_name, no explicit
/// instrumentObservation, no seeded lab map) drives a KNOWN proposal, so a serial the
/// instrument→lab map never had — a PacBio `m64023e` — becomes resolvable after accept.
#[tokio::test]
async fn published_facility_drives_consensus() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping facility-consensus test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let curator = test_user(&pool).await;

    // Three citizens, six runs, all publishing "Dante Labs" for PacBio serial m64023e.
    // No biosamples/center_names and no seeded lab_id — the facility is the only signal.
    let mut t = 7000i64;
    for (did, n_runs) in [("did:ex:xan", 2), ("did:ex:yara", 2), ("did:ex:zed", 2)] {
        for r in 0..n_runs {
            run_with_facility(&pool, did, &format!("at://{did}/frun/{r}"), "m64023e", "Dante Labs", t).await;
            t += 1;
        }
    }

    let cfg = ConsensusConfig::default();
    let rep = sequencer::recompute_consensus(&pool, &cfg).await.expect("recompute");
    assert!(rep.observations_upserted >= 6, "facility runs materialize KNOWN observations");

    let page = sequencer::list_proposals(&pool, None, 1, 50).await.expect("list");
    let p = page.items.iter().find(|p| p.instrument_id == "m64023e").expect("m64023e proposal");
    assert_eq!(p.proposed_lab_name.as_deref(), Some("Dante Labs"));
    assert_eq!(p.observation_count, 6);
    assert_eq!(p.distinct_citizen_count, 3);
    assert_eq!(p.status, "READY_FOR_REVIEW");

    // The supporting observations are KNOWN (facility is an explicit claim, not INFERRED).
    let (_pv, obs) = sequencer::proposal_detail(&pool, p.id).await.expect("detail").expect("found");
    assert!(obs.iter().all(|o| o.confidence.as_deref() == Some("KNOWN")), "facility → KNOWN: {obs:?}");

    // Accept once → the serial resolves for every future contributor.
    sequencer::accept_proposal(&pool, p.id, curator, "Dante Labs", None, None, Some(true)).await.expect("accept");
    let resolved = sequencer::lookup_lab(&pool, "m64023e").await.expect("lookup").expect("resolved");
    assert_eq!(resolved.lab_name, "Dante Labs");
}

#[tokio::test]
async fn consensus_aggregates_proposes_accepts_rejects() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping consensus test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let curator = test_user(&pool).await;
    let mut t = 0i64;
    let mut tick = || {
        t += 1;
        t
    };

    // Three citizens, all claiming "Nebula Genomics" for instrument A00500 (5 runs).
    for (did, n_runs) in [("did:ex:alice", 2), ("did:ex:bob", 2), ("did:ex:carol", 1)] {
        let bio = format!("at://{did}/bio");
        biosample(&pool, did, &bio, Some("Nebula Genomics"), tick()).await;
        for r in 0..n_runs {
            run(&pool, did, &format!("at://{did}/run/{r}"), &bio, "A00500", tick()).await;
        }
    }
    // A generic center is ignored (no observation).
    biosample(&pool, "did:ex:dave", "at://dave/bio", Some("Self"), tick()).await;
    run(&pool, "did:ex:dave", "at://dave/run/0", "at://dave/bio", "A00500", tick()).await;
    // An instrument with too few observations → no proposal.
    biosample(&pool, "did:ex:erin", "at://erin/bio", Some("Dante Labs"), tick()).await;
    run(&pool, "did:ex:erin", "at://erin/run/0", "at://erin/bio", "M00999", tick()).await;

    let cfg = ConsensusConfig::default();
    let rep = sequencer::recompute_consensus(&pool, &cfg).await.expect("recompute");
    assert_eq!(rep.observations_pruned, 0);
    // 5 Nebula observations materialized (the generic "Self" run is excluded; the
    // single Dante run is materialized but its instrument has too few to propose).
    assert!(rep.observations_upserted >= 5);

    // A00500 → a READY proposal for Nebula with 5 obs / 3 citizens.
    let page = sequencer::list_proposals(&pool, None, 1, 50).await.expect("list");
    let p = page.items.iter().find(|p| p.instrument_id == "A00500").expect("A00500 proposal");
    assert_eq!(p.proposed_lab_name.as_deref(), Some("Nebula Genomics"));
    assert_eq!(p.observation_count, 5);
    assert_eq!(p.distinct_citizen_count, 3);
    assert_eq!(p.status, "READY_FOR_REVIEW");
    assert!(p.confidence_score.unwrap() > 0.5);
    // M00999 (1 observation) is below min_observations → no proposal.
    assert!(!page.items.iter().any(|p| p.instrument_id == "M00999"));

    // Detail carries the supporting observations.
    let (_pv, obs) = sequencer::proposal_detail(&pool, p.id).await.expect("detail").expect("found");
    assert_eq!(obs.len(), 5);
    assert!(obs.iter().all(|o| o.lab_name.as_deref() == Some("Nebula Genomics")));

    // A recompute UPSERTs in place: an open proposal keeps its id (curator review
    // survives a background run).
    sequencer::recompute_consensus(&pool, &cfg).await.expect("recompute-stable");
    let page_again = sequencer::list_proposals(&pool, None, 1, 50).await.expect("list-again");
    let p_again = page_again.items.iter().find(|x| x.instrument_id == "A00500").expect("A00500 still proposed");
    assert_eq!(p_again.id, p.id, "active proposal id is stable across recomputes");

    // Accept → instrument resolves via lookup_lab.
    let hit = sequencer::accept_proposal(&pool, p.id, curator, "Nebula Genomics", None, None, Some(true)).await.expect("accept");
    assert_eq!(hit.lab_name, "Nebula Genomics");
    let resolved = sequencer::lookup_lab(&pool, "A00500").await.expect("lookup").expect("resolved");
    assert_eq!(resolved.lab_name, "Nebula Genomics");
    assert!(resolved.is_d2c);

    // Re-running consensus leaves the resolved instrument alone (no active proposal).
    let rep2 = sequencer::recompute_consensus(&pool, &cfg).await.expect("recompute2");
    let _ = rep2;
    let page2 = sequencer::list_proposals(&pool, Some("READY_FOR_REVIEW"), 1, 50).await.expect("list2");
    assert!(!page2.items.iter().any(|p| p.instrument_id == "A00500"), "accepted instrument has no active proposal");

    // ── reject path ──: a second instrument, rejected, is not re-proposed.
    let mut t2 = 1000i64;
    for did in ["did:ex:frank", "did:ex:grace"] {
        let bio = format!("at://{did}/bio2");
        biosample(&pool, did, &bio, Some("YSEQ"), t2).await;
        t2 += 1;
        for r in 0..2 {
            run(&pool, did, &format!("at://{did}/run2/{r}"), &bio, "A00700", t2).await;
            t2 += 1;
        }
    }
    sequencer::recompute_consensus(&pool, &cfg).await.expect("recompute3");
    let pg = sequencer::list_proposals(&pool, None, 1, 50).await.expect("list3");
    let yseq = pg.items.iter().find(|p| p.instrument_id == "A00700").expect("A00700 proposal");
    let rej = sequencer::reject_proposal(&pool, yseq.id, curator, Some("test reject")).await.expect("reject");
    assert_eq!(rej, Some(("A00700".to_string(), Some("YSEQ".to_string()))));

    sequencer::recompute_consensus(&pool, &cfg).await.expect("recompute4");
    let pg2 = sequencer::list_proposals(&pool, None, 1, 50).await.expect("list4");
    let a00700: Vec<_> = pg2.items.iter().filter(|p| p.instrument_id == "A00700").collect();
    // Only the terminal REJECTED proposal remains; no fresh active one for YSEQ.
    assert!(a00700.iter().all(|p| p.status == "REJECTED"), "rejected lab not re-proposed: {a00700:?}");
}
