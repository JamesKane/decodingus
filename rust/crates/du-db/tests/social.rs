//! Live-DB tests for `du_db::social`: the team↔tester support thread lifecycle
//! (open → team reply → user reopen → close, with per-side unread flags) and the
//! announcement/community feed (pinning, replies, reputation default, bidirectional
//! blocks, author-gated soft-delete). Skips when DATABASE_URL is unset.

use du_db::social::{self, ReadSide};
use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// Bridge a throwaway DID into ident.users (the tester identity path).
async fn user(pool: &PgPool, name: &str) -> Uuid {
    du_db::auth::upsert_user_by_did(pool, &format!("did:test:{name}"), None, Some(name))
        .await
        .expect("user")
        .0
}

#[tokio::test]
async fn support_thread_roundtrip_and_status_transitions() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping social thread test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool();

    let tester = user(pool, "alpha-tester").await;
    let curator = user(pool, "team-curator").await;

    let conv = social::open_support_thread(pool, tester, Some("login broken"), "I can't sign in")
        .await
        .expect("open");

    // Authz helper: the thread belongs to the tester.
    assert_eq!(social::thread_requester(pool, conv).await.unwrap(), Some(tester));

    // Fresh thread is open and unread for the team.
    let inbox = social::team_inbox(pool, Some("open"), 1, 50).await.unwrap();
    let t = inbox.items.iter().find(|t| t.id == conv).expect("in open inbox");
    assert!(t.team_unread, "new tester message is unread for team");
    assert!(!t.user_unread, "tester's own message is not unread for them");
    assert_eq!(t.requester_name.as_deref(), Some("alpha-tester"));
    assert_eq!(social::team_open_count(pool).await.unwrap(), 1);

    // Team reads + replies → status replied, user now has an unread.
    social::mark_read(pool, conv, ReadSide::Team).await.unwrap();
    social::post_message(pool, conv, curator, "Try clearing cookies", true).await.unwrap();
    let mine = social::user_threads(pool, tester, 1, 50).await.unwrap();
    let t = mine.items.iter().find(|t| t.id == conv).expect("user thread");
    assert_eq!(t.status, "replied");
    assert!(t.user_unread, "team reply is unread for the user");
    assert_eq!(social::user_unread_count(pool, tester).await.unwrap(), 1, "one unread team reply");
    assert_eq!(social::team_open_count(pool).await.unwrap(), 0, "replied ≠ open");

    // Once the user reads it, the badge clears.
    social::mark_read(pool, conv, ReadSide::User).await.unwrap();
    assert_eq!(social::user_unread_count(pool, tester).await.unwrap(), 0, "read clears the badge");

    // User replies → reopens (back in the team queue).
    social::post_message(pool, conv, tester, "still broken", false).await.unwrap();
    assert_eq!(social::team_open_count(pool).await.unwrap(), 1, "user reply reopens");
    let msgs = social::thread_messages(pool, conv).await.unwrap();
    assert_eq!(msgs.len(), 3);
    assert!(!msgs[0].from_team && msgs[1].from_team && !msgs[2].from_team);
    assert_eq!(msgs[1].sender_name.as_deref(), Some("team-curator"));

    social::set_status(pool, conv, "closed").await.unwrap();
    let closed = social::team_inbox(pool, Some("closed"), 1, 50).await.unwrap();
    assert!(closed.items.iter().any(|t| t.id == conv));
}

