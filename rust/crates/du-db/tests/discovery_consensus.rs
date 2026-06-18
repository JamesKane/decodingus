//! Live-DB test for the discovery variant-set consensus engine
//! (`du_db::discovery::recompute_consensus`). Seeds federated private-variant
//! records, materializes + pools them, and asserts the proposal, idempotency,
//! and split flagging. Skips when DATABASE_URL is unset.

use du_db::discovery::{self, DiscoveryConfig};
use serde_json::json;
use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// A federated biosample (with an atproto uri the engine resolves to sample_guid).
async fn biosample(pool: &PgPool, did: &str) -> (Uuid, String) {
    let uri = format!("at://{did}/bio");
    let guid: Uuid = sqlx::query_scalar(
        "INSERT INTO core.biosample (source, atproto) \
         VALUES ('CITIZEN'::core.biosample_source, jsonb_build_object('uri',$1::text,'repo_did',$2::text)) \
         RETURNING sample_guid",
    )
    .bind(&uri)
    .bind(did)
    .fetch_one(pool)
    .await
    .expect("insert biosample");
    (guid, uri)
}

/// One federated privateVariant record: a sample's variant set beneath `terminal`.
async fn private_variant(pool: &PgPool, did: &str, bio_uri: &str, terminal: &str, variants: serde_json::Value, t: i64) {
    sqlx::query(
        "INSERT INTO fed.private_variant (did, rkey, at_uri, biosample_ref, dna_type, terminal_haplogroup, variants, time_us) \
         VALUES ($1,$2,$3,$4,'Y_DNA',$5,$6,$7)",
    )
    .bind(did)
    .bind(format!("pv-{t}"))
    .bind(format!("at://{did}/pv/{t}"))
    .bind(bio_uri)
    .bind(terminal)
    .bind(&variants)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert private_variant");
}

fn vcall(name: &str, pos: i64) -> serde_json::Value {
    json!({ "name": name, "contig": "chrY", "position": pos, "ancestral": "A", "derived": "G" })
}

/// A Y cross-technology consensus reconciliation for a citizen's repo DID.
async fn reconciliation(pool: &PgPool, did: &str, confidence: f64, t: i64) {
    sqlx::query(
        "INSERT INTO fed.haplogroup_reconciliation \
            (did, rkey, at_uri, dna_type, compatibility_level, consensus_haplogroup, confidence, run_count, time_us) \
         VALUES ($1, $2, $3, 'Y_DNA', 'COMPATIBLE', 'R-M269', $4, 2, $5)",
    )
    .bind(did)
    .bind(format!("rec-{t}"))
    .bind(format!("at://{did}/rec/{t}"))
    .bind(confidence)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert reconciliation");
}

