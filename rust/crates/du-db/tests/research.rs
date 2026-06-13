//! Live-DB test for the D2 ResearchSubject registry (`du_db::research`):
//! register + membership, the tombstone merge (repoint + audit + retired_into),
//! custody flip, and the authorization readers. Skips when DATABASE_URL is unset.

use du_db::research;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn project(pool: &PgPool, name: &str, owner: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO social.group_project (project_name, project_type, owner_did) VALUES ($1, 'RESEARCH', $2) RETURNING id",
    )
    .bind(name)
    .bind(owner)
    .fetch_one(pool)
    .await
    .expect("insert project")
}

#[tokio::test]
async fn registry_register_merge_custody() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping research test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let (a, b) = ("did:key:zAdminA", "did:key:zAdminB");
    let p1 = project(&pool, "P1", a).await;
    let p2 = project(&pool, "P2", b).await;

    // Mint two pseudonymous subjects (one per project).
    let s1 = research::register_in_project(&pool, None, p1, a).await.unwrap();
    let s2 = research::register_in_project(&pool, None, p2, b).await.unwrap();
    assert_ne!(s1, s2);
    assert_eq!(research::subjects_in_project(&pool, p1).await.unwrap().len(), 1);

    // Authorization readers.
    assert_eq!(research::project_owner(&pool, p1).await.unwrap().as_deref(), Some(a));
    assert!(research::is_steward_of(&pool, a, s1).await.unwrap());
    assert!(!research::is_steward_of(&pool, b, s1).await.unwrap());
    assert!(research::is_project_participant(&pool, a, p1).await.unwrap());
    assert!(!research::is_project_participant(&pool, "did:key:zNobody", p1).await.unwrap());

    // Register is idempotent for an externally-agreed id (the id-exchange case).
    let again = research::register_in_project(&pool, Some(s1), p1, a).await.unwrap();
    assert_eq!(again, s1);

    // Merge s2 → s1: s2 is tombstoned, its P2 membership repoints to s1, audit recorded.
    research::merge_subjects(&pool, s1, s2, "GENETIC", a, Some(0.91)).await.unwrap();
    assert_eq!(research::subject(&pool, s2).await.unwrap().unwrap().retired_into, Some(s1));
    // s1 is now in BOTH projects (P2 membership moved over).
    assert_eq!(research::subjects_in_project(&pool, p2).await.unwrap()[0].research_subject_id, s1);
    assert!(research::subjects_in_project(&pool, p1).await.unwrap().iter().all(|r| r.research_subject_id == s1));
    let links: i64 = sqlx::query_scalar("SELECT count(*) FROM research.subject_link WHERE subject_a = $1 AND subject_b = $2")
        .bind(s1)
        .bind(s2)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(links, 1, "merge is audited");

    // A self-merge is rejected.
    assert!(research::merge_subjects(&pool, s1, s1, "GENETIC", a, None).await.is_err());

    // Custody flip (the member-claim pointer).
    assert!(research::set_custody(&pool, s1, "did:key:zMember").await.unwrap());
    assert_eq!(research::subject(&pool, s1).await.unwrap().unwrap().custody_did.as_deref(), Some("did:key:zMember"));

    // Optional sparse biosample link.
    let sample: uuid::Uuid = sqlx::query_scalar(
        "INSERT INTO core.biosample (source) VALUES ('CITIZEN'::core.biosample_source) RETURNING sample_guid",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    research::link_biosample(&pool, s1, sample).await.unwrap();
    let n: i64 = sqlx::query_scalar("SELECT count(*) FROM research.subject_biosample WHERE research_subject_id = $1")
        .bind(s1)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(n, 1);
}

#[tokio::test]
async fn acl_roles_membership_and_revocation() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping ACL test");
        return;
    };
    use du_db::research::{Capability, Role};
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let owner = "did:key:zOwner";
    let p = project(&pool, "Team", owner).await;

    // The owner is the implicit founding ADMIN.
    assert_eq!(research::role_of(&pool, p, owner).await.unwrap(), Some(Role::Admin));
    assert!(research::role_of(&pool, p, "did:key:zStranger").await.unwrap().is_none());
    assert!(research::can(&pool, p, owner, Capability::ManageRoles).await.unwrap());

    // Add a CO_ADMIN and a MODERATOR.
    research::add_member(&pool, p, "did:key:zCo", Role::CoAdmin, &[], owner).await.unwrap();
    research::add_member(&pool, p, "did:key:zMod", Role::Moderator, &[], owner).await.unwrap();
    // CO_ADMIN can manage subjects but not roles; MODERATOR can only read.
    assert!(research::can(&pool, p, "did:key:zCo", Capability::ManageSubjects).await.unwrap());
    assert!(!research::can(&pool, p, "did:key:zCo", Capability::ManageRoles).await.unwrap());
    assert!(!research::can(&pool, p, "did:key:zMod", Capability::ManageSubjects).await.unwrap());
    assert!(research::can(&pool, p, "did:key:zMod", Capability::ReadProject).await.unwrap());
    // Team list = owner + the two added.
    assert_eq!(research::members_of(&pool, p).await.unwrap().len(), 3);

    // Revocation drops the co-admin from the ACL immediately.
    assert!(research::revoke_member(&pool, p, "did:key:zCo").await.unwrap());
    assert!(research::role_of(&pool, p, "did:key:zCo").await.unwrap().is_none());
    assert_eq!(research::members_of(&pool, p).await.unwrap().len(), 2);

    // Capability map spot-checks (D5 §4).
    assert!(Role::Curator.allows(Capability::PromoteToCatalog));
    assert!(!Role::Curator.allows(Capability::ManageRoles));
    assert!(Role::Admin.allows(Capability::ResolveDispute));
    assert!(!Role::Moderator.allows(Capability::WriteAssertions));
}
