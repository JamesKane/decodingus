//! Live-DB test for `du_db::sequencer` — instrument → lab lookup over the
//! preseeded direct `sequencer_instrument.lab_id` association. Re-runnable; skips
//! (passes) when DATABASE_URL is unset.

use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn lab(pool: &PgPool, name: &str, d2c: bool, web: Option<&str>) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO genomics.sequencing_lab (name, is_d2c, website_url) VALUES ($1,$2,$3) RETURNING id",
    )
    .bind(name)
    .bind(d2c)
    .bind(web)
    .fetch_one(pool)
    .await
    .expect("insert lab")
}

async fn instrument(pool: &PgPool, iid: &str, model: Option<&str>, manuf: Option<&str>, lab_id: Option<i64>) {
    sqlx::query(
        "INSERT INTO genomics.sequencer_instrument (instrument_id, model_name, manufacturer, lab_id) \
         VALUES ($1,$2,$3,$4)",
    )
    .bind(iid)
    .bind(model)
    .bind(manuf)
    .bind(lab_id)
    .execute(pool)
    .await
    .expect("insert instrument");
}

#[tokio::test]
async fn lookup_resolves_preseeded_association() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping sequencer test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    let nebula = lab(&pool, "Nebula Genomics", true, Some("https://nebula.org")).await;
    instrument(&pool, "A00123", Some("NovaSeq 6000"), Some("Illumina"), Some(nebula)).await;
    // An instrument with no preseeded lab association.
    instrument(&pool, "M01234", Some("MiSeq"), Some("Illumina"), None).await;

    // Known instrument resolves with full lab detail.
    let hit = du_db::sequencer::lookup_lab(&pool, "A00123").await.expect("lookup").expect("found");
    assert_eq!(hit.lab_name, "Nebula Genomics");
    assert!(hit.is_d2c);
    assert_eq!(hit.website_url.as_deref(), Some("https://nebula.org"));
    assert_eq!(hit.model_name.as_deref(), Some("NovaSeq 6000"));
    assert_eq!(hit.manufacturer.as_deref(), Some("Illumina"));

    // Unknown instrument → None.
    assert!(du_db::sequencer::lookup_lab(&pool, "ZZ99999").await.expect("lookup").is_none());
    // Instrument with no lab association → None (inner join drops it).
    assert!(du_db::sequencer::lookup_lab(&pool, "M01234").await.expect("lookup").is_none());

    // Bulk list returns only associated instruments.
    let all = du_db::sequencer::lab_instruments(&pool).await.expect("list");
    assert_eq!(all.len(), 1);
    assert_eq!(all[0].instrument_id, "A00123");
}
