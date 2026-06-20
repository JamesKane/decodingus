//! Live-DB test for the federated feed mirror (`du_db::fed::feed`): upsert with the
//! time_us last-writer guard, the recency read with author-name resolution, top-level vs
//! reply filtering, and delete routing via `fed::delete`. Skips when DATABASE_URL is unset.

use du_db::fed::{self, feed};
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

fn post(did: &str, rkey: &str, text: &str, parent: Option<&str>, time_us: i64) -> feed::FeedPost {
    feed::FeedPost {
        common: fed::Common {
            did: did.into(),
            rkey: rkey.into(),
            at_uri: format!("at://{did}/com.decodingus.atmosphere.feed.post/{rkey}"),
            cid: Some("bafy".into()),
            record_created_at: Some(chrono::Utc::now()),
            time_us,
        },
        text: text.into(),
        topic: Some("general".into()),
        parent_uri: parent.map(String::from),
        root_uri: parent.map(String::from),
    }
}

#[tokio::test]
async fn mirror_upsert_recent_and_delete() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping fed feed test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool: &PgPool = db.pool();

    // Bridge the author so the feed can resolve a display name.
    du_db::auth::upsert_user_by_did(pool, "did:test:fedder", None, Some("Fedder")).await.unwrap();

    feed::upsert(pool, &post("did:test:fedder", "a", "hello atmosphere", None, 100)).await.unwrap();
    feed::upsert(pool, &post("did:test:fedder", "b", "a reply", Some("at://x/p/a"), 100)).await.unwrap();

    // recent() returns only the top-level post, with the resolved author name.
    let recent = feed::recent(pool, None, 50).await.unwrap();
    assert_eq!(recent.len(), 1, "replies are excluded from the top-level feed");
    assert_eq!(recent[0].text, "hello atmosphere");
    assert_eq!(recent[0].author_name.as_deref(), Some("Fedder"));

    // Last-writer guard: a stale (lower time_us) event can't clobber; a newer one wins.
    feed::upsert(pool, &post("did:test:fedder", "a", "STALE", None, 50)).await.unwrap();
    assert_eq!(feed::recent(pool, None, 50).await.unwrap()[0].text, "hello atmosphere");
    feed::upsert(pool, &post("did:test:fedder", "a", "edited", None, 200)).await.unwrap();
    assert_eq!(feed::recent(pool, None, 50).await.unwrap()[0].text, "edited");

    // Delete routes through the NSID map (a PDS tombstone).
    assert!(fed::delete(pool, fed::NS_FEED_POST, "did:test:fedder", "a").await.unwrap());
    assert!(feed::recent(pool, None, 50).await.unwrap().is_empty());
}
