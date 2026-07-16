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

/// Link a variant to a haplogroup as a current (`valid_until IS NULL`) defining edge —
/// the way the de-novo loader records "this SNP defines this branch" (the
/// `defining_haplogroup_id` column is a separate, often-unset denorm).
async fn link_variant(pool: &PgPool, variant_id: i64, haplogroup_id: i64) {
    sqlx::query(
        "INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1, $2)",
    )
    .bind(haplogroup_id)
    .bind(variant_id)
    .execute(pool)
    .await
    .expect("link variant to haplogroup");
}

/// An **hs1**-framed variant — the frame the de-novo loader and the site match work in.
async fn mk_hs1_variant(
    pool: &PgPool,
    name: Option<&str>,
    status: &str,
    pos: i64,
    anc: &str,
    der: &str,
    defining: Option<i64>,
) -> i64 {
    let coords = serde_json::json!({
        "hs1": { "contig": "chrY", "position": pos, "ancestral": anc, "derived": der }
    });
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
    .expect("insert hs1 variant")
}

/// The real-world shape the authority got wrong: one physical SNP stored as **two rows** —
/// a named, branch-less catalog row (YBrowse) and the de-novo loader's coordinate-named
/// branch row, which carries *no aliases at all*. The name lives only on the sibling.
///
/// Regression cover for three defects: the dedup warning offering a placeholder as a
/// "named variant"; the Reuse button never appearing for exactly the rows the warning
/// fires on (aliases-only name source ⇒ Mint DU name, which forks the marker's identity,
/// was the only action on screen); and no bulk path to fold the ~130k such rows a single
/// mis-ordered load produced.
#[tokio::test]
async fn adopts_name_from_site_twin_not_just_aliases() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping site-twin naming test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    let branch = mk_hg(&pool, "TEST-TWIN-A").await;
    let branch2 = mk_hg(&pool, "TEST-TWIN-B").await;
    let branch3 = mk_hg(&pool, "TEST-TWIN-C").await;

    // The catalog row: named, defines no branch (YBrowse reference data).
    let catalog = mk_hs1_variant(&pool, Some("TESTFT186008"), "NAMED", 10249542, "C", "G", None).await;
    // The de-novo row: same site + mutation state, placeholder name, empty aliases,
    // and it is the one actually wired to the branch.
    let denovo =
        mk_hs1_variant(&pool, Some("chrY:10249542C>G"), "UNNAMED", 10249542, "C", "G", Some(branch)).await;
    // A same-position, different-state SNP must never be treated as the same marker.
    let other_state =
        mk_hs1_variant(&pool, Some("TESTOTHER"), "NAMED", 10249542, "C", "T", None).await;

    // The de-novo row is naming work…
    let q = du_db::naming::queue(&pool, "needs_name", "all", 1, 500).await.expect("queue");
    assert!(q.items.iter().any(|i| i.id == denovo), "placeholder branch row is in the queue");

    // …and the site match finds its real name on the sibling row.
    let dups = du_db::naming::dedup_by_site(&pool, denovo).await.expect("dedup");
    assert!(
        dups.iter().any(|(id, n)| *id == catalog && n == "TESTFT186008"),
        "site twin found: {dups:?}"
    );
    assert!(!dups.iter().any(|(id, _)| *id == other_state), "different mutation state is not a twin");
    // A placeholder is not a name and must never be reported as one.
    assert!(
        !dups.iter().any(|(_, n)| du_db::naming::is_placeholder_name(n)),
        "dedup must not offer a coordinate placeholder as an existing name: {dups:?}"
    );

    // The row has no aliases, so the old aliases-only lookup found nothing to reuse…
    let d = du_db::naming::get(&pool, denovo).await.unwrap().unwrap();
    assert!(du_db::naming::established_name(&d.aliases).is_none(), "no aliases on a de-novo row");
    // …but the name is adoptable all the same, because the twin has it.
    let offer = du_db::naming::adoptable_name(&pool, denovo, &d.aliases).await.expect("adoptable");
    assert_eq!(offer.as_deref(), Some("TESTFT186008"), "the Reuse offer is backed by the site twin");

    // Adopting writes that name — no DU identifier is minted for a marker already named.
    let adopted = du_db::naming::adopt_established_name(&pool, denovo).await.expect("adopt");
    assert_eq!(adopted, "TESTFT186008");
    let d = du_db::naming::get(&pool, denovo).await.unwrap().unwrap();
    assert_eq!(d.canonical_name.as_deref(), Some("TESTFT186008"));
    assert_eq!(d.naming_status, "NAMED");
    // (name + defining branch) is the canonical identity: the same name on the branch-less
    // catalog row and on branch A coexist by design.
    let q = du_db::naming::queue(&pool, "needs_name", "all", 1, 500).await.expect("queue");
    assert!(!q.items.iter().any(|i| i.id == denovo), "adopted row leaves the queue");

    // ── bulk fold ────────────────────────────────────────────────────────────────
    // Two more de-novo duplicates: one adoptable, one whose name is already canonical on
    // its own branch (a true duplicate / unmodelled recurrence — must NOT be overwritten).
    let dn2 =
        mk_hs1_variant(&pool, Some("chrY:10249542C>G"), "UNNAMED", 10249542, "C", "G", Some(branch2)).await;
    let taken =
        mk_hs1_variant(&pool, Some("TESTFT186008"), "NAMED", 10249542, "C", "G", Some(branch3)).await;
    let dn3 =
        mk_hs1_variant(&pool, Some("chrY:10249542C>G"), "UNNAMED", 10249542, "C", "G", Some(branch3)).await;

    let r = du_db::naming::reconcile_placeholder_names(&pool, 2).await.expect("reconcile");
    assert!(r.adopted >= 1, "folded the adoptable duplicate: {r:?}");
    assert!(r.conflicted >= 1, "left the already-taken name for merge review: {r:?}");

    let g2 = du_db::naming::get(&pool, dn2).await.unwrap().unwrap();
    assert_eq!(g2.canonical_name.as_deref(), Some("TESTFT186008"), "dn2 folded onto the catalog name");
    assert_eq!(g2.naming_status, "NAMED");
    let g3 = du_db::naming::get(&pool, dn3).await.unwrap().unwrap();
    assert_eq!(
        g3.canonical_name.as_deref(),
        Some("chrY:10249542C>G"),
        "dn3's name is already canonical on branch3 — left alone, not silently overwritten"
    );

    // Idempotent: a second pass finds nothing new to adopt.
    let again = du_db::naming::reconcile_placeholder_names(&pool, 2).await.expect("reconcile again");
    assert_eq!(again.adopted, 0, "re-running adopts nothing: {again:?}");

    for id in [denovo, dn2, dn3, taken, catalog, other_state] {
        let _ = sqlx::query("DELETE FROM core.variant WHERE id = $1").bind(id).execute(&pool).await;
    }
    for hg in [branch, branch2, branch3] {
        let _ = sqlx::query("DELETE FROM tree.haplogroup WHERE id = $1").bind(hg).execute(&pool).await;
    }
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
    let q = du_db::naming::queue(&pool, "needs_name", "all", 1, 200).await.expect("queue");
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
    let qph = du_db::naming::queue(&pool, "needs_name", "all", 1, 500).await.expect("queue");
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

