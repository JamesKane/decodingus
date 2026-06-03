//! Live-DB test for `du_db::variant::merge_into` — folding one variant into
//! another (aliases + repointed tree links, with current-link dedupe, then
//! delete). Prefix `TESTMG-`. Skips when DATABASE_URL is unset.

use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn cleanup(pool: &PgPool) {
    let _ = sqlx::query(
        "DELETE FROM tree.haplogroup_variant hv USING core.variant v \
         WHERE hv.variant_id = v.id AND v.canonical_name LIKE 'TESTMG-%'",
    )
    .execute(pool)
    .await;
    let _ = sqlx::query(
        "DELETE FROM tree.haplogroup_variant hv USING tree.haplogroup h \
         WHERE hv.haplogroup_id = h.id AND h.name LIKE 'TESTMG-%'",
    )
    .execute(pool)
    .await;
    let _ = sqlx::query("DELETE FROM tree.haplogroup WHERE name LIKE 'TESTMG-%'").execute(pool).await;
    let _ = sqlx::query("DELETE FROM core.variant WHERE canonical_name LIKE 'TESTMG-%'").execute(pool).await;
}

async fn mk_hg(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1,'Y_DNA'::core.dna_type) RETURNING id")
        .bind(name).fetch_one(pool).await.unwrap()
}
async fn mk_var(pool: &PgPool, name: &str, aliases: serde_json::Value) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, aliases) VALUES ($1,'SNP'::core.mutation_type,$2) RETURNING id",
    )
    .bind(name).bind(aliases).fetch_one(pool).await.unwrap()
}
async fn link(pool: &PgPool, hg: i64, v: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)")
        .bind(hg).bind(v).execute(pool).await.unwrap();
}

#[tokio::test]
async fn merge_into_folds_repoints_and_deletes() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping variant_merge test");
        return;
    };
    let pool = du_db::connect(&url, 4).await.expect("connect");
    du_db::run_migrations(&pool).await.expect("migrate");
    cleanup(&pool).await;

    let (hg1, hg2, hg3) = (mk_hg(&pool, "TESTMG-HG1").await, mk_hg(&pool, "TESTMG-HG2").await, mk_hg(&pool, "TESTMG-HG3").await);
    let keep = mk_var(&pool, "TESTMG-A", serde_json::json!({})).await;
    let drop = mk_var(&pool, "TESTMG-B", serde_json::json!({"common_names": ["TESTMG-Balias"]})).await;
    // keep defines HG1,HG2; drop defines HG2 (collision) + HG3 (unique).
    link(&pool, hg1, keep).await;
    link(&pool, hg2, keep).await;
    link(&pool, hg2, drop).await;
    link(&pool, hg3, drop).await;

    du_db::variant::merge_into(&pool, keep, drop).await.expect("merge");

    // drop is gone.
    let drop_alive: i64 = sqlx::query_scalar("SELECT count(*) FROM core.variant WHERE id=$1").bind(drop).fetch_one(&pool).await.unwrap();
    assert_eq!(drop_alive, 0, "merged-away variant deleted");

    // keep absorbed drop's name + its alias.
    let names: serde_json::Value = sqlx::query_scalar("SELECT aliases->'common_names' FROM core.variant WHERE id=$1").bind(keep).fetch_one(&pool).await.unwrap();
    let names: Vec<String> = names.as_array().unwrap().iter().filter_map(|x| x.as_str().map(str::to_string)).collect();
    assert!(names.contains(&"TESTMG-B".to_string()), "drop canonical folded in as alias");
    assert!(names.contains(&"TESTMG-Balias".to_string()), "drop alias folded in");

    // keep's current links are HG1,HG2,HG3 — exactly once each (HG2 collision deduped).
    let rows: Vec<(i64,)> = sqlx::query_as(
        "SELECT haplogroup_id FROM tree.haplogroup_variant WHERE variant_id=$1 AND valid_until IS NULL ORDER BY haplogroup_id",
    ).bind(keep).fetch_all(&pool).await.unwrap();
    let hgs: Vec<i64> = rows.into_iter().map(|r| r.0).collect();
    let mut expect = vec![hg1, hg2, hg3]; expect.sort();
    assert_eq!(hgs, expect, "links repointed to keep, HG2 collision deduped (each branch once)");

    cleanup(&pool).await;
}
