//! Live-DB test for federated-subject anchoring (`du_db::fed_subject`).
//! A subject published live via Jetstream lands only in `fed.biosample` (+ the
//! sibling `fed.*` tables). `link_federated_subjects` must mint a linked
//! `core.biosample` so the unified report's federated sections light up.
//! Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test fed_subject_link

use du_db::{biosample, fed_subject};
use du_domain::ids::SampleGuid;
use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

#[tokio::test]
async fn anchors_federated_subject_and_report_lights_up() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping fed_subject_link test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool: PgPool = db.pool().clone();

    let did = "did:test:citizen";
    let rkey = Uuid::new_v4().to_string();
    let bio_uri = format!("at://{did}/com.decodingus.atmosphere.biosample/{rkey}");
    let run_uri = format!("at://{did}/com.decodingus.atmosphere.sequencerun/{rkey}");

    // A federated subject: only in fed.biosample, NO core.biosample yet.
    sqlx::query(
        "INSERT INTO fed.biosample (did, rkey, at_uri, cid, sex, y_haplogroup, mt_haplogroup, center_name, time_us) \
         VALUES ($1, $2, $3, 'bafy1', 'MALE', 'R-M269', 'U5a1', 'Home Lab', 10)",
    )
    .bind(did)
    .bind(&rkey)
    .bind(&bio_uri)
    .execute(&pool)
    .await
    .expect("fed.biosample");

    // Its sequencing run + coverage, keyed by the biosample record URI.
    sqlx::query(
        "INSERT INTO fed.sequencerun (did, rkey, at_uri, biosample_ref, platform_name, test_type, time_us) \
         VALUES ($1, $2, $3, $4, 'ILLUMINA', 'WGS', 10)",
    )
    .bind(did)
    .bind(&rkey)
    .bind(&run_uri)
    .bind(&bio_uri)
    .execute(&pool)
    .await
    .expect("fed.sequencerun");

    sqlx::query(
        "INSERT INTO fed.coverage_summary \
           (did, collection, rkey, at_uri, biosample_ref, sequence_run_ref, reference_build, \
            aligner, mean_coverage, pct_30x, metrics, time_us) \
         VALUES ($1, 'com.decodingus.atmosphere.alignment', $2, $3, $4, $5, 'GRCh38', \
                 'BWA-MEM', 32.0, 94.0, \
                 jsonb_build_object('meanCoverage','32.0','contigs', jsonb_build_array( \
                    jsonb_build_object('contig','chrY','length',57227415,'callable',11000000, \
                        'meanDepth','28.0','poorMappingQuality',4000000,'refN',33000000))), 10)",
    )
    .bind(did)
    .bind(&rkey)
    .bind(format!("at://{did}/com.decodingus.atmosphere.alignment/{rkey}"))
    .bind(&bio_uri)
    .bind(&run_uri)
    .execute(&pool)
    .await
    .expect("fed.coverage_summary");

    // No anchor yet → the subject is invisible to the catalog.
    let pre: Option<Uuid> =
        sqlx::query_scalar("SELECT sample_guid FROM core.biosample WHERE atproto->>'uri' = $1")
            .bind(&bio_uri)
            .fetch_optional(&pool)
            .await
            .unwrap();
    assert!(pre.is_none(), "no core anchor before linking");

    // ── Anchor ──
    let rep = fed_subject::link_federated_subjects(&pool, true).await.expect("link");
    assert!(rep.unlinked >= 1, "at least one candidate");
    assert_eq!(rep.biosamples_created, 1);
    assert_eq!(rep.donors_created, 1);

    // The minted anchor.
    let guid: Uuid =
        sqlx::query_scalar("SELECT sample_guid FROM core.biosample WHERE atproto->>'uri' = $1")
            .bind(&bio_uri)
            .fetch_one(&pool)
            .await
            .expect("anchor minted");

    // ── The report now surfaces the federated data ──
    let report = biosample::report_by_guid(&pool, SampleGuid(guid))
        .await
        .expect("report")
        .expect("some report");
    assert!(report.identity.is_federated, "federated link recognized");
    assert_eq!(report.identity.sex.as_deref(), Some("MALE"), "sex from minted donor");
    assert_eq!(report.sequencing.len(), 1, "federated run surfaced");
    assert_eq!(report.coverage.len(), 1, "federated coverage surfaced");
    assert_eq!(report.coverage[0].reference_build.as_deref(), Some("GRCh38"));
    // Y haplogroup from the fed.biosample consensus call.
    assert_eq!(report.y.as_ref().map(|c| c.name.as_str()), Some("R-M269"), "Y call surfaced");

    // ── Idempotent: a second run mints nothing ──
    let again = fed_subject::link_federated_subjects(&pool, true).await.expect("relink");
    assert_eq!(again.biosamples_created, 0, "already anchored");
    assert_eq!(again.unlinked, 0);
}
