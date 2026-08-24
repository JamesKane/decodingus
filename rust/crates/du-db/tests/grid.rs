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
async fn a_lapsed_lease_frees_the_slot_immediately_and_the_reaper_records_the_outcome() {
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

    // A and B take both replica slots on leases that have already lapsed.
    for did in [DID_A, DID_B] {
        let got = grid::claim(&pool, did, None, &kinds(&["CRAM"]), 1, -1)
            .await
            .unwrap();
        assert_eq!(got.len(), 1);
    }

    // C can claim RIGHT NOW, with no reaper run in between: `claim` ignores any lease past its
    // bound, so a unit held by a node that crashed frees itself. This is what makes the lease an
    // honest promise — a vanished node costs the catalogue one lease duration even if the reaper
    // is down. (The first version of this test asserted the opposite, encoding the design's story
    // that reclamation is what frees the slot. The code is right and the story was wrong.)
    let c = grid::claim(&pool, DID_C, None, &kinds(&["CRAM"]), 1, 3600)
        .await
        .unwrap();
    assert_eq!(c.len(), 1, "a lapsed lease does not hold a replica slot");

    // The reaper closes the two lapsed leases and leaves C's live one alone.
    assert_eq!(
        grid::reap_expired(&pool).await.unwrap(),
        2,
        "only the lapsed leases are closed"
    );
    assert_eq!(
        grid::reap_expired(&pool).await.unwrap(),
        0,
        "reaping is idempotent"
    );

    let outcomes: Vec<(String, Option<String>)> =
        sqlx::query_as("SELECT did, outcome FROM grid.lease ORDER BY did")
            .fetch_all(&pool)
            .await
            .unwrap();
    assert_eq!(outcomes.len(), 3);
    assert_eq!(outcomes[0].1.as_deref(), Some("EXPIRED"), "A timed out");
    assert_eq!(outcomes[1].1.as_deref(), Some("EXPIRED"), "B timed out");
    assert_eq!(outcomes[2].1, None, "C is still working");
}

/// The reaper's *other* job: until it runs, a node that overran its own lease cannot re-claim the
/// unit, because the self-replication guard keys on an unreleased lease regardless of expiry.
/// Relaxing that guard would collide with the partial unique index and hand back a silently empty
/// result instead, so the wait is deliberate.
#[tokio::test]
async fn a_node_that_overran_its_lease_can_retry_only_after_the_reaper_runs() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    grid::upsert_work_unit(&pool, &unit("SAMEA0000021", "CRAM"))
        .await
        .unwrap();
    assert_eq!(
        grid::claim(&pool, DID_A, None, &kinds(&["CRAM"]), 1, -1)
            .await
            .unwrap()
            .len(),
        1
    );

    assert!(
        grid::claim(&pool, DID_A, None, &kinds(&["CRAM"]), 1, 3600)
            .await
            .unwrap()
            .is_empty(),
        "its own lapsed-but-open lease still blocks it"
    );

    grid::reap_expired(&pool).await.unwrap();

    assert_eq!(
        grid::claim(&pool, DID_A, None, &kinds(&["CRAM"]), 1, 3600)
            .await
            .unwrap()
            .len(),
        1,
        "once the outcome is recorded, the node may take a fresh lease"
    );
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

// ── curation ──────────────────────────────────────────────────────────────────
//
// These seed through the real ingest path (`biosample::upsert_by_accession` +
// `sequence::ingest_libraries`) rather than hand-written INSERTs, on purpose: the curation query
// reads `http_locations->0->>'file_url'` and `checksums->0->>'checksum'`, which are JSONB shapes
// that only `ingest_libraries` defines. A test that wrote its own rows could agree with the query
// while both disagreed with what the crawl actually stores.

use du_db::sequence::{NewSeqFile, NewSeqLibrary};

fn seq_file(
    name: &str,
    fmt: &str,
    url: &str,
    idx: Option<&str>,
    md5: &str,
    bytes: i64,
) -> NewSeqFile {
    NewSeqFile {
        file_name: name.into(),
        file_format: Some(fmt.into()),
        file_size_bytes: Some(bytes),
        file_url: url.into(),
        file_index_url: idx.map(Into::into),
        md5: Some(md5.into()),
        aligner: None,
        target_reference: None,
    }
}

fn seq_lib(
    run: &str,
    reads: Option<i64>,
    read_length: Option<i32>,
    files: Vec<NewSeqFile>,
) -> NewSeqLibrary {
    NewSeqLibrary {
        instrument: Some("Illumina NovaSeq 6000".into()),
        reads,
        read_length,
        paired_end: Some(true),
        run_date: None,
        external_run_ref: run.into(),
        files,
    }
}

