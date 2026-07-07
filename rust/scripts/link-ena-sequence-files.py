#!/usr/bin/env python3
"""Link academic biosamples back to their ENA BAM/CRAM (or FASTQ) files.

Many accessioned academic biosamples (SAM*/ENA) were loaded without any
`genomics.sequence_library` / `genomics.sequence_file` rows, so the per-sample
report has no downloadable data files. This driver finds those samples, pulls the
run file list from ENA's `filereport` API, and posts it to the curator ingest
endpoint (`POST /manage/biosamples/:guid/sequence-libraries`, X-API-Key gated) —
the same machine-auth as the rest of the biosample cleanup. It never writes the DB
directly; it only *reads* it (via `psql`) to enumerate the worklist.

File selection (per the task):
  * Prefer aligned CRAM/BAM from `submitted_ftp` (the object we actually want to
    link), carrying its .crai/.bai index and md5.
  * If a sample has NO aligned CRAM/BAM in any run, FALL BACK to that sample's raw
    FASTQ run files (`fastq_ftp`) so every sample still gets something linked.
  * VCF/analysis products are ignored.

Idempotency: the worklist predicate excludes any sample that already has a
`sequence_library`, and the endpoint itself no-ops when the sample already has one
(`{"skipped": true}`). Re-running is safe; a fixed row drops out of the worklist.

Dependencies: standard library only (urllib + a `psql` client on PATH).

Usage:
    # dry run (default) — fetch ENA, print the plan, write nothing
    DATABASE_URL=postgres://user:pw@host:5432/db \
    DU_CURATION_API_KEY=... \
    python3 link-ena-sequence-files.py --limit 20

    # apply
    ... python3 link-ena-sequence-files.py --apply --rate 0.2

Env:
    DATABASE_URL          read-only connection used only to enumerate targets
    DU_CURATION_API_KEY   X-API-Key sent to the ingest endpoint (required for --apply)
    API_BASE              default http://localhost:9000
    PSQL                  psql binary (default: psql on PATH)
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ENA = "https://www.ebi.ac.uk/ena/portal/api/filereport"
ENA_FIELDS = (
    "run_accession,sample_accession,submitted_ftp,submitted_md5,submitted_bytes,"
    "submitted_format,fastq_ftp,fastq_md5,fastq_bytes,instrument_model,"
    "library_layout,read_count,first_public"
)

# Accessioned academic samples with an ENA sample accession but no sequence_library.
# The NOT EXISTS clause doubles as the idempotency guard.
SQL_WORKLIST = """
SELECT b.sample_guid::text, b.accession
FROM core.biosample b
WHERE b.deleted = false
  AND b.accession ~ '^SAM(EA|N|D)[0-9]'
  AND NOT EXISTS (
    SELECT 1 FROM genomics.sequence_library sl WHERE sl.sample_guid = b.sample_guid
  )
ORDER BY b.accession
"""

ALIGNED = {"BAM", "CRAM"}
INDEX = {"CRAI", "BAI"}


def psql_rows(db_url: str, sql: str) -> list[list[str]]:
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, db_url, "-X", "-A", "-t", "-F", "\t", "-c", sql],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return [line.split("\t") for line in out.splitlines() if line.strip()]


def ena_runs(accession: str) -> list[dict[str, str]]:
    url = f"{ENA}?accession={accession}&result=read_run&fields={ENA_FIELDS}&format=tsv&limit=0"
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            text = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        # 204/404 = no reads for this accession (e.g. genotype-only samples)
        if e.code in (204, 404):
            return []
        raise
    lines = [l for l in text.splitlines() if l.strip()]
    if len(lines) < 2:
        return []
    header = lines[0].split("\t")
    return [dict(zip(header, l.split("\t"))) for l in lines[1:]]


def _fmt_of(url: str, declared: str) -> str:
    """Format for one file: trust the filename extension, fall back to ENA's column."""
    low = url.lower()
    if low.endswith(".cram"):
        return "CRAM"
    if low.endswith(".bam"):
        return "BAM"
    if low.endswith(".crai"):
        return "CRAI"
    if low.endswith(".bai"):
        return "BAI"
    if ".fastq" in low or ".fq" in low:
        return "FASTQ"
    return (declared or "").upper()


def _split(field: str) -> list[str]:
    return [p for p in (field or "").split(";")]


def _basename(url: str) -> str:
    return url.rsplit("/", 1)[-1]


def _to_int(v: str) -> int | None:
    v = (v or "").strip()
    return int(v) if v.isdigit() else None


