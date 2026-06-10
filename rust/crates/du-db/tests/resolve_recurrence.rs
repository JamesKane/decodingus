//! Live-DB test for `du_db::variant::resolve_isogg_recurrence`: ISOGG name-decorated
//! (`.N` recurrence, `^^` stability marker) coordless tips are rewritten onto their
//! base SNP identity with `defining_haplogroup_id` = the branch (inheriting the base
//! coordinate), true duplicates are folded into the base, and the multi-link /
//! no-base residue is left for the curator. Skips when DATABASE_URL is unset.
//!
//!     eval "$(./scripts/test-db.sh up)" && cargo test -p du-db --test resolve_recurrence

use du_domain::enums::DnaType;
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn mk_hg(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ($1,'Y_DNA'::core.dna_type) RETURNING id")
        .bind(name).fetch_one(pool).await.expect("hg")
}
/// Coordinate-bearing PRIMARY variant (defining_haplogroup_id stays NULL).
async fn mk_primary(pool: &PgPool, name: &str, pos: i64) -> i64 {
    let coords = serde_json::json!({"GRCh38": {"contig": "chrY", "position": pos, "ancestral": "G", "derived": "A"}});
    sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, coordinates) \
         VALUES ($1,'SNP'::core.mutation_type,$2) RETURNING id",
    )
    .bind(name).bind(coords).fetch_one(pool).await.expect("primary")
}
/// Decorated COORDLESS primary (the thing to resolve): empty coordinates, NULL defining.
async fn mk_coordless(pool: &PgPool, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, coordinates) \
         VALUES ($1,'SNP'::core.mutation_type,'{}'::jsonb) RETURNING id",
    )
    .bind(name).fetch_one(pool).await.expect("coordless")
}
async fn link(pool: &PgPool, hg: i64, v: i64) {
    sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)")
        .bind(hg).bind(v).execute(pool).await.expect("link");
}

/// (canonical_name, defining_haplogroup_id, has_grch38_coord, common_names) for a variant id,
/// or None if it no longer exists (folded away).
async fn row(pool: &PgPool, id: i64) -> Option<(String, Option<i64>, bool, Vec<String>)> {
    sqlx::query_as::<_, (String, Option<i64>, bool, serde_json::Value)>(
        "SELECT canonical_name, defining_haplogroup_id, coordinates ? 'GRCh38', \
                COALESCE(aliases->'common_names','[]'::jsonb) \
           FROM core.variant WHERE id = $1",
    )
    .bind(id)
    .fetch_optional(pool)
    .await
    .expect("row")
    .map(|(n, d, c, names)| {
        let names = names.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default();
        (n, d, c, names)
    })
}

#[tokio::test]
async fn resolves_recurrence_folds_dups_and_defers_multilink_and_no_base() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping resolve_recurrence test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();

    // Branches (resolve only needs them to exist with the right type + links).
    let a = mk_hg(&pool, "A").await;
    let b = mk_hg(&pool, "B").await;
    let c = mk_hg(&pool, "C").await;

    // Base primaries (coord-bearing, NOT decorated → never candidates).
    let base1 = mk_primary(&pool, "BASE1", 100).await;
    let base2 = mk_primary(&pool, "BASE2", 200).await;
    mk_primary(&pool, "STAR", 300).await;
    link(&pool, c, base2).await; // BASE2 already defines C → makes BASE2.1 a true duplicate.
    // (no BASE primary named NOBASE — that base is absent.)

    // Decorated coordless candidates:
    let recur = mk_coordless(&pool, "BASE1.2").await; // single → B; base BASE1 has coords → recurrence-ize
    let marker = mk_coordless(&pool, "STAR^^").await; // single → A; ^^ stripped; base STAR has coords → recurrence-ize
    let dup = mk_coordless(&pool, "BASE2.1").await; // single → C; BASE2 already defines C → redundant fold
    let multi = mk_coordless(&pool, "MULTI.2").await; // two branches → deferred (multi_link)
    let nobase = mk_coordless(&pool, "NOBASE.2").await; // single → B; base absent → no_base_coords
    link(&pool, b, recur).await;
    link(&pool, a, marker).await;
    link(&pool, c, dup).await;
    link(&pool, a, multi).await;
    link(&pool, c, multi).await;
    link(&pool, b, nobase).await;

    // Dry-run: the five buckets, no writes.
    let dry = du_db::variant::resolve_isogg_recurrence(&pool, DnaType::YDna, false).await.expect("dry");
    assert_eq!(dry.candidates, 5, "all decorated coordless linked variants");
    assert_eq!(dry.multi_link, 1, "MULTI.2 defines >1 branch");
    assert_eq!(dry.no_base_coords, 1, "NOBASE.2 has no coord-bearing base");
    assert_eq!(dry.recurrence_set, 0, "dry-run writes nothing");
    assert_eq!(dry.redundant_folded, 0, "dry-run writes nothing");
    assert_eq!(row(&pool, recur).await.unwrap().0, "BASE1.2", "dry-run left the name untouched");

    // Apply.
    let rep = du_db::variant::resolve_isogg_recurrence(&pool, DnaType::YDna, true).await.expect("apply");
    assert_eq!(rep.recurrence_set, 2, "BASE1.2 + STAR^^");
    assert_eq!(rep.redundant_folded, 1, "BASE2.1 folded into BASE2");
    assert_eq!(rep.multi_link, 1);
    assert_eq!(rep.no_base_coords, 1);

    // BASE1.2 → recurrence on its base identity: renamed, scoped to B, inherited coord,
    // old name kept as an alias.
    let (name, def, has_coord, names) = row(&pool, recur).await.expect("recur survives");
    assert_eq!(name, "BASE1");
    assert_eq!(def, Some(b), "scoped to the branch it defines");
    assert!(has_coord, "inherited BASE1's GRCh38 coordinate");
    assert!(names.contains(&"BASE1.2".to_string()), "old decorated name folded to aliases");
    assert_eq!(row(&pool, base1).await.unwrap().1, None, "primary BASE1 still the NULL-branch identity");

    // STAR^^ → base STAR, scoped to A (verifies the `^^` strip).
    let (name, def, has_coord, names) = row(&pool, marker).await.expect("marker survives");
    assert_eq!(name, "STAR");
    assert_eq!(def, Some(a));
    assert!(has_coord);
    assert!(names.contains(&"STAR^^".to_string()));

    // BASE2.1 → folded away (merge_into deleted the row); its name lives on BASE2's aliases.
    assert!(row(&pool, dup).await.is_none(), "redundant duplicate row removed");
    assert!(row(&pool, base2).await.unwrap().3.contains(&"BASE2.1".to_string()), "folded name on the base");

    // MULTI.2 and NOBASE.2 are untouched residue.
    assert_eq!(row(&pool, multi).await.unwrap().0, "MULTI.2", "multi-link deferred, not rewritten");
    let (nn, nd, nc, _) = row(&pool, nobase).await.unwrap();
    assert_eq!((nn.as_str(), nd, nc), ("NOBASE.2", None, false), "no-base residue untouched");

    // Idempotent: a second apply resolves nothing new (the recurrences now carry a
    // defining branch + coords, so they no longer match the candidate scan).
    let again = du_db::variant::resolve_isogg_recurrence(&pool, DnaType::YDna, true).await.expect("re-apply");
    assert_eq!(again.recurrence_set, 0);
    assert_eq!(again.redundant_folded, 0);
    assert_eq!(again.candidates, 2, "only the multi-link + no-base residue remain");
}
