//! Live-DB test for `du_db::haplogroup::label_recurrence_transitions`: multi-branch
//! SNP links are labeled forward (anc→der) or reverse (der→anc, back-mutation) by
//! topological parsimony, and a labeled variant is then left intact by
//! `scrub_recurrent_links`. Skips when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test recurrence_label

use du_domain::enums::DnaType;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn mk_hg(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1,'Y_DNA'::core.dna_type) RETURNING id")
        .bind(name).fetch_one(pool).await.expect("hg")
}
async fn mk_edge(pool: &PgPool, child: i64, parent: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id) VALUES ($1,$2)")
        .bind(child).bind(parent).execute(pool).await.expect("edge");
}
/// Variant with a GRCh38 anc/der (the labeler needs alleles to record direction).
async fn mk_var(pool: &PgPool, name: &str, anc: &str, der: &str) -> i64 {
    let coords = serde_json::json!({"GRCh38": {"contig": "chrY", "position": 1, "ancestral": anc, "derived": der}});
    sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, coordinates) \
         VALUES ($1,'SNP'::core.mutation_type,$2) RETURNING id",
    )
    .bind(name)
    .bind(coords)
    .fetch_one(pool)
    .await
    .expect("var")
}
async fn link(pool: &PgPool, hg: i64, v: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)")
        .bind(hg).bind(v).execute(pool).await.expect("link");
}
/// (ancestral_allele, derived_allele) of the link from variant `v` to haplogroup named `hg`.
async fn link_dir(pool: &PgPool, v: i64, hg: &str) -> (Option<String>, Option<String>) {
    sqlx::query_as(
        "SELECT hv.ancestral_allele, hv.derived_allele FROM tree.haplogroup_variant hv \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         WHERE hv.variant_id = $1 AND h.name = $2 AND hv.valid_until IS NULL",
    )
    .bind(v)
    .bind(hg)
    .fetch_one(pool)
    .await
    .expect("link dir")
}

#[tokio::test]
async fn labels_forward_recurrence_and_reverse_backmutation_and_scrub_skips_them() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping recurrence_label test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // ROOT → A → B  (a chain); ROOT → C (an unrelated lineage).
    let root = mk_hg(&pool, "ROOT").await;
    let a = mk_hg(&pool, "A").await;
    let b = mk_hg(&pool, "B").await;
    let c = mk_hg(&pool, "C").await;
    mk_edge(&pool, a, root).await;
    mk_edge(&pool, b, a).await;
    mk_edge(&pool, c, root).await;

    // BACKMUT (G→A): forward at A, then reverts at its descendant B (back-mutation).
    let backmut = mk_var(&pool, "BACKMUT", "G", "A").await;
    link(&pool, a, backmut).await;
    link(&pool, b, backmut).await;
    // HOMO (C→T): independently derived on the unrelated B and C → both forward.
    let homo = mk_var(&pool, "HOMO", "C", "T").await;
    link(&pool, b, homo).await;
    link(&pool, c, homo).await;

    // Dry-run reports without writing.
    let dry = du_db::haplogroup::label_recurrence_transitions(&pool, DnaType::YDna, false)
        .await
        .expect("dry");
    assert_eq!(dry.variants_examined, 2);
    assert_eq!(dry.forward_links, 3, "A(backmut) + B,C(homo)");
    assert_eq!(dry.reverse_links, 1, "B(backmut) is the back-mutation");
    assert_eq!(dry.back_mutation_variants, 1);
    assert_eq!(dry.homoplasy_variants, 1);
    assert_eq!(link_dir(&pool, backmut, "A").await, (None, None), "dry-run wrote nothing");

    // Apply.
    du_db::haplogroup::label_recurrence_transitions(&pool, DnaType::YDna, true)
        .await
        .expect("apply");

    // BACKMUT: forward G→A at A, reverse A→G at the descendant B.
    assert_eq!(link_dir(&pool, backmut, "A").await, (Some("G".into()), Some("A".into())));
    assert_eq!(link_dir(&pool, backmut, "B").await, (Some("A".into()), Some("G".into())));
    // HOMO: both occurrences forward C→T.
    assert_eq!(link_dir(&pool, homo, "B").await, (Some("C".into()), Some("T".into())));
    assert_eq!(link_dir(&pool, homo, "C").await, (Some("C".into()), Some("T".into())));

    // The scrub now leaves these explained variants alone (HOMO's B/C scatter would
    // otherwise be pruned to one lineage).
    let scrub = du_db::haplogroup::scrub_recurrent_links(&pool, DnaType::YDna, false)
        .await
        .expect("scrub dry");
    assert_eq!(scrub.variants_examined, 0, "labeled variants are skipped");
    assert_eq!(scrub.links_removed, 0);
}
