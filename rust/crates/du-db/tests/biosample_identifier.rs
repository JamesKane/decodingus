//! Live-DB test for the federated external-identifier mirror
//! (`du_db::fed::core::replace_biosample_identifiers`, Phase 3 of the identifier-dedup
//! design). Verifies store, is_public policy, full-replace on re-publish, and cascade
//! delete with the biosample record. Skips (passes) when DATABASE_URL is unset.

use du_db::fed::core::{self, Biosample};
use du_db::fed::Common;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

fn common(did: &str, rkey: &str, t: i64) -> Common {
    Common {
        did: did.into(),
        rkey: rkey.into(),
        at_uri: format!("at://{did}/com.decodingus.atmosphere.biosample/{rkey}"),
        cid: Some("bafytest".into()),
        record_created_at: None,
        time_us: t,
    }
}

fn biosample(c: Common) -> Biosample {
    Biosample {
        common: c,
        sex: Some("MALE".into()),
        y_haplogroup: None,
        mt_haplogroup: None,
        center_name: None,
        population_breakdown_ref: None,
        str_profile_ref: None,
        sequence_run_count: 0,
        genotype_count: 0,
    }
}

async fn ids_for(pool: &PgPool, at_uri: &str) -> Vec<(String, String, bool)> {
    sqlx::query_as(
        "SELECT namespace, value, is_public FROM fed.biosample_identifier \
         WHERE at_uri = $1 ORDER BY namespace",
    )
    .bind(at_uri)
    .fetch_all(pool)
    .await
    .expect("query ids")
}

#[tokio::test]
async fn fed_identifier_mirror_store_replace_and_cascade() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping fed identifier-mirror test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    let c = common("did:ex:donor1", "bio1", 1000);
    let uri = c.at_uri.clone();
    core::upsert_biosample(&pool, &biosample(c.clone())).await.expect("upsert biosample");

    // Publish a public (PGP) id + a vendor kit (FTDNA) → policy sets is_public per namespace.
    core::replace_biosample_identifiers(
        &pool,
        &c,
        &[("PGP".into(), "HUF98AFD".into()), ("FTDNA".into(), "B5163".into())],
    )
    .await
    .expect("store ids");
    let got = ids_for(&pool, &uri).await;
    assert_eq!(
        got,
        vec![
            ("FTDNA".to_string(), "B5163".to_string(), false), // vendor → background-only
            ("PGP".to_string(), "HUF98AFD".to_string(), true), // open-consent → public
        ]
    );

    // Re-publish with the FTDNA id dropped and a YSEQ id added → full replace.
    core::replace_biosample_identifiers(
        &pool,
        &c,
        &[("PGP".into(), "HUF98AFD".into()), ("YSEQ".into(), "229".into())],
    )
    .await
    .expect("replace ids");
    let got: Vec<(String, String)> = ids_for(&pool, &uri).await.into_iter().map(|(n, v, _)| (n, v)).collect();
    assert_eq!(got, vec![("PGP".to_string(), "HUF98AFD".to_string()), ("YSEQ".to_string(), "229".to_string())]);

    // Deleting the biosample record cascades to its identifiers.
    du_db::fed::delete(&pool, "com.decodingus.atmosphere.biosample", "did:ex:donor1", "bio1")
        .await
        .expect("delete biosample");
    assert!(ids_for(&pool, &uri).await.is_empty(), "identifiers cascade-deleted with the biosample");
}