/// A low-confidence contributor is excluded from pooling; a cluster of modest-but-
/// kept reliability is down-weighted below the READY threshold.
#[tokio::test]
async fn reliability_gates_and_downweights() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping reliability test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    for name in ["R-M269", "R-L21"] {
        sqlx::query("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1,'Y_DNA'::core.dna_type)")
            .bind(name)
            .execute(&pool)
            .await
            .expect("terminal");
    }
    let cfg = DiscoveryConfig::default();
    let shared = json!([vcall("FT9001", 21660001), vcall("FT9002", 21660002)]);

    // ── Gate: under R-M269, four contributors share the set; one has a LOW-confidence
    // consensus (0.2 < floor 0.5) and is excluded → the proposal counts 3, not 4.
    let mut t = 300i64;
    for (did, conf) in [("did:ex:ann", None), ("did:ex:ben", None), ("did:ex:cat", Some(0.95)), ("did:ex:dan", Some(0.2))] {
        let (_g, uri) = biosample(&pool, did).await;
        private_variant(&pool, did, &uri, "R-M269", shared.clone(), t).await;
        if let Some(c) = conf {
            reconciliation(&pool, did, c, t).await;
        }
        t += 1;
    }

    // ── Down-weight: under R-L21, three KEPT contributors all have modest reliability
    // (0.6 ≥ floor) → not excluded, but confidence is pulled below READY.
    for did in ["did:ex:eve", "did:ex:foy", "did:ex:gil"] {
        let (_g, uri) = biosample(&pool, did).await;
        private_variant(&pool, did, &uri, "R-L21", shared.clone(), t).await;
        reconciliation(&pool, did, 0.6, t).await;
        t += 1;
    }

    discovery::recompute_consensus(&pool, &cfg).await.expect("recompute");

    // R-M269: the 0.2 contributor excluded → count 3.
    let (m269_count, m269_status): (i32, String) = sqlx::query_as(
        "SELECT pb.evidence_count, pb.status FROM tree.proposed_branch pb \
         JOIN tree.haplogroup h ON h.id = pb.parent_haplogroup_id \
         WHERE h.name = 'R-M269' AND pb.cluster_key IS NOT NULL",
    )
    .fetch_one(&pool)
    .await
    .expect("R-M269 proposal");
    assert_eq!(m269_count, 3, "the low-confidence contributor is excluded");
    assert_eq!(m269_status, "READY_FOR_REVIEW");

    // R-L21: kept but down-weighted → 3 contributors yet not READY (confidence < 0.95).
    let (l21_count, l21_status, l21_conf): (i32, String, f64) = sqlx::query_as(
        "SELECT pb.evidence_count, pb.status, pb.confidence::float8 FROM tree.proposed_branch pb \
         JOIN tree.haplogroup h ON h.id = pb.parent_haplogroup_id \
         WHERE h.name = 'R-L21' AND pb.cluster_key IS NOT NULL",
    )
    .fetch_one(&pool)
    .await
    .expect("R-L21 proposal");
    assert_eq!(l21_count, 3, "modest-reliability contributors are kept");
    assert!(l21_conf < 0.95, "reliability down-weight pulls confidence below READY: {l21_conf}");
    assert_eq!(l21_status, "PROPOSED");
}

#[tokio::test]
async fn discovery_pools_proposes_and_is_idempotent() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping discovery test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // A terminal haplogroup the private variants extend.
    let _terminal_id: i64 =
        sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ('R-M269','Y_DNA'::core.dna_type) RETURNING id")
            .fetch_one(&pool)
            .await
            .expect("terminal");

    // Three citizens sharing the same two private variants below R-M269.
    let shared = json!([vcall("FT1001", 21648001), vcall("FT1002", 21648002)]);
    for (t, did) in (1i64..).zip(["did:ex:ann", "did:ex:ben", "did:ex:cat"]) {
        let (_g, uri) = biosample(&pool, did).await;
        private_variant(&pool, did, &uri, "R-M269", shared.clone(), t).await;
    }

    let cfg = DiscoveryConfig::default();
    let rep = discovery::recompute_consensus(&pool, &cfg).await.expect("recompute");
    assert_eq!(rep.bpv_upserted, 6, "3 samples × 2 variants materialized");
    assert_eq!(rep.proposals_ready, 1, "one READY proposal at consensus 3");

    // The proposal: under R-M269, 3 submitters, the 2 shared variants, READY.
    let (pid, count, status, conf): (i64, i32, String, f64) = sqlx::query_as(
        "SELECT pb.id, pb.evidence_count, pb.status, pb.confidence::float8 \
         FROM tree.proposed_branch pb JOIN tree.haplogroup h ON h.id = pb.parent_haplogroup_id \
         WHERE h.name = 'R-M269' AND pb.cluster_key IS NOT NULL",
    )
    .fetch_one(&pool)
    .await
    .expect("proposal");
    assert_eq!(count, 3);
    assert_eq!(status, "READY_FOR_REVIEW");
    assert!(conf >= 0.95, "confidence {conf}");
    let var_count: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.proposed_branch_variant WHERE proposed_branch_id = $1")
        .bind(pid)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(var_count, 2, "two defining variants");

    // Idempotency: a second recompute over unchanged fed data leaves the SAME
    // proposal id and the same counts (declarative, no thrash).
    discovery::recompute_consensus(&pool, &cfg).await.expect("recompute2");
    let (pid2, count2): (i64, i32) = sqlx::query_as(
        "SELECT id, evidence_count FROM tree.proposed_branch WHERE cluster_key IS NOT NULL",
    )
    .fetch_one(&pool)
    .await
    .expect("one proposal after re-run");
    assert_eq!(pid2, pid, "proposal id stable across recomputes");
    assert_eq!(count2, 3, "count not double-incremented");

    // A diverging submitter (shares both shared variants + one of its own → Jaccard
    // 2/3 ∈ [0.5,0.8) vs the consensus set) flags the branch for curator split review.
    let diverging = json!([vcall("FT1001", 21648001), vcall("FT1002", 21648002), vcall("FT2001", 21649001)]);
    let (_g, uri) = biosample(&pool, "did:ex:dave").await;
    private_variant(&pool, "did:ex:dave", &uri, "R-M269", diverging, 99).await;
    discovery::recompute_consensus(&pool, &cfg).await.expect("recompute3");
    let split_flag: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM tree.proposed_branch WHERE status = 'SPLIT_CANDIDATE' AND cluster_key IS NOT NULL) \
         OR EXISTS(SELECT 1 FROM tree.proposed_branch_evidence WHERE evidence_type = 'SPLIT_CANDIDATE')",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert!(split_flag, "diverging submitter flags a split candidate");
}

