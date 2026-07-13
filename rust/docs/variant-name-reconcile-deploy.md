# Variant name reconcile — production deployment

Deployment notes for the fix to the Variant Naming Authority duplicate-name defect.
Verified end-to-end on `decodingus_cutover` on 2026-07-13; **not yet applied to production.**

## What was wrong

One physical SNP was stored as two `core.variant` rows: the named YBrowse catalog row
(`FT186008`, defines no branch) and a de-novo coordinate row (`chrY:10249542C>G`, defines
branch `R-BY18864`). The coordinate row is the one linked to the branch, so the marker's
real name was stranded on an unlinked row and the branch row surfaced in the curator
naming queue as if it were a novel discovery.

**The loader's reuse check was never missing — it ran too early.** `resolve_variant` /
`prefill_vcache` (`du-db/src/denovo.rs`) match the catalog by `coordinates @> {'hs1': …}`.
The Y catalog's `hs1` coordinates are populated by a *separate later job*,
`variant-coord-lift`. The cutover ran:

```
ybrowse-ingest  →  de-novo load  →  variant-coord-lift        ← WRONG
```

At load time `FT186008` had only GRCh37/GRCh38 coords, so the hs1 match found nothing and
the loader minted a duplicate. It needed to be:

```
ybrowse-ingest  →  variant-coord-lift  →  de-novo load        ← CORRECT
```

That single mis-ordering produced ~130k duplicate rows and left the naming queue 86% noise.
Worse, the curator UI showed those rows with a "a named variant already exists here"
warning but **no button that could reuse it** — the Reuse offer read the row's own
`aliases`, which a de-novo row does not have. The only actions on screen were *Mint DU
name*, which would have given an already-named marker a second conflicting identity, and
*Flag for review*.

## What shipped

| Change | File |
|---|---|
| Y load refuses to start unless the Y catalog is hs1-lifted (`unlifted_y_catalog_count`) | `du-db/src/denovo.rs` |
| `dedup_by_site` excludes coordinate placeholders; ordering is the adoption preference | `du-db/src/naming.rs` |
| `adoptable_name()` — the Reuse offer now reads the site twin, not just aliases | `du-db/src/naming.rs` |
| `adopt_established_name()` writes from either source | `du-db/src/naming.rs` |
| `reconcile_placeholder_names()` — bulk fold, idempotent, id-cursor batched | `du-db/src/naming.rs` |
| `run-once variant-name-reconcile` | `du-jobs/src/main.rs` |
| Two indexes for the site lookup (was a full scan of 3.1M rows per panel click) | `migrations/0068_variant_hs1_site_index.sql` |
| Nightly wiring, between `resync-ybrowse` and `name-private-nodes` | `scripts/nightly-maintenance.sh` |
| Dedup hint text (all three locales) | `locales/{en,es,fr}.txt` |

## Cutover result (the rehearsal)

```
scanned 157,056   matched 130,553   adopted 130,553   conflicted 0
needs_name queue: 151,318 → 20,765
```

Zero conflicts — no genuine `(name + branch)` collisions exist. Re-running adopts 0.

---

## Production steps

### 0. Decide which situation you're in

If production is going to be **replaced by a fresh ship of the now-fixed cutover DB**, the
data half of this is already done — skip to steps 2 and 4 (code, migration, nightly job)
and skip the backfill entirely.

If production is **already carrying data loaded by the old sequence**, it has the same
duplicates. Confirm and size it:

```sql
-- How many branch-defining placeholder rows have a named twin at the same hs1 site?
SELECT count(*) FROM core.variant v
WHERE v.canonical_name LIKE 'chr%:%'
  AND v.coordinates ? 'hs1'
  AND v.defining_haplogroup_id IS NOT NULL
  AND EXISTS (
    SELECT 1 FROM core.variant n
    WHERE n.id <> v.id
      AND n.canonical_name IS NOT NULL AND n.canonical_name NOT LIKE 'chr%:%'
      AND n.coordinates ? 'hs1'
      AND n.coordinates->'hs1'->>'contig'    = v.coordinates->'hs1'->>'contig'
      AND n.coordinates->'hs1'->>'position'  = v.coordinates->'hs1'->>'position'
      AND n.coordinates->'hs1'->>'ancestral' = v.coordinates->'hs1'->>'ancestral'
      AND n.coordinates->'hs1'->>'derived'   = v.coordinates->'hs1'->>'derived');
```

Expect a number near 130,553. **Run this before the indexes exist and it is a full scan of
a 4.7 GB table** — give it minutes, and don't kill the client and assume the query died:
a `pkill` on the shell leaves the backend running server-side. Check
`pg_stat_activity` and use `pg_terminate_backend()` if you need to stop it.

### 1. Pre-create the indexes CONCURRENTLY — *before* deploying

`core.variant` is **4.7 GB / 3.15M rows**. Migration `0068` uses plain `CREATE INDEX`,
which takes a `SHARE` lock and **blocks all writes to `core.variant` for the whole build**.
`du-web` runs migrations on boot (`du-web/src/main.rs:61`), so deploying without this step
stalls variant writes during startup.

