//! Live-DB test for YFull-style leaf placement (`du_db::tree_sample`): non-D2C samples are
//! placed under the node their published call resolves to (direct name, alias, defining SNP),
//! D2C samples are excluded, an unresolvable call lands UNPLACED, and `samples_under` returns
//! at-or-below membership. Skips when DATABASE_URL is unset.

use du_db::tree_sample;
use du_domain::enums::DnaType;
use serde_json::json;
use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// Insert a Y haplogroup node; returns its id.
async fn node(pool: &PgPool, name: &str, aliases: &[&str]) -> i64 {
    let prov = json!({ "aliases": aliases });
    sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type, provenance) \
         VALUES ($1, 'Y_DNA'::core.dna_type, $2) RETURNING id",
    )
    .bind(name)
    .bind(prov)
    .fetch_one(pool)
    .await
    .expect("insert node")
}

async fn edge(pool: &PgPool, parent: i64, child: i64) {
    sqlx::query(
        "INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id) VALUES ($1, $2)",
    )
    .bind(child)
    .bind(parent)
    .execute(pool)
    .await
    .expect("insert edge");
}

/// A non-D2C biosample with a published Y call; returns its guid.
async fn sample(pool: &PgPool, source: &str, accession: &str, y_call: &str) -> Uuid {
    sqlx::query_scalar(
        "INSERT INTO core.biosample (source, accession, original_haplogroups) \
         VALUES ($1::core.biosample_source, $2, $3) RETURNING sample_guid",
    )
    .bind(source)
    .bind(accession)
    .bind(json!([{ "y": y_call }]))
    .fetch_one(pool)
    .await
    .expect("insert sample")
}

#[tokio::test]
async fn places_non_d2c_samples_and_records_unplaced() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping tree_sample test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Tree: R-M269 (parent) → R-L21 (child, with an old-name alias "R1b1a2a1a2c").
    let parent = node(&pool, "R-M269", &[]).await;
    let child = node(&pool, "R-L21", &["R1b1a2a1a2c"]).await;
    edge(&pool, parent, child).await;
    // R-L21's defining SNP, so an "L21" call resolves via the variant path.
    let variant: i64 = sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type) VALUES ('L21', 'SNP'::core.mutation_type) RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .expect("insert variant");
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1, $2)")
        .bind(child)
        .bind(variant)
        .execute(&pool)
        .await
        .unwrap();

    // Four samples: direct-name (EXTERNAL), alias longhand (ANCIENT), defining-SNP (STANDARD),
    // and a junk call (EXTERNAL) → UNPLACED. Plus a D2C (CITIZEN) that must be excluded.
    let s_direct = sample(&pool, "EXTERNAL", "EX-DIRECT", "R-M269").await;
    let _s_alias = sample(&pool, "ANCIENT", "ANC-ALIAS", "R1b1a2a1a2c").await;
    let _s_snp = sample(&pool, "STANDARD", "STD-SNP", "L21").await;
    let s_junk = sample(&pool, "EXTERNAL", "EX-JUNK", "not-a-haplogroup-zzz").await;
    let s_d2c = sample(&pool, "CITIZEN", "CIT-1", "R-M269").await;

    let report = tree_sample::recompute_placements(&pool, DnaType::YDna).await.unwrap();
    assert_eq!(report.placed, 3, "direct + alias + snp resolved");
    assert_eq!(report.unplaced, 1, "the junk call is UNPLACED");

    // The D2C sample never gets a row.
    let d2c_rows: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup_sample WHERE sample_guid = $1")
        .bind(s_d2c)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(d2c_rows, 0, "D2C excluded");

    // The junk sample is UNPLACED (haplogroup_id NULL, raw call kept).
    let (status, hid): (String, Option<i64>) =
        sqlx::query_as("SELECT status, haplogroup_id FROM tree.haplogroup_sample WHERE sample_guid = $1")
            .bind(s_junk)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(status, "UNPLACED");
    assert!(hid.is_none());

    // Counts: child node has the two samples placed exactly at it (alias + snp); parent has the direct one.
    let counts = tree_sample::counts_by_node(&pool, DnaType::YDna).await.unwrap();
    assert_eq!(counts.get(&child).copied(), Some(2));
    assert_eq!(counts.get(&parent).copied(), Some(1));

    // samples_under(parent) is cumulative: parent's own + everything under the child = 3.
    let under_parent = tree_sample::samples_under(&pool, "R-M269", DnaType::YDna).await.unwrap();
    assert_eq!(under_parent.len(), 3);
    let accs: Vec<&str> = under_parent.iter().filter_map(|s| s.accession.as_deref()).collect();
    assert!(accs.contains(&"EX-DIRECT") && accs.contains(&"ANC-ALIAS") && accs.contains(&"STD-SNP"));
    assert!(!accs.contains(&"CIT-1"), "D2C never surfaces");
    // samples_under(child) is just the two at-or-below it.
    assert_eq!(tree_sample::samples_under(&pool, "R-L21", DnaType::YDna).await.unwrap().len(), 2);

    // Idempotent: a second recompute reproduces the same set.
    let report2 = tree_sample::recompute_placements(&pool, DnaType::YDna).await.unwrap();
    assert_eq!((report2.placed, report2.unplaced), (3, 1));
    let total: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup_sample")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(total, 4, "3 placed + 1 unplaced, no D2C, no thrash");

    // Marking a placed sample deleted prunes it on the next recompute.
    sqlx::query("UPDATE core.biosample SET deleted = true WHERE sample_guid = $1").bind(s_direct).execute(&pool).await.unwrap();
    tree_sample::recompute_placements(&pool, DnaType::YDna).await.unwrap();
    let gone: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup_sample WHERE sample_guid = $1")
        .bind(s_direct)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(gone, 0, "deleted sample pruned");
}
