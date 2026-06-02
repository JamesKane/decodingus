//! Live-DB test for the tree-versioning apply engine (`du_db::change_set`).
//! Seeds a small tree (prefix `TESTCS-`), runs a change set through the
//! lifecycle, applies it, and asserts the production tree reflects the temporal
//! changes. Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test change_set

use du_domain::enums::DnaType;
use serde_json::json;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn cleanup(pool: &PgPool) {
    // change_set cascade removes tree_change/comments. Then edges, then nodes.
    let _ = sqlx::query("DELETE FROM tree.change_set WHERE source LIKE 'TESTCS-%'").execute(pool).await;
    let _ = sqlx::query(
        "DELETE FROM tree.haplogroup_relationship r USING tree.haplogroup h \
         WHERE (r.child_haplogroup_id = h.id OR r.parent_haplogroup_id = h.id) AND h.name LIKE 'TESTCS-%'",
    )
    .execute(pool)
    .await;
    let _ = sqlx::query(
        "DELETE FROM tree.haplogroup_variant hv USING tree.haplogroup h \
         WHERE hv.haplogroup_id = h.id AND h.name LIKE 'TESTCS-%'",
    )
    .execute(pool)
    .await;
    let _ = sqlx::query("DELETE FROM tree.haplogroup WHERE name LIKE 'TESTCS-%'").execute(pool).await;
    let _ = sqlx::query("DELETE FROM core.variant WHERE canonical_name LIKE 'TESTCS-%'").execute(pool).await;
}

async fn mk_hg(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1,'Y_DNA'::core.dna_type) RETURNING id")
        .bind(name)
        .fetch_one(pool)
        .await
        .expect("insert hg")
}

async fn mk_edge(pool: &PgPool, child: i64, parent: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id) VALUES ($1,$2)")
        .bind(child)
        .bind(parent)
        .execute(pool)
        .await
        .expect("insert edge");
}

async fn mk_variant(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO core.variant (canonical_name, mutation_type) VALUES ($1,'SNP'::core.mutation_type) RETURNING id")
        .bind(name)
        .fetch_one(pool)
        .await
        .expect("insert variant")
}

async fn child_names(pool: &PgPool, parent: i64) -> Vec<String> {
    let kids = du_db::haplogroup::children(pool, du_domain::ids::HaplogroupId(parent)).await.expect("children");
    kids.into_iter().map(|h| h.name).collect()
}

