//! Live-DB test for `du_db::haplogroup::scrub_recurrent_links`: recurrent
//! (homoplasic / ASR-scatter) defining-variant links are pruned to the primary
//! lineage, legitimate nested chains are kept, and the self-name tiebreak picks
//! the branch named after the SNP when concentration ties. Skips when
//! DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test scrub_recurrent

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
async fn mk_var(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO core.variant (canonical_name, mutation_type) VALUES ($1,'SNP'::core.mutation_type) RETURNING id")
        .bind(name).fetch_one(pool).await.expect("var")
}
async fn link(pool: &PgPool, hg: i64, v: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)")
        .bind(hg).bind(v).execute(pool).await.expect("link");
}
async fn live_hgs(pool: &PgPool, v: i64) -> Vec<String> {
    sqlx::query_scalar(
        "SELECT h.name FROM tree.haplogroup_variant hv \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         WHERE hv.variant_id = $1 AND hv.valid_until IS NULL ORDER BY h.name",
    )
    .bind(v)
    .fetch_all(pool)
    .await
    .expect("live hgs")
}

#[tokio::test]
async fn scrub_keeps_lineage_drops_scatter_and_prefers_self_named() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping scrub_recurrent test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // ROOT → A → B → D  (a deep chain); ROOT → E; ROOT → "G2-SNPXVAR" (self-named).
    let root = mk_hg(&pool, "ROOT").await;
    let a = mk_hg(&pool, "A").await;
    let b = mk_hg(&pool, "B").await;
    let d = mk_hg(&pool, "D").await;
    let e = mk_hg(&pool, "E").await;
    let g = mk_hg(&pool, "G2-SNPXVAR").await;
    mk_edge(&pool, a, root).await;
    mk_edge(&pool, b, a).await;
    mk_edge(&pool, d, b).await;
    mk_edge(&pool, e, root).await;
    mk_edge(&pool, g, root).await;

    // CHAIN: linked to A,B,D — all collinear → legitimate, kept whole.
    let v_chain = mk_var(&pool, "CHAIN").await;
    for h in [a, b, d] {
        link(&pool, h, v_chain).await;
    }
    // SCAT: linked to B,D (lineage) + E (off-lineage) → keep B,D, drop E.
    let v_scat = mk_var(&pool, "SCAT").await;
    for h in [b, d, e] {
        link(&pool, h, v_scat).await;
    }
    // SNPXVAR: linked to E and G2-SNPXVAR — both isolated singles → self-name wins.
    let v_self = mk_var(&pool, "SNPXVAR").await;
    for h in [e, g] {
        link(&pool, h, v_self).await;
    }

    // Dry-run reports without writing.
    let dry = du_db::haplogroup::scrub_recurrent_links(&pool, DnaType::YDna, false)
        .await
        .expect("dry");
    assert_eq!(dry.variants_examined, 3, "all three are multi-linked");
    assert_eq!(dry.variants_scrubbed, 2, "CHAIN is legit; SCAT + SNPXVAR scrubbed");
    assert_eq!(dry.links_removed, 2, "drop E from SCAT and E from SNPXVAR");
    assert_eq!(live_hgs(&pool, v_scat).await.len(), 3, "dry-run wrote nothing");

    // Apply.
    let rep = du_db::haplogroup::scrub_recurrent_links(&pool, DnaType::YDna, true)
        .await
        .expect("apply");
    assert_eq!(rep.links_removed, 2);

    assert_eq!(
        live_hgs(&pool, v_chain).await,
        vec!["A", "B", "D"],
        "collinear chain kept intact"
    );
    assert_eq!(
        live_hgs(&pool, v_scat).await,
        vec!["B", "D"],
        "off-lineage E dropped, primary lineage kept"
    );
    assert_eq!(
        live_hgs(&pool, v_self).await,
        vec!["G2-SNPXVAR"],
        "self-named branch kept over the isolated E"
    );
}
