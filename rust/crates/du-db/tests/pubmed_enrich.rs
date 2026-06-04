//! Live-DB test for PubMed enrichment (`du_db::publication`). Covers candidate
//! selection, gap-fill, the curated-value guard, and the UNIQUE-safe DOI set.
//! Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test pubmed_enrich

use chrono::NaiveDate;
use du_db::publication::{pmids_needing_enrichment, update_pubmed, PubMedUpdate};
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}


async fn insert(pool: &PgPool, sql: &str) -> i64 {
    sqlx::query_scalar(sql).fetch_one(pool).await.expect("insert")
}

/// Find the returned PublicationId for a given pubmed_id among the candidates.
async fn pid_for(pool: &PgPool, pmid: &str) -> Option<du_domain::ids::PublicationId> {
    let cands = pmids_needing_enrichment(pool, 200).await.expect("candidates");
    cands.into_iter().find(|(_, p)| p == pmid).map(|(id, _)| id)
}

#[tokio::test]
async fn pubmed_enrichment_fills_gaps_safely() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping pubmed_enrich test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // A publication with a PMID but no journal/authors/date/doi.
    let _id1 = insert(
        &pool,
        "INSERT INTO pubs.publication (pubmed_id, title) VALUES ('TESTPM-1', 'TESTPM one') RETURNING id",
    )
    .await;
    // A publication with a curated journal already set (must not be clobbered).
    let _id2 = insert(
        &pool,
        "INSERT INTO pubs.publication (pubmed_id, title, journal) VALUES ('TESTPM-2', 'TESTPM two', 'Curated Journal') RETURNING id",
    )
    .await;
    // A publication already owning a DOI we'll try to (and must not) steal.
    insert(
        &pool,
        "INSERT INTO pubs.publication (pubmed_id, title, doi) VALUES ('TESTPM-OWNER', 'TESTPM owner', '10.dup/x') RETURNING id",
    )
    .await;
    // A publication with no DOI that will attempt to take the duplicate.
    let _id3 = insert(
        &pool,
        "INSERT INTO pubs.publication (pubmed_id, title) VALUES ('TESTPM-3', 'TESTPM three') RETURNING id",
    )
    .await;

    // Candidate set includes the gap-having rows (TESTPM-OWNER lacks journal too).
    let p1 = pid_for(&pool, "TESTPM-1").await.expect("p1 is a candidate");

    // Fill gaps on TESTPM-1.
    let updated = update_pubmed(
        &pool,
        p1,
        &PubMedUpdate {
            journal: Some("Science".into()),
            publication_date: NaiveDate::from_ymd_opt(2019, 9, 6),
            authors: Some("Narasimhan VM, Patterson N".into()),
            doi: Some("10.1126/science.aat7487".into()),
        },
    )
    .await
    .expect("update p1");
    assert!(updated);
    let (journal, authors, doi): (Option<String>, Option<String>, Option<String>) = sqlx::query_as(
        "SELECT journal, authors, doi FROM pubs.publication WHERE pubmed_id = 'TESTPM-1'",
    )
    .fetch_one(&pool)
    .await
    .expect("reselect p1");
    assert_eq!(journal.as_deref(), Some("Science"));
    assert_eq!(authors.as_deref(), Some("Narasimhan VM, Patterson N"));
    assert_eq!(doi.as_deref(), Some("10.1126/science.aat7487"));

    // Curated journal must survive an enrichment attempt (gap-fill, not overwrite).
    let p2 = pid_for(&pool, "TESTPM-2").await.expect("p2 candidate");
    update_pubmed(&pool, p2, &PubMedUpdate { journal: Some("Override".into()), ..Default::default() })
        .await
        .expect("update p2");
    let j2: Option<String> = sqlx::query_scalar("SELECT journal FROM pubs.publication WHERE pubmed_id = 'TESTPM-2'")
        .fetch_one(&pool)
        .await
        .expect("j2");
    assert_eq!(j2.as_deref(), Some("Curated Journal"), "curated journal preserved");

    // Taking an already-used DOI must be skipped (no UNIQUE violation, doi stays null).
    let p3 = pid_for(&pool, "TESTPM-3").await.expect("p3 candidate");
    update_pubmed(&pool, p3, &PubMedUpdate { doi: Some("10.dup/x".into()), ..Default::default() })
        .await
        .expect("update p3 must not error on dup doi");
    let d3: Option<String> = sqlx::query_scalar("SELECT doi FROM pubs.publication WHERE pubmed_id = 'TESTPM-3'")
        .fetch_one(&pool)
        .await
        .expect("d3");
    assert_eq!(d3, None, "duplicate DOI not taken");

}
