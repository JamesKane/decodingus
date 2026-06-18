//! Live-DB test for Y-STR per-branch modal signatures (`du_db::ystr` + the
//! `fed.str_profile` mirror, migration 0013). Seeds a Y haplogroup + biosamples
//! with STR profiles, recomputes, and reads the branch signature.
//! Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test str_signature

use du_db::fed::{self, core, str_profile};
use serde_json::json;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}


fn common(did: &str, rkey: &str, at_uri: &str) -> fed::Common {
    fed::Common { did: did.into(), rkey: rkey.into(), at_uri: at_uri.into(), cid: None, record_created_at: None, time_us: 1 }
}

// Seed: biosample on TESTSTR-R1 + an STR profile with the given DYS393 value.
async fn seed_sample(pool: &PgPool, n: u32, dys393: i32) {
    let did = format!("did:teststr:{n}");
    let bs_uri = format!("at://{did}/bs/1");
    core::upsert_biosample(
        pool,
        &core::Biosample {
            common: common(&did, "bs1", &bs_uri),
            sex: Some("Male".into()),
            y_haplogroup: Some("TESTSTR-R1".into()),
            mt_haplogroup: None,
            center_name: None,
            population_breakdown_ref: None,
            str_profile_ref: None,
            sequence_run_count: 0,
            genotype_count: 0,
        },
    )
    .await
    .expect("seed biosample");
    str_profile::upsert(
        pool,
        &str_profile::StrProfile {
            common: common(&did, "str1", &format!("at://{did}/str/1")),
            biosample_ref: Some(bs_uri),
            sequence_run_ref: None,
            source: Some("DIRECT_TEST".into()),
            imported_from: Some("FTDNA".into()),
            derivation_method: None,
            total_markers: Some(2),
            markers: json!([
                { "marker": "DYS393", "value": { "type": "simple", "repeats": dys393 } },
                { "marker": "DYS385", "value": { "type": "multiCopy", "copies": [11, 14] } }
            ]),
        },
    )
    .await
    .expect("seed str profile");
}

#[tokio::test]
async fn str_signature_recompute_and_read() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping str_signature test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    sqlx::query("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ('TESTSTR-R1', 'Y_DNA'::core.dna_type)")
        .execute(&pool)
        .await
        .expect("seed haplogroup");

    // Three samples on the branch: DYS393 = 13, 13, 14  → modal 13 (2/3).
    seed_sample(&pool, 1, 13).await;
    seed_sample(&pool, 2, 13).await;
    seed_sample(&pool, 3, 14).await;

    let stats = du_db::ystr::recompute_signatures(&pool).await.expect("recompute");
    assert!(stats.haplogroups >= 1, "at least the seeded branch");

    let sig = du_db::ystr::branch_signature(&pool, "TESTSTR-R1").await.expect("read signature");
    let d393 = sig.iter().find(|m| m.marker_name == "DYS393").expect("DYS393 present");
    assert_eq!(d393.ancestral_value, Some(13), "modal DYS393 = 13");
    assert_eq!(d393.supporting_samples, Some(3));
    assert_eq!(d393.method.as_deref(), Some("MODAL"));
    let conf = d393.confidence.expect("confidence");
    assert!((conf - 0.667).abs() < 0.01, "≈2/3, got {conf}");

    let d385 = sig.iter().find(|m| m.marker_name == "DYS385").expect("DYS385 present");
    assert_eq!(d385.ancestral_value, None, "multi-copy → no simple int");
    assert_eq!(
        d385.ancestral_json,
        Some(json!({ "type": "multiCopy", "copies": [11, 14] })),
        "modal multi-copy value preserved"
    );

    // Recompute also produced a contributing STR-variance age estimate.
    assert!(stats.age_estimates >= 1, "an STR age estimate was computed");
    let ages = du_db::ystr::branch_age_estimates(&pool, "TESTSTR-R1").await.expect("ages");
    let str_age = ages.iter().find(|a| a.method == "STR_VARIANCE").expect("STR_VARIANCE estimate");
    assert!(str_age.estimate_ybp.unwrap_or(0) > 0, "positive age");
    assert_eq!(str_age.sample_count, Some(3));
    assert_eq!(str_age.marker_count, Some(1), "only DYS393 (simple) scored; DYS385 multi-copy excluded");
    assert!(str_age.ci_low_ybp < str_age.estimate_ybp && str_age.estimate_ybp < str_age.ci_high_ybp);

    // A MANUAL override must survive a recompute.
    sqlx::query(
        "UPDATE tree.haplogroup_ancestral_str SET method = 'MANUAL', ancestral_value = 99 \
         WHERE marker_name = 'DYS393' AND haplogroup_id = (SELECT id FROM tree.haplogroup WHERE name='TESTSTR-R1')",
    )
    .execute(&pool)
    .await
    .expect("set manual");
    du_db::ystr::recompute_signatures(&pool).await.expect("recompute 2");
    let sig2 = du_db::ystr::branch_signature(&pool, "TESTSTR-R1").await.expect("read 2");
    let d393b = sig2.iter().find(|m| m.marker_name == "DYS393").unwrap();
    assert_eq!(d393b.method.as_deref(), Some("MANUAL"), "manual override preserved");
    assert_eq!(d393b.ancestral_value, Some(99));

}
