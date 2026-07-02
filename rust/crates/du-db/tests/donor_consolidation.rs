//! Live-DB test for `du_db::donor::consolidate_denovo_donors` + the donor-level
//! tree-placement surfacing in the sample report. Models one individual split
//! across two publication accessions (different donors) plus a de-novo tree tip
//! (donor-less, holding the placement), then consolidates and checks the report
//! resolves the haplogroup on every accession. Skips when DATABASE_URL is unset.

use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn donor(pool: &PgPool, identifier: &str, biobank: Option<&str>) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO core.specimen_donor (donor_identifier, origin_biobank, donor_type) \
         VALUES ($1, $2, 'STANDARD') RETURNING id",
    )
    .bind(identifier)
    .bind(biobank)
    .fetch_one(pool)
    .await
    .expect("insert donor")
}

async fn biosample(pool: &PgPool, accession: &str, alias: Option<&str>, denovo: bool, donor_id: Option<i64>) -> Uuid {
    let attrs = if denovo { serde_json::json!({ "denovo": true }) } else { serde_json::json!({}) };
    sqlx::query_scalar(
        "INSERT INTO core.biosample (source, accession, alias, source_attrs, donor_id, is_public) \
         VALUES ('EXTERNAL', $1, $2, $3, $4, true) RETURNING sample_guid",
    )
    .bind(accession)
    .bind(alias)
    .bind(attrs)
    .bind(donor_id)
    .fetch_one(pool)
    .await
    .expect("insert biosample")
}

async fn hg(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1, 'Y_DNA'::core.dna_type) RETURNING id",
    )
    .bind(name)
    .fetch_one(pool)
    .await
    .expect("insert hg")
}

async fn place(pool: &PgPool, guid: Uuid, hg_id: i64) {
    sqlx::query(
        "INSERT INTO tree.haplogroup_sample (sample_guid, dna_type, haplogroup_id, call_text, status, refreshed_at) \
         VALUES ($1, 'Y_DNA'::core.dna_type, $2, 'placed', 'PLACED', now())",
    )
    .bind(guid)
    .bind(hg_id)
    .execute(pool)
    .await
    .expect("place");
}

#[tokio::test]
async fn consolidates_and_surfaces_placement() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping donor consolidation test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // One individual (panel id CONS_TEST_1) as: two publication accessions with
    // *different* donors (one rich w/ biobank, one poor), plus a de-novo tip that is
    // donor-less but carries the Y tree placement.
    let rich = donor(&pool, "DONOR_CONS_TEST_1", Some("Coriell")).await;
    let poor = donor(&pool, "CONS_TEST_1_ALT", None).await;
    let r1 = biosample(&pool, "SAMN_CONS_1", Some("CONS_TEST_1"), false, Some(rich)).await;
    let r2 = biosample(&pool, "SAMEA_CONS_1", Some("CONS_TEST_1"), false, Some(poor)).await;
    let tip = biosample(&pool, "CONS_TEST_1", None, true, None).await;
    let node = hg(&pool, "CONS-TEST-NODE").await;
    place(&pool, tip, node).await;

    // Before: the reference shows no haplogroup call (placement is on the tip's row).
    let g_r1 = du_db::biosample::resolve_guid(&pool, "SAMN_CONS_1").await.unwrap().unwrap();
    let before = du_db::biosample::report_by_guid(&pool, g_r1).await.unwrap().unwrap();
    assert!(before.y.is_none(), "reference has no call before consolidation");

    // Consolidate.
    let rep = du_db::donor::consolidate_denovo_donors(&pool, true).await.expect("consolidate");
    assert!(rep.groups >= 1, "found the group");
    assert!(rep.biosamples_repointed >= 2, "repointed the tip + the poor-donor ref");
    assert!(rep.donors_pruned >= 1, "pruned the emptied poor donor");

    // All three biosamples now share ONE donor, and it's the rich one.
    let donors: Vec<Option<i64>> = sqlx::query_scalar(
        "SELECT donor_id FROM core.biosample WHERE sample_guid = ANY($1)",
    )
    .bind([r1, r2, tip])
    .fetch_all(&pool)
    .await
    .unwrap();
    assert_eq!(donors.iter().flatten().collect::<std::collections::HashSet<_>>().len(), 1, "one shared donor");
    assert!(donors.iter().all(|d| *d == Some(rich)), "survivor is the rich donor");
    // The poor donor was pruned.
    let poor_alive: Option<i64> = sqlx::query_scalar("SELECT id FROM core.specimen_donor WHERE id = $1").bind(poor).fetch_optional(&pool).await.unwrap();
    assert!(poor_alive.is_none(), "poor donor deleted");
    // Survivor's identifier normalized to the clean panel id.
    let ident: Option<String> = sqlx::query_scalar("SELECT donor_identifier FROM core.specimen_donor WHERE id = $1").bind(rich).fetch_one(&pool).await.unwrap();
    assert_eq!(ident.as_deref(), Some("CONS_TEST_1"));

    // After: the report surfaces the tree placement on BOTH accessions (via the donor).
    for (label, acc) in [("SAMN ref", "SAMN_CONS_1"), ("SAMEA ref", "SAMEA_CONS_1")] {
        let g = du_db::biosample::resolve_guid(&pool, acc).await.unwrap().unwrap();
        let rep = du_db::biosample::report_by_guid(&pool, g).await.unwrap().unwrap();
        let call = rep.y.unwrap_or_else(|| panic!("{label} should now have a Y call"));
        assert_eq!(call.name, "CONS-TEST-NODE");
        assert_eq!(call.origin, du_db::biosample::HaplogroupCallOrigin::TreePlacement);
    }

    // cleanup
    for g in [r1, r2, tip] {
        let _ = sqlx::query("DELETE FROM tree.haplogroup_sample WHERE sample_guid = $1").bind(g).execute(&pool).await;
        let _ = sqlx::query("DELETE FROM core.biosample WHERE sample_guid = $1").bind(g).execute(&pool).await;
    }
    let _ = sqlx::query("DELETE FROM tree.haplogroup WHERE id = $1").bind(node).execute(&pool).await;
    let _ = sqlx::query("DELETE FROM core.specimen_donor WHERE id = $1").bind(rich).execute(&pool).await;
}
