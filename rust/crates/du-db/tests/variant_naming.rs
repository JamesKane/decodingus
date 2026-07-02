//! Live-DB test for the Variant Naming Authority (`du_db::naming` + migration
//! 0016: nullable canonical_name, du_variant_name_seq, next_du_name). Exercises
//! the branch-scoped queue, dedup-by-site (locus + mutation state), DU minting
//! (with old-name→alias), reuse of an established name, and the NAMED guard.
//! Re-runnable; skips when DATABASE_URL is unset.

use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

/// A branch to scope canonical names to (canonical identity = name + branch).
async fn mk_hg(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type) \
         VALUES ($1, 'Y_DNA'::core.dna_type) RETURNING id",
    )
    .bind(name)
    .fetch_one(pool)
    .await
    .expect("insert haplogroup")
}

/// A variant at a GRCh38 site with explicit alleles, optionally scoped to a branch.
async fn mk_variant(
    pool: &PgPool,
    name: Option<&str>,
    status: &str,
    pos: Option<&str>,
    alleles: Option<(&str, &str)>,
    defining: Option<i64>,
) -> i64 {
    let coords = match (pos, alleles) {
        (Some(p), Some((a, d))) => serde_json::json!({
            "GRCh38": { "contig": "chrY", "position": p, "ancestral": a, "derived": d }
        }),
        (Some(p), None) => serde_json::json!({ "GRCh38": { "contig": "chrY", "position": p } }),
        _ => serde_json::json!({}),
    };
    sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, coordinates, defining_haplogroup_id) \
         VALUES ($1, 'SNP'::core.mutation_type, $2::core.naming_status, $3, $4) RETURNING id",
    )
    .bind(name)
    .bind(status)
    .bind(coords)
    .bind(defining)
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

    let branch = mk_hg(&pool, "TEST-BRANCH-A").await;
    let branch2 = mk_hg(&pool, "TEST-BRANCH-B").await;

    // A branch-defining unnamed variant (A>G) + a named one at the SAME site & state
    // (dedup hit), a named one at the same POSITION but a DIFFERENT state (A>C — must
    // NOT be a dedup hit), and a branch-defining working-named variant awaiting a DU.
    let unnamed = mk_variant(&pool, None, "UNNAMED", Some("2781234"), Some(("A", "G")), Some(branch)).await;
    let named_same = mk_variant(&pool, Some("TESTNAME-AG"), "NAMED", Some("2781234"), Some(("A", "G")), None).await;
    let named_diff = mk_variant(&pool, Some("TESTNAME-AC"), "NAMED", Some("2781234"), Some(("A", "C")), None).await;
    let working = mk_variant(&pool, Some("TESTNAME-WORK"), "PENDING_REVIEW", None, None, Some(branch)).await;
    let ids = [unnamed, named_same, named_diff, working];

    // The default "needs a name" queue = defines a branch AND has no real name yet.
    let q = du_db::naming::queue(&pool, "needs_name", 1, 200).await.expect("queue");
    assert!(q.items.iter().any(|i| i.id == unnamed && i.canonical_name.is_none()), "unnamed in queue");
    assert!(q.items.iter().any(|i| i.id == working), "pending-review in queue");
    // A branch-less named catalog row is NOT naming work.
    assert!(!q.items.iter().any(|i| i.id == named_same), "branch-less named row excluded");

    // Dedup-by-site: matches the same locus + mutation state (A>G), NOT a same-position
    // different-state variant (A>C) — the L270-vs-recurrence distinction.
    let dups = du_db::naming::dedup_by_site(&pool, unnamed).await.expect("dedup");
    assert!(dups.iter().any(|(id, n)| *id == named_same && n == "TESTNAME-AG"), "dedup finds same-site named");
    assert!(!dups.iter().any(|(id, _)| *id == named_diff), "dedup ignores same-position different-state");

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

    // Adopt: a branch-defining variant that already carries an established (non-DU)
    // name reuses it as canonical instead of minting — "named by definition".
    let adoptable = mk_variant(&pool, None, "UNNAMED", Some("2999999"), Some(("C", "T")), Some(branch2)).await;
    sqlx::query("UPDATE core.variant SET aliases = jsonb_build_object('common_names', jsonb_build_array('FGC00001')) WHERE id = $1")
        .bind(adoptable).execute(&pool).await.unwrap();
    let adopted = du_db::naming::adopt_established_name(&pool, adoptable).await.expect("adopt");
    assert_eq!(adopted, "FGC00001", "reuses the established name");
    let got = du_db::naming::get(&pool, adoptable).await.unwrap().unwrap();
    assert_eq!(got.canonical_name.as_deref(), Some("FGC00001"));
    assert_eq!(got.naming_status, "NAMED");
    // Adopting again (already named) is refused.
    assert!(du_db::naming::adopt_established_name(&pool, adoptable).await.is_err(), "no re-adopt of NAMED");

    // Placeholder names: the de-novo loader stamps branch-defining variants NAMED with a
    // synthetic coordinate string (`chrY:pos anc->der`) — which it ALSO copies into the
    // common_names aliases. The authority must treat these as *unnamed*: they belong in
    // the needs_name queue, are still mintable despite the NAMED status, and the
    // placeholder must not survive as an alias nor be offered as an established name.
    let ph = mk_variant(&pool, Some("chrY:9999999 A->G"), "NAMED", Some("9999999"), Some(("A", "G")), Some(branch)).await;
    sqlx::query("UPDATE core.variant SET aliases = jsonb_build_object('common_names', jsonb_build_array('chrY:9999999 A->G')) WHERE id = $1")
        .bind(ph).execute(&pool).await.unwrap();
    assert!(du_db::naming::is_placeholder_name("chrY:9999999 A->G"), "coordinate string is a placeholder");
    assert!(!du_db::naming::is_placeholder_name("Z12335"), "a real SNP name is not a placeholder");
    let qph = du_db::naming::queue(&pool, "needs_name", 1, 500).await.expect("queue");
    assert!(qph.items.iter().any(|i| i.id == ph), "placeholder-named variant is naming work");
    // No established name to adopt — the only alias is the placeholder itself.
    let phi = du_db::naming::get(&pool, ph).await.unwrap().unwrap();
    assert!(du_db::naming::established_name(&phi.aliases).is_none(), "placeholder alias is not an established name");
    // Minting succeeds despite the NAMED status, and drops the placeholder alias.
    let du_ph = du_db::naming::assign_du_name(&pool, ph).await.expect("mint over placeholder");
    assert!(du_ph.starts_with("DU"));
    let kept_placeholder: bool = sqlx::query_scalar(
        "SELECT COALESCE(aliases->'common_names', '[]'::jsonb) ? 'chrY:9999999 A->G' FROM core.variant WHERE id = $1",
    )
    .bind(ph).fetch_one(&pool).await.unwrap();
    assert!(!kept_placeholder, "placeholder is not preserved as an alias");

    // set_status drives the lifecycle.
    assert!(du_db::naming::set_status(&pool, named_same, "PENDING_REVIEW").await.expect("set"));
    assert_eq!(du_db::naming::get(&pool, named_same).await.unwrap().unwrap().naming_status, "PENDING_REVIEW");

    // cleanup (variants first — they reference the branches via defining_haplogroup_id).
    for id in ids.iter().chain([&adoptable, &ph]) {
        let _ = sqlx::query("DELETE FROM core.variant WHERE id = $1").bind(id).execute(&pool).await;
    }
    for hg in [branch, branch2] {
        let _ = sqlx::query("DELETE FROM tree.haplogroup WHERE id = $1").bind(hg).execute(&pool).await;
    }
}
