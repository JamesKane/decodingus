//! Live-DB tests for the du-db query modules. Seeds sentinel rows (prefixed
//! `TESTQ-`), exercises each module, then cleans up so it is re-runnable against
//! a persistent database. Skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test queries

use du_domain::enums::DnaType;
use du_domain::ids::{PublicationId, SampleGuid};
use serde_json::json;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}


#[tokio::test]
async fn query_modules_against_live_db() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping live-DB query test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // ── seed ──────────────────────────────────────────────────────────────
    let v1: i64 = sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, aliases, coordinates) \
         VALUES ('TESTQ-M269', 'SNP'::core.mutation_type, $1, $2) RETURNING id",
    )
    .bind(json!({"common_names": ["TESTQ-RM269"]}))
    .bind(json!({"GRCh38": {"contig": "chrY", "position": 2787319}}))
    .fetch_one(&pool)
    .await
    .expect("insert v1");

    sqlx::query(
        "INSERT INTO core.variant (canonical_name, mutation_type, aliases) \
         VALUES ('TESTQ-L21', 'SNP'::core.mutation_type, $1)",
    )
    .bind(json!({"rs_ids": ["rsTESTQ999"]}))
    .execute(&pool)
    .await
    .expect("insert v2");

    // The browser lists catalog representatives (one row per physical variant); reconcile
    // maintains the flag, so recompute it here after seeding for `variant::search` to see them.
    du_db::variant::recompute_catalog_representatives(&pool).await.expect("representatives");

    let root: i64 = sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type) \
         VALUES ('TESTQ-ROOT', 'Y_DNA'::core.dna_type) RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .expect("insert root");
    let child: i64 = sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type) \
         VALUES ('TESTQ-CHILD', 'Y_DNA'::core.dna_type) RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .expect("insert child");
    sqlx::query(
        "INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id) \
         VALUES ($1, $2)",
    )
    .bind(child)
    .bind(root)
    .execute(&pool)
    .await
    .expect("insert edge");

    let pub_id: i64 = sqlx::query_scalar(
        "INSERT INTO pubs.publication (title, doi) VALUES ('TESTQ-Ancient DNA', '10.testq/1') RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .expect("insert pub");

    let sample = Uuid::new_v4();
    sqlx::query(
        "INSERT INTO core.biosample (sample_guid, source, accession) \
         VALUES ($1, 'EXTERNAL'::core.biosample_source, 'TESTQ-ACC1')",
    )
    .bind(sample)
    .execute(&pool)
    .await
    .expect("insert biosample");

    // ── variant ───────────────────────────────────────────────────────────
    let by_name = du_db::variant::search(&pool, Some("TESTQ-M269"), 1, 25).await.expect("variant search");
    assert_eq!(by_name.total, 1);
    assert_eq!(by_name.items[0].canonical_name, "TESTQ-M269");
    assert_eq!(by_name.items[0].coordinates.get(du_domain::ReferenceBuild::GRCh38).unwrap().contig, "chrY");

    let got = du_db::variant::get_by_id(&pool, by_name.items[0].id).await.expect("get variant");
    assert_eq!(got.unwrap().canonical_name, "TESTQ-M269");

    // found by rs_id alias (JSONB array search)
    let by_rsid = du_db::variant::search(&pool, Some("rsTESTQ999"), 1, 25).await.expect("variant rsid search");
    assert_eq!(by_rsid.total, 1);
    assert_eq!(by_rsid.items[0].canonical_name, "TESTQ-L21");

    // ── haplogroup / tree ───────────────────────────────────────────────────
    let root_hg = du_db::haplogroup::get_by_name(&pool, "TESTQ-ROOT", DnaType::YDna)
        .await
        .expect("get root")
        .expect("root exists");
    let roots = du_db::haplogroup::roots(&pool, DnaType::YDna).await.expect("roots");
    assert!(roots.iter().any(|h| h.name == "TESTQ-ROOT"), "root listed as a root");
    assert!(!roots.iter().any(|h| h.name == "TESTQ-CHILD"), "child is not a root");

    let kids = du_db::haplogroup::children(&pool, root_hg.id).await.expect("children");
    assert_eq!(kids.len(), 1);
    assert_eq!(kids[0].name, "TESTQ-CHILD");

    // ── publication ──────────────────────────────────────────────────────────
    let pubs = du_db::publication::search(&pool, Some("TESTQ"), 1, 25).await.expect("pub search");
    assert!(pubs.items.iter().any(|p| p.title == "TESTQ-Ancient DNA"));
    let one = du_db::publication::get_by_id(&pool, PublicationId(pub_id)).await.expect("get pub");
    assert_eq!(one.unwrap().doi.as_deref(), Some("10.testq/1"));

    // ── biosample ─────────────────────────────────────────────────────────────
    let bs = du_db::biosample::get_by_guid(&pool, SampleGuid(sample)).await.expect("get biosample");
    assert_eq!(bs.unwrap().accession.as_deref(), Some("TESTQ-ACC1"));
    let found = du_db::biosample::find_by_alias_or_accession(&pool, "TESTQ-ACC1").await.expect("find biosample");
    assert_eq!(found.len(), 1);

    // ensure the unused binding is acknowledged
    let _ = v1;

}
