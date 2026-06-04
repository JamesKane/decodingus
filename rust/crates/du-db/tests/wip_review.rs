//! Live-DB test for the curator merge-review vertical (`du_db::wip` +
//! `change_set::apply`'s WIP pass). Stages review items into a change-set,
//! resolves them (REPARENT / MERGE_EXISTING / DEFER), applies, and asserts the
//! production tree reflects the enacted decisions. Prefix `TESTWIP-`.
//! Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test wip_review

use du_domain::ids::HaplogroupId;
use serde_json::json;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}


async fn mk_hg(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1,'Y_DNA'::core.dna_type) RETURNING id")
        .bind(name)
        .fetch_one(pool)
        .await
        .expect("insert hg")
}

async fn wip_id(pool: &PgPool, cs: i64, name: &str) -> i64 {
    sqlx::query_scalar("SELECT id FROM tree.wip_haplogroup WHERE change_set_id=$1 AND name=$2")
        .bind(cs)
        .bind(name)
        .fetch_one(pool)
        .await
        .expect("wip id")
}

async fn child_names(pool: &PgPool, parent: i64) -> Vec<String> {
    let kids = du_db::haplogroup::children(pool, HaplogroupId(parent)).await.expect("children");
    kids.into_iter().map(|h| h.name).collect()
}

#[tokio::test]
async fn wip_review_resolve_and_apply() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping wip_review test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Seed: an anchor P (reparent target) and an existing node M (merge target).
    let p = mk_hg(&pool, "TESTWIP-P").await;
    let m = mk_hg(&pool, "TESTWIP-M").await;

    let cs = du_db::change_set::create(&pool, "TESTWIP-src", Some("Y_DNA"), Some("wip test"), "tester")
        .await
        .expect("create cs");

    // Stage three items: one to graft, one to merge, one to defer.
    let items = vec![
        du_db::wip::StageItem {
            name: "TESTWIP-NOVEL".into(),
            tentative_parent_id: Some(p),
            review: json!({"category": "weak_plurality", "is_backbone": true,
                           "defining_snps": ["TESTWIP-S1", "TESTWIP-S2"]}),
        },
        du_db::wip::StageItem {
            name: "TESTWIP-MERGE".into(),
            tentative_parent_id: Some(m),
            review: json!({"category": "name_collision",
                           "defining_snps": ["TESTWIP-S3"]}),
        },
        du_db::wip::StageItem {
            name: "TESTWIP-DEFERRED".into(),
            tentative_parent_id: Some(p),
            review: json!({"category": "weak_plurality",
                           "defining_snps": ["TESTWIP-S4"]}),
        },
    ];
    let n = du_db::wip::stage(&pool, cs, "TESTWIP-src", &items).await.expect("stage");
    assert_eq!(n, 3);

    // Worklist: all three are open initially.
    let open = du_db::wip::list(&pool, Some("open"), None, Some("TESTWIP-src"), 1, 50).await.expect("list");
    assert_eq!(open.total, 3, "three open items");

    // Resolve: NOVEL→REPARENT under P, MERGE→MERGE_EXISTING into M, third→DEFER.
    let novel = wip_id(&pool, cs, "TESTWIP-NOVEL").await;
    let merge = wip_id(&pool, cs, "TESTWIP-MERGE").await;
    let deferred = wip_id(&pool, cs, "TESTWIP-DEFERRED").await;
    du_db::wip::resolve(&pool, novel, "REPARENT", Some(p), None, Some("graft it"), "tester").await.expect("resolve novel");
    du_db::wip::resolve(&pool, merge, "MERGE_EXISTING", None, Some(m), None, "tester").await.expect("resolve merge");
    du_db::wip::resolve(&pool, deferred, "DEFER", None, None, Some("later"), "tester").await.expect("defer");

    let (o, r, d) = du_db::wip::counts(&pool, cs).await.expect("counts");
    assert_eq!((o, r, d), (0, 2, 1), "0 open, 2 resolved (non-defer), 1 deferred");

    // Apply through the lifecycle.
    assert!(du_db::change_set::start_review(&pool, cs).await.expect("start review"));
    let result = du_db::change_set::apply(&pool, cs, "tester").await.expect("apply");
    assert_eq!(result.created, 1, "NOVEL created (DEFERRED not enacted)");
    assert_eq!(result.variant_edits, 1, "MERGE folded variants into target");

    // ── assert production tree ──────────────────────────────────────────────
    // NOVEL grafted under P with both SNPs + curated-backbone marker.
    let p_kids = child_names(&pool, p).await;
    assert!(p_kids.contains(&"TESTWIP-NOVEL".to_string()), "NOVEL under P");
    assert!(!p_kids.contains(&"TESTWIP-DEFERRED".to_string()), "DEFERRED not created");

    let novel_id: i64 = sqlx::query_scalar("SELECT id FROM tree.haplogroup WHERE name='TESTWIP-NOVEL'")
        .fetch_one(&pool).await.unwrap();
    let novel_vars: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_variant WHERE haplogroup_id=$1 AND valid_until IS NULL")
        .bind(novel_id).fetch_one(&pool).await.unwrap();
    assert_eq!(novel_vars, 2, "NOVEL defined by its two staged SNPs");
    let novel_backbone: bool = sqlx::query_scalar(
        "SELECT is_backbone AND provenance ? 'backbone_source' FROM tree.haplogroup WHERE id=$1")
        .bind(novel_id).fetch_one(&pool).await.unwrap();
    assert!(novel_backbone, "curated backbone flag + marker set");

    // MERGE folded S3 onto M, and M gained the source name as an alias.
    let m_has_s3: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_variant hv JOIN core.variant v ON v.id=hv.variant_id \
         WHERE hv.haplogroup_id=$1 AND v.canonical_name='TESTWIP-S3' AND hv.valid_until IS NULL")
        .bind(m).fetch_one(&pool).await.unwrap();
    assert_eq!(m_has_s3, 1, "S3 folded into M");
    let m_alias: bool = sqlx::query_scalar(
        "SELECT provenance->'aliases' ? 'TESTWIP-MERGE' FROM tree.haplogroup WHERE id=$1")
        .bind(m).fetch_one(&pool).await.unwrap();
    assert!(m_alias, "merged source name recorded as alias on M");

    // DEFERRED never became a node.
    let deferred_exists: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup WHERE name='TESTWIP-DEFERRED'")
        .fetch_one(&pool).await.unwrap();
    assert_eq!(deferred_exists, 0, "deferred item not enacted");

}
