//! Live-DB test for the per-chromosome "Testing Benchmarks" aggregation
//! (`du_db::coverage::contig_benchmarks`). Upserts a couple of federated coverage
//! summaries carrying `metrics.contigs[]` and checks the per-contig average / CV /
//! years-per-SNP roll-up. Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test contig_benchmarks

use du_db::fed::coverage::{self, CoverageRecord};
use serde_json::json;

const COLL: &str = "com.decodingus.atmosphere.alignment";

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// A record with one chrY contig entry carrying `callable`, `meanDepth`,
/// `poorMappingQuality`, `length`, `refN`.
fn rec(did: &str, callable: i64, depth: f64, poor: i64, time_us: i64) -> CoverageRecord {
    CoverageRecord {
        did: did.to_string(),
        collection: COLL.to_string(),
        rkey: "r1".to_string(),
        at_uri: format!("at://{did}/{COLL}/r1"),
        cid: Some("bafytest".to_string()),
        biosample_ref: Some(format!("at://{did}/com.decodingus.atmosphere.biosample/r1")),
        sequence_run_ref: None,
        reference_build: Some("GRCh38".to_string()),
        aligner: Some("BWA-MEM 0.7.17".to_string()),
        mean_coverage: Some(depth),
        median_coverage: Some(depth),
        pct_10x: Some(99.0),
        pct_20x: Some(95.0),
        pct_30x: Some(90.0),
        metrics: json!({
            "meanCoverage": depth.to_string(),
            "contigs": [{
                "contig": "chrY",
                "length": 57_227_415,
                "numReads": 1_000_000,
                "meanDepth": depth.to_string(),   // wire string, like the real records
                "coveragePct": "41.0",
                "callable": callable,
                "noCoverage": 30_000_000,
                "lowCoverage": 500_000,
                "excessiveCoverage": 10_000,
                "poorMappingQuality": poor,
                "refN": 33_000_000,
                "meanBaseQ": "35.0",
                "meanMapQ": "52.0"
            }]
        }),
        record_created_at: None,
        time_us,
    }
}

/// A coverage summary whose `sequence_run_ref` resolves to a sequencerun with a
/// published `sequencing_facility` but no instrument→lab mapping. The benchmark
/// vendor must fall back to the facility (`COALESCE(lab.name, sr.sequencing_facility)`)
/// and the facility must appear in the vendor filter options.
#[tokio::test]
async fn facility_falls_back_as_vendor() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping facility-vendor test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    let did = "did:test:fac";
    let run_uri = format!("at://{did}/com.decodingus.atmosphere.sequencerun/f1");
    // A PacBio run with a facility but no seeded lab_id — the lab.name join yields NULL.
    sqlx::query(
        "INSERT INTO fed.sequencerun (did, rkey, at_uri, instrument_id, sequencing_facility, platform_name, test_type, time_us) \
         VALUES ($1,'f1',$2,'m64023e','Dante Labs','PACBIO_SMRT','WGS_HIFI',100)",
    )
    .bind(did)
    .bind(&run_uri)
    .execute(&pool)
    .await
    .expect("insert facility run");

    // A coverage summary that references that run.
    let mut r = rec(did, 11_000_000, 30.0, 3_500_000, 100);
    r.reference_build = Some("chm13v2.0".to_string());
    r.sequence_run_ref = Some(run_uri.clone());
    coverage::upsert(&pool, &r).await.expect("coverage upsert");

    let rows = du_db::coverage::contig_benchmarks(&pool, &Default::default()).await.expect("benchmarks");
    let y = rows
        .iter()
        .find(|row| row.contig == "chrY" && row.reference_build.as_deref() == Some("chm13v2.0"))
        .expect("a chrY / chm13v2.0 row");
    assert_eq!(y.vendor.as_deref(), Some("Dante Labs"), "facility used as vendor when lab unmapped");

    // The vendor filter selects on the coalesced expression, so filtering by the
    // facility name returns the row.
    let filtered = du_db::coverage::contig_benchmarks(
        &pool,
        &du_db::coverage::ContigBenchmarkFilter { vendor: Some("Dante Labs".into()), ..Default::default() },
    )
    .await
    .expect("vendor filter");
    assert!(filtered.iter().any(|row| row.vendor.as_deref() == Some("Dante Labs")), "facility vendor filter hits");

    // The options dropdown lists the facility-only lab.
    let options = du_db::coverage::contig_benchmark_options(&pool).await.expect("options");
    assert!(options.vendors.iter().any(|v| v == "Dante Labs"), "facility in vendor options: {:?}", options.vendors);
}

