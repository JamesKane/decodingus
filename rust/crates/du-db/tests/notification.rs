//! Live-DB tests for `du_db::notification`: the producers wired into the social layer
//! (a team reply and a feed reply each notify the right recipient, self-replies don't),
//! plus the SYSTEM rail and read/unread bookkeeping. Skips when DATABASE_URL is unset.

use du_db::{notification, social};
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
async fn producers_and_system_rail_and_read_state() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping notification test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool();
    let tester = user(pool, "notif-tester").await;
    let curator = user(pool, "notif-curator").await;
    let other = user(pool, "notif-other").await;

    // Support thread: a team reply notifies the requester; a user reply does not create a
    // per-user notification (the team has the inbox badge instead).
    let conv = social::open_support_thread(pool, tester, Some("hi"), "help").await.unwrap();
    social::post_message(pool, conv, curator, "on it", true).await.unwrap();
    social::post_message(pool, conv, tester, "thanks", false).await.unwrap();
    assert_eq!(notification::unread_count(pool, tester).await.unwrap(), 1, "one team-reply notification");
    let items = notification::list(pool, tester, 50).await.unwrap();
    assert_eq!(items[0].kind, notification::kinds::THREAD_REPLY);
    assert_eq!(items[0].link.as_deref(), Some(format!("/messages/{conv}").as_str()));

    // Feed reply notifies the post author, attributed to the replier; a self-reply doesn't.
    let post = social::create_post(pool, tester, "COMMUNITY", None, "my post", None).await.unwrap();
    social::create_post(pool, other, "COMMUNITY", None, "nice", Some(post)).await.unwrap();
    social::create_post(pool, tester, "COMMUNITY", None, "self note", Some(post)).await.unwrap();
    let items = notification::list(pool, tester, 50).await.unwrap();
    let reply_notif = items.iter().find(|n| n.kind == notification::kinds::FEED_REPLY).expect("feed-reply notif");
    assert_eq!(reply_notif.actor_name.as_deref(), Some("notif-other"));
    assert_eq!(notification::unread_count(pool, tester).await.unwrap(), 2, "thread + feed reply, not self");

    // SYSTEM rail: a one-way platform alert with no actor (the IBD/consent entry point).
    notification::notify_system(pool, tester, "A possible match wants to connect", Some("/feed"), None)
        .await
        .unwrap();
    assert_eq!(notification::unread_count(pool, tester).await.unwrap(), 3);

    // Read bookkeeping is recipient-scoped: another user can't mark these read.
    let id = notification::list(pool, tester, 1).await.unwrap()[0].id;
    assert!(!notification::mark_read(pool, id, other).await.unwrap(), "not your notification");
    assert!(notification::mark_read(pool, id, tester).await.unwrap());
    assert_eq!(notification::unread_count(pool, tester).await.unwrap(), 2);
    notification::mark_all_read(pool, tester).await.unwrap();
    assert_eq!(notification::unread_count(pool, tester).await.unwrap(), 0);
}
