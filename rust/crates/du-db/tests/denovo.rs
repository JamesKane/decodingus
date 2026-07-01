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

    denovo::load(&pool, &doc(), false).await.expect("load Y"); // Y_DNA: 4 nodes, Node4 collapses → 3
    denovo::load(&pool, &mt_doc(), false).await.expect("load mt"); // MT_DNA, 2 nodes
    assert_eq!(count_dna(&pool, "Y_DNA").await, 3);
    assert_eq!(count_dna(&pool, "MT_DNA").await, 2);

    // clear_dna touches only the named lineage.
    let cleared = haplogroup::clear_dna(&pool, DnaType::MtDna).await.expect("clear mt");
    assert_eq!(cleared, 2);
    assert_eq!(count_dna(&pool, "MT_DNA").await, 0, "mt cleared");
    assert_eq!(count_dna(&pool, "Y_DNA").await, 3, "Y preserved");
}

#[tokio::test]
async fn clear_dna_neutralizes_private_variant_refs_for_repeatable_reload() {
    // The de-novo tree is reloaded repeatedly during iteration. A prior load's
    // private-variant rows reference nodes by id via a non-cascading FK; clear_dna
    // must neutralize them (delete loader-owned DENOVO rows, unlink fed-mirrored FED
    // rows) so the haplogroup delete isn't blocked and the reload is idempotent.
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping clear_dna private-variant test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    seed_catalog_snp(&pool, "M269", 21452686, "T", "C").await;
    seed_catalog_snp(&pool, "L21", 13500000, "G", "A").await;
    denovo::load(&pool, &doc(), false).await.expect("load Y");

    // Seed one DENOVO + one FED private-variant row referencing a loaded node.
    let sample: uuid::Uuid =
        sqlx::query_scalar("SELECT sample_guid FROM core.biosample WHERE accession='TESTSAMPLE1'")
            .fetch_one(&pool).await.unwrap();
    let node: i64 = sqlx::query_scalar("SELECT id FROM tree.haplogroup WHERE name='R-M269'")
        .fetch_one(&pool).await.unwrap();
    // Two distinct variants — (sample, variant, type) is unique.
    for (snp, origin) in [("M269", "DENOVO"), ("L21", "FED")] {
        let var: i64 = sqlx::query_scalar("SELECT id FROM core.variant WHERE canonical_name=$1")
            .bind(snp).fetch_one(&pool).await.unwrap();
        sqlx::query(
            "INSERT INTO tree.biosample_private_variant \
               (sample_guid, variant_id, haplogroup_type, terminal_haplogroup_id, origin) \
             VALUES ($1, $2, 'Y_DNA'::core.dna_type, $3, $4)",
        )
        .bind(sample).bind(var).bind(node).bind(origin)
        .execute(&pool).await.unwrap();
    }

    // Clear must succeed despite the FK refs (this is what the bug failed on).
    let cleared = haplogroup::clear_dna(&pool, DnaType::YDna).await.expect("clear with private refs");
    assert_eq!(cleared, 3); // Node4 collapsed, so 3 public nodes
    assert_eq!(count_dna(&pool, "Y_DNA").await, 0, "tree cleared");

    // DENOVO rows deleted (the seeded one + Node4's auto-collapsed private); FED kept.
    let denovo_left: i64 =
        sqlx::query_scalar("SELECT count(*) FROM tree.biosample_private_variant WHERE origin='DENOVO'")
            .fetch_one(&pool).await.unwrap();
    assert_eq!(denovo_left, 0, "DENOVO private rows deleted on clear");
    let (fed_left, fed_terminal): (i64, Option<i64>) = sqlx::query_as(
        "SELECT count(*), max(terminal_haplogroup_id) FROM tree.biosample_private_variant WHERE origin='FED'",
    )
    .fetch_one(&pool).await.unwrap();
    assert_eq!(fed_left, 1, "FED private row preserved across reload");
    assert_eq!(fed_terminal, None, "FED row's stale node link nulled");

    // The actual goal: a fresh load now succeeds end-to-end over the cleared lineage.
    denovo::load(&pool, &doc(), false).await.expect("reload Y");
    assert_eq!(count_dna(&pool, "Y_DNA").await, 3, "reloaded cleanly");
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

    let rep = denovo::load(&pool, &doc(), false).await.expect("load");

    // Node4 is an unlabeled single-tip leaf → collapsed as a private singleton: it is
    // not published as a node, its tip hangs on Node4's public parent (Node3), and its
    // novel SNP becomes that sample's DENOVO private. So 3 public nodes, not 4.
    assert_eq!(rep.nodes, 3);
    assert_eq!(rep.edges, 2);
    assert_eq!(rep.variant_links, 2, "M269 + L21 define nodes; Node4's SNP is a private");
    assert_eq!(rep.variants_reused, 2, "M269 + L21 reused from catalog");
    assert_eq!(rep.variants_created, 1, "the novel SNP is still minted (as the private's variant)");
    assert_eq!(rep.unresolved_block, 1, "the collapsed-branch SNP is a tagged block, not a link");
    assert_eq!(rep.private_collapsed, 1, "Node4 collapsed into the discovery substrate");
    assert_eq!(rep.private_seeded, 1, "its novel SNP seeded as a private");

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

    // Naming: label primary; unlabeled node falls back to its catalog SNP name.
    // (Node4 collapsed, so it is no longer a named node.)
    assert_eq!(node_name(&pool, "Node1").await.as_deref(), Some("Node1"), "root: no label/SNP → NodeN");
    assert_eq!(node_name(&pool, "Node2").await.as_deref(), Some("R-M269"), "label primary");
    assert_eq!(node_name(&pool, "Node3").await.as_deref(), Some("L21"), "fallback to catalog SNP");
    assert_eq!(node_name(&pool, "Node4").await, None, "collapsed private singleton is not a node");

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

    // Tip placement: a biosample (deduped by accession) + a haplogroup_sample leaf.
    // TESTSAMPLE1's terminal (Node4) collapsed, so the leaf hangs on Node4's public
    // parent (Node3 = "L21"). PRJEB* cohorts are public.
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
    assert_eq!(placed_under.as_deref(), Some("L21"), "leaf hangs on the collapsed node's parent (Node3)");
    // The collapsed node's novel SNP became this sample's DENOVO private at that node.
    let priv_at: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.biosample_private_variant pv \
         JOIN core.biosample b ON b.sample_guid = pv.sample_guid \
         JOIN tree.haplogroup h ON h.id = pv.terminal_haplogroup_id \
         WHERE b.accession='TESTSAMPLE1' AND pv.origin='DENOVO' AND h.name='L21'")
        .fetch_one(&pool).await.unwrap();
    assert_eq!(priv_at, 1, "Node4's novel SNP is TESTSAMPLE1's private at Node3");

    // Conflicts: recorded for curator triage and listed worst-first.
    assert_eq!(rep.conflicts_loaded, 1);
    let conflicts = denovo::list_conflicts(&pool, Some(DnaType::YDna), 1, 25).await.unwrap();
    assert_eq!(conflicts.total, 1);
    assert_eq!(conflicts.items[0].haplogroup, "R1b1a");
    assert_eq!(conflicts.items[0].foreign_in, 1);

    // clear_dna wipes the lineage topology + conflicts (catalog SNP rows survive).
    let cleared = haplogroup::clear_dna(&pool, DnaType::YDna).await.expect("clear");
    assert_eq!(cleared, 3);
    let remaining: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup").fetch_one(&pool).await.unwrap();
    assert_eq!(remaining, 0);
    let snps: i64 = sqlx::query_scalar("SELECT count(*) FROM core.variant WHERE canonical_name IN ('M269','L21')")
        .fetch_one(&pool).await.unwrap();
    assert_eq!(snps, 2, "catalog SNPs survive a tree clear");
}
