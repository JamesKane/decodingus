//! Live-DB test for curator structural ops (`du_db::haplogroup` reparent /
//! merge_into_parent / split). Prefix `TESTRS-`. Re-runnable; skips when
//! DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test restructure

use du_domain::enums::DnaType;
use du_domain::ids::HaplogroupId;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn cleanup(pool: &PgPool) {
    let _ = sqlx::query(
        "DELETE FROM tree.haplogroup_relationship r USING tree.haplogroup h \
         WHERE (r.child_haplogroup_id = h.id OR r.parent_haplogroup_id = h.id) AND h.name LIKE 'TESTRS-%'",
    )
    .execute(pool)
    .await;
    let _ = sqlx::query(
        "DELETE FROM tree.haplogroup_variant hv USING tree.haplogroup h \
         WHERE hv.haplogroup_id = h.id AND h.name LIKE 'TESTRS-%'",
    )
    .execute(pool)
    .await;
    let _ = sqlx::query("DELETE FROM tree.haplogroup WHERE name LIKE 'TESTRS-%'").execute(pool).await;
    let _ = sqlx::query("DELETE FROM core.variant WHERE canonical_name LIKE 'TESTRS-%'").execute(pool).await;
}

async fn mk_hg(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1,'Y_DNA'::core.dna_type) RETURNING id")
        .bind(name).fetch_one(pool).await.expect("hg")
}
async fn mk_edge(pool: &PgPool, child: i64, parent: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id) VALUES ($1,$2)")
        .bind(child).bind(parent).execute(pool).await.expect("edge");
}
async fn mk_var(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO core.variant (canonical_name, mutation_type) VALUES ($1,'SNP'::core.mutation_type) RETURNING id")
        .bind(name).fetch_one(pool).await.expect("var")
}
async fn link(pool: &PgPool, hg: i64, v: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)")
        .bind(hg).bind(v).execute(pool).await.expect("link");
}
async fn child_names(pool: &PgPool, parent: i64) -> Vec<String> {
    du_db::haplogroup::children(pool, HaplogroupId(parent)).await.expect("children")
        .into_iter().map(|h| h.name).collect()
}
async fn var_names(pool: &PgPool, hg: i64) -> Vec<String> {
    du_db::haplogroup::current_variant_links(pool, HaplogroupId(hg)).await.expect("links")
        .into_iter().map(|(_, n)| n).collect()
}

#[tokio::test]
async fn restructure_reparent_split_merge() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping restructure test");
        return;
    };
    let pool = du_db::connect(&url, 4).await.expect("connect");
    du_db::run_migrations(&pool).await.expect("migrate");
    cleanup(&pool).await;

    // ROOT → A → {B, C}; B defined by v1, v2.
    let root = mk_hg(&pool, "TESTRS-ROOT").await;
    let a = mk_hg(&pool, "TESTRS-A").await;
    let b = mk_hg(&pool, "TESTRS-B").await;
    let c = mk_hg(&pool, "TESTRS-C").await;
    mk_edge(&pool, a, root).await;
    mk_edge(&pool, b, a).await;
    mk_edge(&pool, c, a).await;
    let v1 = mk_var(&pool, "TESTRS-V1").await;
    let v2 = mk_var(&pool, "TESTRS-V2").await;
    link(&pool, b, v1).await;
    link(&pool, b, v2).await;

    // ── reparent C under ROOT ───────────────────────────────────────────────
    du_db::haplogroup::reparent(&pool, HaplogroupId(c), HaplogroupId(root)).await.expect("reparent");
    assert!(child_names(&pool, root).await.contains(&"TESTRS-C".to_string()), "C under ROOT");
    assert!(!child_names(&pool, a).await.contains(&"TESTRS-C".to_string()), "C no longer under A");

    // cycle guard: ROOT cannot move under C (C is below ROOT now)
    assert!(du_db::haplogroup::reparent(&pool, HaplogroupId(root), HaplogroupId(c)).await.is_err(), "cycle rejected");

    // ── split B: move v2 to a new child TESTRS-BX ───────────────────────────
    let bx = du_db::haplogroup::split(&pool, HaplogroupId(b), "TESTRS-BX", &[v2], DnaType::YDna, Some("curator"))
        .await.expect("split");
    assert!(child_names(&pool, b).await.contains(&"TESTRS-BX".to_string()), "BX under B");
    assert_eq!(var_names(&pool, b).await, vec!["TESTRS-V1"], "B keeps only v1");
    assert_eq!(var_names(&pool, bx.0).await, vec!["TESTRS-V2"], "BX carries v2");

    // ── merge B into its parent A ────────────────────────────────────────────
    du_db::haplogroup::merge_into_parent(&pool, HaplogroupId(b)).await.expect("merge");
    let a_kids = child_names(&pool, a).await;
    assert!(a_kids.contains(&"TESTRS-BX".to_string()), "BX reparented to A on merge");
    assert!(!a_kids.contains(&"TESTRS-B".to_string()), "B gone from A's children");
    assert!(var_names(&pool, a).await.contains(&"TESTRS-V1".to_string()), "A absorbed B's v1");
    let b_alive: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup WHERE id=$1 AND valid_until IS NULL")
        .bind(b).fetch_one(&pool).await.unwrap();
    assert_eq!(b_alive, 0, "B temporal-deleted");

    cleanup(&pool).await;
}
