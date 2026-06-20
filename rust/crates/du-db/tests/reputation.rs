//! Live-DB tests for `du_db::reputation`: the seeded event types, the ledger↔score
//! invariant (sum of actual_points_change == cached score), vote-delta overrides,
//! idempotent one-time bonuses, and the guard. Skips when DATABASE_URL is unset.

use du_db::reputation::{self, events, Related};
use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn user(pool: &PgPool, name: &str) -> Uuid {
    du_db::auth::upsert_user_by_did(pool, &format!("did:test:{name}"), None, Some(name))
        .await
        .expect("user")
        .0
}

#[tokio::test]
async fn ledger_score_invariant_and_one_time_bonuses() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping reputation test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool();
    let author = user(pool, "rep-author").await;
    let voter = user(pool, "rep-voter").await;
    let post = Uuid::from_u128(0xABCD);

    // Default-points award (verified = +10).
    assert_eq!(reputation::record_event(pool, author, events::ACCOUNT_VERIFIED, None, None, None).await.unwrap(), 10);

    // A vote with an explicit delta override (down→up toggle = +2), attributed + related.
    let related = Related { entity_type: "FEED_POST", entity_id: post };
    let score = reputation::record_event(pool, author, events::FEED_POST_UPVOTED, Some(2), Some(related), Some(voter))
        .await
        .unwrap();
    assert_eq!(score, 12);
    assert_eq!(reputation::score_of(pool, author).await.unwrap(), 12);

    // Ledger and cache agree (the core invariant).
    let ledger_sum: i64 = sqlx::query_scalar(
        "SELECT COALESCE(sum(actual_points_change),0) FROM social.reputation_event WHERE user_id = $1",
    )
    .bind(author)
    .fetch_one(pool)
    .await
    .unwrap();
    assert_eq!(ledger_sum, 12, "sum(ledger) == cached score");

    // One-time bonus is idempotent: two calls award once.
    let first = reputation::record_once(pool, author, events::NEW_USER_BONUS).await.unwrap();
    let second = reputation::record_once(pool, author, events::NEW_USER_BONUS).await.unwrap();
    assert_eq!(first, 17, "+5 welcome once");
    assert_eq!(second, 17, "second call is a no-op");
    let bonus_rows: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM social.reputation_event e JOIN social.reputation_event_type t \
         ON t.id = e.event_type_id WHERE e.user_id = $1 AND t.name = $2",
    )
    .bind(author)
    .bind(events::NEW_USER_BONUS)
    .fetch_one(pool)
    .await
    .unwrap();
    assert_eq!(bonus_rows, 1, "exactly one welcome event");

    // Unknown event type → Conflict, not a silent miss.
    assert!(reputation::record_event(pool, author, "NOPE", None, None, None).await.is_err());

    // Guard: feed gate is open for alpha (0), and at_least reflects the score.
    assert!(reputation::can_post_to_feed(pool, voter).await.unwrap());
    assert!(reputation::at_least(pool, author, 17).await.unwrap());
    assert!(!reputation::at_least(pool, author, 18).await.unwrap());
}
