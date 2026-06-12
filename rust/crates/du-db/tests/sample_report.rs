//! Live-DB test for the unified per-sample report (`du_db::biosample::report*`).
//! Covers a federated sample (core ↔ fed via atproto uri: haplogroups, sequencing,
//! coverage, ancestry, publication), an academic-only sample (original_haplogroups
//! fallback, no federated extras), identifier resolution, and the is_public gate.
//! Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test sample_report

use du_db::biosample;
use du_domain::enums::DnaType;
use du_domain::ids::SampleGuid;
use serde_json::json;
use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// Insert a donor and return its id.
async fn donor(pool: &PgPool, sex: &str, lat: f64, lon: f64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO core.specimen_donor (sex, geocoord) \
         VALUES ($1::core.biological_sex, ST_SetSRID(ST_MakePoint($2, $3), 4326)) RETURNING id",
    )
    .bind(sex)
    .bind(lon)
    .bind(lat)
    .fetch_one(pool)
    .await
    .expect("insert donor")
}

#[tokio::test]
async fn federated_and_academic_reports() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping sample_report test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // ── Federated, public sample: core.biosample linked to fed.* via atproto uri ──
    let guid = Uuid::new_v4();
    let at_uri = format!("at://did:test:fed/com.decodingus.atmosphere.biosample/{guid}");
    let did = donor(&pool, "MALE", 54.4, -6.2).await;
    sqlx::query(
        "INSERT INTO core.biosample (sample_guid, donor_id, source, accession, alias, description, \
            center_name, is_public, atproto) \
         VALUES ($1, $2, 'CITIZEN', 'ACC1', 'rath1', 'Bronze Age sample', 'Acme Lab', true, $3)",
    )
    .bind(guid)
    .bind(did)
    .bind(json!({"uri": at_uri, "cid": "bafy", "repo_did": "did:test:fed"}))
    .execute(&pool)
    .await
    .expect("insert federated biosample");

    sqlx::query(
        "INSERT INTO fed.biosample (did, rkey, at_uri, sex, y_haplogroup, mt_haplogroup, time_us) \
         VALUES ('did:test:fed', $1, $2, 'Male', 'R-M269', 'U5a1', 1)",
    )
    .bind(guid.to_string())
    .bind(&at_uri)
    .execute(&pool)
    .await
    .expect("insert fed.biosample");

    // Cross-technology consensus for Y (a DIFFERENT call than the single fed.biosample
    // one) — keyed by the citizen's repo DID. It must outrank the fed.biosample call.
    sqlx::query(
        "INSERT INTO fed.haplogroup_reconciliation \
            (did, rkey, at_uri, specimen_donor_ref, dna_type, compatibility_level, \
             consensus_haplogroup, confidence, snp_concordance, run_count, time_us) \
         VALUES ('did:test:fed', 'rec1', 'at://did:test:fed/rec/1', 'at://did:test:fed/donor/1', \
                 'Y_DNA', 'COMPATIBLE', 'R-Z2103', 0.92, 0.97, 3, 1)",
    )
    .execute(&pool)
    .await
    .expect("insert fed.haplogroup_reconciliation");

    sqlx::query(
        "INSERT INTO fed.sequencerun (did, rkey, at_uri, biosample_ref, platform_name, test_type, \
            total_reads, read_length, time_us) \
         VALUES ('did:test:fed', 'run1', 'at://did:test:fed/run/1', $1, 'ILLUMINA', 'WGS', 900000000, 150, 1)",
    )
    .bind(&at_uri)
    .execute(&pool)
    .await
    .expect("insert fed.sequencerun");

    sqlx::query(
        "INSERT INTO fed.coverage_summary (did, collection, rkey, at_uri, biosample_ref, reference_build, \
            mean_coverage, pct_30x, time_us) \
         VALUES ('did:test:fed', 'c', 'cov1', 'at://did:test:fed/cov/1', $1, 'GRCh38', 32.5, 95.0, 1)",
    )
    .bind(&at_uri)
    .execute(&pool)
    .await
    .expect("insert fed.coverage_summary");

    sqlx::query(
        "INSERT INTO fed.population_breakdown (did, rkey, at_uri, biosample_ref, analysis_method, \
            super_population_summary, components, time_us) \
         VALUES ('did:test:fed', 'pb1', 'at://did:test:fed/pb/1', $1, 'PCA_PROJECTION_GMM', $2, '[]'::jsonb, 1)",
    )
    .bind(&at_uri)
    .bind(json!([{"superPopulation": "Steppe", "percentage": 49.0}, {"superPopulation": "EEF", "percentage": 31.0}]))
    .execute(&pool)
    .await
    .expect("insert fed.population_breakdown");

    let pub_id: i64 =
        sqlx::query_scalar("INSERT INTO pubs.publication (title, doi) VALUES ('A paper', '10.1/x') RETURNING id")
            .fetch_one(&pool)
            .await
            .expect("insert publication");
    sqlx::query("INSERT INTO pubs.publication_biosample (publication_id, sample_guid) VALUES ($1, $2)")
        .bind(pub_id)
        .bind(guid)
        .execute(&pool)
        .await
        .expect("link publication");

    let rep = biosample::report_by_guid(&pool, SampleGuid(guid)).await.expect("report").expect("some");
    assert!(rep.identity.is_public);
    assert!(rep.identity.is_federated, "atproto-linked → federated");
    assert_eq!(rep.identity.accession.as_deref(), Some("ACC1"));
    assert_eq!(rep.identity.sex.as_deref(), Some("MALE"));
    let origin = rep.identity.origin.expect("origin");
    assert!((origin.lat - 54.4).abs() < 1e-6 && (origin.lon - -6.2).abs() < 1e-6);
    // Y resolves to the cross-technology consensus (R-Z2103), not the single
    // fed.biosample call (R-M269), carrying its reliability.
    let y = rep.y.as_ref().expect("y");
    assert_eq!(y.name, "R-Z2103", "reconciliation consensus outranks the single fed.biosample call");
    assert_eq!(y.origin, du_db::biosample::HaplogroupCallOrigin::Reconciled);
    assert_eq!(y.run_count, Some(3));
    assert!(y.confidence.unwrap() > 0.9);
    // mt has no reconciliation → falls back to the single fed.biosample call.
    let mt = rep.mt.as_ref().expect("mt");
    assert_eq!(mt.name, "U5a1");
    assert_eq!(mt.origin, du_db::biosample::HaplogroupCallOrigin::FedConsensus);
    assert_eq!(rep.sequencing.len(), 1);
    assert_eq!(rep.coverage.len(), 1);
    assert!(rep.ancestry.is_some());
    assert_eq!(rep.publications.len(), 1);

    // Resolve by accession and by alias.
    let by_acc = biosample::resolve_guid(&pool, "acc1").await.expect("resolve acc");
    assert_eq!(by_acc.map(|g| g.0), Some(guid));
    let by_alias = biosample::resolve_guid(&pool, "rath1").await.expect("resolve alias");
    assert_eq!(by_alias.map(|g| g.0), Some(guid));

    // ── Academic-only sample: no atproto, haplogroup from original_haplogroups ──
    let guid2 = Uuid::new_v4();
    let did2 = donor(&pool, "FEMALE", 40.0, -3.7).await;
    sqlx::query(
        "INSERT INTO core.biosample (sample_guid, donor_id, source, accession, is_public, original_haplogroups) \
         VALUES ($1, $2, 'ANCIENT', 'ACC2', false, $3)",
    )
    .bind(guid2)
    .bind(did2)
    .bind(json!([{"publication_id": 1, "y_result": "I2a", "mt_result": "H1"}]))
    .execute(&pool)
    .await
    .expect("insert academic biosample");

    let rep2 = biosample::report_by_guid(&pool, SampleGuid(guid2)).await.expect("report2").expect("some2");
    assert!(!rep2.identity.is_public);
    assert!(!rep2.identity.is_federated, "no atproto → not federated");
    assert_eq!(rep2.y.as_ref().expect("y2").name, "I2a", "original_haplogroups fallback");
    assert_eq!(rep2.mt.as_ref().expect("mt2").name, "H1");
    assert!(rep2.sequencing.is_empty() && rep2.coverage.is_empty() && rep2.ancestry.is_none());

    // is_public toggle.
    assert!(biosample::set_public(&pool, SampleGuid(guid2), true).await.expect("set_public"));
    let rep2b = biosample::report_by_guid(&pool, SampleGuid(guid2)).await.expect("report2b").expect("some2b");
    assert!(rep2b.identity.is_public);
}

