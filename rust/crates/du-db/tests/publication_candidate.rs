//! Live-DB test for the publication-candidate review queue
//! (`du_db::publication` candidate fns). Upserts candidates, reviews/promotes
//! them, and asserts the resulting `pubs.publication`. Prefix `TESTPC-`.
//! Re-runnable; skips (passes) when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test publication_candidate

use sqlx::PgPool;
use uuid::Uuid;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}


/// Queue filter that only narrows by status — what most of these assertions want.
fn status_filter(status: Option<&str>) -> du_db::publication::CandidateFilter<'_> {
    du_db::publication::CandidateFilter { status, ..Default::default() }
}

async fn test_user(pool: &PgPool) -> Uuid {
    sqlx::query_scalar(
        "INSERT INTO ident.users (handle, display_name) VALUES ('testpc-curator', 'Test Curator') \
         ON CONFLICT (handle) DO UPDATE SET display_name = EXCLUDED.display_name RETURNING id",
    )
    .fetch_one(pool)
    .await
    .expect("test user")
}

#[tokio::test]
async fn candidate_review_and_promote() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping publication_candidate test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let curator = test_user(&pool).await;

    // Discovery upserts two candidates.
    du_db::publication::upsert_candidate(
        &pool,
        &du_db::publication::NewCandidate {
            openalex_id: "TESTPC-W1",
            doi: Some("10.1234/testpc.1"),
            title: Some("A Y-DNA study"),
            abstract_summary: Some("abstract one"),
            journal_name: Some("J. Phylogenetics"),
            cited_by_count: Some(12),
            open_access_status: Some("gold"),
            ..Default::default()
        },
    ).await.expect("upsert 1");
    du_db::publication::upsert_candidate(
        &pool,
        &du_db::publication::NewCandidate {
            openalex_id: "TESTPC-W2",
            title: Some("An off-topic paper"),
            ..Default::default()
        },
    ).await.expect("upsert 2");

    // Both are pending.
    let pending = du_db::publication::list_candidates(&pool, &status_filter(Some("pending")), 1, 50).await.expect("list");
    let mine: Vec<_> = pending.items.iter().filter(|c| c.openalex_id.starts_with("TESTPC-")).collect();
    assert_eq!(mine.len(), 2, "two pending candidates");

    let c1 = du_db::publication::list_candidates(&pool, &status_filter(Some("pending")), 1, 50)
        .await.unwrap().items.into_iter().find(|c| c.openalex_id == "TESTPC-W1").unwrap();

    // Promote W1 → a real publication; candidate flips to accepted.
    let pub_id = du_db::publication::promote_candidate(&pool, c1.id, curator).await.expect("promote");
    let got = du_db::publication::get_by_id(&pool, pub_id).await.expect("get pub").expect("pub exists");
    assert_eq!(got.title, "A Y-DNA study");
    assert_eq!(got.doi.as_deref(), Some("10.1234/testpc.1"));
    // Open-access status and citations come across with the promotion, so the
    // reference list badges the paper immediately (not after the nightly job).
    assert_eq!(got.open_access_status.as_deref(), Some("gold"));
    assert_eq!(got.cited_by_count, Some(12));
    let c1_after = du_db::publication::get_candidate(&pool, c1.id).await.unwrap().unwrap();
    assert_eq!(c1_after.status, "accepted");

    // Promote is idempotent: re-promoting reuses the same publication (no dup).
    let pub_id2 = du_db::publication::promote_candidate(&pool, c1.id, curator).await.expect("re-promote");
    assert_eq!(pub_id, pub_id2, "re-promote reuses existing publication");
    let dup: i64 = sqlx::query_scalar("SELECT count(*) FROM pubs.publication WHERE open_alex_id = 'TESTPC-W1'")
        .fetch_one(&pool).await.unwrap();
    assert_eq!(dup, 1, "no duplicate publication");

    // Reject W2.
    let c2 = du_db::publication::list_candidates(&pool, &status_filter(None), 1, 50)
        .await.unwrap().items.into_iter().find(|c| c.openalex_id == "TESTPC-W2").unwrap();
    assert!(du_db::publication::review_candidate(&pool, c2.id, "rejected", curator).await.expect("reject"));
    let c2_after = du_db::publication::get_candidate(&pool, c2.id).await.unwrap().unwrap();
    assert_eq!(c2_after.status, "rejected");

    // Filter now shows one accepted, one rejected, zero pending (of ours).
    let still_pending = du_db::publication::list_candidates(&pool, &status_filter(Some("pending")), 1, 50)
        .await.unwrap().items.into_iter().filter(|c| c.openalex_id.starts_with("TESTPC-")).count();
    assert_eq!(still_pending, 0, "no TESTPC pending left");

}

