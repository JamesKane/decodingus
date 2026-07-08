//! Live-DB test for the publication-candidate review queue
//! (`du_db::publication` candidate fns). Upserts candidates, reviews/promotes
//! them, and asserts the resulting `pubs.publication`. Prefix `TESTPC-`.
//! Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test publication_candidate

use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}


async fn test_user(pool: &PgPool) -> Uuid {
    sqlx::query_scalar(
        "INSERT INTO ident.users (handle, display_name) VALUES ('testpc-curator', 'Test Curator') \
         ON CONFLICT (handle) DO UPDATE SET display_name = EXCLUDED.display_name RETURNING id",
    )
    .fetch_one(pool)
    .await
    .expect("test user")
}

#[tokio::test]
async fn candidate_review_and_promote() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping publication_candidate test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let curator = test_user(&pool).await;

    // Discovery upserts two candidates.
    du_db::publication::upsert_candidate(
        &pool, "TESTPC-W1", Some("10.1234/testpc.1"), Some("A Y-DNA study"),
        Some("abstract one"), None, Some("J. Phylogenetics"),
    ).await.expect("upsert 1");
    du_db::publication::upsert_candidate(
        &pool, "TESTPC-W2", None, Some("An off-topic paper"), None, None, None,
    ).await.expect("upsert 2");

    // Both are pending.
    let pending = du_db::publication::list_candidates(&pool, Some("pending"), 1, 50).await.expect("list");
    let mine: Vec<_> = pending.items.iter().filter(|c| c.openalex_id.starts_with("TESTPC-")).collect();
    assert_eq!(mine.len(), 2, "two pending candidates");

    let c1 = du_db::publication::list_candidates(&pool, Some("pending"), 1, 50)
        .await.unwrap().items.into_iter().find(|c| c.openalex_id == "TESTPC-W1").unwrap();

    // Promote W1 → a real publication; candidate flips to accepted.
    let pub_id = du_db::publication::promote_candidate(&pool, c1.id, curator).await.expect("promote");
    let got = du_db::publication::get_by_id(&pool, pub_id).await.expect("get pub").expect("pub exists");
    assert_eq!(got.title, "A Y-DNA study");
    assert_eq!(got.doi.as_deref(), Some("10.1234/testpc.1"));
    let c1_after = du_db::publication::get_candidate(&pool, c1.id).await.unwrap().unwrap();
    assert_eq!(c1_after.status, "accepted");

    // Promote is idempotent: re-promoting reuses the same publication (no dup).
    let pub_id2 = du_db::publication::promote_candidate(&pool, c1.id, curator).await.expect("re-promote");
    assert_eq!(pub_id, pub_id2, "re-promote reuses existing publication");
    let dup: i64 = sqlx::query_scalar("SELECT count(*) FROM pubs.publication WHERE open_alex_id = 'TESTPC-W1'")
        .fetch_one(&pool).await.unwrap();
    assert_eq!(dup, 1, "no duplicate publication");

    // Reject W2.
    let c2 = du_db::publication::list_candidates(&pool, None, 1, 50)
        .await.unwrap().items.into_iter().find(|c| c.openalex_id == "TESTPC-W2").unwrap();
    assert!(du_db::publication::review_candidate(&pool, c2.id, "rejected", curator).await.expect("reject"));
    let c2_after = du_db::publication::get_candidate(&pool, c2.id).await.unwrap().unwrap();
    assert_eq!(c2_after.status, "rejected");

    // Filter now shows one accepted, one rejected, zero pending (of ours).
    let still_pending = du_db::publication::list_candidates(&pool, Some("pending"), 1, 50)
        .await.unwrap().items.into_iter().filter(|c| c.openalex_id.starts_with("TESTPC-")).count();
    assert_eq!(still_pending, 0, "no TESTPC pending left");

}

#[tokio::test]
async fn bulk_system_reject_only_touches_pending() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping bulk_system_reject test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let curator = test_user(&pool).await;

    // Three pending candidates + one already accepted.
    for oa in ["TESTPC-R1", "TESTPC-R2", "TESTPC-R3", "TESTPC-R4"] {
        du_db::publication::upsert_candidate(&pool, oa, None, Some("t"), None, None, None)
            .await.expect("upsert");
    }
    let r4 = du_db::publication::list_candidates(&pool, None, 1, 100)
        .await.unwrap().items.into_iter().find(|c| c.openalex_id == "TESTPC-R4").unwrap();
    du_db::publication::review_candidate(&pool, r4.id, "accepted", curator).await.unwrap();

    // pending_candidate_ids returns exactly our three still-pending rows.
    let pending = du_db::publication::pending_candidate_ids(&pool).await.expect("pending ids");
    let ours: Vec<i64> = pending.iter()
        .filter(|(_, oa)| oa.starts_with("TESTPC-R"))
        .map(|(id, _)| *id)
        .collect();
    assert_eq!(ours.len(), 3, "R1..R3 pending (R4 is accepted)");

    // Bulk-reject all four ids: only the three pending flip; the accepted one is safe.
    let all_ids: Vec<i64> = [ours.clone(), vec![r4.id]].concat();
    let n = du_db::publication::reject_candidates_system(&pool, &all_ids).await.expect("bulk reject");
    assert_eq!(n, 3, "only the 3 pending rows were rejected");

    let r4_after = du_db::publication::get_candidate(&pool, r4.id).await.unwrap().unwrap();
    assert_eq!(r4_after.status, "accepted", "accepted candidate untouched");
    for id in &ours {
        let c = du_db::publication::get_candidate(&pool, *id).await.unwrap().unwrap();
        assert_eq!(c.status, "rejected");
    }

    // Idempotent: a second run rejects nothing (no rows still pending).
    let n2 = du_db::publication::reject_candidates_system(&pool, &all_ids).await.expect("re-reject");
    assert_eq!(n2, 0, "nothing left to reject");
    assert_eq!(du_db::publication::reject_candidates_system(&pool, &[]).await.unwrap(), 0, "empty is a no-op");
}
