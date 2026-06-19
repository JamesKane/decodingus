//! Live-DB tests for the group-project social surface (`du_db::research` project
//! helpers + the `kind=PROJECT` feed): create/list/membership, and that a project's
//! members-only feed does NOT leak into the global community feed. Skips when
//! DATABASE_URL is unset.

use du_db::research::{self, Capability, Role};
use du_db::social;
use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// Create a user bridged from a DID; returns (uuid, did).
async fn user(pool: &PgPool, name: &str) -> (Uuid, String) {
    let did = format!("did:test:{name}");
    let uid = du_db::auth::upsert_user_by_did(pool, &did, None, Some(name)).await.expect("user").0;
    (uid, did)
}

#[tokio::test]
async fn project_create_membership_and_feed_isolation() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping projects test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool();
    let (owner_uid, owner_did) = user(pool, "proj-owner").await;
    let (_member_uid, member_did) = user(pool, "proj-member").await;
    let (_outsider_uid, outsider_did) = user(pool, "proj-outsider").await;

    // Create → owner is the founding ADMIN.
    let id = research::create_project(pool, "R-M269 study", "HAPLOGROUP", Some("Y_DNA"), Some("desc"), &owner_did)
        .await
        .unwrap();
    let p = research::get_project(pool, id).await.unwrap().expect("project");
    assert_eq!(p.project_name, "R-M269 study");
    assert_eq!(research::role_of(pool, id, &owner_did).await.unwrap(), Some(Role::Admin));
    assert!(research::can(pool, id, &owner_did, Capability::ManageRoles).await.unwrap());

    // Listing reflects ownership/membership.
    assert_eq!(research::projects_for_member(pool, &owner_did).await.unwrap().len(), 1);
    assert!(research::projects_for_member(pool, &member_did).await.unwrap().is_empty());

    // Add a CO_ADMIN member (not an admin for ManageRoles).
    research::add_member(pool, id, &member_did, Role::CoAdmin, &[], &owner_did).await.unwrap();
    assert!(research::is_team_member(pool, id, &member_did).await.unwrap());
    assert!(!research::can(pool, id, &member_did, Capability::ManageRoles).await.unwrap());
    assert_eq!(research::projects_for_member(pool, &member_did).await.unwrap().len(), 1);
    assert!(!research::is_team_member(pool, id, &outsider_did).await.unwrap());

    // The members-only project feed is kind=PROJECT + topic=project:<id> and must NOT
    // appear in the global community feed.
    let topic = format!("project:{id}");
    social::create_post(pool, owner_uid, "PROJECT", Some(&topic), "members only", None).await.unwrap();
    let in_project = social::list_feed(pool, Some("PROJECT"), Some(&topic), 1, 50).await.unwrap();
    assert_eq!(in_project.items.len(), 1, "post is in the project feed");
    let global = social::list_feed(pool, Some("COMMUNITY"), None, 1, 50).await.unwrap();
    assert!(global.items.is_empty(), "project post must not leak into the community feed");

    // Revoke drops membership.
    assert!(research::revoke_member(pool, id, &member_did).await.unwrap());
    assert!(!research::is_team_member(pool, id, &member_did).await.unwrap());
}
