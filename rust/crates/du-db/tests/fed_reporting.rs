//! Live-DB test for the federated reporting mirror (`du_db::fed` — migration
//! 0012). Exercises a representative upsert per table family, the ordered-write
//! guard, the JSONB super-population aggregate, and the generic delete.
//! Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test fed_reporting

use du_db::fed::{self, analytics, core};
use serde_json::json;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}


fn common(did: &str, rkey: &str, time_us: i64) -> fed::Common {
    fed::Common {
        did: did.to_string(),
        rkey: rkey.to_string(),
        at_uri: format!("at://{did}/c/{rkey}"),
        cid: Some("bafy".into()),
        record_created_at: None,
        time_us,
    }
}

#[tokio::test]
async fn fed_reporting_upsert_guard_aggregate_delete() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping fed_reporting test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Biosample with haplogroup + ordered-write guard.
    let mk_bs = |did: &str, y: &str, t: i64| core::Biosample {
        common: common(did, "bs1", t),
        sex: Some("Male".into()),
        y_haplogroup: Some(y.into()),
        mt_haplogroup: None,
        center_name: Some("Acme".into()),
        population_breakdown_ref: None,
        str_profile_ref: None,
        sequence_run_count: 1,
        genotype_count: 0,
    };
    core::upsert_biosample(&pool, &mk_bs("did:test:a", "R-M269", 100)).await.expect("bs a");
    core::upsert_biosample(&pool, &mk_bs("did:test:a", "R-L21", 200)).await.expect("bs a update");
    core::upsert_biosample(&pool, &mk_bs("did:test:a", "STALE", 150)).await.expect("bs a stale");
    let y: Option<String> = sqlx::query_scalar("SELECT y_haplogroup FROM fed.biosample WHERE did = 'did:test:a'")
        .fetch_one(&pool)
        .await
        .expect("select y");
    assert_eq!(y.as_deref(), Some("R-L21"), "newer write wins, stale rejected");

    // Genotype with file-stripped record JSONB.
    core::upsert_biosample(&pool, &mk_bs("did:test:b", "I-M253", 100)).await.expect("bs b");
    analytics::upsert_genotype(
        &pool,
        &analytics::Genotype {
            common: common("did:test:a", "gt1", 100),
            biosample_ref: Some("at://did:test:a/bs/bs1".into()),
            provider: Some("23andMe".into()),
            test_type_code: Some("ARRAY_23ANDME_V5".into()),
            chip_version: Some("v5".into()),
            total_markers_called: Some(600_000),
            total_markers_possible: Some(640_000),
            no_call_rate: Some(0.02),
            y_markers_called: Some(2000),
            mt_markers_called: Some(3000),
            autosomal_markers_called: Some(580_000),
            het_rate: Some(0.21),
            build_version: Some("GRCh37".into()),
            y_haplogroup: Some("R-M269".into()),
            mt_haplogroup: None,
            population_breakdown_ref: None,
            record: json!({ "provider": "23andMe" }),
        },
    )
    .await
    .expect("genotype");

    // Two population breakdowns → super-population distribution aggregate.
    let mk_pb = |did: &str, eur: f64| analytics::PopulationBreakdown {
        common: common(did, "pb1", 100),
        biosample_ref: None,
        analysis_method: Some("PCA_PROJECTION_GMM".into()),
        panel_type: Some("genome-wide".into()),
        reference_populations: Some("1000G_HGDP_v1".into()),
        snps_analyzed: Some(500_000),
        snps_with_genotype: Some(480_000),
        snps_missing: Some(20_000),
        confidence_level: Some(0.95),
        components: json!([{ "populationCode": "CEU", "percentage": eur }]),
        super_population_summary: json!([{ "superPopulation": "European", "percentage": eur }]),
        pca_coordinates: Some(json!([0.1, 0.2, 0.3])),
    };
    analytics::upsert_population_breakdown(&pool, &mk_pb("did:test:a", 80.0)).await.expect("pb a");
    analytics::upsert_population_breakdown(&pool, &mk_pb("did:test:b", 60.0)).await.expect("pb b");

    let dist = analytics::super_population_distribution(&pool).await.expect("super-pop dist");
    let eur = dist
        .iter()
        .find(|d| d.super_population.as_deref() == Some("European"))
        .expect("European bucket");
    assert_eq!(eur.samples, 2);
    let avg = eur.avg_percentage.expect("avg");
    assert!((avg - 70.0).abs() < 1e-6, "avg European should be 70.0, got {avg}");

    // Haplogroup distribution over fed.biosample (a: R-L21, b: I-M253).
    let hg = core::haplogroup_distribution(&pool).await.expect("haplogroup dist");
    let y: Vec<_> = hg.iter().filter(|h| h.dna_type == "Y_DNA").collect();
    assert!(
        y.iter().any(|h| h.haplogroup == "R-L21") && y.iter().any(|h| h.haplogroup == "I-M253"),
        "both Y haplogroups present, got {y:?}"
    );
    assert!(y.iter().all(|h| h.samples == 1));

    // Generic delete removes from the right table.
    fed::delete(&pool, fed::NS_BIOSAMPLE, "did:test:b", "bs1").await.expect("delete bs b");
    let remaining: i64 = sqlx::query_scalar("SELECT count(*) FROM fed.biosample WHERE did LIKE 'did:test:%'")
        .fetch_one(&pool)
        .await
        .expect("count");
    assert_eq!(remaining, 1, "only did:test:a biosample remains");

}
