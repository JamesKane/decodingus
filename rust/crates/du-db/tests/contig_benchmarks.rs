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
