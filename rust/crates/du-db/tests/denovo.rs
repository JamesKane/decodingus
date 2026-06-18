//! Live-DB test for the de-novo tree foundation loader (`du_db::denovo`).
//!
//! Covers: catalog reuse by hs1 coordinate (a known SNP keeps its name), minting
//! a novel de-novo SNP, node naming (ISOGG label → catalog-SNP fallback → NodeN),
//! edges, and `clear_dna` clearing a prior load. Skips when DATABASE_URL is unset.

use du_db::denovo::{self, DenovoTree};
use du_db::haplogroup;
use du_domain::enums::DnaType;
use serde_json::json;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn seed_catalog_snp(pool: &PgPool, name: &str, pos: i64, anc: &str, der: &str) {
    sqlx::query(
        "INSERT INTO core.variant (canonical_name, mutation_type, coordinates) \
         VALUES ($1, 'SNP'::core.mutation_type, jsonb_build_object('hs1', \
           jsonb_build_object('contig','chrY','position',$2::bigint,'ancestral',$3,'derived',$4)))",
    )
    .bind(name)
    .bind(pos)
    .bind(anc)
    .bind(der)
    .execute(pool)
    .await
    .expect("seed catalog snp");
}

async fn node_name(pool: &PgPool, denovo_node: &str) -> Option<String> {
    sqlx::query_scalar("SELECT name FROM tree.haplogroup WHERE provenance->>'denovo_node' = $1")
        .bind(denovo_node)
        .fetch_optional(pool)
        .await
        .expect("node lookup")
}

async fn defines(pool: &PgPool, node_name: &str, snp: &str) -> bool {
    let n: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup h \
         JOIN tree.haplogroup_variant hv ON hv.haplogroup_id = h.id \
         JOIN core.variant v ON v.id = hv.variant_id \
         WHERE h.name = $1 AND v.canonical_name = $2",
    )
    .bind(node_name)
    .bind(snp)
    .fetch_one(pool)
    .await
    .expect("defines count");
    n > 0
}

fn doc() -> DenovoTree {
    // Node1 (root) → Node2 [R-M269 / var M269] → Node3 [unlabeled / var L21]
    //                                          → Node4 [unlabeled / novel SNP]
    serde_json::from_value(json!({
        "chromosome": "chrY", "haplogroupType": "Y_DNA", "build": "chm13v2.0",
        "source": "decodingus-denovo", "root": "Node1",
        "nodes": [
            { "id": "Node1", "parent": null, "support": 100, "definingVariants": [] },
            { "id": "Node2", "parent": "Node1", "support": 100, "label": "R-M269", "isogg": "R",
              "definingVariants": [
                { "chrom":"chrY","pos":21452686,"ref":"T","alt":"C","ancestral":"T","derived":"C","reversion":false,"polarity":"forward" }
              ] },
            { "id": "Node3", "parent": "Node2", "support": 96,
              "definingVariants": [
                { "chrom":"chrY","pos":13500000,"ref":"G","alt":"A","ancestral":"G","derived":"A","reversion":false,"polarity":"forward" }
              ],
              "unresolvedVariants": [
                { "chrom":"chrY","pos":5000000,"ref":"C","alt":"T","ancestral":"C","derived":"T","reversion":false,"polarity":"forward" }
              ] },
            { "id": "Node4", "parent": "Node3", "support": 80,
              "definingVariants": [
                { "chrom":"chrY","pos":999999,"ref":"A","alt":"G","ancestral":"A","derived":"G","reversion":false,"polarity":"forward" }
              ] }
        ],
        "tips": [
            { "sample": "TESTSAMPLE1", "parentNode": "Node4", "cohort": "PRJEB31736",
              "sex": "male", "terminalLabel": "chrY:999999A>G" }
        ],
        "conflicts": [
            { "isogg": "R1b1a", "label": "R-L389", "nTips": 5, "magnitude": 2,
              "homeNode": "Node2", "foreignIn": 1, "membersAway": 0 }
        ]
    }))
    .expect("doc")
}

