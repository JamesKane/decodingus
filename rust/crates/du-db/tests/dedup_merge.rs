//! Integration test for `du_db::dedup::merge_biosamples` on an isolated,
//! freshly-migrated ephemeral database (never the shared dev DB). Skips when
//! DATABASE_URL is unset. Re-runnable.
//!
//! Exercises both repoint paths — collision-drop (tree.haplogroup_sample, a
//! per-sample unique key) and simple repoint (genomics.biosample_callable_loci) —
//! plus metadata fold, tombstone, audit, and dedup-candidate resolution.

use sqlx::Row;
use uuid::Uuid;

fn db_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

const S: Uuid = Uuid::from_u128(0x1111_1111_1111_1111_1111_1111_1111_1111);
const M: Uuid = Uuid::from_u128(0x2222_2222_2222_2222_2222_2222_2222_2222);

#[tokio::test]
async fn merge_repoints_folds_tombstones_audits() {
    let Some(url) = db_url() else {
        eprintln!("DATABASE_URL unset — skipping dedup_merge test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool();

    // Two biosamples: survivor S (private, no alias) and merged M (public, alias,
    // an accession, and an original-haplogroup entry to fold).
    for (g, acc, pubf, alias, oh) in [
        (S, "ACC_S", false, Option::<&str>::None, "[]"),
        (M, "ACC_M", true, Some("m-alias"), r#"[{"y":"R-M269"}]"#),
    ] {
        sqlx::query(
            "INSERT INTO core.biosample (sample_guid, source, accession, is_public, alias, original_haplogroups) \
             VALUES ($1, 'EXTERNAL'::core.biosample_source, $2, $3, $4, $5::jsonb)",
        )
        .bind(g).bind(acc).bind(pubf).bind(alias).bind(oh)
        .execute(pool).await.expect("insert biosample");
    }

    // haplogroup_sample: S has Y; M has Y (collides → dropped) and MT (repoints).
    for (g, dna) in [(S, "Y_DNA"), (M, "Y_DNA"), (M, "MT_DNA")] {
        sqlx::query(
            "INSERT INTO tree.haplogroup_sample (sample_guid, dna_type, call_text, status) \
             VALUES ($1, $2::core.dna_type, 'x', 'PLACED')",
        )
        .bind(g).bind(dna).execute(pool).await.expect("insert haplogroup_sample");
    }

    // callable_loci: only M has a row → simple repoint to S.
    sqlx::query(
        "INSERT INTO genomics.biosample_callable_loci (sample_guid, chromosome) VALUES ($1, 'chrY')",
    )
    .bind(M).execute(pool).await.expect("insert callable_loci");

    // A dedup candidate for the pair (canonical order S < M).
    let cand_id: i64 = sqlx::query_scalar(
        "INSERT INTO dedup.duplicate_candidate (sample_a, sample_b, block_key, score) \
         VALUES ($1, $2, 'Y=1;MT=2', 0.9) RETURNING id",
    )
    .bind(S).bind(M).fetch_one(pool).await.expect("insert candidate");

    // Merge M into S.
    let rep = du_db::dedup::merge_biosamples(
        pool, S, M, "curator-test", Some(cand_id), serde_json::json!({"call":"DUPLICATE"}),
    )
    .await
    .expect("merge");

    assert_eq!(rep.rows_dropped, 1, "the colliding Y haplogroup_sample row is dropped");
    assert_eq!(rep.rows_repointed, 2, "MT haplogroup_sample + callable_loci repoint to survivor");

    // Survivor inherited MT (own Y kept) → 2 rows; merged has none.
    let s_hg: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup_sample WHERE sample_guid=$1")
        .bind(S).fetch_one(pool).await.unwrap();
    let m_hg: i64 = sqlx::query_scalar("SELECT count(*) FROM tree.haplogroup_sample WHERE sample_guid=$1")
        .bind(M).fetch_one(pool).await.unwrap();
    assert_eq!((s_hg, m_hg), (2, 0));

    // callable_loci repointed.
    let s_cl: i64 = sqlx::query_scalar("SELECT count(*) FROM genomics.biosample_callable_loci WHERE sample_guid=$1")
        .bind(S).fetch_one(pool).await.unwrap();
    assert_eq!(s_cl, 1);

    // Survivor metadata folded.
    let srow = sqlx::query(
        "SELECT deleted, is_public, alias, original_haplogroups, source_attrs FROM core.biosample WHERE sample_guid=$1",
    )
    .bind(S).fetch_one(pool).await.unwrap();
    assert!(!srow.get::<bool, _>("deleted"));
    assert!(srow.get::<bool, _>("is_public"), "public OR'd in from merged");
    assert_eq!(srow.get::<Option<String>, _>("alias").as_deref(), Some("m-alias"), "alias filled from merged");
    let oh: serde_json::Value = srow.get("original_haplogroups");
    assert_eq!(oh.as_array().map(|a| a.len()), Some(1), "merged original_haplogroups folded in");
    let sa: serde_json::Value = srow.get("source_attrs");
    assert_eq!(sa["merged_guids"][0], serde_json::json!(M.to_string()));
    assert_eq!(sa["merged_accessions"][0], serde_json::json!("ACC_M"));

    // Merged tombstoned with a pointer.
    let mrow = sqlx::query("SELECT deleted, source_attrs FROM core.biosample WHERE sample_guid=$1")
        .bind(M).fetch_one(pool).await.unwrap();
    assert!(mrow.get::<bool, _>("deleted"));
    let msa: serde_json::Value = mrow.get("source_attrs");
    assert_eq!(msa["merged_into"], serde_json::json!(S.to_string()));

    // Candidate marked MERGED; audit row written.
    let cstatus: String = sqlx::query_scalar("SELECT status FROM dedup.duplicate_candidate WHERE id=$1")
        .bind(cand_id).fetch_one(pool).await.unwrap();
    assert_eq!(cstatus, "MERGED");
    let audit: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM core.biosample_merge WHERE surviving_guid=$1 AND merged_guid=$2",
    )
    .bind(S).bind(M).fetch_one(pool).await.unwrap();
    assert_eq!(audit, 1);

    // Guard: merging a tombstoned sample again is refused.
    let err = du_db::dedup::merge_biosamples(pool, S, M, "x", None, serde_json::json!({})).await;
    assert!(err.is_err(), "re-merging a deleted sample must fail");
}
