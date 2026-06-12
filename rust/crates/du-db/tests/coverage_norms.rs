//! Live-DB test for the empirical per-test-type coverage norms
//! (`du_db::coverage::recompute_norms`). Seeds federated coverage + sequenceruns,
//! aggregates the cohort median/quartiles, and checks idempotency. Skips when
//! DATABASE_URL is unset.

use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// A federated sequencerun carrying a test type.
async fn run(pool: &PgPool, did: &str, run_uri: &str, test_type: &str, t: i64) {
    sqlx::query(
        "INSERT INTO fed.sequencerun (did, rkey, at_uri, test_type, time_us) VALUES ($1,$2,$3,$4,$5)",
    )
    .bind(did)
    .bind(format!("run-{t}"))
    .bind(run_uri)
    .bind(test_type)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert run");
}

/// A federated coverage summary for a run (mean depth + pct@30x).
async fn coverage(pool: &PgPool, did: &str, run_uri: &str, mean: f64, pct30: f64, t: i64) {
    sqlx::query(
        "INSERT INTO fed.coverage_summary (did, collection, rkey, at_uri, sequence_run_ref, mean_coverage, pct_30x, time_us) \
         VALUES ($1, 'com.decodingus.atmosphere.alignment', $2, $3, $4, $5, $6, $7)",
    )
    .bind(did)
    .bind(format!("cov-{t}"))
    .bind(format!("at://{did}/cov/{t}"))
    .bind(run_uri)
    .bind(mean)
    .bind(pct30)
    .bind(t)
    .execute(pool)
    .await
    .expect("insert coverage");
}

#[tokio::test]
async fn norms_aggregate_cohort_and_are_idempotent() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping coverage-norms test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // WGS cohort: three runs at 28/30/32× (median 30, p25 29, p75 31).
    let mut t = 1i64;
    for (did, mean) in [("did:ex:a", 28.0), ("did:ex:b", 30.0), ("did:ex:c", 32.0)] {
        let uri = format!("at://{did}/run");
        run(&pool, did, &uri, "WGS", t).await;
        coverage(&pool, did, &uri, mean, 90.0, t).await;
        t += 1;
    }
    // A second test type, sparser.
    for (did, mean) in [("did:ex:d", 55.0), ("did:ex:e", 65.0)] {
        let uri = format!("at://{did}/run");
        run(&pool, did, &uri, "BIG_Y_700", t).await;
        coverage(&pool, did, &uri, mean, 95.0, t).await;
        t += 1;
    }

    let rep = du_db::coverage::recompute_norms(&pool).await.expect("recompute");
    assert_eq!(rep.test_types, 2);
    assert_eq!(rep.pruned, 0);

    let norms = du_db::coverage::norms(&pool).await.expect("norms");
    let wgs = norms.iter().find(|n| n.test_type == "WGS").expect("WGS norm");
    assert_eq!(wgs.sample_count, 3);
    assert_eq!(wgs.median_mean_depth, Some(30.0));
    assert_eq!(wgs.p25_mean_depth, Some(29.0));
    assert_eq!(wgs.p75_mean_depth, Some(31.0));
    assert_eq!(wgs.median_pct_30x, Some(90.0));
    let by = norms.iter().find(|n| n.test_type == "BIG_Y_700").expect("BIG_Y norm");
    assert_eq!(by.sample_count, 2);
    assert_eq!(by.median_mean_depth, Some(60.0));

    // norm_for reader.
    let one = du_db::coverage::norm_for(&pool, "WGS").await.expect("norm_for").expect("found");
    assert_eq!(one.sample_count, 3);

    // Idempotency: a second recompute over unchanged data keeps the same values.
    du_db::coverage::recompute_norms(&pool).await.expect("recompute2");
    let again = du_db::coverage::norm_for(&pool, "WGS").await.expect("norm_for2").expect("found");
    assert_eq!(again.median_mean_depth, Some(30.0));
    assert_eq!(du_db::coverage::norms(&pool).await.unwrap().len(), 2, "no duplicate test-type rows");
}
