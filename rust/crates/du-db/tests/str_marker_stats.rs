//! Live-DB test for the corpus-wide Y-STR marker report (`du_db::ystr::marker_stats`,
//! migrations 0069/0070).
//!
//! Runs against an isolated ephemeral database rather than the shared dev one: the
//! report aggregates *every* profile in the catalog, so assertions about exact
//! min/modal/max values are only meaningful when this test owns the whole corpus.
//! A fresh database also has an empty `genomics.str_marker` — the 0069 seed derives
//! from `genomics.str_mutation_rate`, which is populated by a script, not a
//! migration — so the reference rows are seeded here explicitly.
//! Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test str_marker_stats

use du_db::ystr::{self, MarkerStat};
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// One profile: a biosample plus its `strMarkerValue[]`, in the lexicon shape both
/// the federated mirror and the vendor importer write.
async fn seed_profile(pool: &PgPool, accession: &str, markers: serde_json::Value) {
    let guid: uuid::Uuid = sqlx::query_scalar(
        "INSERT INTO core.biosample (source, accession) \
         VALUES ('EXTERNAL'::core.biosample_source, $1) RETURNING sample_guid",
    )
    .bind(accession)
    .fetch_one(pool)
    .await
    .unwrap();
    let total = markers.as_array().map(|a| a.len()).unwrap_or(0) as i32;
    sqlx::query(
        "INSERT INTO genomics.biosample_str_profile (sample_guid, total_markers, markers) \
         VALUES ($1, $2, $3)",
    )
    .bind(guid)
    .bind(total)
    .bind(&markers)
    .execute(pool)
    .await
    .unwrap();
}

fn simple(marker: &str, repeats: i32) -> serde_json::Value {
    serde_json::json!({ "marker": marker, "value": { "type": "simple", "repeats": repeats } })
}

fn multi(marker: &str, copies: &[i32]) -> serde_json::Value {
    serde_json::json!({ "marker": marker, "value": { "type": "multiCopy", "copies": copies } })
}

fn find<'a>(stats: &'a [MarkerStat], marker: &str) -> &'a MarkerStat {
    stats.iter().find(|m| m.marker_name == marker).unwrap_or_else(|| panic!("no row for {marker}"))
}

