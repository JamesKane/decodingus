#!/usr/bin/env python3
"""Rate-limited biosample field-correction driver for the post-cutover cleanup.

Fixes two legacy→cutover load defects by driving the curator PATCH API
(`PATCH /manage/biosamples/:guid`, X-API-Key gated) — the same machine-auth as
the curation intake. It never writes the DB directly; it only *reads* the DB
(via `psql`) to enumerate what still needs fixing, then patches over HTTP.

Defect A — wrong `source`:
    ~18,389 publication-linked accessioned samples (1000G etc.) loaded as
    source=STANDARD + is_public=false. They are published external cohorts.
    Fix: source := EXTERNAL, is_public := true.
    Worklist predicate (also the idempotency guard — a fixed row no longer
    matches): source='STANDARD' AND has a pubs.publication_biosample link.

Defect B — mis-mapped fields on de-novo ENA tips:
    EXTERNAL tips where the sample *name* (HG.../NA...) was written into
    `accession`, `center_name` holds the bioproject accession, and alias/
    description are empty.
    Fix, using the real ENA record (name matched across sample_alias /
    library_name / sample_title): accession := real accession, alias := HG...,
    center_name := real center, description := ENA study title.
    Idempotency guard: after the fix accession is no longer HG/NA and
    center_name no longer equals the cohort, so the row drops out of the worklist.

Defect C (opt-in, --defect C) — merge duplicate tips into their accessioned twin:
    The Defect-B tips that DO have an accessioned twin (same alias + donor_id) are
    exact duplicates. Merge each bare tip (loser) into the publication-linked
    accessioned row (survivor) via POST /manage/biosamples/merge, which repoints
    every FK to the survivor and tombstones the loser. Only unambiguous 1:1 pairs
    are merged; a name mapping to >1 accessioned row (a deeper accession-level
    duplicate) is excluded and reported. NOT part of `all` (it tombstones rows).

    IMPORTANT — only *remap-safe* tips are touched by Defect B: a tip is skipped when an
    accessioned row (SAM*) with the same alias already exists (`NOT EXISTS`
    clause below). Those are exact DUPLICATES of an already-loaded record (same
    person, same donor_id) — rewriting their accession would collide on the
    UNIQUE constraint and would leave two public rows per individual. They belong
    to the dedup/merge pipeline, not here; the script counts and reports them.
    In the cutover snapshot this leaves PRJEB36890 (new SAMEA-accessioned trios)
    as the only remap target; PRJEB31736 + PRJEB9586 are all duplicates.

Dependencies: standard library only (urllib + a `psql` client on PATH).

Usage:
    # dry run (default) — prints the plan, writes nothing
    DATABASE_URL=postgres://user:pw@host:5432/db \
    DU_CURATION_API_KEY=... \
    python3 patch-cutover-biosamples.py --defect all

    # apply
    ... python3 patch-cutover-biosamples.py --defect all --apply

Env:
    DATABASE_URL          read-only connection used only to enumerate targets
    DU_CURATION_API_KEY   X-API-Key sent to the PATCH endpoint (required for --apply)
    API_BASE              default http://localhost:9000
    PSQL                  psql binary (default: psql on PATH)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

ENA = "https://www.ebi.ac.uk/ena/portal/api/filereport"
DEFECT_B_COHORTS = ("PRJEB36890", "PRJEB31736", "PRJEB9586")

# ── worklist SQL (each predicate doubles as the idempotency guard) ──────────────
SQL_DEFECT_A = """
SELECT sample_guid::text
FROM core.biosample b
WHERE source = 'STANDARD' AND deleted = false
  AND EXISTS (SELECT 1 FROM pubs.publication_biosample pb WHERE pb.sample_guid = b.sample_guid)
ORDER BY accession NULLS LAST
"""

# Remap-safe Defect-B tips only: exclude any tip whose name already exists as an
# accessioned (SAM*) row — those are duplicates for the dedup pipeline, not remaps.
SQL_DEFECT_B = """
SELECT b.sample_guid::text, b.accession, b.source_attrs->>'cohort'
FROM core.biosample b
WHERE b.source = 'EXTERNAL' AND b.deleted = false
  AND b.accession ~ '^HG|^NA'
  AND b.center_name = b.source_attrs->>'cohort'
  AND NOT EXISTS (
    SELECT 1 FROM core.biosample x
    WHERE x.deleted = false AND x.alias = b.accession AND x.accession ~ '^SAM'
  )