#[tokio::test]
async fn queue_search_and_sort() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping queue_search_and_sort test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    use du_db::publication::{CandidateFilter, CandidateSort};

    for (oa, title, journal, date) in [
        ("TESTPC-S1", "Zebra haplogroups of the steppe", "J. Phylogenetics", "2025-01-02"),
        ("TESTPC-S2", "Ancient mitochondrial lineages", "Nature 100% Genetics", "2026-03-04"),
        ("TESTPC-S3", "Survey of Y-chromosome markers", "Cell", "2024-05-06"),
    ] {
        du_db::publication::upsert_candidate(
            &pool,
            &du_db::publication::NewCandidate {
                openalex_id: oa,
                title: Some(title),
                journal_name: Some(journal),
                publication_date: Some(date.parse().unwrap()),
                ..Default::default()
            },
        ).await.expect("upsert");
    }

    let search = |q: &'static str| CandidateFilter { q: Some(q), ..Default::default() };
    let ids = |p: du_db::Page<du_db::publication::Candidate>| -> Vec<String> {
        p.items.into_iter().map(|c| c.openalex_id).collect()
    };

    // Title, journal and OpenAlex id all match, case-insensitively.
    let r = du_db::publication::list_candidates(&pool, &search("zebra"), 1, 50).await.unwrap();
    assert_eq!(ids(r), vec!["TESTPC-S1"], "title match, case-insensitive");
    let r = du_db::publication::list_candidates(&pool, &search("cell"), 1, 50).await.unwrap();
    assert_eq!(ids(r), vec!["TESTPC-S3"], "journal match");
    let r = du_db::publication::list_candidates(&pool, &search("testpc-s2"), 1, 50).await.unwrap();
    assert_eq!(ids(r), vec!["TESTPC-S2"], "openalex id match");

    // The count is filtered too, so the pager doesn't advertise phantom pages.
    let r = du_db::publication::list_candidates(&pool, &search("TESTPC-S"), 1, 2).await.unwrap();
    assert_eq!(r.total, 3);
    assert_eq!(r.total_pages(), 2);

    // `%` is a literal, not a wildcard: it only matches the journal that has one.
    let r = du_db::publication::list_candidates(&pool, &search("100%"), 1, 50).await.unwrap();
    assert_eq!(ids(r), vec!["TESTPC-S2"], "LIKE metacharacters are escaped");

    // Sorts. All three rows share a created_at, so sort on their own fields.
    let sorted = |sort| CandidateFilter { q: Some("TESTPC-S"), sort, ..Default::default() };
    let r = du_db::publication::list_candidates(&pool, &sorted(CandidateSort::Published), 1, 50).await.unwrap();
    assert_eq!(ids(r), vec!["TESTPC-S2", "TESTPC-S1", "TESTPC-S3"], "publication date, newest first");
    let r = du_db::publication::list_candidates(&pool, &sorted(CandidateSort::Title), 1, 50).await.unwrap();
    assert_eq!(ids(r), vec!["TESTPC-S2", "TESTPC-S3", "TESTPC-S1"], "title A→Z");

    assert_eq!(CandidateSort::parse("published"), CandidateSort::Published);
    assert_eq!(CandidateSort::parse("nonsense"), CandidateSort::Newest, "unknown falls back");
}