fn mt_doc() -> DenovoTree {
    serde_json::from_value(json!({
        "chromosome": "chrM", "haplogroupType": "MT_DNA", "build": "chm13v2.0",
        "source": "decodingus-denovo", "root": "Node1",
        "nodes": [
            { "id": "Node1", "parent": null, "support": 100, "definingVariants": [] },
            { "id": "Node2", "parent": "Node1", "support": 100, "label": "L0", "isogg": "L0",
              "definingVariants": [
                { "chrom":"chrM","pos":263,"ref":"A","alt":"G","ancestral":"A","derived":"G","reversion":false,"polarity":"forward" }
              ] }
        ],
        "tips": [], "conflicts": []
    }))
    .expect("mt doc")
}

async fn count_dna(pool: &PgPool, dna: &str) -> i64 {
    sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup WHERE haplogroup_type::text = $1")
        .bind(dna).fetch_one(pool).await.unwrap()
}

#[tokio::test]
async fn denovo_lineages_coexist_and_clear_independently() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping denovo coexistence test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    denovo::load(&pool, &doc()).await.expect("load Y"); // Y_DNA, 4 nodes
    denovo::load(&pool, &mt_doc()).await.expect("load mt"); // MT_DNA, 2 nodes
    assert_eq!(count_dna(&pool, "Y_DNA").await, 4);
    assert_eq!(count_dna(&pool, "MT_DNA").await, 2);

    // clear_dna touches only the named lineage.
    let cleared = haplogroup::clear_dna(&pool, DnaType::MtDna).await.expect("clear mt");
    assert_eq!(cleared, 2);
    assert_eq!(count_dna(&pool, "MT_DNA").await, 0, "mt cleared");
    assert_eq!(count_dna(&pool, "Y_DNA").await, 4, "Y preserved");
}

