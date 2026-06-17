//! Live-DB tests for IBD attestation ingest (`du_db::ibd::record_attestation`) and the
//! tree-depth weighting of the haplogroup signal. Covers: the consensus rule (one-sided →
//! PENDING, both-agree → CONFIRMED + publicly discoverable, dispute → DISPUTED), the privacy
//! rails (only a party to a CONSENTED IBD exchange, owning the claimed pair, may attest), the
//! end-to-end activation of the shared-match signal, and that a deeper shared clade outscores
//! a shallow one. Skips when DATABASE_URL is unset.

use du_db::ibd::{self, Attestation, AttestationOutcome, IbdConfig};
use serde_json::{json, Value};
use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn fed_sample(pool: &PgPool, did: &str) -> Uuid {
    sqlx::query_scalar(
        "INSERT INTO core.biosample (source, atproto) \
         VALUES ('CITIZEN'::core.biosample_source, jsonb_build_object('uri',$1::text,'repo_did',$2::text)) \
         RETURNING sample_guid",
    )
    .bind(format!("at://{did}/bio"))
    .bind(did)
    .fetch_one(pool)
    .await
    .expect("insert biosample")
}

async fn exchange(pool: &PgPool, uri: &str, a: &str, b: &str, purpose: &str, status: &str) {
    sqlx::query(
        "INSERT INTO exchange.exchange_request (request_uri, initiator_did, partner_did, purpose, status) \
         VALUES ($1, $2, $3, $4, $5)",
    )
    .bind(uri)
    .bind(a)
    .bind(b)
    .bind(purpose)
    .bind(status)
    .execute(pool)
    .await
    .expect("insert exchange request");
}

/// Convenience: build an attestation with the common fields filled in.
fn att<'a>(
    did: &'a str,
    uri: &'a str,
    claimed: Uuid,
    counterpart: Uuid,
    cm: f64,
    kind: &'a str,
) -> Attestation<'a> {
    Attestation {
        attester_did: did,
        request_uri: uri,
        claimed_sample: claimed,
        counterpart_sample: counterpart,
        region_type: "AUTOSOMAL",
        total_shared_cm: Some(cm),
        num_segments: Some(12),
        attestation_type: kind,
        signature: "sig-stub",
        notes: None,
    }
}

#[tokio::test]
async fn attestation_consensus_confirms_and_activates_shared_match() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping ibd attestation test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    let (a, b, c) = ("did:ex:alice", "did:ex:bob", "did:ex:carol");
    let sa = fed_sample(&pool, a).await;
    let sb = fed_sample(&pool, b).await;
    let sc = fed_sample(&pool, c).await;
    exchange(&pool, "urn:x:ac", a, c, "IBD_AUTOSOMAL", "CONSENTED").await;
    exchange(&pool, "urn:x:bc", b, c, "IBD_AUTOSOMAL", "CONSENTED").await;

    // One-sided report → PENDING, not yet discoverable.
    let o1 = ibd::record_attestation(&pool, &att(a, "urn:x:ac", sa, sc, 300.0, "INITIAL_REPORT")).await.unwrap();
    let AttestationOutcome::Recorded { consensus_status, publicly_discoverable, .. } = &o1 else {
        panic!("expected Recorded, got {o1:?}");
    };
    assert_eq!(consensus_status, "PENDING");
    assert!(!publicly_discoverable, "a single report is never publicly discoverable");

    // The counterpart's compatible report (300 vs 310, within tolerance) → CONFIRMED + discoverable.
    let o2 = ibd::record_attestation(&pool, &att(c, "urn:x:ac", sc, sa, 310.0, "INITIAL_REPORT")).await.unwrap();
    let AttestationOutcome::Recorded { consensus_status, publicly_discoverable, discovery_index_id } = &o2 else {
        panic!("expected Recorded, got {o2:?}");
    };
    assert_eq!(consensus_status, "CONFIRMED");
    assert!(publicly_discoverable, "both parties agreeing makes the edge discoverable");
    // The agreed total is summarized onto the edge.
    let cm: Option<f64> = sqlx::query_scalar("SELECT total_shared_cm_approx FROM ibd.ibd_discovery_index WHERE id = $1")
        .bind(discovery_index_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert!((cm.unwrap() - 305.0).abs() < 1e-6, "agreed cM is the mean of the two reports");

    // The bob↔carol edge confirms too.
    ibd::record_attestation(&pool, &att(b, "urn:x:bc", sb, sc, 200.0, "INITIAL_REPORT")).await.unwrap();
    let obc = ibd::record_attestation(&pool, &att(c, "urn:x:bc", sc, sb, 205.0, "INITIAL_REPORT")).await.unwrap();
    assert!(matches!(obc, AttestationOutcome::Recorded { publicly_discoverable: true, .. }));

    // SHARED_MATCH activation: alice & bob now share a confirmed third party (carol), so a
    // 2-hop expansion suggests them to each other (min_shared lowered to 1 for the 1-neighbour case).
    let cfg = IbdConfig { min_shared: 1, ..IbdConfig::default() };
    ibd::recompute_suggestions(&pool, &cfg).await.unwrap();
    let alice = ibd::suggestions_for(&pool, sa, 50).await.unwrap();
    assert!(
        alice.iter().any(|s| s.suggested_sample_guid == sb && s.suggestion_type == "SHARED_MATCH"),
        "confirmed A–C and B–C edges yield a SHARED_MATCH A→B"
    );

    // A one-sided edge stays private and never propagates: dave reports, no counterpart does.
    let sd = fed_sample(&pool, "did:ex:dave").await;
    exchange(&pool, "urn:x:ad", a, "did:ex:dave", "IBD_AUTOSOMAL", "CONSENTED").await;
    ibd::record_attestation(&pool, &att(a, "urn:x:ad", sa, sd, 90.0, "INITIAL_REPORT")).await.unwrap();
    let discoverable: bool = sqlx::query_scalar(
        "SELECT is_publicly_discoverable FROM ibd.ibd_discovery_index \
         WHERE LEAST(sample_guid_1,sample_guid_2)=LEAST($1,$2) AND GREATEST(sample_guid_1,sample_guid_2)=GREATEST($1,$2)",
    )
    .bind(sa)
    .bind(sd)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert!(!discoverable, "a one-sided (unconfirmed) edge is never publicly discoverable");
}

