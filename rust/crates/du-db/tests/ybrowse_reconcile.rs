//! Live-DB test for YBrowse mirror reconciliation (`du_db::ybrowse`). Exercises
//! synonym folding, additive enrichment of an existing NAMED variant (canonical
//! locked), DU-minting for a provisional-only cluster, and idempotency. Prefix
//! `TESTYB-` / `YFS-TESTX`. Skips when DATABASE_URL is unset.

use serde_json::json;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

fn row(name: &str, pos: i64, anc: &str, der: &str) -> du_db::ybrowse::MirrorRow {
    du_db::ybrowse::MirrorRow {
        name: name.into(),
        contig: "chrY".into(),
        position: pos,
        allele_anc: Some(anc.into()),
        allele_der: Some(der.into()),
        coordinates: json!({ "GRCh38": { "contig": "chrY", "position": pos } }),
        evidence: json!({ "source": "YBrowse" }),
    }
}

async fn cleanup(pool: &PgPool) {
    let _ = du_db::variant::delete_by_evidence_source(pool, "YBrowse").await;
    let _ = sqlx::query("DELETE FROM core.variant WHERE canonical_name LIKE 'TESTYB-%'").execute(pool).await;
    let _ = sqlx::query("DELETE FROM source.ybrowse_snp WHERE name LIKE 'TESTYB-%' OR name LIKE 'YFS-TEST%'")
        .execute(pool)
        .await;
}

/// (canonical_name, naming_status, common_names) for a variant by any of its names.
async fn by_name(pool: &PgPool, name: &str) -> Option<(Option<String>, String, Vec<String>)> {
    let r: Option<(Option<String>, String, serde_json::Value)> = sqlx::query_as(
        "SELECT canonical_name, naming_status::text, COALESCE(aliases->'common_names','[]'::jsonb) \
         FROM core.variant \
         WHERE canonical_name = $1 OR aliases->'common_names' ? $1 LIMIT 1",
    )
    .bind(name)
    .fetch_optional(pool)
    .await
    .unwrap();
    r.map(|(c, s, names)| {
        let names = names.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default();
        (c, s, names)
    })
}

/// mutation_type for a variant by any of its names.
async fn mtype(pool: &PgPool, name: &str) -> Option<String> {
    sqlx::query_scalar(
        "SELECT mutation_type::text FROM core.variant WHERE canonical_name=$1 OR aliases->'common_names' ? $1 LIMIT 1",
    )
    .bind(name)
    .fetch_optional(pool)
    .await
    .unwrap()
}

