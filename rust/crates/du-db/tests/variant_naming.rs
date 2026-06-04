//! Live-DB test for the Variant Naming Authority (`du_db::naming` + migration
//! 0016: nullable canonical_name, du_variant_name_seq, next_du_name). Exercises
//! the queue, dedup-by-coordinate, DU minting (with old-name→alias), and the
//! NAMED guard. Re-runnable; skips when DATABASE_URL is unset.

use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn mk_variant(pool: &PgPool, name: Option<&str>, status: &str, pos: Option<&str>) -> i64 {
    let coords = match pos {
        Some(p) => serde_json::json!({ "GRCh38": { "contig": "chrY", "position": p } }),
        None => serde_json::json!({}),
    };
    sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, coordinates) \
         VALUES ($1, 'SNP'::core.mutation_type, $2::core.naming_status, $3) RETURNING id",
    )
    .bind(name)
    .bind(status)
    .bind(coords)
    .fetch_one(pool)
    .await
    .expect("insert variant")
}

#[tokio::test]
async fn variant_naming_authority_flow() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping variant_naming test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Three test variants: an unnamed one + a named one at the SAME coord (dedup),
    // and one with a working name awaiting an official DU name.
    let unnamed = mk_variant(&pool, None, "UNNAMED", Some("2781234")).await;
    let named_same = mk_variant(&pool, Some("TESTNAME-AT2781234"), "NAMED", Some("2781234")).await;
    let working = mk_variant(&pool, Some("TESTNAME-WORK"), "PENDING_REVIEW", None).await;
    let ids = [unnamed, named_same, working];

    // The default "needs a name" queue = no-name-yet OR flagged-for-review.
    let q = du_db::naming::queue(&pool, "needs_name", 1, 200).await.expect("queue");
    assert!(q.items.iter().any(|i| i.id == unnamed && i.canonical_name.is_none()), "unnamed in queue");
    assert!(q.items.iter().any(|i| i.id == working), "pending-review in queue");

    // Dedup: the unnamed variant shares a coord with the named one → suggest reuse.
    let dups = du_db::naming::dedup_by_coordinates(&pool, unnamed).await.expect("dedup");
    assert!(dups.iter().any(|(id, n)| *id == named_same && n == "TESTNAME-AT2781234"), "dedup finds same-coord named");

    // Mint a DU name for the unnamed variant.
    let du1 = du_db::naming::assign_du_name(&pool, unnamed).await.expect("mint");
    assert!(du1.starts_with("DU"), "minted a DU name: {du1}");
    let got = du_db::naming::get(&pool, unnamed).await.expect("get").unwrap();
    assert_eq!(got.canonical_name.as_deref(), Some(du1.as_str()));
    assert_eq!(got.naming_status, "NAMED");

    // Mint for the working-named one → old name preserved as a common-name alias.
    let du2 = du_db::naming::assign_du_name(&pool, working).await.expect("mint2");
    assert_ne!(du1, du2, "sequence advances");
    let alias_kept: bool = sqlx::query_scalar(
        "SELECT aliases->'common_names' ? 'TESTNAME-WORK' FROM core.variant WHERE id = $1",
    )
    .bind(working)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert!(alias_kept, "prior working name kept as alias");

    // Re-minting a NAMED variant is refused.
    assert!(du_db::naming::assign_du_name(&pool, unnamed).await.is_err(), "no re-mint of NAMED");

    // set_status drives the lifecycle.
    assert!(du_db::naming::set_status(&pool, named_same, "PENDING_REVIEW").await.expect("set"));
    assert_eq!(du_db::naming::get(&pool, named_same).await.unwrap().unwrap().naming_status, "PENDING_REVIEW");

    // cleanup (by id — unnamed→DU rows can't be found by a test-prefix name).
    for id in ids {
        let _ = sqlx::query("DELETE FROM core.variant WHERE id = $1").bind(id).execute(&pool).await;
    }
}