ORDER BY b.source_attrs->>'cohort', b.accession
"""

# Count of Defect-B tips that ARE duplicates of an accessioned row (deferred to
# the dedup/merge pipeline; reported, never patched here).
SQL_DEFECT_B_DUPLICATES = """
SELECT b.source_attrs->>'cohort' AS cohort, count(*)
FROM core.biosample b
WHERE b.source = 'EXTERNAL' AND b.deleted = false
  AND b.accession ~ '^HG|^NA'
  AND b.center_name = b.source_attrs->>'cohort'
  AND EXISTS (
    SELECT 1 FROM core.biosample x
    WHERE x.deleted = false AND x.alias = b.accession AND x.accession ~ '^SAM'
  )
GROUP BY 1 ORDER BY 1
"""

# ── Defect C: merge duplicate de-novo tips into their accessioned twin ──────────
# Pair each bare tip (loser: HG/NA accession, cohort-as-center, denovo) with the
# accessioned publication-linked row carrying the same alias (survivor). Only
# UNAMBIGUOUS 1:1 pairs sharing a donor_id are emitted; a name that resolves to >1
# accessioned row (a deeper accession-level duplicate) is excluded and reported.
# Survivor = the accessioned/publication-linked biosample (the one to keep).
SQL_MERGE_PAIRS = """
WITH loser AS (
  SELECT sample_guid, accession AS name, donor_id
  FROM core.biosample
  WHERE source = 'EXTERNAL' AND deleted = false AND accession ~ '^HG|^NA'
    AND center_name = source_attrs->>'cohort'
),
surv AS (
  SELECT sample_guid, alias, donor_id
  FROM core.biosample
  WHERE deleted = false AND accession ~ '^SAM'
),
paired AS (
  SELECT l.sample_guid AS loser, l.name,
         (SELECT count(*) FROM surv s WHERE s.alias = l.name) AS n_surv,
         (SELECT min(s.sample_guid::text) FROM surv s WHERE s.alias = l.name) AS survivor,
         l.donor_id AS loser_donor,
         (SELECT min(s.donor_id) FROM surv s WHERE s.alias = l.name) AS surv_donor
  FROM loser l
)
SELECT survivor, loser::text, name
FROM paired
WHERE n_surv = 1
  AND survivor IS NOT NULL
  AND loser_donor IS NOT DISTINCT FROM surv_donor
ORDER BY name
"""

# The ambiguous names excluded above (a name with >1 accessioned row) — reported.
SQL_MERGE_AMBIGUOUS = """
WITH loser AS (
  SELECT accession AS name FROM core.biosample
  WHERE source = 'EXTERNAL' AND deleted = false AND accession ~ '^HG|^NA'
    AND center_name = source_attrs->>'cohort'
)
SELECT count(*) FROM loser l
WHERE (SELECT count(*) FROM core.biosample s
       WHERE s.deleted = false AND s.accession ~ '^SAM' AND s.alias = l.name) > 1
