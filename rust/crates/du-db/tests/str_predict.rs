//! Live-DB test for STR→branch prediction (`du_db::ystr::predict`). Seeds two Y
//! branch signatures and predicts a query close to one. Re-runnable; skips when
//! DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test str_predict

use du_db::ystr::{predict, StrValue};
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn cleanup(pool: &PgPool) {
    let _ = sqlx::query(
        "DELETE FROM tree.haplogroup_ancestral_str WHERE haplogroup_id IN \
         (SELECT id FROM tree.haplogroup WHERE name LIKE 'TESTPRED-%')",
    )
    .execute(pool)
    .await;
    let _ = sqlx::query("DELETE FROM tree.haplogroup WHERE name LIKE 'TESTPRED-%'").execute(pool).await;
}

async fn seed_branch(pool: &PgPool, name: &str, markers: &[(&str, i32)]) {
    let id: i64 =
        sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1,'Y_DNA'::core.dna_type) RETURNING id")
            .bind(name)
            .fetch_one(pool)
            .await
            .expect("insert hg");
    for (marker, value) in markers {
        sqlx::query(
            "INSERT INTO tree.haplogroup_ancestral_str (haplogroup_id, marker_name, ancestral_value, method) \
             VALUES ($1,$2,$3,'MODAL')",
        )
        .bind(id)
        .bind(marker)
        .bind(value)
        .execute(pool)
        .await
        .expect("insert sig");
    }
}

fn q(markers: &[(&str, i32)]) -> Vec<(String, StrValue)> {
    markers.iter().map(|(m, v)| (m.to_string(), StrValue::Simple(*v))).collect()
}

#[tokio::test]
async fn predict_ranks_closest_branch() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping str_predict test");
        return;
    };
    let pool = du_db::connect(&url, 4).await.expect("connect");
    du_db::run_migrations(&pool).await.expect("migrate");
    cleanup(&pool).await;

    seed_branch(&pool, "TESTPRED-A", &[("DYS393", 13), ("DYS390", 24), ("DYS19", 14), ("DYS391", 11)]).await;
    seed_branch(&pool, "TESTPRED-B", &[("DYS393", 14), ("DYS390", 25), ("DYS19", 15), ("DYS391", 10)]).await;

    // Query: identical to A except DYS391 (12 vs 11) → GD(A)=1, GD(B)=5.
    let query = q(&[("DYS393", 13), ("DYS390", 24), ("DYS19", 14), ("DYS391", 12)]);
    let preds = predict(&pool, &query, 10, 4).await.expect("predict");

    // Only our two seeded branches have a full 4-marker overlap; both should rank,
    // A first.
    let a = preds.iter().position(|p| p.haplogroup == "TESTPRED-A");
    let b = preds.iter().position(|p| p.haplogroup == "TESTPRED-B");
    assert!(a.is_some() && b.is_some(), "both seeded branches predicted");
    assert!(a.unwrap() < b.unwrap(), "closer branch A ranks before B");
    let pa = &preds[a.unwrap()];
    assert_eq!(pa.distance, 1, "GD(A) = 1");
    assert_eq!(pa.compared_markers, 4);
    let pb = preds.iter().find(|p| p.haplogroup == "TESTPRED-B").unwrap();
    assert_eq!(pb.distance, 5, "GD(B) = 5");

    // min_compared gate: require 5 shared markers (more than the 4 we have) → none.
    let gated = predict(&pool, &query, 10, 5).await.expect("predict gated");
    assert!(!gated.iter().any(|p| p.haplogroup.starts_with("TESTPRED-")), "min_compared excludes both");

    cleanup(&pool).await;
}