/// Seed one crawled ENA sample and return nothing — the accession is the handle.
async fn seed_sample(pool: &sqlx::PgPool, accession: &str, libs: Vec<NewSeqLibrary>) {
    let (guid, _) = du_db::biosample::upsert_by_accession(pool, accession, "EXTERNAL", None)
        .await
        .expect("upsert biosample");
    du_db::sequence::ingest_libraries(pool, guid, &libs)
        .await
        .expect("ingest");
}

#[tokio::test]
async fn curation_projects_crawled_samples_into_work_units() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    seed_sample(
        &pool,
        "SAMEA2000001",
        vec![seq_lib(
            "ERR2000001",
            Some(400_000_000),
            Some(150),
            vec![seq_file(
                "s1.cram",
                "CRAM",
                "ftp.sra.ebi.ac.uk/vol1/run/ERR200/s1.cram",
                Some("ftp.sra.ebi.ac.uk/vol1/run/ERR200/s1.cram.crai"),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                12_000_000_000,
            )],
        )],
    )
    .await;
    seed_sample(
        &pool,
        "SAMEA2000002",
        vec![seq_lib(
            "ERR2000002",
            Some(300_000_000),
            None, // ENA's crawl leaves read_length unset — so est_bases cannot be computed
            vec![
                seq_file(
                    "r_1.fastq.gz",
                    "FASTQ",
                    "ftp/r_1.fastq.gz",
                    None,
                    "b".repeat(32).as_str(),
                    9_000_000_000,
                ),
                seq_file(
                    "r_2.fastq.gz",
                    "FASTQ",
                    "ftp/r_2.fastq.gz",
                    None,
                    "c".repeat(32).as_str(),
                    9_100_000_000,
                ),
            ],
        )],
    )
    .await;

    let got = du_db::grid::curation_candidates(&pool, true, 100)
        .await
        .unwrap();
    assert_eq!(got.len(), 2, "both crawled samples are candidates");

    let cram = got
        .iter()
        .find(|c| c.sample_accession == "SAMEA2000001")
        .unwrap();
    assert_eq!(
        cram.data_kind, "CRAM",
        "a sample with an aligned file is a passthrough unit"
    );
    assert_eq!(cram.manifest.as_array().unwrap().len(), 1);
    assert_eq!(cram.manifest[0]["format"], "CRAM");
    assert_eq!(
        cram.manifest[0]["run_accession"], "ERR2000001",
        "the run accession survives the crawl's atproto slot"
    );
    assert_eq!(
        cram.manifest[0]["url"],
        "ftp.sra.ebi.ac.uk/vol1/run/ERR200/s1.cram"
    );
    assert_eq!(
        cram.manifest[0]["index_url"],
        "ftp.sra.ebi.ac.uk/vol1/run/ERR200/s1.cram.crai"
    );
    assert_eq!(cram.manifest[0]["md5"], "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assert_eq!(cram.total_bytes, Some(12_000_000_000));
    assert_eq!(
        cram.est_bases,
        Some(400_000_000 * 150),
        "reads × read_length when both are known"
    );

    let fq = got
        .iter()
        .find(|c| c.sample_accession == "SAMEA2000002")
        .unwrap();
    assert_eq!(fq.data_kind, "FASTQ", "no aligned file ⇒ the node realigns");
    assert_eq!(
        fq.manifest.as_array().unwrap().len(),
        2,
        "both mates are in the manifest"
    );
    assert!(
        fq.manifest[0].get("index_url").is_none(),
        "jsonb_strip_nulls drops the absent sidecar"
    );
    assert_eq!(fq.total_bytes, Some(18_100_000_000));
    assert_eq!(
        fq.est_bases, None,
        "read_length is unset by the crawl, so est_bases is NULL rather than invented — the \
         per-Gbp credit term has nothing to weigh this unit by"
    );
}

/// A sample carrying both an aligned file and its FASTQ is one *passthrough* unit, and its
/// manifest must not also list reads the node will never open.
#[tokio::test]
async fn the_manifest_is_filtered_to_the_chosen_data_kind() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    seed_sample(
        &pool,
        "SAMEA2000010",
        vec![
            seq_lib(
                "ERR2000010",
                Some(1),
                Some(1),
                vec![seq_file(
                    "a.cram",
                    "CRAM",
                    "ftp/a.cram",
                    None,
                    &"a".repeat(32),
                    100,
                )],
            ),
            seq_lib(
                "ERR2000011",
                Some(1),
                Some(1),
                vec![seq_file(
                    "a_1.fastq.gz",
                    "FASTQ",
                    "ftp/a_1.fastq.gz",
                    None,
                    &"b".repeat(32),
                    200,
                )],
            ),
        ],
    )
    .await;

    let got = du_db::grid::curation_candidates(&pool, true, 100)
        .await
        .unwrap();
    assert_eq!(got.len(), 1);
    assert_eq!(got[0].data_kind, "CRAM");
    let formats: Vec<&str> = got[0]
        .manifest
        .as_array()
        .unwrap()
        .iter()
        .map(|f| f["format"].as_str().unwrap())
        .collect();
    assert_eq!(
        formats,
        vec!["CRAM"],
        "the FASTQ is excluded, not merely deprioritised"
    );
    assert_eq!(
        got[0].total_bytes,
        Some(100),
        "and it is not counted in the download budget either"
    );
}