#[tokio::test]
async fn pathway_resolves_and_gaps() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping pathway test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // A name not in the tree resolves to a gap (no steps), not an error.
    let p = du_db::haplogroup::pathway(&pool, "NOT-A-CLADE-XYZ", DnaType::YDna).await.expect("pathway");
    assert!(p.resolved_name.is_none());
    assert!(p.steps.is_empty());
}

/// Heterogeneous publication Y calls (FTDNA terminal-SNP shorthand, path strings,
/// SNP synonyms) resolve to the defining haplogroup via the normalization fallback;
/// old YCC longhand and `n/a` stay unresolved.
#[tokio::test]
async fn resolve_normalizes_publication_calls() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping resolution-fallback test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // A node defined by SNP M269 (the canonical tip of the R-M269 shorthand).
    let hid: i64 =
        sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ('R-M269', 'Y_DNA'::core.dna_type) RETURNING id")
            .fetch_one(&pool)
            .await
            .expect("insert haplogroup");
    let vid: i64 =
        sqlx::query_scalar("INSERT INTO core.variant (canonical_name, mutation_type) VALUES ('M269', 'SNP'::core.mutation_type) RETURNING id")
            .fetch_one(&pool)
            .await
            .expect("insert variant");
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)")
        .bind(hid)
        .bind(vid)
        .execute(&pool)
        .await
        .expect("link variant");

    let resolve = |q: &'static str| {
        let pool = pool.clone();
        async move { du_db::haplogroup::resolve_name_or_variant(&pool, q, DnaType::YDna).await.expect("resolve") }
    };

    // Direct node name still resolves (unchanged behavior).
    assert_eq!(resolve("R-M269").await.as_deref(), Some("R-M269"));
    // Bare defining SNP resolves via the existing variant phase.
    assert_eq!(resolve("M269").await.as_deref(), Some("R-M269"));
    // FTDNA shorthand for a *different* letter prefix on the same SNP normalizes in.
    assert_eq!(resolve("Z-M269").await.as_deref(), Some("R-M269"));
    // Path string → terminal SNP token.
    assert_eq!(resolve("R-DF27 > L2 > M269").await.as_deref(), Some("R-M269"));
    // SNP synonym list → first matching token.
    assert_eq!(resolve("M269/PF1234").await.as_deref(), Some("R-M269"));

    // Old YCC nested-letter longhand has no SNP to recover → unresolved.
    assert!(resolve("R1b1a2").await.is_none());
    // Non-calls → unresolved.
    assert!(resolve("n/a (female)").await.is_none());
}
