//! Live-DB test for the federated coverage mirror (`du_db::fed::coverage`).
//! Upserts a couple of mirrored summary records (DID prefix `did:test:`), checks
//! the out-of-order guard, aggregation, delete, and the cursor round-trip.
//! Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test fed_coverage

use du_db::fed;
use du_db::fed::coverage::{self, CoverageRecord};
use serde_json::json;
use sqlx::PgPool;

const COLL: &str = "com.decodingus.atmosphere.alignment";

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn cleanup(pool: &PgPool) {
    let _ = sqlx::query("DELETE FROM fed.coverage_summary WHERE did LIKE 'did:test:%'")
        .execute(pool)
        .await;
}

fn rec(did: &str, rkey: &str, mean: f64, pct30: f64, time_us: i64) -> CoverageRecord {
    CoverageRecord {
        did: did.to_string(),
        collection: COLL.to_string(),
        rkey: rkey.to_string(),
        at_uri: format!("at://{did}/{COLL}/{rkey}"),
        cid: Some("bafytest".to_string()),
        biosample_ref: Some(format!("at://{did}/com.decodingus.atmosphere.biosample/{rkey}")),
        sequence_run_ref: None,
        reference_build: Some("GRCh38".to_string()),
        aligner: Some("BWA-MEM 0.7.17".to_string()),
        mean_coverage: Some(mean),
        median_coverage: Some(mean),
        pct_10x: Some(99.0),
        pct_20x: Some(95.0),
        pct_30x: Some(pct30),
        metrics: json!({ "meanCoverage": mean, "pct30x": pct30, "contigs": [] }),
        record_created_at: None,
        time_us,
    }
}

#[tokio::test]
async fn coverage_mirror_upsert_guard_aggregate_delete() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping fed_coverage test");
        return;
    };
    let pool = du_db::connect(&url, 4).await.expect("connect");
    du_db::run_migrations(&pool).await.expect("migrate");
    cleanup(&pool).await;

    // Two distinct samples on GRCh38.
    coverage::upsert(&pool, &rec("did:test:a", "r1", 30.0, 90.0, 100)).await.expect("upsert a");
    coverage::upsert(&pool, &rec("did:test:b", "r1", 10.0, 50.0, 100)).await.expect("upsert b");

    // Newer event updates the row...
    coverage::upsert(&pool, &rec("did:test:a", "r1", 40.0, 95.0, 200)).await.expect("update a");
    // ...but a stale (older time_us) event must NOT overwrite it.
    coverage::upsert(&pool, &rec("did:test:a", "r1", 1.0, 1.0, 150)).await.expect("stale a");

    let builds = coverage::aggregate_by_build(&pool).await.expect("aggregate");
    let grch38 = builds
        .iter()
        .find(|b| b.reference_build.as_deref() == Some("GRCh38"))
        .expect("GRCh38 bucket");
    assert_eq!(grch38.samples, 2, "two mirrored samples");
    // mean of {40, 10} = 25 — proves the stale write was rejected (would be ~5.5).
    let mean = grch38.mean_coverage.expect("mean");
    assert!((mean - 25.0).abs() < 1e-6, "expected mean 25.0, got {mean}");

    // Delete one sample; aggregate drops to a single row.
    fed::delete(&pool, COLL, "did:test:b", "r1").await.expect("delete b");
    let after = coverage::aggregate_by_build(&pool).await.expect("aggregate 2");
    let grch38 = after
        .iter()
        .find(|b| b.reference_build.as_deref() == Some("GRCh38"))
        .expect("GRCh38 bucket");
    assert_eq!(grch38.samples, 1, "one sample after delete");

    // Cursor round-trip.
    fed::save_cursor(&pool, 12_345).await.expect("save cursor");
    assert_eq!(fed::load_cursor(&pool).await.expect("load cursor"), Some(12_345));
    fed::save_cursor(&pool, 67_890).await.expect("save cursor 2");
    assert_eq!(fed::load_cursor(&pool).await.expect("load cursor 2"), Some(67_890));

    cleanup(&pool).await;
}