#[tokio::test]
async fn attestation_rejects_forged_and_disputed() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping ibd attestation rejection test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    let (a, b, c) = ("did:ex:alice", "did:ex:bob", "did:ex:carol");
    let sa = fed_sample(&pool, a).await;
    let sb = fed_sample(&pool, b).await;
    let sc = fed_sample(&pool, c).await; // carol: a non-party stranger
    exchange(&pool, "urn:x:ab", a, b, "IBD_AUTOSOMAL", "CONSENTED").await;

    let rejected = |o: AttestationOutcome| matches!(o, AttestationOutcome::Rejected(_));

    // Unknown request.
    assert!(rejected(ibd::record_attestation(&pool, &att(a, "urn:x:nope", sa, sb, 100.0, "INITIAL_REPORT")).await.unwrap()));

    // A non-party (carol) cannot attest on alice↔bob.
    assert!(rejected(ibd::record_attestation(&pool, &att(c, "urn:x:ab", sc, sa, 100.0, "INITIAL_REPORT")).await.unwrap()));

    // Ownership rails: claimed sample not the attester's; counterpart sample not the other party's.
    assert!(rejected(ibd::record_attestation(&pool, &att(a, "urn:x:ab", sb, sb, 100.0, "INITIAL_REPORT")).await.unwrap()));
    assert!(rejected(ibd::record_attestation(&pool, &att(a, "urn:x:ab", sa, sc, 100.0, "INITIAL_REPORT")).await.unwrap()));

    // A non-consented exchange cannot be attested.
    exchange(&pool, "urn:x:pending", a, b, "IBD_AUTOSOMAL", "PENDING").await;
    assert!(rejected(ibd::record_attestation(&pool, &att(a, "urn:x:pending", sa, sb, 100.0, "INITIAL_REPORT")).await.unwrap()));

    // A consented but non-IBD exchange is out of scope.
    exchange(&pool, "urn:x:pii", a, b, "GENEALOGY_PII", "CONSENTED").await;
    assert!(rejected(ibd::record_attestation(&pool, &att(a, "urn:x:pii", sa, sb, 100.0, "INITIAL_REPORT")).await.unwrap()));

    // Confirm alice↔bob, then a DISPUTE downgrades it and revokes discoverability.
    ibd::record_attestation(&pool, &att(a, "urn:x:ab", sa, sb, 100.0, "INITIAL_REPORT")).await.unwrap();
    let confirmed = ibd::record_attestation(&pool, &att(b, "urn:x:ab", sb, sa, 105.0, "INITIAL_REPORT")).await.unwrap();
    assert!(matches!(confirmed, AttestationOutcome::Recorded { publicly_discoverable: true, .. }));
    let disputed = ibd::record_attestation(&pool, &att(b, "urn:x:ab", sb, sa, 0.0, "DISPUTE")).await.unwrap();
    let AttestationOutcome::Recorded { consensus_status, publicly_discoverable, .. } = &disputed else {
        panic!("expected Recorded, got {disputed:?}");
    };
    assert_eq!(consensus_status, "DISPUTED");
    assert!(!publicly_discoverable, "a dispute revokes public discoverability");
}