def build_libraries(runs: list[dict[str, str]]) -> tuple[list[dict], str]:
    """Turn ENA run rows into the endpoint payload. Returns (libraries, mode).

    mode is 'aligned' when any CRAM/BAM was found, else 'fastq' (fallback), else 'none'.
    """
    aligned_libs: list[dict] = []
    fastq_libs: list[dict] = []

    for r in runs:
        run_acc = (r.get("run_accession") or "").strip()
        common = {
            "instrument": (r.get("instrument_model") or "").strip() or None,
            "reads": _to_int(r.get("read_count", "")),
            "read_length": None,
            "paired_end": (r.get("library_layout") or "").strip().upper() == "PAIRED"
            if r.get("library_layout")
            else None,
            "run_date": ((r.get("first_public") or "").strip() or None),
            "external_run_ref": run_acc,
        }

        # ── aligned submitted files (CRAM/BAM + index) ──────────────────────────
        ftp = _split(r.get("submitted_ftp", ""))
        md5 = _split(r.get("submitted_md5", ""))
        byt = _split(r.get("submitted_bytes", ""))
        dfmt = _split(r.get("submitted_format", ""))
        entries = []
        for i, u in enumerate(ftp):
            if not u:
                continue
            fmt = _fmt_of(u, dfmt[i] if i < len(dfmt) else "")
            entries.append(
                {
                    "url": u,
                    "fmt": fmt,
                    "md5": md5[i] if i < len(md5) else "",
                    "bytes": _to_int(byt[i]) if i < len(byt) else None,
                }
            )
        primaries = [e for e in entries if e["fmt"] in ALIGNED]
        indexes = [e for e in entries if e["fmt"] in INDEX]
        if primaries:
            files = []
            for p in primaries:
                # An index sidecar shares the primary's stem (foo.cram -> foo.cram.crai).
                idx = next((ix["url"] for ix in indexes if ix["url"].startswith(p["url"])), None)
                files.append(
                    {
                        "file_name": _basename(p["url"]),
                        "file_format": p["fmt"],
                        "file_size_bytes": p["bytes"],
                        "file_url": p["url"],
                        "file_index_url": idx,
                        "md5": p["md5"] or None,
                    }
                )
            aligned_libs.append({**common, "files": files})
            continue  # this run contributed aligned files; don't also stage its fastq

        # ── fastq fallback (staged; only used if NO run had aligned files) ──────
        fq = _split(r.get("fastq_ftp", ""))
        fqmd5 = _split(r.get("fastq_md5", ""))
        fqbytes = _split(r.get("fastq_bytes", ""))
        fq_files = []
        for i, u in enumerate(fq):
            if not u:
                continue
            fq_files.append(
                {
                    "file_name": _basename(u),
                    "file_format": "FASTQ",
                    "file_size_bytes": _to_int(fqbytes[i]) if i < len(fqbytes) else None,
                    "file_url": u,
                    "file_index_url": None,
                    "md5": (fqmd5[i] if i < len(fqmd5) else "") or None,
                }
            )
        if fq_files:
            fastq_libs.append({**common, "files": fq_files})

    if aligned_libs:
        return aligned_libs, "aligned"
    if fastq_libs:
        return fastq_libs, "fastq"
    return [], "none"


def post(api_base: str, api_key: str, guid: str, libraries: list[dict]) -> tuple[bool, str]:
    req = urllib.request.Request(
        f"{api_base}/manage/biosamples/{guid}/sequence-libraries",
        data=json.dumps({"libraries": libraries}).encode(),
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
    ap.add_argument("--apply", action="store_true", help="actually POST (default: dry run)")
    ap.add_argument("--rate", type=float, default=0.15, help="seconds to sleep between samples")
    ap.add_argument("--limit", type=int, default=0, help="cap samples processed (0 = all)")
    ap.add_argument("--db-url", default=os.environ.get("DATABASE_URL"))
    ap.add_argument("--api-base", default=os.environ.get("API_BASE", "http://localhost:9000"))
    ap.add_argument("--api-key", default=os.environ.get("DU_CURATION_API_KEY"))
    ap.add_argument("--log", default=None, help="append per-sample JSONL outcomes to this file")
    args = ap.parse_args()

    if not args.db_url:
        sys.exit("DATABASE_URL (or --db-url) is required to enumerate targets")
    if args.apply and not args.api_key:
        sys.exit("DU_CURATION_API_KEY (or --api-key) is required with --apply")

    logf = open(args.log, "a") if args.log else None

    def record(rec: dict):
        if logf:
            logf.write(json.dumps(rec) + "\n")
            logf.flush()

    rows = psql_rows(args.db_url, SQL_WORKLIST)
    if args.limit:
        rows = rows[: args.limit]
    mode = "APPLY" if args.apply else "DRY-RUN"
    print(f"[{mode}] api={args.api_base} rate={args.rate}s  worklist={len(rows)} samples")

    totals = {"linked": 0, "fastq": 0, "no_files": 0, "skipped": 0, "failed": 0}

    for guid, accession in rows:
        try:
            runs = ena_runs(accession)
        except Exception as e:  # noqa: BLE001 — network/parse hiccup: report, keep going
            totals["failed"] += 1
            print(f"  ENA FAIL {accession} ({guid}): {e}", file=sys.stderr)
            record({"guid": guid, "accession": accession, "status": f"ena-error: {e}"})
            continue

        libraries, kind = build_libraries(runs)
        if not libraries:
            totals["no_files"] += 1
            record({"guid": guid, "accession": accession, "status": "no-files", "kind": kind})
            continue
        if kind == "fastq":
            totals["fastq"] += 1

        nfiles = sum(len(l["files"]) for l in libraries)
        if not args.apply:
            print(f"  would LINK {accession} ({guid}): {len(libraries)} run(s), {nfiles} {kind} file(s)")
            record({"guid": guid, "accession": accession, "status": "dry-run", "kind": kind,
                    "runs": len(libraries), "files": nfiles})
            totals["linked"] += 1
            time.sleep(args.rate)
            continue

        ok, resp = post(args.api_base, args.api_key, guid, libraries)
        record({"guid": guid, "accession": accession, "status": resp if ok else f"FAIL {resp}",
                "kind": kind, "runs": len(libraries), "files": nfiles})
        if ok:
            if '"skipped": true' in resp or '"skipped":true' in resp:
                totals["skipped"] += 1
            else:
                totals["linked"] += 1
        else:
            totals["failed"] += 1
            print(f"  FAIL {accession} ({guid}): {resp}", file=sys.stderr)
        time.sleep(args.rate)

    print(
        f"\nDone [{mode}]: linked={totals['linked']} (fastq-fallback={totals['fastq']}) "
        f"no-files={totals['no_files']} skipped={totals['skipped']} failed={totals['failed']}"
    )
    if logf:
        logf.close()
    return 1 if totals["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