/// The coverage benchmark's test-type dimension is the standardized profile label
/// (`COALESCE(sr.test_profile_label, sr.test_type)`): two runs with the same raw
/// `test_type` but different profile labels form distinct, comparable cohorts, and the
/// label (not the raw code) drives grouping, filtering, and the options list.
#[tokio::test]
async fn coverage_groups_by_standardized_profile_label() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping profile-label grouping test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Two runs, both raw testType "WGS", but standardized to different labels — YSEQ's
    // "WGS" is 100-or-150 bp, so the label is the only thing that separates the cohorts.
    for (did, label) in [("did:test:p150", "WGS150 45Gbases"), ("did:test:p100", "WGS100 45Gbases")] {
        let run_uri = format!("at://{did}/com.decodingus.atmosphere.sequencerun/p1");
        sqlx::query(
            "INSERT INTO fed.sequencerun (did, rkey, at_uri, test_type, test_profile_label, platform_name, time_us) \
             VALUES ($1,'p1',$2,'WGS',$3,'ILLUMINA',100)",
        )
        .bind(did)
        .bind(&run_uri)
        .bind(label)
        .execute(&pool)
        .await
        .expect("insert run");

        let mut r = rec(did, 11_000_000, 30.0, 3_500_000, 100);
        r.reference_build = Some("GRCh38".to_string());
        r.sequence_run_ref = Some(run_uri);
        coverage::upsert(&pool, &r).await.expect("coverage upsert");
    }

    let rows = du_db::coverage::contig_benchmarks(&pool, &Default::default()).await.expect("benchmarks");
    let labels: Vec<_> = rows.iter().filter(|r| r.contig == "chrY").filter_map(|r| r.test_type.clone()).collect();
    assert!(labels.iter().any(|t| t == "WGS150 45Gbases"), "150 cohort labelled: {labels:?}");
    assert!(labels.iter().any(|t| t == "WGS100 45Gbases"), "100 cohort labelled: {labels:?}");
    // Two distinct cohorts, not one merged "WGS".
    assert!(!labels.iter().any(|t| t == "WGS"), "raw code not used as the dimension: {labels:?}");

    // Filtering by the standardized label isolates that cohort.
    let only150 = du_db::coverage::contig_benchmarks(
        &pool,
        &du_db::coverage::ContigBenchmarkFilter { test_type: Some("WGS150 45Gbases".into()), ..Default::default() },
    )
    .await
    .expect("label filter");
    assert!(!only150.is_empty());
    assert!(only150.iter().all(|r| r.test_type.as_deref() == Some("WGS150 45Gbases")), "filter restricts to the label");

    // The options dropdown lists the labels, not the raw code.
    let options = du_db::coverage::contig_benchmark_options(&pool).await.expect("options");
    assert!(options.test_types.iter().any(|t| t == "WGS150 45Gbases"), "label in test-type options: {:?}", options.test_types);
    assert!(!options.test_types.iter().any(|t| t == "WGS"), "raw code absent from options: {:?}", options.test_types);
}

#[tokio::test]
async fn contig_benchmarks_avg_cv_and_years_per_snp() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping contig_benchmarks test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Two samples, chrY callable 10M and 12M (mean 11M).
    coverage::upsert(&pool, &rec("did:test:a", 10_000_000, 28.0, 4_000_000, 100)).await.expect("a");
    coverage::upsert(&pool, &rec("did:test:b", 12_000_000, 32.0, 3_000_000, 100)).await.expect("b");

    let rows = du_db::coverage::contig_benchmarks(&pool, &Default::default())
        .await
        .expect("benchmarks");
    let y = rows
        .iter()
        .find(|r| r.contig == "chrY" && r.reference_build.as_deref() == Some("GRCh38"))
        .expect("a chrY / GRCh38 row");

    assert_eq!(y.samples, 2);
    let callable_avg = y.callable_avg.expect("callable avg");
    assert!((callable_avg - 11_000_000.0).abs() < 1.0, "callable avg {callable_avg}");
    // Depth avg = (28+32)/2 = 30.
    assert!((y.depth_avg.unwrap() - 30.0).abs() < 1e-6);
    // Two samples → CV is defined and positive.
    assert!(y.callable_cv.unwrap() > 0.0);
    // Total loci = length - refN = 57,227,415 - 33,000,000 (same both samples) → CV ~ 0.
    assert!((y.total_avg.unwrap() - 24_227_415.0).abs() < 1.0);
    // chrY → years/SNP = 1 / (callable_avg · 8.33e-10).
    let expected_yps = 1.0 / (callable_avg * 8.33e-10);
    let got = y.est_years_per_snp.expect("years per snp on chrY");
    assert!((got - expected_yps).abs() / expected_yps < 1e-9, "yps {got} vs {expected_yps}");

    // ── Filter: only chrY rows come back when the contig filter is set ──
    let filter = du_db::coverage::ContigBenchmarkFilter {
        contig: Some("chrY".into()),
        ..Default::default()
    };
    let only_y = du_db::coverage::contig_benchmarks(&pool, &filter).await.expect("filtered");
    assert!(!only_y.is_empty(), "chrY filter returns rows");
    assert!(only_y.iter().all(|r| r.contig == "chrY"), "filter restricts to chrY");

    // A build that isn't in the cohort yields nothing.
    let none = du_db::coverage::contig_benchmarks(
        &pool,
        &du_db::coverage::ContigBenchmarkFilter { build: Some("GRCh99".into()), ..Default::default() },
    )
    .await
    .expect("filtered build");
    assert!(none.is_empty(), "unknown build filters everything out");

    // Options expose the distinct values for the dropdowns.
    let options = du_db::coverage::contig_benchmark_options(&pool).await.expect("options");
    assert!(options.builds.iter().any(|b| b == "GRCh38"), "GRCh38 in build options");
    assert!(options.contigs.iter().any(|c| c == "chrY"), "chrY in contig options");
}
