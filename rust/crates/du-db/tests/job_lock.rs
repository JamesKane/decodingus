//! Live-DB test for the job serialization lock (`du_db::job_lock`). Proves the core
//! "never overlap" guarantee: while one holder has the lock, a `skip` acquire is
//! refused. Skips (passes) when DATABASE_URL is unset.

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

#[tokio::test]
async fn lock_is_mutually_exclusive() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping job_lock test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // First holder (blocking) takes the lock and keeps it.
    let held = du_db::job_lock::acquire(&pool, true).await.expect("acquire");
    assert!(held.is_some(), "first acquire gets the lock");

    // A concurrent try-acquire (a `skip` poller) is refused while it's held — this is
    // the "never overlap" guarantee: two holders never coexist.
    let refused = du_db::job_lock::acquire(&pool, false).await.expect("try-acquire");
    assert!(refused.is_none(), "try-acquire is refused while the lock is held");
    drop(held);
}
