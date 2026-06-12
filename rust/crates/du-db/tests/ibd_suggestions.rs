//! Live-DB test for the IBD candidate-generation engine
//! (`du_db::ibd::recompute_suggestions`). Seeds federated ancestry breakdowns +
//! a shared haplogroup, then checks ancestry blocking, the signals, idempotency,
//! and that a dismissed pair is not re-suggested. Skips when DATABASE_URL is unset.

use du_db::ibd::{self, IbdConfig};
use serde_json::{json, Value};
use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// A federated biosample (atproto uri + repo_did); returns (sample_guid, bio uri).
async fn fed_sample(pool: &PgPool, did: &str) -> (Uuid, String) {
    let bio_uri = format!("at://{did}/bio");
    let guid: Uuid = sqlx::query_scalar(
        "INSERT INTO core.biosample (source, atproto) \
         VALUES ('CITIZEN'::core.biosample_source, jsonb_build_object('uri',$1::text,'repo_did',$2::text)) \
         RETURNING sample_guid",
    )
    .bind(&bio_uri)
    .bind(did)
    .fetch_one(pool)
    .await
    .expect("insert biosample");
    (guid, bio_uri)
}

async fn breakdown(pool: &PgPool, did: &str, bio_uri: &str, super_pop: &str, components: Value, pca: Value, t: i64) {
    sqlx::query(
        "INSERT INTO fed.population_breakdown \
            (did, rkey, at_uri, biosample_ref, analysis_method, super_population_summary, components, pca_coordinates, time_us) \
         VALUES ($1, $2, $3, $4, 'PCA_PROJECTION_GMM', $5, $6, $7, $8)",
    )
    .bind(did)
    .bind(format!("pb-{t}"))
    .bind(format!("at://{did}/pb/{t}"))
    .bind(bio_uri)
    .bind(json!([{ "superPopulation": super_pop, "percentage": 100.0 }]))
    .bind(components)
    .bind(pca)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert population_breakdown");
}

async fn y_haplogroup(pool: &PgPool, did: &str, hg: &str, t: i64) {
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
}

fn euro() -> Value {
    json!([{ "population": "Steppe", "percentage": 50.0 }, { "population": "EEF", "percentage": 50.0 }])
}

#[tokio::test]
async fn candidate_generation_blocks_signals_and_respects_dismiss() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping ibd test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // ann & ben: same European ancestry + nearby PCA → same block, overlap 1.0.
    let (ann, ann_uri) = fed_sample(&pool, "did:ex:ann").await;
    breakdown(&pool, "did:ex:ann", &ann_uri, "EUR", euro(), json!([0.01, 0.01]), 1).await;
    y_haplogroup(&pool, "did:ex:ann", "R-FT100", 1).await;

    let (ben, ben_uri) = fed_sample(&pool, "did:ex:ben").await;
    breakdown(&pool, "did:ex:ben", &ben_uri, "EUR", euro(), json!([0.012, 0.011]), 2).await;

    // cat: East-Asian, far PCA → different block, no overlap.
    let (cat, cat_uri) = fed_sample(&pool, "did:ex:cat").await;
    breakdown(&pool, "did:ex:cat", &cat_uri, "EAS", json!([{ "population": "Han", "percentage": 100.0 }]), json!([5.0, 5.0]), 3).await;

    // dan: different ancestry (no overlap with ann) but shares the rare Y haplogroup.
    let (dan, dan_uri) = fed_sample(&pool, "did:ex:dan").await;
    breakdown(&pool, "did:ex:dan", &dan_uri, "AMR", json!([{ "population": "Native American", "percentage": 100.0 }]), json!([-5.0, -5.0]), 4).await;
    y_haplogroup(&pool, "did:ex:dan", "R-FT100", 4).await;

    let cfg = IbdConfig::default();
    let rep = ibd::recompute_suggestions(&pool, &cfg).await.expect("recompute");
    assert_eq!(rep.population_pairs, 1, "only the within-block European pair overlaps");
    assert_eq!(rep.haplogroup_pairs, 1, "ann↔dan share the Y haplogroup");

    // ann's candidates: ben (population overlap) + dan (haplogroup); NOT cat (blocked).
    let anns = ibd::suggestions_for(&pool, ann, 50).await.expect("suggestions");
    let by: std::collections::HashMap<Uuid, String> =
        anns.iter().map(|s| (s.suggested_sample_guid, s.suggestion_type.clone())).collect();
    assert_eq!(by.get(&ben).map(String::as_str), Some("POPULATION_OVERLAP"));
    assert_eq!(by.get(&dan).map(String::as_str), Some("HAPLOGROUP"));
    assert!(!by.contains_key(&cat), "cross-continental pair is blocked, never suggested");

    // Idempotency: a second run reproduces the same active suggestion set.
    let count_active = || async {
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM ibd.match_suggestion WHERE status = 'ACTIVE'")
            .fetch_one(&pool)
            .await
            .unwrap()
    };
    let before = count_active().await;
    ibd::recompute_suggestions(&pool, &cfg).await.expect("recompute2");
    assert_eq!(count_active().await, before, "idempotent: no thrash");

    // Dismiss ann→ben; a re-run must not resurrect it.
    sqlx::query("UPDATE ibd.match_suggestion SET status = 'DISMISSED' WHERE target_sample_guid = $1 AND suggested_sample_guid = $2")
        .bind(ann)
        .bind(ben)
        .execute(&pool)
        .await
        .unwrap();
    ibd::recompute_suggestions(&pool, &cfg).await.expect("recompute3");
    let anns2 = ibd::suggestions_for(&pool, ann, 50).await.expect("suggestions2");
    assert!(!anns2.iter().any(|s| s.suggested_sample_guid == ben), "dismissed pair is not re-suggested");
    assert!(anns2.iter().any(|s| s.suggested_sample_guid == dan), "other suggestions remain");
}
