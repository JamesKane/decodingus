//! Live-DB test for the tree revision marker (`du_db::tree_revision`). Seeded at
//! 1, monotonically bumped (standalone and in-transaction), and a change-set apply
//! bumps it. Re-runnable; skips (passes) when DATABASE_URL is unset.

use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

#[tokio::test]
async fn revision_seeds_and_bumps() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping tree_revision test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool: PgPool = db.pool().clone();

    // Seeded at 1.
    let (rev0, t0) = du_db::tree_revision::current(&pool).await.expect("current");
    assert_eq!(rev0, 1);

    // Standalone bump.
    let rev1 = du_db::tree_revision::bump(&pool).await.expect("bump");
    assert_eq!(rev1, 2);
    let (rev1r, t1) = du_db::tree_revision::current(&pool).await.expect("current");
    assert_eq!(rev1r, 2);
    assert!(t1 >= t0, "updated_at advances");

    // In-transaction bump that commits.
    let mut tx = pool.begin().await.expect("begin");
    let rev2 = du_db::tree_revision::bump(&mut *tx).await.expect("bump in tx");
    assert_eq!(rev2, 3);
    tx.commit().await.expect("commit");
    assert_eq!(du_db::tree_revision::current(&pool).await.expect("current").0, 3);

    // A rolled-back bump does not advance the marker.
    let mut tx = pool.begin().await.expect("begin");
    du_db::tree_revision::bump(&mut *tx).await.expect("bump in tx");
    tx.rollback().await.expect("rollback");
    assert_eq!(du_db::tree_revision::current(&pool).await.expect("current").0, 3);
}