/// Promoting an engine proposal reassigns its contributing samples to the new
/// terminal (marks their private variants PROMOTED) and they are not re-proposed.
#[tokio::test]
async fn promotion_reassigns_and_freezes_contributors() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping promotion test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    sqlx::query("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ('R-M269','Y_DNA'::core.dna_type)")
        .execute(&pool)
        .await
        .expect("terminal");

    let shared = json!([vcall("FT3001", 21650001), vcall("FT3002", 21650002)]);
    let mut guids = Vec::new();
    for (t, did) in (200i64..).zip(["did:ex:eve", "did:ex:foy", "did:ex:gil"]) {
        let (g, uri) = biosample(&pool, did).await;
        guids.push(g);
        private_variant(&pool, did, &uri, "R-M269", shared.clone(), t).await;
    }

    let cfg = DiscoveryConfig::default();
    discovery::recompute_consensus(&pool, &cfg).await.expect("recompute");
    let pid: i64 = sqlx::query_scalar("SELECT id FROM tree.proposed_branch WHERE cluster_key IS NOT NULL")
        .fetch_one(&pool)
        .await
        .expect("proposal");

    // Curator names + accepts, then promotes.
    sqlx::query("UPDATE tree.proposed_branch SET proposed_name = 'R-NEW1' WHERE id = $1")
        .bind(pid)
        .execute(&pool)
        .await
        .unwrap();
    du_db::proposal::review(&pool, pid, "APPROVE", "tester", None).await.expect("approve");
    let new_hg: i64 = du_db::proposal::promote(&pool, pid, "tester").await.expect("promote");

    // Contributing private variants are PROMOTED and point at the new branch.
    let (promoted, active): (i64, i64) = sqlx::query_as(
        "SELECT count(*) FILTER (WHERE status='PROMOTED' AND terminal_haplogroup_id=$1), \
                count(*) FILTER (WHERE status='ACTIVE') \
         FROM tree.biosample_private_variant WHERE sample_guid = ANY($2)",
    )
    .bind(new_hg)
    .bind(&guids)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(promoted, 6, "3 samples × 2 variants reassigned to the new terminal");
    assert_eq!(active, 0, "no ACTIVE private variants remain for the promoted samples");

    // A subsequent recompute does not resurrect a proposal from the frozen samples.
    discovery::recompute_consensus(&pool, &cfg).await.expect("recompute2");
    let open: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.proposed_branch WHERE cluster_key IS NOT NULL \
         AND status IN ('PROPOSED','READY_FOR_REVIEW','SPLIT_CANDIDATE')",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(open, 0, "promoted contributions are not re-proposed");
}