#[tokio::test]
async fn marker_stats_reports_range_motif_and_rate_basis() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping marker_stats test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Reference rows. DYS19 is registered under the name profiles use, carrying the
    // literature's DYS394 designation as an alias — the rate below is filed under
    // DYS394, so only alias folding can connect the two.
    sqlx::query(
        "INSERT INTO genomics.str_marker (marker_name, motif, period, aliases) \
         VALUES ('DYS19', 'AGAT', 4, ARRAY['DYS394'])",
    )
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO genomics.str_mutation_rate (marker_name, mutation_rate, method) \
         VALUES ('DYS394', 0.0028513662, 'PUBLISHED')",
    )
    .execute(&pool)
    .await
    .unwrap();

    // Three testers. DYS19 spans 12–14 with 14 twice (the mode). DYS448 carries a
    // reported 0 — a deleted locus, not a zero repeat count. DYS385 is multi-copy.
    // DYS447 has no rate row at all. DYS442 carries a partial repeat.
    seed_profile(
        &pool,
        "STRTEST-1",
        serde_json::json!([
            simple("DYS19", 12),
            simple("DYS448", 0),
            simple("DYS447", 24),
            multi("DYS385", &[11, 15]),
            serde_json::json!({ "marker": "DYS442", "value": { "type": "complex", "raw": "10.2" } }),
        ]),
    )
    .await;
    seed_profile(
        &pool,
        "STRTEST-2",
        serde_json::json!([
            simple("DYS19", 14),
            simple("DYS448", 20),
            simple("DYS447", 25),
            multi("DYS385", &[11, 14]),
        ]),
    )
    .await;
    seed_profile(
        &pool,
        "STRTEST-3",
        serde_json::json!([
            simple("DYS19", 14),
            simple("DYS448", 22),
            simple("DYS447", 25),
            multi("DYS385", &[11, 14]),
        ]),
    )
    .await;

    // Before any refresh the table is empty, so this exercises the live fallback.
    let live = ystr::marker_stats(&pool).await.expect("marker_stats (live fallback)");

    // …and after a refresh it is served from genomics.str_marker_stat. The report
    // is precomputed for speed, so the two paths must agree exactly — a drift here
    // would mean the page shows something the aggregation never produced.
    let written = ystr::refresh_marker_stats(&pool).await.expect("refresh");
    assert_eq!(written as usize, live.len(), "refresh writes one row per marker");
    let stats = ystr::marker_stats(&pool).await.expect("marker_stats (precomputed)");
    assert_eq!(stats.len(), live.len());
    for (a, b) in stats.iter().zip(live.iter()) {
        assert_eq!(a.marker_name, b.marker_name, "precomputed order matches live");
        assert_eq!(
            (a.min_value, a.modal_value, a.max_value, a.null_alleles, &a.age_model_status),
            (b.min_value, b.modal_value, b.max_value, b.null_alleles, &b.age_model_status),
            "precomputed row differs from the live aggregation for {}",
            a.marker_name
        );
    }
    assert!(
        ystr::marker_stats_refreshed_at(&pool).await.expect("refreshed_at").is_some(),
        "refresh stamps a timestamp for the report to display"
    );

    // Simple marker: min/modal/max, and the motif + rate reached through the alias.
    let dys19 = find(&stats, "DYS19");
    assert_eq!((dys19.min_value, dys19.modal_value, dys19.max_value), (Some(12), Some(14), Some(14)));
    assert_eq!(dys19.observations, 3);
    assert_eq!(dys19.samples, 3);
    assert_eq!(dys19.distinct_values, 2);
    assert_eq!(dys19.motif.as_deref(), Some("AGAT"));
    assert_eq!(dys19.period, Some(4));
    assert_eq!(dys19.age_model_status, "MEASURED_RATE");
    assert_eq!(dys19.rate_method.as_deref(), Some("PUBLISHED"));

    // A reported 0 is excluded from the range and counted as a null allele —
    // without this the minimum reads 0 rather than 20.
    let dys448 = find(&stats, "DYS448");
    assert_eq!(dys448.min_value, Some(20), "null allele must not become the minimum");
    assert_eq!(dys448.max_value, Some(22));
    assert_eq!(dys448.null_alleles, 1);
    assert_eq!(dys448.observations, 3, "the null allele is still an observation");

    // Multi-copy: rendered copy vectors, no scalar values, never age-scored.
    let dys385 = find(&stats, "DYS385");
    assert!(dys385.multi_copy);
    assert_eq!(dys385.min_value, None);
    assert_eq!(dys385.min_combination.as_deref(), Some("11-14"));
    assert_eq!(dys385.modal_combination.as_deref(), Some("11-14"));
    assert_eq!(dys385.max_combination.as_deref(), Some("11-15"));
    assert_eq!(dys385.age_model_status, "EXCLUDED");

    // No rate row ⇒ the age model falls back to DEFAULT_STR_RATE, and says so.
    let dys447 = find(&stats, "DYS447");
    assert_eq!(dys447.age_model_status, "DEFAULT_RATE");
    assert_eq!(dys447.mutation_rate, None);
    assert_eq!((dys447.min_value, dys447.modal_value, dys447.max_value), (Some(24), Some(25), Some(25)));

    // Partial repeats are preserved and counted, but never scored.
    let dys442 = find(&stats, "DYS442");
    assert_eq!(dys442.complex_count, 1);
    assert_eq!(dys442.min_value, None);
    assert_eq!(dys442.modal_value, None);
}

#[tokio::test]
async fn load_marker_models_honors_the_active_rate() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping active-rate test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // A published rate alongside one derived from our own tree, as the re-keyed
    // table now allows. Only the active row may reach the age model — promoting a
    // derived rate is a data change, not a code change.
    sqlx::query(
        "INSERT INTO genomics.str_mutation_rate \
           (marker_name, mutation_rate, method, is_active) VALUES \
           ('DYSTEST1', 0.0030, 'PUBLISHED', true), \
           ('DYSTEST1', 0.0090, 'DERIVED',   false), \
           ('DYSTEST2', 0.0040, 'DERIVED',   true)",
    )
    .execute(&pool)
    .await
    .unwrap();

    let models = ystr::load_marker_models(&pool).await.expect("load_marker_models");

    let m1 = models.get("DYSTEST1").expect("DYSTEST1 model");
    assert_eq!(m1.mu_per_gen, 0.0030, "the inactive DERIVED rate must not win");
    assert_eq!(m1.method, "PUBLISHED");

    let m2 = models.get("DYSTEST2").expect("DYSTEST2 model");
    assert_eq!(m2.mu_per_gen, 0.0040);
    assert_eq!(m2.method, "DERIVED", "an active derived rate is used, and labeled as such");
}