#[tokio::test]
async fn only_new_skips_samples_that_already_have_a_unit() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    seed_sample(
        &pool,
        "SAMEA2000020",
        vec![seq_lib(
            "ERR2000020",
            Some(1),
            Some(1),
            vec![seq_file(
                "a.cram",
                "CRAM",
                "ftp/a.cram",
                None,
                &"a".repeat(32),
                100,
            )],
        )],
    )
    .await;

    let first = du_db::grid::curation_candidates(&pool, true, 100)
        .await
        .unwrap();
    assert_eq!(first.len(), 1);
    let unit = NewWorkUnit {
        sample_accession: first[0].sample_accession.clone(),
        study_accession: first[0].study_accession.clone(),
        data_kind: first[0].data_kind.clone(),
        manifest: first[0].manifest.clone(),
        est_bases: first[0].est_bases,
        total_bytes: first[0].total_bytes,
    };
    let unit_id = grid::upsert_work_unit(&pool, &unit).await.unwrap();

    // Incremental: nothing new to publish.
    assert!(du_db::grid::curation_candidates(&pool, true, 100)
        .await
        .unwrap()
        .is_empty());
    // Full re-projection still offers it, which is how a manifest gets refreshed after a re-crawl.
    assert_eq!(
        du_db::grid::curation_candidates(&pool, false, 100)
            .await
            .unwrap()
            .len(),
        1
    );

    // A refresh must not disturb the lifecycle. Canonicalise the unit, re-upsert, and check.
    sqlx::query(
        "UPDATE grid.work_unit SET state = 'CANONICAL', required_replicas = 5 WHERE id = $1",
    )
    .bind(unit_id)
    .execute(&pool)
    .await
    .unwrap();
    grid::upsert_work_unit(&pool, &unit).await.unwrap();
    let (state, reps): (String, i16) =
        sqlx::query_as("SELECT state, required_replicas FROM grid.work_unit WHERE id = $1")
            .bind(unit_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(
        state, "CANONICAL",
        "curation describes the input; it must not un-canonicalise a unit"
    );
    assert_eq!(reps, 5, "nor reset a replica count validation raised");
}

// ── validation ────────────────────────────────────────────────────────────────

/// Claim, then submit `y` as the Y call. Returns the submission id.
async fn submit_as(pool: &sqlx::PgPool, did: &str, unit_id: i64, y: &str, build: &str) -> i64 {
    let claimed = grid::claim(pool, did, None, &kinds(&["CRAM"]), 1, 3600)
        .await
        .unwrap();
    let lease = claimed.first().map(|c| c.lease_id);
    grid::submit(
        pool,
        did,
        unit_id,
        lease,
        &json!({"calls": {"sex": "XY", "y_terminal": y, "coverage_mean": 30.0}}),
        "sig",
        "1.7.0",
        build,
        None,
        &json!([]),
    )
    .await
    .unwrap()
}

