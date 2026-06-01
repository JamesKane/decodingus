//! Integration test: applies the redesigned schema to a live Postgres and
//! exercises the JSONB variant contract end-to-end.
//!
//! Skips (passes) when DATABASE_URL is unset so `cargo test` stays green without
//! a database. To run for real:
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db -- --nocapture

use du_domain::variant::{BuildCoordinate, Coordinates};
use du_domain::ReferenceBuild;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

#[tokio::test]
async fn migrations_apply_and_variant_jsonb_roundtrips() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping live-DB test");
        return;
    };

    let pool = du_db::connect(&url, 4).await.expect("connect");
    du_db::run_migrations(&pool).await.expect("run migrations");

    // A representative table from every schema in the redesign exists — proves
    // the full migration sequence (0001..0009) applies as a unit.
    for obj in [
        "core.variant",
        "core.biosample",
        "core.specimen_donor",
        "core.genome_region",
        "tree.haplogroup",
        "tree.change_set",
        "tree.biosample_private_variant",
        "genomics.sequence_file",
        "genomics.alignment_metadata",
        "genomics.test_type_definition",
        "pubs.publication",
        "pubs.publication_biosample",
        "ident.users",
        "ident.roles",
        "ibd.ibd_discovery_index",
        "ibd.population_breakdown",
        "fed.pds_node",
        "fed.pds_submission",
        "social.reputation_event",
        "social.group_project",
        "support.contact_message",
        "billing.patron_subscription",
    ] {
        let exists: Option<String> = sqlx::query_scalar("SELECT to_regclass($1)::text")
            .bind(obj)
            .fetch_one(&pool)
            .await
            .expect("to_regclass");
        assert_eq!(exists.as_deref(), Some(obj), "{obj} should exist");
    }

    // The base RBAC roles are seeded.
    let roles: i64 =
        sqlx::query_scalar("SELECT count(*) FROM ident.roles WHERE name IN ('Admin','Curator','TreeCurator')")
            .fetch_one(&pool)
            .await
            .expect("count roles");
    assert_eq!(roles, 3, "base roles should be seeded");

    // PostGIS / citext extensions are installed.
    let postgis: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM pg_extension WHERE extname IN ('postgis','citext','pgcrypto')",
    )
    .fetch_one(&pool)
    .await
    .expect("pg_extension");
    assert_eq!(postgis, 3, "postgis, citext, pgcrypto should be installed");

    // Insert a variant whose coordinates JSONB is the du-domain shape, then read
    // it back and confirm it deserializes and is queryable by JSONB path.
    let mut coords = Coordinates::default();
    coords.set(
        ReferenceBuild::GRCh38,
        BuildCoordinate {
            contig: "chrY".into(),
            position: 2_787_319,
            reference_allele: Some("C".into()),
            alternate_allele: Some("T".into()),
        },
    );
    let coords_json = serde_json::to_value(&coords).unwrap();

    let id: i64 = sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, coordinates)
         VALUES ($1, 'SNP', $2) RETURNING id",
    )
    .bind("M269")
    .bind(&coords_json)
    .fetch_one(&pool)
    .await
    .expect("insert variant");

    // Read the JSONB back as a serde_json::Value and decode into the typed shape.
    let stored: serde_json::Value =
        sqlx::query_scalar("SELECT coordinates FROM core.variant WHERE id = $1")
            .bind(id)
            .fetch_one(&pool)
            .await
            .expect("select coordinates");
    let decoded: Coordinates = serde_json::from_value(stored).unwrap();
    assert_eq!(decoded.get(ReferenceBuild::GRCh38).unwrap().position, 2_787_319);

    // JSONB-path query (the kind the GIN index accelerates) finds it by build.
    let by_path: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM core.variant WHERE coordinates -> 'GRCh38' ->> 'contig' = 'chrY'",
    )
    .fetch_one(&pool)
    .await
    .expect("jsonb path query");
    assert!(by_path >= 1, "variant should be found by JSONB path");

    // Clean up so the test is re-runnable against a persistent DB.
    sqlx::query("DELETE FROM core.variant WHERE id = $1")
        .bind(id)
        .execute(&pool)
        .await
        .expect("cleanup");
}