/// The stuck-mint collision the Delete-duplicate action exists for, in the shape it actually
/// occurs: the covering name is **split across two rows**. A placeholder defines a branch; a
/// same-site named row carries the SNP's real name but no branch (so it is offered as Reuse);
/// a *different-site* named row carries that same name on the branch (the un-lifted-coordinate
/// drift residue). No single row is both same-site and same-branch — so Reuse hits the branch
/// guard, Mint would fork an identity, and the only correct action is to delete the redundant
/// placeholder.
#[tokio::test]
async fn deletes_split_covering_duplicate() {
    let Some(url) = database_url() else { return };
    let pool = PgPool::connect(&url).await.expect("connect");

    let branch = mk_hg(&pool, "R-SPLITTEST").await;

    // The placeholder: coordinate-named, defines the branch via a tree edge (not the column).
    let ph = mk_hs1_variant(&pool, Some("chrY:20000043T>C"), "UNNAMED", 20000043, "T", "C", None).await;
    link_variant(&pool, ph, branch).await;
    // Same-site named twin, no branch — this is what dedup/Reuse offers.
    let same_site = mk_hs1_variant(&pool, Some("SPLIT1"), "NAMED", 20000043, "T", "C", None).await;
    // Different-site named twin carrying that name ON the branch (both the column — to satisfy
    // the (name, branch) unique key against `same_site` — and a tree edge). This is the row
    // that makes the placeholder redundant and keeps the branch defined after deletion.
    let on_branch = mk_hs1_variant(&pool, Some("SPLIT1"), "NAMED", 20000047, "T", "C", Some(branch)).await;
    link_variant(&pool, on_branch, branch).await;

    // The placeholder's adoptable name is the same-site twin's — SPLIT1.
    let phi = du_db::naming::get(&pool, ph).await.unwrap().unwrap();
    let adoptable = du_db::naming::adoptable_name(&pool, ph, &phi.aliases).await.expect("adoptable");
    assert_eq!(adoptable.as_deref(), Some("SPLIT1"), "adopts the same-site twin's name");

    // That name is already canonical on the shared branch via the tree-linked twin ⇒ deletable.
    let cov = du_db::naming::branch_covering_twin(&pool, ph, "SPLIT1").await.expect("covering");
    assert_eq!(cov.map(|(id, n)| (id, n)), Some((on_branch, "SPLIT1".into())), "on-branch twin covers it");

    // A row whose adoptable name is NOT yet on its branch is NOT deletable (adopt/mint instead).
    assert!(
        du_db::naming::branch_covering_twin(&pool, same_site, "NOPE").await.expect("covering").is_none(),
        "no covering twin ⇒ not deletable"
    );

    // The hard delete: succeeds, reports the covering name, and the placeholder is gone…
    let name = du_db::naming::delete_erroneous_duplicate(&pool, ph).await.expect("delete");
    assert_eq!(name, "SPLIT1");
    assert!(du_db::naming::get(&pool, ph).await.unwrap().is_none(), "placeholder deleted");
    // …while the branch stays defined by the covering twin (not orphaned).
    let still_defined: bool = sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM tree.haplogroup_variant WHERE haplogroup_id = $1 AND valid_until IS NULL)",
    )
    .bind(branch)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert!(still_defined, "branch remains defined after deleting the placeholder");

    // Re-deleting (or deleting a non-duplicate) is refused, not a 500.
    assert!(
        du_db::naming::delete_erroneous_duplicate(&pool, on_branch).await.is_err(),
        "a really-named row is never an erroneous duplicate"
    );

    // cleanup
    for id in [same_site, on_branch] {
        let _ = sqlx::query("DELETE FROM tree.haplogroup_variant WHERE variant_id = $1").bind(id).execute(&pool).await;
        let _ = sqlx::query("DELETE FROM core.variant WHERE id = $1").bind(id).execute(&pool).await;
    }
    let _ = sqlx::query("DELETE FROM tree.haplogroup WHERE id = $1").bind(branch).execute(&pool).await;
}