#[tokio::test]
async fn loads_denovo_tree_with_catalog_reuse_and_mint() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping denovo test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Two known catalog SNPs (hs1) for reuse; the third defining SNP is novel.
    seed_catalog_snp(&pool, "M269", 21452686, "T", "C").await;
    seed_catalog_snp(&pool, "L21", 13500000, "G", "A").await;

    let rep = denovo::load(&pool, &doc()).await.expect("load");

    assert_eq!(rep.nodes, 4);
    assert_eq!(rep.edges, 3);
    assert_eq!(rep.variant_links, 3);
    assert_eq!(rep.variants_reused, 2, "M269 + L21 reused from catalog");
    assert_eq!(rep.variants_created, 1, "the novel SNP is minted");
    assert_eq!(rep.unresolved_block, 1, "the collapsed-branch SNP is a tagged block, not a link");

    // Unresolved (collapsed) SNP: recorded in provenance, NOT a defining link.
    let (uc, uv): (Option<i32>, Option<String>) = sqlx::query_as(
        "SELECT (provenance->>'unresolved_count')::int, provenance->>'unresolved_variants' \
         FROM tree.haplogroup WHERE provenance->>'denovo_node' = 'Node3'")
        .fetch_one(&pool).await.unwrap();
    assert_eq!(uc, Some(1));
    assert!(uv.unwrap().contains("chrY:5000000C>T"), "unresolved SNP recorded in provenance block");
    let unres_linked: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_variant hv JOIN core.variant v ON v.id=hv.variant_id \
         WHERE v.coordinates->'hs1'->>'position' = '5000000'").fetch_one(&pool).await.unwrap();
    assert_eq!(unres_linked, 0, "unresolved SNP is NOT a haplogroup_variant defining link");

    // Naming: label primary; unlabeled node falls back to its catalog SNP name;
    // unlabeled node with only a novel SNP takes the minted coordinate name.
    assert_eq!(node_name(&pool, "Node1").await.as_deref(), Some("Node1"), "root: no label/SNP → NodeN");
    assert_eq!(node_name(&pool, "Node2").await.as_deref(), Some("R-M269"), "label primary");
    assert_eq!(node_name(&pool, "Node3").await.as_deref(), Some("L21"), "fallback to catalog SNP");
    assert_eq!(node_name(&pool, "Node4").await.as_deref(), Some("chrY:999999A>G"), "minted coord name");

    // SNP linkage + catalog reuse (M269 keeps its identity, not a duplicate).
    assert!(defines(&pool, "R-M269", "M269").await);
    assert!(defines(&pool, "L21", "L21").await);
    let m269_rows: i64 = sqlx::query_scalar("SELECT count(*) FROM core.variant WHERE canonical_name='M269'")
        .fetch_one(&pool).await.unwrap();
    assert_eq!(m269_rows, 1, "M269 reused, not duplicated");
    let minted: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM core.variant WHERE canonical_name='chrY:999999A>G' \
         AND coordinates->'hs1'->>'position' = '999999'")
        .fetch_one(&pool).await.unwrap();
    assert_eq!(minted, 1, "novel SNP minted with hs1 coords");

    // Edge: Node2 (R-M269) is a child of Node1.
    let parent: Option<String> = sqlx::query_scalar(
        "SELECT p.name FROM tree.haplogroup c \
         JOIN tree.haplogroup_relationship r ON r.child_haplogroup_id=c.id \
         JOIN tree.haplogroup p ON p.id=r.parent_haplogroup_id WHERE c.name='R-M269'")
        .fetch_optional(&pool).await.unwrap();
    assert_eq!(parent.as_deref(), Some("Node1"));

    // Backbone: load() runs recompute_backbone. The single-letter isogg node (R)
    // and its ancestors are the spine; off-spine nodes are not flagged.
    async fn is_backbone(pool: &PgPool, name: &str) -> bool {
        sqlx::query_scalar::<_, bool>("SELECT is_backbone FROM tree.haplogroup WHERE name = $1")
            .bind(name).fetch_one(pool).await.unwrap()
    }
    assert!(is_backbone(&pool, "R-M269").await, "isogg=R node is backbone");
    assert!(is_backbone(&pool, "Node1").await, "ancestor of the major clade is backbone");
    assert!(!is_backbone(&pool, "L21").await, "off-spine node is not backbone");
    assert!(!is_backbone(&pool, "chrY:999999A>G").await, "off-spine node is not backbone");

    // Tip placement: a biosample (deduped by accession) + a haplogroup_sample leaf
    // under its terminal node. PRJEB* cohorts are public.
    assert_eq!(rep.tips_placed, 1);
    assert_eq!(rep.biosamples_created, 1);
    let (src, is_public): (String, bool) =
        sqlx::query_as("SELECT source::text, is_public FROM core.biosample WHERE accession='TESTSAMPLE1'")
            .fetch_one(&pool).await.unwrap();
    assert_eq!(src, "EXTERNAL");
    assert!(is_public, "PRJEB* reference panel is public");
    let placed_under: Option<String> = sqlx::query_scalar(
        "SELECT h.name FROM tree.haplogroup_sample hs \
         JOIN core.biosample b ON b.sample_guid = hs.sample_guid \
         JOIN tree.haplogroup h ON h.id = hs.haplogroup_id \
         WHERE b.accession = 'TESTSAMPLE1' AND hs.dna_type::text = 'Y_DNA' AND hs.status = 'PLACED'")
        .fetch_optional(&pool).await.unwrap();
    assert_eq!(placed_under.as_deref(), Some("chrY:999999A>G"), "leaf under its terminal node (Node4)");

    // Conflicts: recorded for curator triage and listed worst-first.
    assert_eq!(rep.conflicts_loaded, 1);
    let conflicts = denovo::list_conflicts(&pool, Some(DnaType::YDna), 1, 25).await.unwrap();
    assert_eq!(conflicts.total, 1);
    assert_eq!(conflicts.items[0].haplogroup, "R1b1a");
    assert_eq!(conflicts.items[0].foreign_in, 1);

    // clear_dna wipes the lineage topology + conflicts (catalog SNP rows survive).
    let cleared = haplogroup::clear_dna(&pool, DnaType::YDna).await.expect("clear");
    assert_eq!(cleared, 4);
    let remaining: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup").fetch_one(&pool).await.unwrap();
    assert_eq!(remaining, 0);
    let snps: i64 = sqlx::query_scalar("SELECT count(*) FROM core.variant WHERE canonical_name IN ('M269','L21')")
        .fetch_one(&pool).await.unwrap();
    assert_eq!(snps, 2, "catalog SNPs survive a tree clear");
}
