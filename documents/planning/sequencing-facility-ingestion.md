# Spec: ingest & surface `sequencingFacility` on the sequencerun record

**Status:** ✅ implemented (migration `0056`) · **Scope:** AppView (`rust/` crates `du-jobs`, `du-db`, `du-web`) + one migration
**Depends on:** `decodingus-shared` du-domain `1d073b6` (adds the wire field), Navigator `695cd9e` (publishes it)

## Problem

A contributor's sequencing lab is collected but never reaches reporting. Navigator resolves and
stores each run's facility (e.g. **"Dante Labs"** for a PacBio HiFi WGS) but, until du-domain
`1d073b6`, the `com.decodingus.atmosphere.sequencerun` record had no field for it — it published
only `instrumentId`. The AppView can't recover the lab from the instrument either: the coverage
benchmark's lab column comes from `sequencer_instrument.lab_id`, and the instrument→lab map has no
PacBio serials (`m64023e` isn't in it). Result: the benchmark shows `WGS_HIFI / chm13v2.0` with a
**blank lab**.

Navigator now publishes `sequencingFacility` (camelCase, optional string) on the sequencerun
record. The AppView must ingest it, display it, and (optionally) feed it into the instrument→lab
consensus so the map *learns* the serial.

The wire contract is already in place — `du_domain::fed::SequenceRunRecord.sequencing_facility`
(`decodingus-shared/crates/du-domain/src/fed.rs:347`, wire key `sequencingFacility`). Everything
below is AppView-side; `grep -rn "sequencing_facility\|sequencingFacility" rust/` is currently empty.

## Change 1 — store the column (migration)

New migration `rust/migrations/0056_sequencerun_facility.sql` (the table is defined in
`0012_fed_reporting.sql:46`):

```sql
ALTER TABLE fed.sequencerun ADD COLUMN sequencing_facility TEXT;
-- Optional: index if we filter/group by it in reporting (we do — see Change 3).
CREATE INDEX fed_sequencerun_facility_idx ON fed.sequencerun (sequencing_facility);
```

Backward-compatible: the column is nullable; existing rows stay `NULL` until their sequencerun
record is re-published (Navigator uses a deterministic rkey, so a re-publish overwrites in place).

## Change 2 — ingest the field (`du-jobs` + `du-db`)

Three edits, mirroring how `instrumentId` is handled:

1. **Builder** — `rust/crates/du-jobs/src/jetstream.rs:299` (`build_sequencerun`), add one line:
   ```rust
   sequencing_facility: str_at(record, "sequencingFacility"),
   ```
2. **Storage struct** — `rust/crates/du-db/src/fed/core.rs:91` (`struct SequenceRun`), add:
   ```rust
   pub sequencing_facility: Option<String>,
   ```
3. **Upsert** — `rust/crates/du-db/src/fed/core.rs:104` (`upsert_sequencerun`): add
   `sequencing_facility` to the INSERT column list, the `VALUES` placeholders, the
   `DO UPDATE SET` clause, and a `.bind(&s.sequencing_facility)` in the correct positional order.

No lexicon/NSID change — same collection, one new optional key. Deletes and the `(did, rkey)`
idempotency guard are unchanged.

## Change 3 — surface the lab in the coverage benchmark

The federated per-contig report (`GET /coverage-benchmarks` → `routes/coverage.rs:205` →
`du_db::coverage::contig_benchmarks`, SQL at `rust/crates/du-db/src/coverage.rs:115`) already joins
`fed.coverage_summary → fed.sequencerun` on `sr.at_uri = cs.sequence_run_ref` and derives the lab
(`vendor`) indirectly via `sr.instrument_id → sequencer_instrument → sequencing_lab.name`. Because
the sequencerun is already joined, `sr.sequencing_facility` is available for free — prefer the
resolved consensus lab, fall back to the contributor's published facility:

- **`coverage.rs:116`** vendor select:
  ```sql
  COALESCE(lab.name, sr.sequencing_facility) AS vendor,
  ```
- **`coverage.rs:140`** `GROUP BY`: replace `lab.name` with the same `COALESCE(lab.name, sr.sequencing_facility)`.
- **`coverage.rs:136`** vendor filter (`$1`): match against the coalesced expression.
- **Options query** `contig_benchmark_options` (`coverage.rs:191`, vendors list `191-200`): union
  `lab.name` with `sr.sequencing_facility` (or select the same coalesced expression) so the filter
  dropdown lists facility-only labs too.

Optional polish: keep them distinguishable in the UI (a resolved-consensus lab vs. a self-reported
facility) — e.g. return an extra `lab_source` flag — but not required to close the gap.

The projection type `du_domain::coverage::ContigBenchmark`
(`decodingus-shared/crates/du-domain/src/coverage.rs:26`) already carries `vendor`; no shape change.

## Change 4 (optional, recommended) — feed the instrument→lab consensus

Today the map only *reads* `sequencer_instrument.lab_id`, populated by seed data + the
observation→proposal→accept flow in `du_db::sequencer::recompute_locked`
(`rust/crates/du-db/src/sequencer.rs:160`). A published `instrumentId + sequencingFacility` pair is
a strong, explicit contributor claim — plug it in as a third observation source alongside the
`center_name`-INFERRED path (`sequencer.rs:208`) and the explicit `instrument_observation` path
(`sequencer.rs:232`):

```sql
-- inside recompute_locked, after the existing observation refreshes:
INSERT INTO genomics.instrument_observation
    (instrument_id, lab_name, biosample_ref, platform, instrument_model, confidence, atproto, observed_at)
SELECT si.id, s.sequencing_facility, s.biosample_ref, s.platform_name, s.instrument_model,
       'KNOWN', jsonb_build_object('uri', s.at_uri, 'repo_did', s.did), s.record_created_at
FROM fed.sequencerun s
JOIN genomics.sequencer_instrument si ON si.instrument_id = s.instrument_id
WHERE s.instrument_id IS NOT NULL AND s.sequencing_facility IS NOT NULL
ON CONFLICT ((atproto->>'uri')) WHERE atproto IS NOT NULL DO UPDATE SET
    lab_name = EXCLUDED.lab_name, confidence = EXCLUDED.confidence, observed_at = EXCLUDED.observed_at;
```

> **As shipped:** the `WHERE atproto IS NOT NULL` predicate is required — the unique index
> (`0004_genomics.sql:48`) is partial, so conflict inference fails without it. The insert also
> excludes generic center names (`<> ALL($1)`) for parity with the `center_name` path, keys the
> `atproto` object with `cid` too, and is placed as step **2c**, right after the explicit-observation
> refresh (`sequencer.rs` ~`:251`) and before the prune.

Notes:
- `'KNOWN'` outranks the `center_name` `INFERRED` claims, so a real reported facility wins the
  dominant-lab vote in `recompute_locked` (`sequencer.rs:264`+).
- Key on `atproto->>'uri'` = the sequencerun's `at_uri`, matching the existing de-dup/prune
  (`sequencer.rs:218/242/256`). The `sequencer_instrument` row is already ensured from
  `fed.sequencerun` at `sequencer.rs:186`.
- This lets a curator accept `m64023e → Dante Labs` once, after which `lookup_lab` /
  `lab-instruments` resolve it for *every* contributor — including those who never knew their lab.
  With `auto_accept` on and enough agreement it needs no curator at all.

Without Change 4, Change 3's `COALESCE` still shows the lab for anyone who published a facility; the
map just won't learn the serial for facility-less contributors.

## Rollout / verification

1. Ship the migration + ingest (Changes 1–2) first; safe no-op until records carry the field.
2. Have Navigator **re-publish** the affected subject (deterministic rkey overwrites `seqrun:8`),
   or wait for the firehose to redeliver on the next publish.
3. Verify ingest: `SELECT instrument_id, test_type, sequencing_facility FROM fed.sequencerun WHERE instrument_id='m64023e';` → `Dante Labs`.
4. Ship Change 3; confirm the `WGS_HIFI / chm13v2.0` section now shows the lab.
5. (Change 4) run/await `recompute_consensus`; confirm a `PENDING` proposal appears for `m64023e`
   with `proposed_lab_name = 'Dante Labs'`; accept it; confirm `GET /api/v1/sequencer/lab?instrumentId=m64023e` resolves.

## Files touched (summary)

| Change | File · anchor |
|---|---|
| 1 migration | `rust/migrations/0056_sequencerun_facility.sql` (new); table at `0012_fed_reporting.sql:46` |
| 2 ingest | `du-jobs/src/jetstream.rs:305`; `du-db/src/fed/core.rs:97` (struct) + `:104` (upsert) |
| 3 report | `du-db/src/coverage.rs:116/136/140` + options `:191` |
| 4 consensus | `du-db/src/sequencer.rs` `recompute_locked` step 2c (after `:251`) |
| tests | `du-db/tests/contig_benchmarks.rs::facility_falls_back_as_vendor`; `du-db/tests/sequencer_consensus.rs::published_facility_drives_consensus` |