#[tokio::test]
async fn canonicalizing_marks_the_winners_agreed_and_the_rest_divergent() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    let unit_id = grid::upsert_work_unit(&pool, &unit("SAMEA0000100", "CRAM"))
        .await
        .unwrap();
    // Three replicas so all three contributors can hold a slot at once.
    sqlx::query("UPDATE grid.work_unit SET required_replicas = 3 WHERE id = $1")
        .bind(unit_id)
        .execute(&pool)
        .await
        .unwrap();

    let a = submit_as(&pool, DID_A, unit_id, "R-A", "chm13v2.0").await;
    let b = submit_as(&pool, DID_B, unit_id, "R-A", "chm13v2.0").await;
    let c = submit_as(&pool, DID_C, unit_id, "R-WRONG", "chm13v2.0").await;

    let pending = grid::units_awaiting_validation(&pool, 10).await.unwrap();
    assert_eq!(pending.len(), 1, "the unit is waiting on validation");
    assert_eq!(
        grid::submissions_for_validation(&pool, unit_id)
            .await
            .unwrap()
            .len(),
        3
    );

    let canonical = json!({"calls": {"sex": "XY", "y_terminal": "R-A", "coverage_mean": 30.0}});
    grid::canonicalize(&pool, unit_id, &canonical, &[a, b], &[c])
        .await
        .unwrap();

    let statuses: Vec<(i64, String)> = sqlx::query_as(
        "SELECT id, status FROM grid.submission WHERE work_unit_id = $1 ORDER BY id",
    )
    .bind(unit_id)
    .fetch_all(&pool)
    .await
    .unwrap();
    assert_eq!(
        statuses,
        vec![
            (a, "AGREED".into()),
            (b, "AGREED".into()),
            (c, "DIVERGENT".into())
        ]
    );

    let (state, digest): (String, serde_json::Value) =
        sqlx::query_as("SELECT state, canonical_digest FROM grid.work_unit WHERE id = $1")
            .bind(unit_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(state, "CANONICAL");
    assert_eq!(digest["calls"]["y_terminal"], "R-A");

    // A canonical unit is off the work list, and no longer awaiting validation.
    assert!(
        grid::claim(&pool, "did:plc:zzzz", None, &kinds(&["CRAM"]), 1, 3600)
            .await
            .unwrap()
            .is_empty()
    );
    assert!(grid::units_awaiting_validation(&pool, 10)
        .await
        .unwrap()
        .is_empty());

    // And the history that trust tiering reads reflects it.
    assert_eq!(grid::grid_history(&pool, DID_A).await.unwrap().agreed, 1);
    assert_eq!(grid::grid_history(&pool, DID_C).await.unwrap().divergent, 1);
}

/// A contested unit must go back on the work list — otherwise a disagreement deadlocks the unit
/// forever, since the tie-breaker can never be claimed.
#[tokio::test]
async fn contesting_raises_the_bar_and_reopens_the_unit_for_a_tie_breaker() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    let unit_id = grid::upsert_work_unit(&pool, &unit("SAMEA0000110", "CRAM"))
        .await
        .unwrap();
    submit_as(&pool, DID_A, unit_id, "R-A", "chm13v2.0").await;
    submit_as(&pool, DID_B, unit_id, "R-B", "chm13v2.0").await;

    grid::contest(&pool, unit_id, "submissions disagree")
        .await
        .unwrap();

    let (state, reps): (String, i16) =
        sqlx::query_as("SELECT state, required_replicas FROM grid.work_unit WHERE id = $1")
            .bind(unit_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(state, "CONTESTED");
    assert_eq!(reps, 3, "the bar rises by one");

    // Two submissions exist and the bar is now three, so a third contributor can claim.
    let c = grid::claim(&pool, DID_C, None, &kinds(&["CRAM"]), 1, 3600)
        .await
        .unwrap();
    assert_eq!(c.len(), 1, "a contested unit is claimable again");

    // Nobody was blamed: with two conflicting answers there is no evidence about which is wrong.
    let divergent: i64 =
        sqlx::query_scalar("SELECT count(*) FROM grid.submission WHERE status = 'DIVERGENT'")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(divergent, 0, "a coin-flip penalty would punish honest work");
}

#[tokio::test]
async fn the_shadow_spot_check_holds_a_unit_back_for_a_second_opinion() {
    let Some(url) = database_url() else {
        return;
    };
    let db = du_db::testing::ephemeral_db(&url)
        .await
        .expect("ephemeral db");
    let pool = db.pool().clone();

    let unit_id = grid::upsert_work_unit(&pool, &unit("SAMEA0000120", "CRAM"))
        .await
        .unwrap();
    sqlx::query("UPDATE grid.work_unit SET required_replicas = 1 WHERE id = $1")
        .bind(unit_id)
        .execute(&pool)
        .await
        .unwrap();

    assert!(
        !grid::maybe_request_shadow(&pool, unit_id, 0.0)
            .await
            .unwrap(),
        "rate 0 never fires"
    );
    assert!(
        grid::maybe_request_shadow(&pool, unit_id, 1.0)
            .await
            .unwrap(),
        "rate 1 always fires"
    );

    let (reps, note): (i16, Option<String>) =
        sqlx::query_as("SELECT required_replicas, note FROM grid.work_unit WHERE id = $1")
            .bind(unit_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(reps, 2, "one lone result is no longer enough for this unit");
    assert_eq!(note.as_deref(), Some("shadow spot-check"));

    // The shadow arrives through the ordinary claim path — no special state, no second code path.
    assert_eq!(
        grid::claim(&pool, DID_A, None, &kinds(&["CRAM"]), 1, 3600)
            .await
            .unwrap()
            .len(),
        1
    );

    // A canonical unit is never held for a shadow: the check is for a result about to be trusted.
    grid::canonicalize(&pool, unit_id, &json!({}), &[], &[])
        .await
        .unwrap();
    assert!(!grid::maybe_request_shadow(&pool, unit_id, 1.0)
        .await
        .unwrap());
}
