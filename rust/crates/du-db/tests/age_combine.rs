//! Live-DB test for combined branch-age estimation (`du_db::age`). Seeds an
//! STR_VARIANCE estimate + a genealogical anchor, then combines and gap-fills
//! tmrca_ybp (and verifies a curated tmrca_ybp survives recompute).
//! Re-runnable; skips when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test age_combine

use du_db::age::{recompute_combined_ages, PRESENT_YEAR};

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}


#[tokio::test]
async fn combine_str_and_genealogical_gapfills_tmrca() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping age_combine test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    let hg: i64 =
        sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ('TESTAGE-A','Y_DNA'::core.dna_type) RETURNING id")
            .fetch_one(&pool)
            .await
            .expect("hg");

    // Contributing STR term: 3000 ybp (95% CI 2400–3600 → σ≈306). A real STR_VARIANCE
    // row carries sample_count (direct tips); the reconstruction fallback gates on the
    // within-clade diversity floor (sample_count ≥ ystr::MIN_STR_TESTERS_FOR_COMBINE), so
    // seed enough tips to clear it — otherwise the term is (correctly) excluded.
    sqlx::query(
        "INSERT INTO tree.haplogroup_age_estimate (haplogroup_id, method, estimate_ybp, ci_low_ybp, ci_high_ybp, sample_count) \
         VALUES ($1,'STR_VARIANCE',3000,2400,3600,5)",
    )
    .bind(hg)
    .execute(&pool)
    .await
    .expect("str estimate");

    // Genealogical anchor: a KNOWN_MRCA at 3300 ybp (date_ce = PRESENT-3300), σ=300.
    sqlx::query(
        "INSERT INTO tree.genealogical_anchor (haplogroup_id, anchor_type, date_ce, confidence, details) \
         VALUES ($1,'KNOWN_MRCA',$2,0.9,'{\"uncertainty_years\":300}'::jsonb)",
    )
    .bind(hg)
    .bind(PRESENT_YEAR - 3300)
    .execute(&pool)
    .await
    .expect("anchor");

    let stats = recompute_combined_ages(&pool).await.expect("combine");
    assert!(stats.genealogical >= 1, "genealogical term produced");
    assert!(stats.combined >= 1, "combined produced");

    let ages = du_db::ystr::branch_age_estimates(&pool, "TESTAGE-A").await.expect("ages");
    let gen = ages.iter().find(|a| a.method == "GENEALOGICAL").expect("GENEALOGICAL");
    assert_eq!(gen.estimate_ybp, Some(3300));
    let combined = ages.iter().find(|a| a.method == "COMBINED").expect("COMBINED");
    let c = combined.estimate_ybp.unwrap();
    assert!((3000..=3300).contains(&c), "combined between the two terms, got {c}");
    assert!((3100..=3200).contains(&c), "≈3153 by inverse-variance, got {c}");

    // tmrca_ybp refreshed with the combined value.
    let tmrca: Option<i32> = sqlx::query_scalar("SELECT tmrca_ybp FROM tree.haplogroup WHERE id=$1")
        .bind(hg)
        .fetch_one(&pool)
        .await
        .expect("tmrca");
    assert_eq!(tmrca, Some(c), "tmrca_ybp written from COMBINED");

    // A computed tmrca_ybp is REFRESHED on re-run (not frozen at the first value).
    sqlx::query("UPDATE tree.haplogroup SET tmrca_ybp = 9999 WHERE id=$1").bind(hg).execute(&pool).await.expect("stale");
    recompute_combined_ages(&pool).await.expect("combine refresh");
    let refreshed: Option<i32> = sqlx::query_scalar("SELECT tmrca_ybp FROM tree.haplogroup WHERE id=$1")
        .bind(hg).fetch_one(&pool).await.expect("refreshed");
    assert_eq!(refreshed, Some(c), "non-curated tmrca_ybp is recomputed, not frozen");

    // A curator-pinned tmrca_ybp (marked age_curated) MUST survive a recompute.
    sqlx::query("UPDATE tree.haplogroup SET tmrca_ybp = 9999, \
                 provenance = provenance || '{\"age_curated\": true}'::jsonb WHERE id=$1")
        .bind(hg).execute(&pool).await.expect("curate");
    recompute_combined_ages(&pool).await.expect("combine 2");
    let tmrca2: Option<i32> = sqlx::query_scalar("SELECT tmrca_ybp FROM tree.haplogroup WHERE id=$1")
        .bind(hg)
        .fetch_one(&pool)
        .await
        .expect("tmrca2");
    assert_eq!(tmrca2, Some(9999), "curated (age_curated) tmrca_ybp preserved");
}