Build them concurrently against live prod first. `0068` is written with `IF NOT EXISTS`, so
the migration then finds them present and is a no-op — this is exactly the sequence used on
cutover.

```sql
-- Not in a transaction. Safe on a live table; no write lock.
CREATE INDEX CONCURRENTLY IF NOT EXISTS variant_hs1_site_named_idx
    ON core.variant ((coordinates -> 'hs1' ->> 'contig'),
                     (coordinates -> 'hs1' ->> 'position'),
                     (coordinates -> 'hs1' ->> 'ancestral'),
                     (coordinates -> 'hs1' ->> 'derived'))
    WHERE coordinates ? 'hs1'
      AND canonical_name IS NOT NULL
      AND canonical_name NOT LIKE 'chr%:%';

CREATE INDEX CONCURRENTLY IF NOT EXISTS variant_placeholder_branch_idx
    ON core.variant (id)
    WHERE canonical_name LIKE 'chr%:%' AND defining_haplogroup_id IS NOT NULL;

ANALYZE core.variant;
```

The index expressions must stay **character-identical** to the query predicates or the
planner will not use them. If a `CONCURRENTLY` build fails it leaves an `INVALID` index —
check `pg_index.indisvalid`, `DROP` it, and retry.

### 2. Deploy the code

Migration `0068` applies as a no-op given step 1. Nothing else in the migration touches
data.

### 3. Backfill (skip if prod is a fresh ship from fixed cutover)

```bash
DATABASE_URL=postgres://... /opt/decoding-us/bin/decodingus-jobs run-once variant-name-reconcile
```

Took ~30s on cutover. Batched by id cursor (5,000/batch), so it never long-locks the
catalog — safe to run against a live prod. Idempotent: re-running is free.

Expect `conflicted=0`. **If `conflicted > 0`, stop and look at it** — it means a name is
already canonical on the same branch, i.e. a true duplicate or an unmodelled recurrence.
The job deliberately leaves those rows alone rather than overwrite; they need merge review.

### 4. Verify

```sql
-- The naming queue should collapse to genuinely novel variants only.
SELECT count(*) FROM core.variant v
WHERE v.defining_haplogroup_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM tree.haplogroup h
              WHERE h.id = v.defining_haplogroup_id AND h.haplogroup_type = 'Y_DNA')
  AND (v.canonical_name IS NULL OR v.canonical_name LIKE 'chr%:%'
       OR v.naming_status = 'PENDING_REVIEW');
```

Then open **Curator → Variant Naming Authority** and click a row. Any row still showing the
"a named variant already exists at this locus" warning must now also show a green
**Reuse ⟨name⟩** button. Warning without a Reuse button is the original bug.

### 5. Nightly job

`scripts/nightly-maintenance.sh` already runs `variant-name-reconcile` between
`resync-ybrowse` and `name-private-nodes`. No systemd change needed — the timer invokes the
script, not individual jobs. Confirm it appears in the next nightly log.

It has to stay recurring, not one-shot: a YBrowse refresh that names a marker **after** a
tree load recreates exactly this state, and the fix is the same fold. It must run *before*
`name-private-nodes`, which picks node names from the variants on a branch and can only see
a name once the variant actually carries it.

---

## The durable rule

**`variant-coord-lift` must complete before any de-novo Y tree load.** This is now enforced
— `denovo::load` refuses to start for a Y tree while any named Y catalog row lacks an hs1
coordinate, and names the count. If you see:

```
N named Y catalog variants have no hs1 coordinate — the de-novo catalog match is hs1-only,
so loading now would mint a duplicate coordinate-named row for each of them instead of
reusing its name. Run `du-jobs run-once variant-coord-lift` first, then re-run this load.
```

that guard just saved you another 130k duplicates. Do not work around it.

## Known residue (not fixed here)

**94,576 branch-less coordinate rows**, ~67k of which duplicate an already-named catalog
row. They define no branch and are linked to nothing (`tree.haplogroup_variant` and
`defining_haplogroup_id` agree exactly — 157,056 linked / 94,576 unlinked, no overlap), so
they never reach the naming queue and no curator ever sees them. They are catalog dead
weight: they inflate variant counts and every same-site lookup.

They are deliberately **out of scope for the reconcile**, which is scoped to
`defining_haplogroup_id IS NOT NULL`. Renaming them cannot work — they would collide with
the catalog row on `(name, NULL)`. Resolving them means *merging rows* (`du-db/src/merge.rs`,
`merge_into`), which has FK implications. An unscoped reconcile reports these as 67,131
"conflicts", which is misleading noise; that is why the job is scoped.

Separate cleanup pass; file it before it becomes folklore.

## Rollback

The backfill overwrites `canonical_name` on branch-defining rows, replacing a synthetic
placeholder with a real name. The placeholder is deterministically reconstructible from the
row's own hs1 coordinates (`{contig}:{position}{ancestral}>{derived}`), so the write is
reversible in principle, but there is no automated undo. Take a snapshot before step 3 if
production data is not otherwise recoverable.
