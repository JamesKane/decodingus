//! Live-DB test for the `naming_status` normalizer
//! (`du_db::variant::normalize_naming_status_batch`). A real canonical name must read
//! NAMED; only a coordinate placeholder (`chr%:%`) or a NULL name is UNNAMED; the curator
//! PENDING_REVIEW workflow state is left alone. Skips when DATABASE_URL is unset.

use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn insert(pool: &PgPool, name: &str, status: &str) {
    sqlx::query(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status) \
         VALUES ($1, 'SNP'::core.mutation_type, $2::core.naming_status)",
    )
    .bind(name)
    .bind(status)
    .execute(pool)
    .await
    .expect("insert variant");
}

async fn status_of(pool: &PgPool, name: &str) -> String {
    sqlx::query_scalar("SELECT naming_status::text FROM core.variant WHERE canonical_name = $1")
        .bind(name)
        .fetch_one(pool)
        .await
        .expect("fetch status")
}

#[tokio::test]
async fn normalizes_status_from_the_name() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping naming_status test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool();

    // The oxymoron the fix targets: an established community name stamped UNNAMED.
    insert(pool, "CTS4466", "UNNAMED").await;
    // A coordinate placeholder wrongly stamped NAMED (must flip the other way).
    insert(pool, "chrY:2812345A>G", "NAMED").await;
    // Already correct — must not be touched (and not counted).
    insert(pool, "M269", "NAMED").await;
    // Curator workflow state — left alone even though it has a real name.
    insert(pool, "A15857", "PENDING_REVIEW").await;

    let fixed = du_db::variant::normalize_naming_status_batch(pool, 10_000)
        .await
        .expect("normalize");
    assert_eq!(fixed, 2, "only the two mis-stamped rows change");

    assert_eq!(status_of(pool, "CTS4466").await, "NAMED", "real name ⇒ NAMED");
    assert_eq!(status_of(pool, "chrY:2812345A>G").await, "UNNAMED", "placeholder ⇒ UNNAMED");
    assert_eq!(status_of(pool, "M269").await, "NAMED", "already-correct untouched");
    assert_eq!(status_of(pool, "A15857").await, "PENDING_REVIEW", "PENDING_REVIEW preserved");

    // Idempotent: a second pass finds nothing.
    let again = du_db::variant::normalize_naming_status_batch(pool, 10_000)
        .await
        .expect("normalize again");
    assert_eq!(again, 0, "converges to 0");
}
