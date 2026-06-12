//! Live-DB test for the sequencer-lab consensus engine
//! (`du_db::sequencer::recompute_consensus` + proposal lifecycle). Seeds federated
//! sequenceruns/biosamples, aggregates, accepts, and rejects. Skips when
//! DATABASE_URL is unset.

use du_db::sequencer::{self, ConsensusConfig};
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
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

#[tokio::test]
async fn consensus_aggregates_proposes_accepts_rejects() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping consensus test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
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

    // Accept → instrument resolves via lookup_lab.
    let hit = sequencer::accept_proposal(&pool, p.id, "Nebula Genomics", None, None, true).await.expect("accept");
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
    let rej = sequencer::reject_proposal(&pool, yseq.id).await.expect("reject");
    assert_eq!(rej, Some(("A00700".to_string(), Some("YSEQ".to_string()))));

    sequencer::recompute_consensus(&pool, &cfg).await.expect("recompute4");
    let pg2 = sequencer::list_proposals(&pool, None, 1, 50).await.expect("list4");
    let a00700: Vec<_> = pg2.items.iter().filter(|p| p.instrument_id == "A00700").collect();
    // Only the terminal REJECTED proposal remains; no fresh active one for YSEQ.
    assert!(a00700.iter().all(|p| p.status == "REJECTED"), "rejected lab not re-proposed: {a00700:?}");
}
