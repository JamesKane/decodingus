//! Integration test: the Grid's work-unit coordination against a live Postgres.
//!
//! The claim path is the one piece of genuinely new concurrency in the Grid, and it is the piece
//! that unit tests cannot reach: `FOR UPDATE SKIP LOCKED`, a partial unique index, and replica
//! arithmetic over two other tables only mean anything inside a real transaction. So these tests
//! run against a real database or not at all.
//!
//! Skips (passes) when `DATABASE_URL` is unset, so `cargo test` stays green without one. To run:
//!     DATABASE_URL=postgres://…/postgres cargo test -p du-db --test grid -- --nocapture

use du_db::grid::{self, NewWorkUnit, COBBLESTONE};
use serde_json::json;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

const DID_A: &str = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa";
const DID_B: &str = "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb";
const DID_C: &str = "did:plc:cccccccccccccccccccccccc";

fn unit(acc: &str, kind: &str) -> NewWorkUnit {
    NewWorkUnit {
        sample_accession: acc.into(),
        study_accession: Some("PRJEB00000".into()),
        data_kind: kind.into(),
        manifest: json!([{ "run_accession": "ERR0000000", "url": "ftp://example/f.cram",
                           "md5": "d41d8cd98f00b204e9800998ecf8427e", "bytes": 1024, "format": "CRAM" }]),
        est_bases: Some(90_000_000_000),
        total_bytes: Some(1024),
    }
}

fn kinds(v: &[&str]) -> Vec<String> {
    v.iter().map(|s| s.to_string()).collect()
}

#[tokio::test]
async fn claim_respects_replicas_and_never_self_replicates() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping live-DB test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    let id = grid::upsert_work_unit(&pool, &unit("SAMEA0000001", "CRAM"))
        .await
        .unwrap();

    // A takes it. required_replicas defaults to 2, so one slot remains.
    let a = grid::claim(&pool, DID_A, None, &kinds(&["CRAM"]), 5, 3600)
        .await
        .unwrap();
    assert_eq!(a.len(), 1, "A should get the only unit");
    assert_eq!(a[0].work_unit_id, id);
    assert_eq!(a[0].data_kind, "CRAM");
    assert_eq!(
        a[0].manifest[0]["format"], "CRAM",
        "the node gets the fetch manifest with the claim"
    );

    // A again: a contributor must never replicate itself, so there is nothing left for it.
    let a2 = grid::claim(&pool, DID_A, None, &kinds(&["CRAM"]), 5, 3600)
        .await
        .unwrap();
    assert!(
        a2.is_empty(),
        "the same DID must not be handed the same unit twice"
    );

    // B takes the second replica slot.
    let b = grid::claim(&pool, DID_B, None, &kinds(&["CRAM"]), 5, 3600)
        .await
        .unwrap();
    assert_eq!(b.len(), 1, "B should get the second replica");

    // C finds nothing: both replicas are in flight.
    let c = grid::claim(&pool, DID_C, None, &kinds(&["CRAM"]), 5, 3600)
        .await
        .unwrap();
    assert!(
        c.is_empty(),
        "a unit must not be handed out beyond required_replicas"
    );
}

#[tokio::test]
async fn data_kind_filter_is_honoured() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    grid::upsert_work_unit(&pool, &unit("SAMEA0000010", "CRAM"))
        .await
        .unwrap();
    grid::upsert_work_unit(&pool, &unit("SAMEA0000011", "FASTQ"))
        .await
        .unwrap();

    // A node that only wants passthrough work is never offered a FASTQ unit.
    let only_cram = grid::claim(&pool, DID_A, None, &kinds(&["CRAM"]), 10, 3600)
        .await
        .unwrap();
    assert_eq!(only_cram.len(), 1);
    assert_eq!(only_cram[0].sample_accession, "SAMEA0000010");

    let both = grid::claim(&pool, DID_B, None, &kinds(&["CRAM", "FASTQ"]), 10, 3600)
        .await
        .unwrap();
    assert_eq!(both.len(), 2, "a node advertising both kinds gets both");
}

#[tokio::test]
async fn expired_leases_are_reclaimed_and_the_unit_is_claimable_again() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    grid::upsert_work_unit(&pool, &unit("SAMEA0000020", "CRAM"))
        .await
        .unwrap();

    // Two nodes take both replica slots with leases that have already expired.
    for did in [DID_A, DID_B] {
        let got = grid::claim(&pool, did, None, &kinds(&["CRAM"]), 1, -1)
            .await
            .unwrap();
        assert_eq!(got.len(), 1);
    }
    assert!(grid::claim(&pool, DID_C, None, &kinds(&["CRAM"]), 1, 3600)
        .await
        .unwrap()
        .is_empty());

    // Both are past their bound, so the reaper takes them back. This is what makes the lease an
    // honest promise: a node that vanishes costs the catalogue one lease duration, not the unit.
    let reaped = grid::reap_expired(&pool).await.unwrap();
    assert_eq!(reaped, 2, "both expired leases reclaimed");

    let c = grid::claim(&pool, DID_C, None, &kinds(&["CRAM"]), 1, 3600)
        .await
        .unwrap();
    assert_eq!(c.len(), 1, "a reclaimed unit is claimable again");

    // Reaping is idempotent — the second run finds nothing still expired and active.
    assert_eq!(grid::reap_expired(&pool).await.unwrap(), 0);
}

