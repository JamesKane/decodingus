//! Live-DB test for `du_db::recruitment` — the privacy-preserving cohort broker:
//! haplogroup cohort computation (with self-exclusion), delivery, per-target response,
//! and the key invariant that the researcher read-path (`accepted_dids`) surfaces ONLY
//! opt-ins — never invited/declined DIDs. Skips when DATABASE_URL is unset.

use du_db::{recruitment, research};
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn biosample(pool: &PgPool, did: &str, rkey: &str, y_hap: &str) {
    sqlx::query("INSERT INTO fed.biosample (did, rkey, at_uri, y_haplogroup, time_us) VALUES ($1,$2,$3,$4,$5)")
        .bind(did)
        .bind(rkey)
        .bind(format!("at://{did}/bs/{rkey}"))
        .bind(y_hap)
        .bind(1i64)
        .execute(pool)
        .await
        .expect("biosample");
}

#[tokio::test]
async fn cohort_delivery_response_and_optin_privacy() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping recruitment test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool();

    // The researcher (project owner) + four DIDs: three R-M269 matches and one off-target.
    let researcher_did = "did:test:researcher";
    let ruid = du_db::auth::upsert_user_by_did(pool, researcher_did, None, Some("Researcher")).await.unwrap().0;
    for (did, name) in [("did:test:m1", "Match One"), ("did:test:m2", "Match Two"), ("did:test:m3", "Match Three")] {
        du_db::auth::upsert_user_by_did(pool, did, None, Some(name)).await.unwrap();
    }
    biosample(pool, "did:test:m1", "a", "R-M269").await;
    biosample(pool, "did:test:m2", "a", "R-M269").await;
    biosample(pool, "did:test:m3", "a", "R-M269").await;
    biosample(pool, "did:test:other", "a", "J-M172").await;
    biosample(pool, researcher_did, "a", "R-M269").await; // the researcher matches but is excluded

    let project = research::create_project(pool, "R study", "HAPLOGROUP", Some("Y_DNA"), None, researcher_did)
        .await
        .unwrap();
    let cid = recruitment::create_campaign(pool, project, ruid, "Join", "Help us", "R-M269", "Y_DNA").await.unwrap();

    // Cohort = the three matches, excluding the off-target sample AND the researcher.
    let cohort = recruitment::compute_cohort(pool, "R-M269", "Y_DNA", Some(researcher_did)).await.unwrap();
    assert_eq!(cohort.len(), 3);
    assert!(cohort.contains(&"did:test:m1".to_string()));
    assert!(!cohort.contains(&researcher_did.to_string()), "researcher excluded");
    assert!(!cohort.contains(&"did:test:other".to_string()), "off-target excluded");

    // Deliver → three INVITED, cohort_size set, fresh = all three. Re-deliver is a no-op.
    let fresh = recruitment::deliver(pool, cid, &cohort).await.unwrap();
    assert_eq!(fresh.len(), 3);
    assert!(recruitment::deliver(pool, cid, &cohort).await.unwrap().is_empty(), "re-deliver doesn't re-invite");
    let summary = &recruitment::campaigns_for_project(pool, project).await.unwrap()[0];
    assert_eq!(summary.cohort_size, 3);
    assert_eq!(summary.accepted_count, 0);

    // Authz: a target sees their status; a non-target gets None.
    assert_eq!(recruitment::target_status(pool, cid, "did:test:m1").await.unwrap().as_deref(), Some("INVITED"));
    assert!(recruitment::target_status(pool, cid, "did:test:nobody").await.unwrap().is_none());

    // m1 accepts (idempotent), m2 declines, m3 stays invited.
    assert!(recruitment::respond(pool, cid, "did:test:m1", true).await.unwrap());
    assert!(!recruitment::respond(pool, cid, "did:test:m1", true).await.unwrap(), "no re-response");
    assert!(recruitment::respond(pool, cid, "did:test:m2", false).await.unwrap());

    // THE PRIVACY INVARIANT: the researcher sees only the opt-in — not the decliner, not
    // the still-invited member.
    let accepted = recruitment::accepted_dids(pool, cid).await.unwrap();
    assert_eq!(accepted, vec!["did:test:m1".to_string()]);
    assert_eq!(recruitment::campaigns_for_project(pool, project).await.unwrap()[0].accepted_count, 1);

    // Open invitations track the still-pending target only.
    assert_eq!(recruitment::invitations_for(pool, "did:test:m3").await.unwrap().len(), 1);
    assert!(recruitment::invitations_for(pool, "did:test:m1").await.unwrap().is_empty(), "answered ⇒ no longer pending");
}
