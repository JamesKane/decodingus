//! Live-DB test for `du_db::haplogroup::rename_to_snp_shorthand` — drops YCC
//! longhand names to `<MajorClade>-<definingSNP>`, retaining the old name in
//! `provenance.aliases`. Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test rename_shorthand

use du_domain::enums::DnaType;
use sqlx::PgPool;
use std::collections::HashMap;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn node(pool: &PgPool, name: &str, provenance: serde_json::Value) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type, provenance) \
         VALUES ($1, 'Y_DNA'::core.dna_type, $2) RETURNING id",
    )
    .bind(name)
    .bind(provenance)
    .fetch_one(pool)
    .await
    .expect("insert haplogroup")
}

async fn link_variant(pool: &PgPool, hid: i64, snp: &str) {
    let vid: i64 = sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type) VALUES ($1,'SNP'::core.mutation_type) RETURNING id",
    )
    .bind(snp)
    .fetch_one(pool)
    .await
    .expect("insert variant");
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)")
        .bind(hid)
        .bind(vid)
        .execute(pool)
        .await
        .expect("link variant");
}

async fn name_of(pool: &PgPool, id: i64) -> String {
    sqlx::query_scalar("SELECT name FROM tree.haplogroup WHERE id=$1")
        .bind(id)
        .fetch_one(pool)
        .await
        .expect("name")
}

async fn aliases_of(pool: &PgPool, id: i64) -> Vec<String> {
    let v: serde_json::Value =
        sqlx::query_scalar("SELECT COALESCE(provenance->'aliases','[]'::jsonb) FROM tree.haplogroup WHERE id=$1")
            .bind(id)
            .fetch_one(pool)
            .await
            .expect("aliases");
    v.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default()
}

#[tokio::test]
async fn renames_ycc_to_clade_snp_and_keeps_provenance() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping rename_shorthand test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // (1) graft shorthand alias wins.
    let n_graft = node(&pool, "R1b1a1b", serde_json::json!({"aliases": ["R-M269"]})).await;
    // (2) ISOGG-designated (via map), no alias.
    let n_isogg = node(&pool, "I1", serde_json::json!({})).await;
    // (3) DB-linked variant fallback, no alias / not in map.
    let n_db = node(&pool, "A1b1a1", serde_json::json!({})).await;
    link_variant(&pool, n_db, "L968").await;
    // (4) backbone macro node — untouched.
    let n_bb = node(&pool, "BT", serde_json::json!({})).await;
    // (5) collision: a node already named I-CTS5887 blocks I2 from taking it.
    let _n_taken = node(&pool, "E-CTS5887", serde_json::json!({})).await;
    let n_collide = node(&pool, "E1b1b1", serde_json::json!({})).await; // map → E-CTS5887 (taken)

    let mut isogg = HashMap::new();
    isogg.insert("I1".to_string(), "CTS5887".to_string());
    isogg.insert("E1b1b1".to_string(), "CTS5887".to_string()); // forces the collision

    let rep = du_db::haplogroup::rename_to_snp_shorthand(&pool, DnaType::YDna, &isogg, true)
        .await
        .expect("rename");

    // Renames applied.
    assert_eq!(name_of(&pool, n_graft).await, "R-M269");
    assert_eq!(name_of(&pool, n_isogg).await, "I-CTS5887");
    assert_eq!(name_of(&pool, n_db).await, "A-L968");
    // Backbone untouched.
    assert_eq!(name_of(&pool, n_bb).await, "BT");
    // Collision: kept YCC.
    assert_eq!(name_of(&pool, n_collide).await, "E1b1b1");
    assert!(rep.skipped_collision.iter().any(|s| s.contains("E1b1b1")));

    // Old YCC name retained in provenance.aliases (graft alias preserved too).
    let graft_aliases = aliases_of(&pool, n_graft).await;
    assert!(graft_aliases.contains(&"R1b1a1b".to_string()), "old YCC name kept: {graft_aliases:?}");
    assert!(graft_aliases.contains(&"R-M269".to_string()), "graft alias kept: {graft_aliases:?}");
    assert!(aliases_of(&pool, n_isogg).await.contains(&"I1".to_string()));

    // Source tallies.
    assert_eq!(rep.from_shorthand, 1);
    assert_eq!(rep.from_isogg, 1);
    assert_eq!(rep.from_db_fallback, 1);

    // The YCC longhand now resolves to the renamed node via the alias phase —
    // closing the biosample YCC-call residual.
    let resolved = du_db::haplogroup::resolve_name_or_variant(&pool, "R1b1a1b", DnaType::YDna)
        .await
        .expect("resolve");
    assert_eq!(resolved.as_deref(), Some("R-M269"));
}
