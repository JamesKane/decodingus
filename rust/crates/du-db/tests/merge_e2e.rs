//! End-to-end merge pipeline test: seed a production tree, load it, run the pure
//! `du_domain::merge`, materialize the plan into a change set, review + apply,
//! and assert the production tree. Exercises existing_tree -> merge ->
//! materialize -> placeholder-resolving apply. Skips when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test merge_e2e

use du_domain::enums::DnaType;
use du_domain::merge::SourceNode;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn cleanup(pool: &PgPool) {
    let _ = sqlx::query("DELETE FROM tree.change_set WHERE source LIKE 'TESTMERGE%'").execute(pool).await;
    let _ = sqlx::query(
        "DELETE FROM tree.haplogroup_relationship r USING tree.haplogroup h \
         WHERE (r.child_haplogroup_id=h.id OR r.parent_haplogroup_id=h.id) AND h.name LIKE 'TESTMERGE-%'",
    ).execute(pool).await;
    let _ = sqlx::query(
        "DELETE FROM tree.haplogroup_variant hv USING tree.haplogroup h \
         WHERE hv.haplogroup_id=h.id AND h.name LIKE 'TESTMERGE-%'",
    ).execute(pool).await;
    let _ = sqlx::query("DELETE FROM tree.haplogroup WHERE name LIKE 'TESTMERGE-%'").execute(pool).await;
    let _ = sqlx::query("DELETE FROM core.variant WHERE canonical_name LIKE 'TESTMERGE-%'").execute(pool).await;
}

async fn hg(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1,'Y_DNA'::core.dna_type) RETURNING id")
        .bind(name).fetch_one(pool).await.unwrap()
}
async fn edge(pool: &PgPool, child: i64, parent: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id) VALUES ($1,$2)")
        .bind(child).bind(parent).execute(pool).await.unwrap();
}
async fn var_link(pool: &PgPool, hgid: i64, vname: &str) {
    let vid: i64 = sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type) VALUES ($1,'SNP'::core.mutation_type) \
         ON CONFLICT (canonical_name) DO UPDATE SET canonical_name=EXCLUDED.canonical_name RETURNING id")
        .bind(vname).fetch_one(pool).await.unwrap();
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)")
        .bind(hgid).bind(vid).execute(pool).await.unwrap();
}
async fn child_names(pool: &PgPool, parent: i64) -> Vec<String> {
    du_db::haplogroup::children(pool, du_domain::ids::HaplogroupId(parent)).await.unwrap()
        .into_iter().map(|h| h.name).collect()
}
async fn id_of(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("SELECT id FROM tree.haplogroup WHERE name=$1").bind(name).fetch_one(pool).await.unwrap()
}
fn s(name: &str, vars: &[&str], children: Vec<SourceNode>) -> SourceNode {
    SourceNode { name: name.into(), variants: vars.iter().map(|x| x.to_string()).collect(), children }
}
async fn run_and_apply(pool: &PgPool, source: Vec<SourceNode>) {
    let existing = du_db::haplogroup::existing_tree(pool, DnaType::YDna).await.unwrap();
    let plan = du_domain::merge::merge(&existing, &source, "TESTMERGE");
    let m = du_db::merge::materialize(pool, &plan, "TESTMERGE", "Y_DNA", "tester").await.unwrap();
    assert!(du_db::change_set::start_review(pool, m.change_set_id).await.unwrap());
    du_db::change_set::approve_all(pool, m.change_set_id).await.unwrap();
    du_db::change_set::apply(pool, m.change_set_id, "tester").await.unwrap();
}

// Both scenarios share the `TESTMERGE-` namespace, so run them sequentially in
// one test (cargo runs separate test fns on parallel threads, which would race
// the shared `TESTMERGE-R` seed).
#[tokio::test]
async fn merge_pipeline_end_to_end() {
    let Some(url) = database_url() else { eprintln!("DATABASE_URL unset — skipping"); return };
    let pool = du_db::connect(&url, 4).await.unwrap();
    du_db::run_migrations(&pool).await.unwrap();
    new_subtree_chain(&pool).await;
    node_contraction(&pool).await;
}