#[tokio::test]
async fn change_set_apply_writes_temporal_tree() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping change_set test");
        return;
    };
    let pool = du_db::connect(&url, 4).await.expect("connect");
    du_db::run_migrations(&pool).await.expect("migrate");
    cleanup(&pool).await;

    // Seed: ROOT -> {A, C}.  Variants v1, v2.
    let root = mk_hg(&pool, "TESTCS-ROOT").await;
    let a = mk_hg(&pool, "TESTCS-A").await;
    let c = mk_hg(&pool, "TESTCS-C").await;
    let d = mk_hg(&pool, "TESTCS-D").await;
    mk_edge(&pool, a, root).await;
    mk_edge(&pool, c, root).await;
    mk_edge(&pool, d, root).await;
    let v1 = mk_variant(&pool, "TESTCS-V1").await;
    let v2 = mk_variant(&pool, "TESTCS-V2").await;

    // Build a change set: create B under ROOT (w/ v1), reparent A under C,
    // add v2 to C, update A's formed_ybp, delete D. Plus one change we reject.
    let cs = du_db::change_set::create(&pool, "TESTCS-src", Some("Y_DNA"), Some("test set"), "tester")
        .await
        .expect("create cs");

    du_db::change_set::add_change(&pool, cs, "CREATE", None, None,
        Some(&json!({"name": "TESTCS-B", "haplogroup_type": "Y_DNA", "parent_haplogroup_id": root, "variant_ids": [v1]})))
        .await.expect("create change");
    du_db::change_set::add_change(&pool, cs, "REPARENT", Some(a), None,
        Some(&json!({"new_parent_haplogroup_id": c}))).await.expect("reparent change");
    du_db::change_set::add_change(&pool, cs, "VARIANT_EDIT", Some(c), None,
        Some(&json!({"add": [v2]}))).await.expect("variant_edit change");
    du_db::change_set::add_change(&pool, cs, "UPDATE", Some(a), None,
        Some(&json!({"formed_ybp": 12345}))).await.expect("update change");
    du_db::change_set::add_change(&pool, cs, "DELETE", Some(d), None, None).await.expect("delete change");
    let rejected = du_db::change_set::add_change(&pool, cs, "UPDATE", Some(c), None,
        Some(&json!({"name": "TESTCS-SHOULD-NOT-APPLY"}))).await.expect("rejected change");

    // Cannot apply while DRAFT.
    assert!(du_db::change_set::apply(&pool, cs, "tester").await.is_err(), "DRAFT must not apply");

    // Lifecycle: start review, reject one change, approve the rest, apply.
    assert!(du_db::change_set::start_review(&pool, cs).await.expect("start review"));
    assert!(du_db::change_set::review_change(&pool, rejected, false).await.expect("reject"));
    let approved = du_db::change_set::approve_all(&pool, cs).await.expect("approve all");
    assert_eq!(approved, 5, "5 PENDING approved (the rejected one is skipped)");

    let result = du_db::change_set::apply(&pool, cs, "tester").await.expect("apply");
    assert_eq!(result.created, 1);
    assert_eq!(result.reparented, 1);
    assert_eq!(result.variant_edits, 1);
    assert_eq!(result.updated, 1);
    assert_eq!(result.deleted, 1);

    // ── assert the production tree ──────────────────────────────────────────
    // ROOT now parents B and C; A moved off ROOT; D deleted (detached + expired).
    let root_kids = child_names(&pool, root).await;
    assert!(root_kids.contains(&"TESTCS-B".to_string()), "B created under ROOT");
    assert!(root_kids.contains(&"TESTCS-C".to_string()), "C still under ROOT");
    assert!(!root_kids.contains(&"TESTCS-A".to_string()), "A reparented off ROOT");
    assert!(!root_kids.contains(&"TESTCS-D".to_string()), "D deleted");

    // A is now under C.
    let c_kids = child_names(&pool, c).await;
    assert!(c_kids.contains(&"TESTCS-A".to_string()), "A reparented under C");

    // D is gone from navigation entirely (expired node, not a stray root).
    let roots = du_db::haplogroup::roots(&pool, DnaType::YDna).await.expect("roots");
    assert!(!roots.iter().any(|h| h.name == "TESTCS-D"), "deleted node is not a root");

    // B carries v1; C now carries v2 (one current link each).
    let b_id: i64 = sqlx::query_scalar("SELECT id FROM tree.haplogroup WHERE name='TESTCS-B'").fetch_one(&pool).await.unwrap();
    let b_vars: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_variant WHERE haplogroup_id=$1 AND variant_id=$2 AND valid_until IS NULL")
        .bind(b_id).bind(v1).fetch_one(&pool).await.unwrap();
    assert_eq!(b_vars, 1, "B defined by v1");
    let c_vars: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_variant WHERE haplogroup_id=$1 AND variant_id=$2 AND valid_until IS NULL")
        .bind(c).bind(v2).fetch_one(&pool).await.unwrap();
    assert_eq!(c_vars, 1, "C defined by v2");

    // A's metadata updated; C's name NOT changed (that change was rejected).
    let a_ybp: Option<i32> = sqlx::query_scalar("SELECT formed_ybp FROM tree.haplogroup WHERE id=$1").bind(a).fetch_one(&pool).await.unwrap();
    assert_eq!(a_ybp, Some(12345), "A formed_ybp updated");
    let c_name: String = sqlx::query_scalar("SELECT name FROM tree.haplogroup WHERE id=$1").bind(c).fetch_one(&pool).await.unwrap();
    assert_eq!(c_name, "TESTCS-C", "rejected change did not apply");

    // Set is APPLIED; old A->ROOT edge is closed (exactly one current edge for A).
    let detail = du_db::change_set::get(&pool, cs).await.expect("get").unwrap();
    assert_eq!(detail.summary.status, "APPLIED");
    let a_current_edges: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_relationship WHERE child_haplogroup_id=$1 AND valid_until IS NULL")
        .bind(a).fetch_one(&pool).await.unwrap();
    assert_eq!(a_current_edges, 1, "A has exactly one current parent edge");

    // Diff reflects the non-rejected changes.
    let diff = du_db::change_set::diff(&pool, cs).await.expect("diff");
    assert_eq!(diff.summary.added, 1);
    assert_eq!(diff.summary.reparented, 1);
    assert_eq!(diff.summary.removed, 1);

    // Re-applying an APPLIED set is rejected.
    assert!(du_db::change_set::apply(&pool, cs, "tester").await.is_err(), "re-apply must fail");

    cleanup(&pool).await;
}
