//! Live-DB test for `du_db::sequencer` — instrument → lab lookup over the
//! preseeded direct `sequencer_instrument.lab_id` association. Re-runnable; skips
//! (passes) when DATABASE_URL is unset.

use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn lab(pool: &PgPool, name: &str, d2c: bool, web: Option<&str>) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO genomics.sequencing_lab (name, is_d2c, website_url) VALUES ($1,$2,$3) RETURNING id",
    )
    .bind(name)
    .bind(d2c)
    .bind(web)
    .fetch_one(pool)
    .await
    .expect("insert lab")
}

/// A curator user — reassignments write an audit row (FK to ident.users).
async fn test_user(pool: &PgPool) -> uuid::Uuid {
    sqlx::query_scalar(
        "INSERT INTO ident.users (handle, display_name) VALUES ('testmaint-curator', 'Test Curator') \
         ON CONFLICT (handle) DO UPDATE SET display_name = EXCLUDED.display_name RETURNING id",
    )
    .fetch_one(pool)
    .await
    .expect("test user")
}

async fn instrument(pool: &PgPool, iid: &str, model: Option<&str>, manuf: Option<&str>, lab_id: Option<i64>) {
    sqlx::query(
        "INSERT INTO genomics.sequencer_instrument (instrument_id, model_name, manufacturer, lab_id) \
         VALUES ($1,$2,$3,$4)",
    )
    .bind(iid)
    .bind(model)
    .bind(manuf)
    .bind(lab_id)
    .execute(pool)
    .await
    .expect("insert instrument");
}

#[tokio::test]
async fn lookup_resolves_preseeded_association() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping sequencer test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // A test-only lab/instrument (names chosen to not collide with the 0038 seed).
    let acme = lab(&pool, "Acme Test Sequencing", true, Some("https://acme.test")).await;
    instrument(&pool, "ACME-T1", Some("NovaSeq 6000"), Some("Illumina"), Some(acme)).await;
    // An instrument with no preseeded lab association.
    instrument(&pool, "M01234", Some("MiSeq"), Some("Illumina"), None).await;

    // Known instrument resolves with full lab detail.
    let hit = du_db::sequencer::lookup_lab(&pool, "ACME-T1").await.expect("lookup").expect("found");
    assert_eq!(hit.lab_name, "Acme Test Sequencing");
    assert!(hit.is_d2c);
    assert_eq!(hit.website_url.as_deref(), Some("https://acme.test"));
    assert_eq!(hit.model_name.as_deref(), Some("NovaSeq 6000"));
    assert_eq!(hit.manufacturer.as_deref(), Some("Illumina"));

    // Unknown instrument → None.
    assert!(du_db::sequencer::lookup_lab(&pool, "ZZ99999").await.expect("lookup").is_none());
    // Instrument with no lab association → None (inner join drops it).
    assert!(du_db::sequencer::lookup_lab(&pool, "M01234").await.expect("lookup").is_none());

    // Bulk list includes the test instrument (alongside the 0038-seeded ones); the
    // unassociated M01234 is excluded.
    let all = du_db::sequencer::lab_instruments(&pool).await.expect("list");
    assert!(all.iter().any(|i| i.instrument_id == "ACME-T1"));
    assert!(!all.iter().any(|i| i.instrument_id == "M01234"));
}