async fn new_subtree_chain(pool: &PgPool) {
    cleanup(pool).await;

    // Existing: just R (defined by M207).
    let r = hg(pool, "TESTMERGE-R").await;
    var_link(pool, r, "TESTMERGE-M207").await;

    // Source extends R with a two-deep new chain: R -> R1b(M343) -> L21node(L21).
    run_and_apply(pool, vec![
        s("TESTMERGE-R", &["TESTMERGE-M207"], vec![
            s("TESTMERGE-R1b", &["TESTMERGE-M343"], vec![
                s("TESTMERGE-L21", &["TESTMERGE-L21v"], vec![]),
            ]),
        ]),
    ]).await;

    // Placeholder chain resolved: R -> R1b -> L21.
    assert_eq!(child_names(pool, r).await, vec!["TESTMERGE-R1b"]);
    let r1b = id_of(pool, "TESTMERGE-R1b").await;
    assert_eq!(child_names(pool, r1b).await, vec!["TESTMERGE-L21"]);
    // Variants got created (UNNAMED) and linked to the new nodes.
    let r1b_var: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_variant hv JOIN core.variant v ON v.id=hv.variant_id \
         WHERE hv.haplogroup_id=$1 AND v.canonical_name='TESTMERGE-M343' AND hv.valid_until IS NULL")
        .bind(r1b).fetch_one(pool).await.unwrap();
    assert_eq!(r1b_var, 1, "R1b defined by M343");

    cleanup(pool).await;
}

async fn node_contraction(pool: &PgPool) {
    cleanup(pool).await;

    // Existing lumps three SNPs on one coarse node: R(M207) -> RC(M343,L23,L51).
    let r = hg(pool, "TESTMERGE-R").await;
    var_link(pool, r, "TESTMERGE-M207").await;
    let rc = hg(pool, "TESTMERGE-RC").await;
    edge(pool, rc, r).await;
    for v in ["TESTMERGE-M343", "TESTMERGE-L23", "TESTMERGE-L51"] {
        var_link(pool, rc, v).await;
    }

    // Source splits the top SNP out: R(M207) -> R1b(M343). M343 ⊂ RC -> contraction.
    run_and_apply(pool, vec![
        s("TESTMERGE-R", &["TESTMERGE-M207"], vec![
            s("TESTMERGE-R1b", &["TESTMERGE-M343"], vec![]),
        ]),
    ]).await;

    // New R1b inserted between R and RC; RC reparented under R1b; M343 downflowed off RC.
    assert_eq!(child_names(pool, r).await, vec!["TESTMERGE-R1b"]);
    let r1b = id_of(pool, "TESTMERGE-R1b").await;
    assert_eq!(child_names(pool, r1b).await, vec!["TESTMERGE-RC"]);
    let rc = id_of(pool, "TESTMERGE-RC").await;
    // RC retains L23/L51 but no longer M343 (downflow).
    let rc_m343: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_variant hv JOIN core.variant v ON v.id=hv.variant_id \
         WHERE hv.haplogroup_id=$1 AND v.canonical_name='TESTMERGE-M343' AND hv.valid_until IS NULL")
        .bind(rc).fetch_one(pool).await.unwrap();
    assert_eq!(rc_m343, 0, "M343 downflowed off RC");
    let rc_l23: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_variant hv JOIN core.variant v ON v.id=hv.variant_id \
         WHERE hv.haplogroup_id=$1 AND v.canonical_name='TESTMERGE-L23' AND hv.valid_until IS NULL")
        .bind(rc).fetch_one(pool).await.unwrap();
    assert_eq!(rc_l23, 1, "RC keeps L23");
    // R1b now carries M343.
    let r1b_m343: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_variant hv JOIN core.variant v ON v.id=hv.variant_id \
         WHERE hv.haplogroup_id=$1 AND v.canonical_name='TESTMERGE-M343' AND hv.valid_until IS NULL")
        .bind(r1b).fetch_one(pool).await.unwrap();
    assert_eq!(r1b_m343, 1, "R1b carries M343");

    cleanup(pool).await;
}
