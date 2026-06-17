//! Live-DB regression test for `du_db::haplogroup::reconcile_tilde_twins`.
//!
//! The `~` suffix is a systematic ISOGG paragroup convention: a same-parent
//! `X` / `X~` pair where `X~` carries **its own defining SNPs** is legitimate
//! (e.g. `NO` terminal vs `NO~` paragroup whose children include `P`) and must
//! survive. Only a genuine *empty stub* — a `~` twin with **no** variants of
//! its own — is a stitch artifact to fold. Re-runnable; skips when DATABASE_URL
//! is unset.

use du_db::haplogroup;
use du_domain::enums::DnaType;
use serde_json::json;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn node(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type, provenance) \
         VALUES ($1, 'Y_DNA'::core.dna_type, $2) RETURNING id",
    )
    .bind(name)
    .bind(json!({ "aliases": [] }))
    .fetch_one(pool)
    .await
    .expect("insert node")
}

async fn edge(pool: &PgPool, parent: i64, child: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id) VALUES ($1, $2)")
        .bind(child)
        .bind(parent)
        .execute(pool)
        .await
        .expect("insert edge");
}

/// Give `hg` a fresh defining variant named `snp`.
async fn defining(pool: &PgPool, hg: i64, snp: &str) {
    let v: i64 = sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type) VALUES ($1, 'SNP'::core.mutation_type) RETURNING id",
    )
    .bind(snp)
    .fetch_one(pool)
    .await
    .expect("insert variant");
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1, $2)")
        .bind(hg)
        .bind(v)
        .execute(pool)
        .await
        .expect("link variant");
}

async fn parent_name(pool: &PgPool, child: &str) -> Option<String> {
    sqlx::query_scalar(
        "SELECT p.name FROM tree.haplogroup c \
         JOIN tree.haplogroup_relationship r ON r.child_haplogroup_id = c.id AND r.valid_until IS NULL \
         JOIN tree.haplogroup p ON p.id = r.parent_haplogroup_id \
         WHERE c.name = $1 AND c.valid_until IS NULL",
    )
    .bind(child)
    .fetch_optional(pool)
    .await
    .expect("parent lookup")
}

async fn exists(pool: &PgPool, name: &str) -> bool {
    let n: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup WHERE name = $1 AND valid_until IS NULL")
        .bind(name)
        .fetch_one(pool)
        .await
        .expect("count");
    n > 0
}

async fn has_variant(pool: &PgPool, hg: &str, snp: &str) -> bool {
    let n: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup h \
         JOIN tree.haplogroup_variant hv ON hv.haplogroup_id = h.id AND hv.valid_until IS NULL \
         JOIN core.variant v ON v.id = hv.variant_id \
         WHERE h.name = $1 AND v.canonical_name = $2",
    )
    .bind(hg)
    .bind(snp)
    .fetch_one(pool)
    .await
    .expect("variant count");
    n > 0
}

#[tokio::test]
async fn paragroup_twins_survive_only_empty_stubs_fold() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping tilde_twins test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // K2 → [NO, NO~] : the legitimate ISOGG paragroup. NO is the terminal
    // (its own SNP Z12176); NO~ is the interior paragroup (its own SNP CTS1320)
    // whose child P descends from NO~, not NO.
    let k2 = node(&pool, "K2").await;
    let no = node(&pool, "NO").await;
    let no_tilde = node(&pool, "NO~").await;
    let p = node(&pool, "P").await;
    edge(&pool, k2, no).await;
    edge(&pool, k2, no_tilde).await;
    edge(&pool, no_tilde, p).await;
    defining(&pool, no, "Z12176").await;
    defining(&pool, no_tilde, "CTS1320").await;

    // K2 → [FOO, FOO~] : a genuine stitch artifact. FOO carries the SNP; FOO~
    // is an EMPTY STUB (no variants of its own) merely holding the subtree BAR.
    let foo = node(&pool, "FOO").await;
    let foo_tilde = node(&pool, "FOO~").await;
    let bar = node(&pool, "BAR").await;
    edge(&pool, k2, foo).await;
    edge(&pool, k2, foo_tilde).await;
    edge(&pool, foo_tilde, bar).await;
    defining(&pool, foo, "FOOSNP").await;
    // (FOO~ deliberately has no defining variant.)

    let rep = haplogroup::reconcile_tilde_twins(&pool, DnaType::YDna).await.expect("reconcile");

    // Exactly one fold: the empty stub FOO~. The paragroup NO~ is untouched.
    assert_eq!(rep.folded, 1, "only the empty-stub twin folds");
    assert!(rep.skipped.is_empty(), "no cross-parent stubs in this fixture");

    // NO~ paragroup is preserved: still present, P still descends from it, and
    // its SNP did NOT bleed onto the terminal NO.
    assert!(exists(&pool, "NO~").await, "NO~ paragroup survives");
    assert_eq!(parent_name(&pool, "P").await.as_deref(), Some("NO~"), "P stays under NO~");
    assert!(!has_variant(&pool, "NO", "CTS1320").await, "NO~'s SNP did not move to NO");
    assert!(has_variant(&pool, "NO", "Z12176").await, "NO keeps its own SNP");

    // FOO~ empty stub is folded into FOO: gone, child BAR reparented to FOO.
    assert!(!exists(&pool, "FOO~").await, "empty stub FOO~ removed");
    assert_eq!(parent_name(&pool, "BAR").await.as_deref(), Some("FOO"), "BAR reparented to FOO");
}
