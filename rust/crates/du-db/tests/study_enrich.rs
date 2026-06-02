//! Live-DB test for ENA study enrichment (`du_db::study`). Seeds an ENA study
//! with no title/center, confirms it's a candidate, applies metadata, and checks
//! the gaps fill (and that it drops out of the candidate set). Re-runnable; skips
//! (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test study_enrich

use chrono::NaiveDate;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn cleanup(pool: &PgPool) {
    let _ = sqlx::query("DELETE FROM pubs.genomic_study WHERE accession LIKE 'TESTENA-%'")
        .execute(pool)
        .await;
}

#[tokio::test]
async fn ena_enrichment_fills_gaps() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping study_enrich test");
        return;
    };
    let pool = du_db::connect(&url, 4).await.expect("connect");
    du_db::run_migrations(&pool).await.expect("migrate");
    cleanup(&pool).await;

    // An ENA study lacking title + center.
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO pubs.genomic_study (accession, source) VALUES ('TESTENA-1', 'ENA') RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .expect("insert study");
    // A non-ENA study should never be a candidate.
    sqlx::query("INSERT INTO pubs.genomic_study (accession, source) VALUES ('TESTENA-2', 'NCBI_BIOPROJECT')")
        .execute(&pool)
        .await
        .expect("insert ncbi study");

    let cands = du_db::study::needing_ena_enrichment(&pool, 50).await.expect("candidates");
    assert!(cands.iter().any(|c| c.id == id && c.accession == "TESTENA-1"), "ENA study is a candidate");
    assert!(!cands.iter().any(|c| c.accession == "TESTENA-2"), "non-ENA study excluded");

    let updated = du_db::study::apply_ena_metadata(
        &pool,
        id,
        Some("Steppe ancient genomes"),
        Some("Some Institute"),
        NaiveDate::from_ymd_opt(2019, 7, 1),
    )
    .await
    .expect("apply");
    assert!(updated, "row updated");

    let (title, center, first_public): (Option<String>, Option<String>, Option<String>) = sqlx::query_as(
        "SELECT title, center_name, details->>'ena_first_public' FROM pubs.genomic_study WHERE id = $1",
    )
    .bind(id)
    .fetch_one(&pool)
    .await
    .expect("reselect");
    assert_eq!(title.as_deref(), Some("Steppe ancient genomes"));
    assert_eq!(center.as_deref(), Some("Some Institute"));
    assert_eq!(first_public.as_deref(), Some("2019-07-01"));

    // Now enriched → no longer a candidate.
    let after = du_db::study::needing_ena_enrichment(&pool, 50).await.expect("candidates 2");
    assert!(!after.iter().any(|c| c.id == id), "enriched study drops out");

    // COALESCE must not clobber an existing title.
    du_db::study::apply_ena_metadata(&pool, id, Some("OVERWRITE?"), None, None).await.expect("apply 2");
    let title2: Option<String> = sqlx::query_scalar("SELECT title FROM pubs.genomic_study WHERE id = $1")
        .bind(id)
        .fetch_one(&pool)
        .await
        .expect("title2");
    assert_eq!(title2.as_deref(), Some("Steppe ancient genomes"), "existing title preserved");

    cleanup(&pool).await;
}