/// The "Established" maintenance surface: list all instrument→lab associations
/// (incl. unassigned), open one, and reassign its lab directly (no proposal).
#[tokio::test]
async fn established_maintenance_flow() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping established test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let user = test_user(&pool).await;

    let acme = lab(&pool, "Acme Test Sequencing", false, None).await;
    instrument(&pool, "ACME-T1", Some("NovaSeq 6000"), Some("Illumina"), Some(acme)).await;
    // An unassigned instrument — must surface in the maintenance list (unlike lookup).
    instrument(&pool, "MAINT-UN1", Some("MiSeq"), Some("Illumina"), None).await;

    // Search narrows to our test instruments; the unassigned one is included.
    let page = du_db::sequencer::list_established(&pool, Some("maint-un1"), 1, 50).await.expect("list");
    let un = page.items.iter().find(|r| r.instrument_id == "MAINT-UN1").expect("unassigned listed");
    assert!(un.lab_name.is_none(), "unassigned instrument has no lab");

    // The lab picker offers our seeded/test labs.
    let labs = du_db::sequencer::list_labs(&pool).await.expect("labs");
    assert!(labs.iter().any(|l| l.name == "Acme Test Sequencing"));

    // Reassign the unassigned instrument to an existing lab — sets what the public
    // lookup resolves, and is auditable.
    let hit = du_db::sequencer::update_instrument_lab(
        &pool, user, un.id, "Acme Test Sequencing", None, Some("MiSeq v3"), None,
    )
    .await
    .expect("reassign");
    assert_eq!(hit.lab_name, "Acme Test Sequencing");
    // lookup now resolves it (was None before).
    let resolved = du_db::sequencer::lookup_lab(&pool, "MAINT-UN1").await.unwrap().expect("now resolves");
    assert_eq!(resolved.lab_name, "Acme Test Sequencing");
    assert_eq!(resolved.model_name.as_deref(), Some("MiSeq v3"), "model updated");

    // Typing a brand-new lab name creates it and reassigns in one step.
    let a1 = du_db::sequencer::established_detail(&pool, un.id).await.unwrap().unwrap();
    let hit2 = du_db::sequencer::update_instrument_lab(
        &pool, user, a1.id, "Brand New Lab Co", Some("BGI"), None, Some(true),
    )
    .await
    .expect("reassign to new lab");
    assert_eq!(hit2.lab_name, "Brand New Lab Co");
    assert!(hit2.is_d2c, "explicit d2c flag applied to the new lab");
    // The audit trail recorded the reassignment.
    let audited: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM ident.audit_log WHERE entity_type = 'sequencer_instrument' AND action = 'REASSIGN_LAB' AND entity_id = $1",
    )
    .bind(a1.id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert!(audited >= 2, "two reassignments audited, got {audited}");
}

/// The 0038 seed preloads the YDNA-Warehouse d2c instrument→lab map (n_crams>2, max-lab).
#[tokio::test]
async fn seed_preloads_ydna_warehouse_labs() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping seed test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // A few representative ties: the max-lab winner, not a runner-up or NO_CSV.
    let a00186 = du_db::sequencer::lookup_lab(&pool, "A00186").await.unwrap().expect("seeded");
    assert_eq!(a00186.lab_name, "Family Tree DNA");
    assert!(a00186.is_d2c);
    assert_eq!(a00186.model_name.as_deref(), Some("NovaSeq6000"));
    assert_eq!(du_db::sequencer::lookup_lab(&pool, "YSEQ1").await.unwrap().unwrap().lab_name, "YSEQ");
    assert_eq!(du_db::sequencer::lookup_lab(&pool, "A00182").await.unwrap().unwrap().lab_name, "YSEQ"); // YSEQ:6 beats NO_CSV:1
    assert_eq!(du_db::sequencer::lookup_lab(&pool, "FP200007833").await.unwrap().unwrap().lab_name, "Nebula Genomics");
    assert_eq!(du_db::sequencer::lookup_lab(&pool, "ST-E00317").await.unwrap().unwrap().lab_name, "Full Genomes Corporation");

    // Below-threshold / no-lab instruments are NOT seeded (n_crams ≤ 2, or NO_CSV/blank).
    assert!(du_db::sequencer::lookup_lab(&pool, "UNKNOWN").await.unwrap().is_none());
    assert!(du_db::sequencer::lookup_lab(&pool, "MG01HX03").await.unwrap().is_none()); // n_crams=2

    // 36 instruments across 5 labs were seeded.
    let seeded: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM genomics.sequencer_instrument i \
         JOIN genomics.sequencing_lab l ON l.id = i.lab_id \
         WHERE l.name IN ('Family Tree DNA','Dante Labs','Nebula Genomics','Full Genomes Corporation','YSEQ')",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(seeded, 36);
}
