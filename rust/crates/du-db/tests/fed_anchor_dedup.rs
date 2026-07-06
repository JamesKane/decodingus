//! Live-DB test for Phase 4 anchor dedup in `link_federated_subjects`. A re-published donor
//! (a fresh federated biosample) whose published external id already belongs to an existing
//! sample must: mint its anchor, register its *new* ids, and raise an IDENTIFIER-tier
//! duplicate candidate against the existing sample (never silently merge). Skips when
//! DATABASE_URL is unset.

use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// An existing (non-federated) sample that already owns `FTDNA:B5163`.
async fn seed_existing(pool: &PgPool) -> Uuid {
    let guid: Uuid = sqlx::query_scalar(
        "INSERT INTO core.biosample (source, accession, is_public) \
         VALUES ('STANDARD'::core.biosample_source, 'EXISTING-TIP', true) RETURNING sample_guid",
    )
    .fetch_one(pool)
    .await
    .expect("insert existing biosample");
    sqlx::query(
        "INSERT INTO core.biosample_identifier (sample_guid, namespace, value, is_public, source) \
         VALUES ($1, 'FTDNA', 'B5163', false, 'cohort-manifest')",
    )
    .bind(guid)
    .execute(pool)
    .await
    .expect("register existing id");
    guid
}

/// A live-published biosample (new DID) carrying a colliding id (FTDNA:B5163) and a new one
/// (YSEQ:229), mirrored into fed.biosample_identifier.
async fn seed_federated(pool: &PgPool, did: &str) -> String {
    let rkey = "bio1";
    let at_uri = format!("at://{did}/com.decodingus.atmosphere.biosample/{rkey}");
    sqlx::query(
        "INSERT INTO fed.biosample (did, rkey, at_uri, sex, time_us) VALUES ($1,$2,$3,'MALE',100)",
    )
    .bind(did)
    .bind(rkey)
    .bind(&at_uri)
    .execute(pool)
    .await
    .expect("insert fed biosample");
    for (ns, val, pubf) in [("FTDNA", "B5163", false), ("YSEQ", "229", false)] {
        sqlx::query(
            "INSERT INTO fed.biosample_identifier (did, rkey, at_uri, namespace, value, is_public) \
             VALUES ($1,$2,$3,$4,$5,$6)",
        )
        .bind(did)
        .bind(rkey)
        .bind(&at_uri)
        .bind(ns)
        .bind(val)
        .bind(pubf)
        .execute(pool)
        .await
        .expect("mirror fed id");
    }
    at_uri
}

#[tokio::test]
async fn anchor_dedup_registers_new_ids_and_flags_collisions() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping anchor-dedup test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    let existing = seed_existing(&pool).await;
    let at_uri = seed_federated(&pool, "did:ex:republisher").await;

    let rep = du_db::fed_subject::link_federated_subjects(&pool, true).await.expect("link");
    assert_eq!(rep.biosamples_created, 1, "anchor minted");
    assert_eq!(rep.identifiers_registered, 1, "only the new (YSEQ) id registered — FTDNA collided");
    assert_eq!(rep.dup_candidates, 1, "the FTDNA collision raised one candidate");

    // The anchor now exists; resolve it via its at_uri.
    let anchor: Uuid = sqlx::query_scalar(
        "SELECT sample_guid FROM core.biosample WHERE atproto->>'uri' = $1",
    )
    .bind(&at_uri)
    .fetch_one(&pool)
    .await
    .expect("anchor");
    assert_ne!(anchor, existing);

    // YSEQ:229 registered to the anchor; FTDNA:B5163 still owned by the existing sample (not stolen).
    let yseq_owner: Uuid = sqlx::query_scalar(
        "SELECT sample_guid FROM core.biosample_identifier WHERE namespace='YSEQ' AND value='229'",
    )
    .fetch_one(&pool)
    .await
    .expect("yseq owner");
    assert_eq!(yseq_owner, anchor, "new id belongs to the anchor");
    let ftdna_owner: Uuid = sqlx::query_scalar(
        "SELECT sample_guid FROM core.biosample_identifier WHERE namespace='FTDNA' AND value='B5163'",
    )
    .fetch_one(&pool)
    .await
    .expect("ftdna owner");
    assert_eq!(ftdna_owner, existing, "colliding id stays with the existing sample");

    // The candidate: an IDENTIFIER-tier pair of {existing, anchor} keyed on the FTDNA kit.
    let (a, b): (Uuid, Uuid) = sqlx::query_as(
        "SELECT sample_a, sample_b FROM dedup.duplicate_candidate \
         WHERE tier='IDENTIFIER' AND block_key='FTDNA=B5163'",
    )
    .fetch_one(&pool)
    .await
    .expect("candidate row");
    let pair = [a, b];
    assert!(pair.contains(&existing) && pair.contains(&anchor), "candidate links the two samples");
}

/// Place a sample under a Y haplogroup node (the tree-placement link classify() reads).
async fn place(pool: &PgPool, sample: Uuid, node: i64) {
    sqlx::query(
        "INSERT INTO tree.haplogroup_sample (sample_guid, dna_type, haplogroup_id, call_text, status) \
         VALUES ($1, 'Y_DNA'::core.dna_type, $2, 'R-TESTTERM', 'PLACED')",
    )
    .bind(sample)
    .bind(node)
    .execute(pool)
    .await
    .expect("place sample");
}

#[tokio::test]
async fn readjudication_confirms_once_placed() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping readjudication test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    let existing = seed_existing(&pool).await;
    let at_uri = seed_federated(&pool, "did:ex:republisher2").await;
    du_db::fed_subject::link_federated_subjects(&pool, true).await.expect("link");
    let anchor: Uuid = sqlx::query_scalar("SELECT sample_guid FROM core.biosample WHERE atproto->>'uri' = $1")
        .bind(&at_uri)
        .fetch_one(&pool)
        .await
        .expect("anchor");

    // At ingest the anchor isn't tree-placed → the candidate is UNPLACED.
    let disp0: String = sqlx::query_scalar(
        "SELECT verdict->>'disposition' FROM dedup.duplicate_candidate WHERE block_key='FTDNA=B5163'",
    )
    .fetch_one(&pool)
    .await
    .expect("disposition");
    assert_eq!(disp0, "UNPLACED", "unplaced at ingest");

    // Place both samples at the SAME terminal, then re-adjudicate → CONFIRMED.
    let node: i64 = sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ('R-TESTTERM', 'Y_DNA'::core.dna_type) RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .expect("node");
    place(&pool, existing, node).await;
    place(&pool, anchor, node).await;

    let n = du_db::dedup::readjudicate_identifier_candidates(&pool).await.expect("readjudicate");
    assert_eq!(n, 1, "one candidate refreshed");
    let (disp, score): (String, f64) = sqlx::query_as(
        "SELECT verdict->>'disposition', score FROM dedup.duplicate_candidate WHERE block_key='FTDNA=B5163'",
    )
    .fetch_one(&pool)
    .await
    .expect("after");
    assert_eq!(disp, "CONFIRMED", "same terminal → confirmed");
    assert!((score - 0.98).abs() < 1e-9, "confirmed score");
    // signals.source is preserved across re-adjudication (still 'federation' from the anchor path).
    let src: String = sqlx::query_scalar("SELECT signals->>'source' FROM dedup.duplicate_candidate WHERE block_key='FTDNA=B5163'")
        .fetch_one(&pool)
        .await
        .expect("source");
    assert_eq!(src, "federation", "source preserved");
}
