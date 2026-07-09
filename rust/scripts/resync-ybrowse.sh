#!/usr/bin/env bash
# Resync the YBrowse Y-SNP catalog into core.variant, processing only the daily diff.
#
# YBrowse re-dumps a FULL snps_hg38.gff3 (~3M lines) every day with no deltas. The
# du-jobs `ybrowse` ingest upserts the mirror BY SNP NAME and never truncates it, so
# feeding it only the lines that changed since yesterday is equivalent to a full load
# for adds/edits — at a fraction of the parse+liftover cost — followed by the usual
# full reconcile. (Deletions are the one thing a diff misses; run with --full
# periodically, or when in doubt, to catch upstream removals.)
#
# Flow: move current snapshot -> .prev, download today's, diff, ingest the delta.
# First run (no .prev) or --full: ingest the whole snapshot (this also seeds the
# baseline that tomorrow's diff compares against).
#
# Usage:
#   DATABASE_URL=postgres://... rust/scripts/resync-ybrowse.sh [--full]
#
# Env overrides (sensible ~/.decodingus defaults):
#   YBROWSE_GFF_DIR   dir holding snps_hg38.gff3 (+ .prev)   [~/.decodingus/ysnp]
#   YBROWSE_GFF_URL   source URL                             [ybrowse.org snps_hg38.gff3]
#   YBROWSE_CHAIN_HS1 GRCh38->hs1 UCSC chain (needed so new  [~/.decodingus/liftover/hg38ToHs1.over.chain]
#                     SNPs get hs1 coords that fold onto the de-novo tree's sites)
#   YBROWSE_CHAIN_GRCH37 GRCh38->GRCh37 chain                [~/.decodingus/liftover/GRCh38-to-GRCh37.chain]
#   DECODINGUS_JOBS_BIN  prebuilt jobs binary to run instead of `cargo run` (prod)  [unset -> cargo run]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GFF_DIR="${YBROWSE_GFF_DIR:-$HOME/.decodingus/ysnp}"
URL="${YBROWSE_GFF_URL:-https://ybrowse.org/gbrowse2/gff/snps_hg38.gff3}"
CHAIN_HS1="${YBROWSE_CHAIN_HS1:-$HOME/.decodingus/liftover/hg38ToHs1.over.chain}"
CHAIN_GRCH37="${YBROWSE_CHAIN_GRCH37:-$HOME/.decodingus/liftover/GRCh38-to-GRCh37.chain}"
FULL=0; [ "${1:-}" = "--full" ] && FULL=1

: "${DATABASE_URL:?set DATABASE_URL to the target Postgres}"

CUR="$GFF_DIR/snps_hg38.gff3"
PREV="$GFF_DIR/snps_hg38.gff3.prev"
TMP="$GFF_DIR/.snps_hg38.gff3.download"
mkdir -p "$GFF_DIR"

# 1. Roll the current snapshot to .prev (the diff baseline).
if [ -f "$CUR" ]; then mv -f "$CUR" "$PREV"; fi

# 2. Download today's dump to a temp, sanity-check, then promote.
echo "downloading $URL ..." >&2
curl -fL -sS --retry 3 -o "$TMP" "$URL"
lines=$(wc -l < "$TMP" | tr -d ' ')
if [ "$lines" -lt 100000 ]; then
  echo "ERROR: download only $lines lines — looks truncated; keeping .prev, aborting" >&2
  rm -f "$TMP"; [ -f "$PREV" ] && mv -f "$PREV" "$CUR"; exit 1
fi
mv -f "$TMP" "$CUR"
echo "downloaded $lines lines -> $CUR" >&2

# 3. Decide the ingest set: full snapshot, or just today's added/changed lines.
INGEST="$CUR"
if [ "$FULL" -eq 0 ] && [ -f "$PREV" ]; then
  DELTA="$GFF_DIR/snps_hg38.gff3.delta"
  # comm on LC_ALL=C-sorted files: lines present in CUR but not PREV = added OR edited.
  added=$(LC_ALL=C comm -13 <(LC_ALL=C sort "$PREV") <(LC_ALL=C sort "$CUR") | tee "$DELTA" | wc -l | tr -d ' ')
  removed=$(LC_ALL=C comm -23 <(LC_ALL=C sort "$PREV") <(LC_ALL=C sort "$CUR") | wc -l | tr -d ' ')
  echo "diff vs .prev: $added added/changed, $removed removed (removals need --full to purge)" >&2
  if [ "$added" -eq 0 ]; then
    echo "no changes since last resync — skipping ingest" >&2
    exit 0
  fi
  INGEST="$DELTA"
elif [ "$FULL" -eq 1 ]; then
  echo "full ingest (--full: also purges upstream deletions on reconcile)" >&2
else
  echo "full ingest (no baseline snapshot to diff against — seeds tomorrow's diff)" >&2
fi

# 4. Ingest: upsert the mirror from $INGEST, then reconcile the full catalog.
#    Prefer a prebuilt binary (prod: set DECODINGUS_JOBS_BIN=/opt/decoding-us/bin/decodingus-jobs);
#    else fall back to `cargo run --release` from the source tree (dev).
echo "ingesting $(wc -l < "$INGEST" | tr -d ' ') lines via run-once ybrowse ..." >&2
run_job() {
  if [ -n "${DECODINGUS_JOBS_BIN:-}" ]; then
    "$DECODINGUS_JOBS_BIN" run-once ybrowse
  else
    cargo run --manifest-path "$HERE/../Cargo.toml" -p du-jobs --release -- run-once ybrowse
  fi
}
YBROWSE_GFF="$INGEST" \
YBROWSE_CHAIN_HS1="$CHAIN_HS1" \
YBROWSE_CHAIN_GRCH37="$CHAIN_GRCH37" \
  run_job

echo "resync complete" >&2