#[tokio::test]
async fn reconcile_folds_enriches_mints_and_is_idempotent() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping ybrowse_reconcile test");
        return;
    };
    let pool = du_db::connect(&url, 4).await.expect("connect");
    du_db::run_migrations(&pool).await.expect("migrate");
    cleanup(&pool).await;

    // A pre-existing, curator-NAMED variant the EXIST cluster will match by name.
    sqlx::query(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, coordinates) \
         VALUES ('TESTYB-EXIST','SNP'::core.mutation_type,'NAMED'::core.naming_status, \
                 '{\"GRCh38\":{\"contig\":\"chrY\",\"position\":8910002}}'::jsonb)",
    )
    .execute(&pool)
    .await
    .unwrap();
    // A pre-existing variant the COORD cluster will match by COORDINATE only
    // (no shared name) — #2 coordinate-fallback. Carries GRCh38 alleles A>G.
    sqlx::query(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, coordinates) \
         VALUES ('TESTYB-COORD-EXIST','SNP'::core.mutation_type,'NAMED'::core.naming_status, \
                 '{\"GRCh38\":{\"contig\":\"chrY\",\"position\":8910005,\"reference_allele\":\"A\",\"alternate_allele\":\"G\"}}'::jsonb)",
    )
    .execute(&pool)
    .await
    .unwrap();
    // A pre-existing variant MIS-TYPED as SNP that YBrowse clearly identifies as
    // an insertion (T>TC) — enrich should upgrade its type.
    sqlx::query(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, coordinates) \
         VALUES ('TESTYB-TYPEFIX','SNP'::core.mutation_type,'NAMED'::core.naming_status, \
                 '{\"GRCh38\":{\"contig\":\"chrY\",\"position\":8910009,\"reference_allele\":\"T\",\"alternate_allele\":\"TC\"}}'::jsonb)",
    )
    .execute(&pool)
    .await
    .unwrap();

    // Mirror: synonym cluster; existing-name-match+synonym; provisional-only (YFS);
    // a STRAND-FLIP pair (A>G + T>C, #1); and a COORDINATE-only match (#2).
    let mirror = vec![
        row("TESTYB-FOLD-1", 8910001, "T", "C"),
        row("TESTYB-FOLD-2", 8910001, "T", "C"),
        row("TESTYB-EXIST", 8910002, "A", "G"),
        row("TESTYB-EXIST-SYN", 8910002, "A", "G"),
        row("YFS-TESTX", 8910003, "G", "A"),
        row("TESTYB-FWD", 8910004, "A", "G"),
        row("TESTYB-REV", 8910004, "T", "C"),
        row("TESTYB-COORD-NEW", 8910005, "A", "G"),
        row("TESTYB-INDEL", 8910006, "ins", "del"),
        row("TESTYB-INS-A", 8910007, "T", "TC"),
        row("TESTYB-INS-B", 8910007, "TA", "TCA"),
        row("TESTYB-MNP", 8910008, "GC", "AA"),
        row("TESTYB-TYPEFIX-SYN", 8910009, "T", "TC"),
    ];
    du_db::ybrowse::upsert_mirror(&pool, &mirror).await.unwrap();

    let rep = du_db::ybrowse::reconcile(&pool).await.unwrap();
    assert!(rep.clusters >= 3, "at least our 3 clusters");

    // 1. Synonyms folded into ONE variant, canonical = best-ranked (lexicographic
    //    tie) TESTYB-FOLD-1, the other an alias.
    let fold = by_name(&pool, "TESTYB-FOLD-2").await.expect("fold variant");
    assert_eq!(fold.0.as_deref(), Some("TESTYB-FOLD-1"), "canonical is FOLD-1");
    assert!(fold.2.contains(&"TESTYB-FOLD-2".to_string()), "FOLD-2 is an alias");

    // 2. Existing NAMED variant enriched additively — canonical + NAMED locked,
    //    synonym added as alias.
    let exist = by_name(&pool, "TESTYB-EXIST").await.expect("exist variant");
    assert_eq!(exist.0.as_deref(), Some("TESTYB-EXIST"), "canonical unchanged");
    assert_eq!(exist.1, "NAMED", "naming_status locked");
    assert!(exist.2.contains(&"TESTYB-EXIST-SYN".to_string()), "synonym folded in as alias");

    // 3. Provisional-only cluster → minted DU canonical (NAMED), name as alias.
    let du = by_name(&pool, "YFS-TESTX").await.expect("du variant");
    assert!(du.0.as_deref().unwrap_or("").starts_with("DU"), "DU minted: {:?}", du.0);
    assert_eq!(du.1, "NAMED");
    assert!(du.2.contains(&"YFS-TESTX".to_string()), "YFS name is an alias");

    // 4. #1 Strand flip: A>G (FWD) and reverse-complement T>C (REV) fold into ONE
    //    variant — REV resolves to the same canonical as FWD.
    let fwd = by_name(&pool, "TESTYB-FWD").await.expect("fwd");
    let rev = by_name(&pool, "TESTYB-REV").await.expect("rev");
    assert_eq!(fwd.0, rev.0, "strand-flip pair folded into one variant");
    assert_eq!(fwd.0.as_deref(), Some("TESTYB-FWD"), "canonical is the forward name");

    // 5. #2 Coordinate-fallback: TESTYB-COORD-NEW shares no name with the existing
    //    variant but the SAME GRCh38 coordinate → enriched, not duplicated.
    let coord = by_name(&pool, "TESTYB-COORD-NEW").await.expect("coord-matched variant");
    assert_eq!(coord.0.as_deref(), Some("TESTYB-COORD-EXIST"), "matched existing by coordinate");
    let dup: i64 = sqlx::query_scalar("SELECT count(*) FROM core.variant WHERE canonical_name='TESTYB-COORD-NEW'")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(dup, 0, "no duplicate row created for the coordinate-matched name");

    // 6. INDELs: bare ins/del marker → INDEL type; T>TC and trim-equivalent
    //    TA>TCA fold into one INDEL; an equal-length multi-base stays an MNP.
    assert_eq!(mtype(&pool, "TESTYB-INDEL").await.as_deref(), Some("INDEL"), "ins/del marker typed INDEL");
    let ins_a = by_name(&pool, "TESTYB-INS-A").await.expect("ins-a");
    let ins_b = by_name(&pool, "TESTYB-INS-B").await.expect("ins-b");
    assert_eq!(ins_a.0, ins_b.0, "T>TC and TA>TCA fold (trim-equivalent indels)");
    assert_eq!(mtype(&pool, "TESTYB-INS-A").await.as_deref(), Some("INDEL"), "insertion typed INDEL");
    let mnp = by_name(&pool, "TESTYB-MNP").await.expect("mnp");
    assert_eq!(mnp.0.as_deref(), Some("TESTYB-MNP"), "MNP is its own variant (left alone)");
    assert_eq!(mtype(&pool, "TESTYB-MNP").await.as_deref(), Some("MNP"), "equal-length multi-base typed MNP");

    // 7. Enrich upgrades a pre-existing SNP that's clearly an indel (T>TC).
    let typefix = by_name(&pool, "TESTYB-TYPEFIX-SYN").await.expect("typefix");
    assert_eq!(typefix.0.as_deref(), Some("TESTYB-TYPEFIX"), "matched the existing variant by coordinate");
    assert_eq!(mtype(&pool, "TESTYB-TYPEFIX").await.as_deref(), Some("INDEL"), "mis-typed SNP upgraded to INDEL");

    // 4. Idempotent: re-running creates nothing new and keeps the same canonicals.
    let before: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM core.variant WHERE evidence->>'source'='YBrowse' OR canonical_name='TESTYB-EXIST'",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    let rep2 = du_db::ybrowse::reconcile(&pool).await.unwrap();
    assert_eq!(rep2.created, 0, "no new variants on re-run");
    let after: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM core.variant WHERE evidence->>'source'='YBrowse' OR canonical_name='TESTYB-EXIST'",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(before, after, "variant count stable across re-reconcile");
    let du2 = by_name(&pool, "YFS-TESTX").await.unwrap();
    assert_eq!(du.0, du2.0, "DU name stable (matched via alias, not re-minted)");

    cleanup(&pool).await;
}