// ── depth weighting ────────────────────────────────────────────────────────────

async fn hg_node(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1, 'Y_DNA') RETURNING id")
        .bind(name)
        .fetch_one(pool)
        .await
        .expect("insert haplogroup")
}

async fn hg_edge(pool: &PgPool, parent: i64, child: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id) VALUES ($1, $2)")
        .bind(child)
        .bind(parent)
        .execute(pool)
        .await
        .expect("insert relationship");
}

/// A federated sample with a distinct ancestry block (so it never population-overlaps any
/// other) carrying a single Y consensus haplogroup — isolates the HAPLOGROUP signal.
async fn hg_sample(pool: &PgPool, did: &str, pop: &str, pca: Value, hg: &str, t: i64) -> Uuid {
    let guid = fed_sample(pool, did).await;
    sqlx::query(
        "INSERT INTO fed.population_breakdown \
            (did, rkey, at_uri, biosample_ref, analysis_method, super_population_summary, components, pca_coordinates, time_us) \
         VALUES ($1, $2, $3, $4, 'PCA_PROJECTION_GMM', $5, $6, $7, $8)",
    )
    .bind(did)
    .bind(format!("pb-{t}"))
    .bind(format!("at://{did}/pb/{t}"))
    .bind(format!("at://{did}/bio"))
    .bind(json!([{ "superPopulation": pop, "percentage": 100.0 }]))
    .bind(json!([{ "population": pop, "percentage": 100.0 }]))
    .bind(pca)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert breakdown");
    sqlx::query(
        "INSERT INTO fed.haplogroup_reconciliation \
            (did, rkey, at_uri, dna_type, consensus_haplogroup, confidence, run_count, time_us) \
         VALUES ($1, $2, $3, 'Y_DNA', $4, 0.9, 2, $5)",
    )
    .bind(did)
    .bind(format!("rec-{t}"))
    .bind(format!("at://{did}/rec/{t}"))
    .bind(hg)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert reconciliation");
    guid
}

#[tokio::test]
async fn haplogroup_depth_weights_deeper_shares_higher() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping ibd depth test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Tree: ROOT(0) → N1 → … → N5 (depth 5), and ROOT → S1 (depth 1).
    let root = hg_node(&pool, "ROOT").await;
    let mut prev = root;
    for i in 1..=5 {
        let n = hg_node(&pool, &format!("N{i}")).await;
        hg_edge(&pool, prev, n).await;
        prev = n;
    }
    let s1 = hg_node(&pool, "S1").await;
    hg_edge(&pool, root, s1).await;

    // Two equal-rarity pairs (each 2 of 4 samples), distinct ancestry blocks so the only
    // signal is the shared haplogroup: deep pair shares N5 (depth 5), shallow pair shares S1.
    let d1 = hg_sample(&pool, "did:ex:d1", "EUR", json!([10.0, 10.0]), "N5", 1).await;
    let d2 = hg_sample(&pool, "did:ex:d2", "SAS", json!([-10.0, 10.0]), "N5", 2).await;
    let _s1a = hg_sample(&pool, "did:ex:s1", "EAS", json!([10.0, -10.0]), "S1", 3).await;
    let s2 = hg_sample(&pool, "did:ex:s2", "AMR", json!([-10.0, -10.0]), "S1", 4).await;

    ibd::recompute_suggestions(&pool, &IbdConfig::default()).await.unwrap();

    let score_of = |from: Uuid, to: Uuid| {
        let pool = pool.clone();
        async move {
            ibd::suggestions_for(&pool, from, 50)
                .await
                .unwrap()
                .into_iter()
                .find(|s| s.suggested_sample_guid == to && s.suggestion_type == "HAPLOGROUP")
                .and_then(|s| s.score)
                .expect("haplogroup suggestion exists")
        }
    };
    let deep = score_of(d1, d2).await;
    let shallow = score_of(s2, _s1a).await;
    assert!(
        deep > shallow,
        "a deeper shared clade (N5, depth 5) must outscore a shallow one (S1, depth 1): {deep} vs {shallow}"
    );
}
