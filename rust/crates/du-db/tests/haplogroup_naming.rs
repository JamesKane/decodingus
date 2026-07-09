//! Live-DB test for the private-node naming backfill (`du_db::haplogroup`
//! private_nodes_needing_names / rename_with_alias). Builds a placeholder node whose
//! defining links point at de-novo coordinate variants, with named + recurrent sibling
//! rows at the same sites, and asserts the by-site candidate resolution + rename.
//! Skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test haplogroup_naming

use serde_json::json;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// Insert a core.variant at an hs1 site. `region` (if any) becomes a recurrent
/// region_overlaps annotation. Returns the variant id.
async fn variant(pool: &PgPool, name: &str, pos: i64, anc: &str, der: &str, region: Option<&str>) -> i64 {
    let coords = json!({ "hs1": { "contig": "chrY", "position": pos, "ancestral": anc, "derived": der } });
    let ann = match region {
        Some(r) => json!({ "region_overlaps": [r] }),
        None => json!({}),
    };
    sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, coordinates, annotations) \
         VALUES ($1, 'SNP'::core.mutation_type, 'UNNAMED'::core.naming_status, $2, $3) RETURNING id",
    )
    .bind(name)
    .bind(coords)
    .bind(ann)
    .fetch_one(pool)
    .await
    .expect("insert variant")
}

async fn link(pool: &PgPool, hap: i64, var: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1, $2)")
        .bind(hap)
        .bind(var)
        .execute(pool)
        .await
        .expect("link");
}

#[tokio::test]
async fn names_placeholder_node_by_site_lowest_natural_sort() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping haplogroup_naming test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Placeholder node whose links point at the DE-NOVO COORD rows (as the loader wrote them).
    let node: i64 = sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type, provenance) \
         VALUES ('P-Test:n0', 'Y_DNA'::core.dna_type, '{\"aliases\":[]}'::jsonb) RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .expect("node");

    // Site 1000: de-novo coord (linked) + a named sibling C500.
    let c1 = variant(&pool, "chrY:1000C>T", 1000, "C", "T", None).await;
    variant(&pool, "C500", 1000, "C", "T", None).await;
    // Site 2000: de-novo coord (linked) + a named sibling C100 (lower natural sort).
    let c2 = variant(&pool, "chrY:2000A>G", 2000, "A", "G", None).await;
    variant(&pool, "C100", 2000, "A", "G", None).await;
    // Site 3000: de-novo coord (linked) + a named sibling in a RECURRENT region (must be excluded).
    let c3 = variant(&pool, "chrY:3000G>A", 3000, "G", "A", None).await;
    variant(&pool, "A001", 3000, "G", "A", Some("heterochromatin:DYZ1")).await;
    for v in [c1, c2, c3] {
        link(&pool, node, v).await;
    }

    // By-site resolution: candidates are the named siblings, minus the recurrent one.
    let found = du_db::haplogroup::private_nodes_needing_names(&pool).await.expect("scan");
    let me = found.iter().find(|n| n.id == node).expect("placeholder found");
    let mut cands = me.candidates.clone();
    cands.sort();
    assert_eq!(cands, vec!["C100".to_string(), "C500".to_string()], "recurrent + coord names excluded");
    assert_eq!(me.dna_label, "Y_DNA");

    // Rename to the lowest natural-sort candidate; old id preserved as an alias.
    assert!(du_db::haplogroup::rename_with_alias(&pool, node, "C100", "P-Test:n0").await.unwrap());
    let (name, prov): (String, serde_json::Value) =
        sqlx::query_as("SELECT name, provenance FROM tree.haplogroup WHERE id = $1")
            .bind(node)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(name, "C100");
    let aliases = prov.get("aliases").and_then(|a| a.as_array()).cloned().unwrap_or_default();
    assert!(aliases.iter().any(|a| a == "P-Test:n0"), "old placeholder kept as alias: {aliases:?}");

    // No longer a placeholder → not re-selected on a second scan.
    let again = du_db::haplogroup::private_nodes_needing_names(&pool).await.unwrap();
    assert!(!again.iter().any(|n| n.id == node), "renamed node drops out of the work-list");
}