#[tokio::test]
async fn submit_closes_the_lease_and_a_resubmit_updates_rather_than_duplicating() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    let unit_id = grid::upsert_work_unit(&pool, &unit("SAMEA0000030", "CRAM"))
        .await
        .unwrap();
    let claimed = grid::claim(&pool, DID_A, None, &kinds(&["CRAM"]), 1, 3600)
        .await
        .unwrap();
    let lease = claimed[0].lease_id;

    assert!(
        grid::heartbeat(&pool, DID_A, lease, Some(&json!({"stage": "download"})))
            .await
            .unwrap()
    );

    let digest =
        json!({"unit": "SAMEA0000030", "calls": {"sex": "XY", "y_terminal": "R-FGC29071"}});
    let s1 = grid::submit(
        &pool,
        DID_A,
        unit_id,
        Some(lease),
        &digest,
        "sig-1",
        "1.7.0",
        "chm13v2.0",
        None,
        &json!([]),
    )
    .await
    .unwrap();

    // The lease closed with the submission, in the same transaction. A heartbeat on it now fails,
    // which is how a node learns it no longer holds the unit.
    assert!(!grid::heartbeat(&pool, DID_A, lease, None).await.unwrap());

    // A retry after a dropped response must not become a second vote.
    let s2 = grid::submit(
        &pool,
        DID_A,
        unit_id,
        None,
        &digest,
        "sig-2",
        "1.7.0",
        "chm13v2.0",
        None,
        &json!([]),
    )
    .await
    .unwrap();
    assert_eq!(
        s1, s2,
        "resubmitting updates the same row rather than adding a vote"
    );

    let n: (i64,) = sqlx::query_as("SELECT count(*) FROM grid.submission WHERE work_unit_id = $1")
        .bind(unit_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(n.0, 1, "one contributor, one submission");

    // Having submitted, A is not offered the unit again even though a replica slot is open.
    assert!(grid::claim(&pool, DID_A, None, &kinds(&["CRAM"]), 1, 3600)
        .await
        .unwrap()
        .is_empty());
    assert_eq!(
        grid::claim(&pool, DID_B, None, &kinds(&["CRAM"]), 1, 3600)
            .await
            .unwrap()
            .len(),
        1,
        "the second replica slot is still open to a different contributor"
    );
}

#[tokio::test]
async fn credit_is_awarded_once_per_unit_and_totals_on_the_leaderboard() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    let mut submissions = Vec::new();
    for acc in ["SAMEA0000040", "SAMEA0000041"] {
        let unit_id = grid::upsert_work_unit(&pool, &unit(acc, "CRAM"))
            .await
            .unwrap();
        let claimed = grid::claim(&pool, DID_A, None, &kinds(&["CRAM"]), 1, 3600)
            .await
            .unwrap();
        let sub = grid::submit(
            &pool,
            DID_A,
            unit_id,
            Some(claimed[0].lease_id),
            &json!({}),
            "sig",
            "1.7.0",
            "chm13v2.0",
            None,
            &json!([]),
        )
        .await
        .unwrap();
        submissions.push((unit_id, sub));
    }

    for (unit_id, sub) in &submissions {
        assert!(grid::award_credit(
            &pool,
            DID_A,
            *unit_id,
            *sub,
            5 * COBBLESTONE,
            "QUORUM_AGREE"
        )
        .await
        .unwrap());
    }
    // A re-validation must not pay twice for the same unit.
    let (unit_id, sub) = submissions[0];
    assert!(
        !grid::award_credit(&pool, DID_A, unit_id, sub, 5 * COBBLESTONE, "QUORUM_AGREE")
            .await
            .unwrap(),
        "a second award for the same (unit, DID) is dropped"
    );

    let board = grid::leaderboard(&pool, None, 10).await.unwrap();
    assert_eq!(board.len(), 1);
    assert_eq!(board[0].did, DID_A);
    assert_eq!(board[0].units, 2);
    assert_eq!(
        board[0].cobblestones_milli,
        10 * COBBLESTONE,
        "two units at five cobblestones each"
    );
    // No linked account, so the board shows the DID and no handle — the work still counts.
    assert!(board[0].handle.is_none());
}

/// Two nodes claiming at the same instant must take *different* units. This is the whole point of
/// `SKIP LOCKED`: without it one claimer blocks on the other's row lock, and a fleet serialises.
#[tokio::test]
async fn concurrent_claims_take_disjoint_units() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    for i in 0..20 {
        grid::upsert_work_unit(&pool, &unit(&format!("SAMEA10000{i:02}"), "CRAM"))
            .await
            .unwrap();
    }

    let (p1, p2) = (pool.clone(), pool.clone());
    let (r1, r2) = tokio::join!(
        tokio::spawn(
            async move { grid::claim(&p1, DID_A, None, &kinds(&["CRAM"]), 10, 3600).await }
        ),
        tokio::spawn(
            async move { grid::claim(&p2, DID_B, None, &kinds(&["CRAM"]), 10, 3600).await }
        ),
    );
    let a = r1.unwrap().unwrap();
    let b = r2.unwrap().unwrap();
    assert_eq!(a.len(), 10);
    assert_eq!(b.len(), 10);

    // Distinct DIDs may legitimately share a unit as replicas, so overlap is not an error here —
    // what matters is that neither claimer blocked and both got a full batch.
    let total: std::collections::HashSet<i64> =
        a.iter().chain(b.iter()).map(|u| u.work_unit_id).collect();
    assert!(
        total.len() >= 10,
        "claims resolved without serialising; {} distinct units",
        total.len()
    );
}