"""


def psql_rows(db_url: str, sql: str) -> list[list[str]]:
    """Run SQL via psql, return rows as lists of column strings (tab-split)."""
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, db_url, "-X", "-A", "-t", "-F", "\t", "-c", sql],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return [line.split("\t") for line in out.splitlines() if line.strip()]


def ena_tsv(accession: str, result: str, fields: str) -> list[dict[str, str]]:
    url = f"{ENA}?accession={accession}&result={result}&fields={fields}&format=tsv&limit=0"
    with urllib.request.urlopen(url, timeout=60) as resp:
        text = resp.read().decode("utf-8", "replace")
    lines = text.splitlines()
    if not lines:
        return []
    header = lines[0].split("\t")
    rows = []
    for line in lines[1:]:
        if not line.strip():
            continue
        rows.append(dict(zip(header, line.split("\t"))))
    return rows


_NAME_RE = re.compile(r"\b((?:HG|NA)\d{4,})\b")


def load_ena_cohort(cohort: str) -> tuple[dict[str, dict[str, str]], str]:
    """Return (HG/NA name -> {accession, center_name}, study_title) for a bioproject.

    Different 1000G-derived studies stash the HG/NA sample name in different ENA
    fields — PRJEB36890 uses `sample_alias`, PRJEB31736 uses `library_name` /
    `sample_title` ("Coriell HG00096"). We scan all three and index by the
    extracted name so matching is field-agnostic.
    """
    runs = ena_tsv(cohort, "read_run", "sample_accession,sample_alias,library_name,sample_title,center_name")
    by_name: dict[str, dict[str, str]] = {}
    for r in runs:
        acc = (r.get("sample_accession") or "").strip()
        if not acc:
            continue
        center = (r.get("center_name") or "").strip()
        for field in ("sample_alias", "library_name", "sample_title"):
            m = _NAME_RE.search(r.get(field) or "")
            if m:
                # multiple runs per sample -> first wins (identical for our fields)
                by_name.setdefault(m.group(1), {"accession": acc, "center_name": center})
    study = ena_tsv(cohort, "study", "study_title")
    title = study[0].get("study_title", "").strip() if study else ""
    return by_name, title


def patch(api_base: str, api_key: str, guid: str, body: dict) -> tuple[bool, str]:
    req = urllib.request.Request(
        f"{api_base}/manage/biosamples/{guid}",
        data=json.dumps(body).encode(),
        method="PATCH",
        headers={"Content-Type": "application/json", "X-API-Key": api_key},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return True, f"{resp.status}"
    except urllib.error.HTTPError as e:
        return False, f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:200]}"
    except urllib.error.URLError as e:
        return False, f"URL error: {e.reason}"


def merge_pair(api_base: str, api_key: str, survivor: str, merged: str) -> tuple[bool, str]:
    req = urllib.request.Request(
        f"{api_base}/manage/biosamples/merge",
        data=json.dumps({"survivor": survivor, "merged": merged, "merged_by": "ops-dedup-script"}).encode(),
        method="POST",
        headers={"Content-Type": "application/json", "X-API-Key": api_key},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return True, resp.read().decode("utf-8", "replace")[:200]
    except urllib.error.HTTPError as e:
        return False, f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:200]}"
    except urllib.error.URLError as e:
        return False, f"URL error: {e.reason}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--defect", choices=["A", "B", "C", "all"], default="all",
                    help="A=source flip, B=ENA remap, C=merge duplicate tips. 'all' = A+B (C is opt-in).")
    ap.add_argument("--apply", action="store_true", help="actually PATCH (default: dry run)")
    ap.add_argument("--rate", type=float, default=0.1, help="seconds to sleep between PATCH calls")
    ap.add_argument("--limit", type=int, default=0, help="cap rows processed per defect (0 = all)")
    ap.add_argument("--cohort", help="restrict Defect B to one bioproject accession")
    ap.add_argument("--db-url", default=os.environ.get("DATABASE_URL"))
    ap.add_argument("--api-base", default=os.environ.get("API_BASE", "http://localhost:9000"))
    ap.add_argument("--api-key", default=os.environ.get("DU_CURATION_API_KEY"))
    ap.add_argument("--log", default=None, help="append per-row JSONL outcomes to this file")
    args = ap.parse_args()

    if not args.db_url:
        sys.exit("DATABASE_URL (or --db-url) is required to enumerate targets")
    if args.apply and not args.api_key:
        sys.exit("DU_CURATION_API_KEY (or --api-key) is required with --apply")

    logf = open(args.log, "a") if args.log else None

    def record(defect: str, guid: str, body: dict, status: str):
        if logf:
            logf.write(json.dumps({"defect": defect, "guid": guid, "body": body, "status": status}) + "\n")
            logf.flush()

    mode = "APPLY" if args.apply else "DRY-RUN"
    print(f"[{mode}] api={args.api_base} rate={args.rate}s")

    totals = {"patched": 0, "skipped": 0, "failed": 0}

    def do(defect: str, guid: str, body: dict):
        if not args.apply:
            print(f"  would PATCH {guid} {defect}: {body}")
            record(defect, guid, body, "dry-run")
            totals["skipped"] += 1
            return
        ok, status = patch(args.api_base, args.api_key, guid, body)
        record(defect, guid, body, status)
        if ok:
            totals["patched"] += 1
        else:
            totals["failed"] += 1
            print(f"  FAIL {guid} {defect}: {status}", file=sys.stderr)
        time.sleep(args.rate)

    # ── Defect A ──────────────────────────────────────────────────────────────
    if args.defect in ("A", "all"):
        rows = psql_rows(args.db_url, SQL_DEFECT_A)
        if args.limit:
            rows = rows[: args.limit]
        print(f"Defect A (STANDARD+pub-linked -> EXTERNAL/public): {len(rows)} rows")
        for (guid,) in ((r[0],) for r in rows):
            do("A", guid, {"source": "EXTERNAL", "is_public": True})

    # ── Defect B ──────────────────────────────────────────────────────────────
    if args.defect in ("B", "all"):
        rows = psql_rows(args.db_url, SQL_DEFECT_B)
        if args.cohort:
            rows = [r for r in rows if r[2] == args.cohort]
        if args.limit:
            rows = rows[: args.limit]
        # Report (never touch) the duplicate tips deferred to the dedup pipeline.
        dupes = psql_rows(args.db_url, SQL_DEFECT_B_DUPLICATES)
        if dupes:
            total_dupes = sum(int(c) for _, c in dupes)
            print(f"Defect B: DEFERRED {total_dupes} duplicate tips to the dedup pipeline "
                  f"(accessioned twin already exists): "
                  + ", ".join(f"{coh}={c}" for coh, c in dupes))

        cohorts = sorted({r[2] for r in rows})
        print(f"Defect B (remap-safe alias-in-accession tips): {len(rows)} rows across {cohorts}")
        ena: dict[str, tuple[dict, str]] = {}
        for c in cohorts:
            print(f"  fetching ENA metadata for {c} ...")
            ena[c] = load_ena_cohort(c)
            print(f"    {len(ena[c][0])} names indexed, study='{ena[c][1][:60]}'")
        unmatched = 0
        for guid, name, cohort in rows:
            by_name, title = ena.get(cohort, ({}, ""))
            hit = by_name.get(name)
            if not hit:
                unmatched += 1
                print(f"  UNMATCHED {guid} {name} ({cohort}) — no ENA alias match", file=sys.stderr)
                record("B", guid, {"name": name, "cohort": cohort}, "unmatched")
                continue
            body = {
                "accession": hit["accession"],
                "alias": name,
                "center_name": hit["center_name"] or None,
                "description": title or None,
            }
            body = {k: v for k, v in body.items() if v is not None}
            do("B", guid, body)
        if unmatched:
            print(f"Defect B: {unmatched} rows had no ENA match (left untouched)")

    # ── Defect C: merge duplicate tips into their accessioned survivor ─────────
    # Opt-in only (never part of 'all') — this tombstones the loser rows.
    if args.defect == "C":
        n_amb = int(psql_rows(args.db_url, SQL_MERGE_AMBIGUOUS)[0][0])
        pairs = psql_rows(args.db_url, SQL_MERGE_PAIRS)
        if args.limit:
            pairs = pairs[: args.limit]
        print(f"Defect C (merge duplicate tips -> accessioned survivor): {len(pairs)} pairs"
              + (f"; EXCLUDED {n_amb} ambiguous (name maps to >1 accessioned row)" if n_amb else ""))
        for survivor, loser, name in pairs:
            if not args.apply:
                print(f"  would MERGE {loser} ({name}) -> survivor {survivor}")
                record("C", loser, {"survivor": survivor, "name": name}, "dry-run")
                totals["skipped"] += 1
                continue
            ok, status = merge_pair(args.api_base, args.api_key, survivor, loser)
            record("C", loser, {"survivor": survivor, "name": name}, status)
            if ok:
                totals["patched"] += 1
            else:
                totals["failed"] += 1
                print(f"  FAIL merge {loser} ({name}) -> {survivor}: {status}", file=sys.stderr)
            time.sleep(args.rate)

    print(f"\nDone [{mode}]: patched={totals['patched']} "
          f"skipped/dry={totals['skipped']} failed={totals['failed']}")
    if logf:
        logf.close()
    return 1 if totals["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