#[tokio::test]
async fn feed_announcements_replies_and_blocks() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping social feed test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool();

    let team = user(pool, "feed-team").await;
    let citizen = user(pool, "feed-citizen").await;

    let ann = social::create_post(pool, team, "ANNOUNCEMENT", Some("general"), "Beta build 3 is live", None)
        .await
        .expect("announcement");
    social::set_pinned(pool, ann, true).await.unwrap();
    social::create_post(pool, citizen, "COMMUNITY", Some("general"), "Nice!", Some(ann))
        .await
        .unwrap();

    // Announcements list shows the pinned post with its reply count; the reply is a
    // COMMUNITY post but does NOT appear as a top-level community item.
    let anns = social::list_feed(pool, Some("ANNOUNCEMENT"), None, 1, 50).await.unwrap();
    let a = anns.items.iter().find(|p| p.id == ann).expect("announcement listed");
    assert!(a.pinned && a.reply_count == 1);
    assert_eq!(social::post_replies(pool, ann).await.unwrap().len(), 1);
    let community = social::list_feed(pool, Some("COMMUNITY"), None, 1, 50).await.unwrap();
    assert!(community.items.is_empty(), "a reply is not a top-level feed post");

    // Blocks are bidirectional.
    assert!(!social::is_blocked_either(pool, team, citizen).await.unwrap());
    social::block(pool, citizen, team, Some("spam")).await.unwrap();
    assert!(social::is_blocked_either(pool, team, citizen).await.unwrap(), "either direction");
    social::unblock(pool, citizen, team).await.unwrap();
    assert!(!social::is_blocked_either(pool, team, citizen).await.unwrap());

    // Author soft-delete is gated to the author.
    assert!(!social::delete_post(pool, ann, Some(citizen)).await.unwrap(), "non-author cannot delete");
    assert!(social::delete_post(pool, ann, Some(team)).await.unwrap());
    assert!(social::get_post(pool, ann).await.unwrap().is_none());
}

#[tokio::test]
async fn feed_voting_drives_reputation_and_moderation() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping feed voting test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool();
    let author = user(pool, "vote-author").await;
    let voter = user(pool, "vote-voter").await;
    let curator = user(pool, "vote-mod").await;

    let post = social::create_post(pool, author, "COMMUNITY", None, "hello world", None).await.unwrap();

    // Upvote: post score +1, author reputation +1, voter's vote recorded.
    assert_eq!(social::cast_vote(pool, post, voter, author, 1).await.unwrap(), 1);
    assert_eq!(du_db::reputation::score_of(pool, author).await.unwrap(), 1);
    assert_eq!(social::user_vote(pool, post, voter).await.unwrap(), 1);

    // Re-casting the same value clears the vote (toggle off): score and rep back to 0.
    assert_eq!(social::cast_vote(pool, post, voter, author, 1).await.unwrap(), 0);
    assert_eq!(du_db::reputation::score_of(pool, author).await.unwrap(), 0);
    assert_eq!(social::user_vote(pool, post, voter).await.unwrap(), 0);

    // Downvote: −1 each.
    assert_eq!(social::cast_vote(pool, post, voter, author, -1).await.unwrap(), -1);
    assert_eq!(du_db::reputation::score_of(pool, author).await.unwrap(), -1);

    // Self-votes are a no-op.
    assert_eq!(social::cast_vote(pool, post, author, author, 1).await.unwrap(), -1, "self-vote ignored");
    assert_eq!(du_db::reputation::score_of(pool, author).await.unwrap(), -1);

    // Report → moderation queue → resolve as spam removes the post and docks the author.
    social::report_post(pool, post, voter, Some("spam")).await.unwrap();
    let reports = social::open_reports(pool).await.unwrap();
    assert_eq!(reports.len(), 1);
    assert_eq!(social::open_report_count(pool).await.unwrap(), 1);
    let report_id = reports[0].id;

    assert!(social::resolve_report(pool, report_id, curator, true).await.unwrap());
    assert!(social::get_post(pool, post).await.unwrap().is_none(), "spam post removed");
    assert_eq!(du_db::reputation::score_of(pool, author).await.unwrap(), -51, "-1 vote + -50 spam");
    assert_eq!(social::open_report_count(pool).await.unwrap(), 0);
    assert!(!social::resolve_report(pool, report_id, curator, true).await.unwrap(), "already resolved");
}