#[tokio::test]
async fn retract_moves_accepted_back_to_rejected() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping retract test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let curator = test_user(&pool).await;

    let new = |oa: &'static str| du_db::publication::NewCandidate {
        openalex_id: oa,
        title: Some("Retract me"),
        ..Default::default()
    };
    for oa in ["TESTPC-T1", "TESTPC-T2", "TESTPC-T3"] {
        du_db::publication::upsert_candidate(&pool, &new(oa)).await.expect("upsert");
    }
    async fn by_oa(pool: &PgPool, oa: &str) -> du_db::publication::Candidate {
        du_db::publication::list_candidates(pool, &status_filter(None), 1, 200)
            .await.unwrap().items.into_iter().find(|c| c.openalex_id == oa).unwrap()
    }

    // 1. Retract without deleting: the paper stays in the catalog.
    let t1 = by_oa(&pool, "TESTPC-T1").await;
    let p1 = du_db::publication::promote_candidate(&pool, t1.id, curator).await.expect("promote");
    let r = du_db::publication::retract_candidate(&pool, t1.id, curator, false).await.expect("retract");
    assert_eq!(r.publication_id, Some(p1));
    assert!(!r.publication_deleted);
    assert_eq!(du_db::publication::get_candidate(&pool, t1.id).await.unwrap().unwrap().status, "rejected");
    assert!(du_db::publication::get_by_id(&pool, p1).await.unwrap().is_some(), "paper kept");

    // 2. Retract with delete: an untouched promoted paper goes away.
    let t2 = by_oa(&pool, "TESTPC-T2").await;
    let p2 = du_db::publication::promote_candidate(&pool, t2.id, curator).await.expect("promote");
    let r = du_db::publication::retract_candidate(&pool, t2.id, curator, true).await.expect("retract");
    assert!(r.publication_deleted, "unattached paper removed");
    assert_eq!(r.publication_id, None);
    assert!(du_db::publication::get_by_id(&pool, p2).await.unwrap().is_none(), "paper gone");
    assert_eq!(du_db::publication::get_candidate(&pool, t2.id).await.unwrap().unwrap().status, "rejected");

    // 3. Delete is refused once a study hangs off the paper — the candidate still
    //    flips, but the curated links survive.
    let t3 = by_oa(&pool, "TESTPC-T3").await;
    let p3 = du_db::publication::promote_candidate(&pool, t3.id, curator).await.expect("promote");
    let study = du_db::study::upsert_by_accession(&pool, "TESTPC-PRJEB1", du_db::study::source_for_accession("PRJEB1"))
        .await.expect("study");
    du_db::study::link_publication(&pool, p3.0, study).await.expect("link");
    assert_eq!(du_db::publication::attachment_count(&pool, p3).await.unwrap(), 1);
    let r = du_db::publication::retract_candidate(&pool, t3.id, curator, true).await.expect("retract");
    assert!(!r.publication_deleted, "attached paper is not deleted");
    assert_eq!(r.attached, 1);
    assert!(du_db::publication::get_by_id(&pool, p3).await.unwrap().is_some(), "paper kept");
    assert_eq!(du_db::publication::get_candidate(&pool, t3.id).await.unwrap().unwrap().status, "rejected");

    // Retracting again is idempotent — and the paper kept in step 1 is now free of
    // attachments, so a second pass with the box ticked does remove it.
    let r = du_db::publication::retract_candidate(&pool, t1.id, curator, true).await.expect("re-retract");
    assert!(r.publication_deleted, "second pass removes the paper left behind");
    assert!(du_db::publication::get_by_id(&pool, p1).await.unwrap().is_none());
}

#[tokio::test]
async fn bulk_system_reject_only_touches_pending() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping bulk_system_reject test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let curator = test_user(&pool).await;

    // Three pending candidates + one already accepted.
    for oa in ["TESTPC-R1", "TESTPC-R2", "TESTPC-R3", "TESTPC-R4"] {
        du_db::publication::upsert_candidate(
            &pool,
            &du_db::publication::NewCandidate { openalex_id: oa, title: Some("t"), ..Default::default() },
        ).await.expect("upsert");
    }
    let r4 = du_db::publication::list_candidates(&pool, &status_filter(None), 1, 100)
        .await.unwrap().items.into_iter().find(|c| c.openalex_id == "TESTPC-R4").unwrap();
    du_db::publication::review_candidate(&pool, r4.id, "accepted", curator).await.unwrap();

    // pending_candidate_ids returns exactly our three still-pending rows.
    let pending = du_db::publication::pending_candidate_ids(&pool).await.expect("pending ids");
    let ours: Vec<i64> = pending.iter()
        .filter(|(_, oa)| oa.starts_with("TESTPC-R"))
        .map(|(id, _)| *id)
        .collect();
    assert_eq!(ours.len(), 3, "R1..R3 pending (R4 is accepted)");

    // Bulk-reject all four ids: only the three pending flip; the accepted one is safe.
    let all_ids: Vec<i64> = [ours.clone(), vec![r4.id]].concat();
    let n = du_db::publication::reject_candidates_system(&pool, &all_ids).await.expect("bulk reject");
    assert_eq!(n, 3, "only the 3 pending rows were rejected");

    let r4_after = du_db::publication::get_candidate(&pool, r4.id).await.unwrap().unwrap();
    assert_eq!(r4_after.status, "accepted", "accepted candidate untouched");
    for id in &ours {
        let c = du_db::publication::get_candidate(&pool, *id).await.unwrap().unwrap();
        assert_eq!(c.status, "rejected");
    }

    // Idempotent: a second run rejects nothing (no rows still pending).
    let n2 = du_db::publication::reject_candidates_system(&pool, &all_ids).await.expect("re-reject");
    assert_eq!(n2, 0, "nothing left to reject");
    assert_eq!(du_db::publication::reject_candidates_system(&pool, &[]).await.unwrap(), 0, "empty is a no-op");
}
