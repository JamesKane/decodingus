#!/usr/bin/env bash
# Nightly maintenance: run every batch job ONCE, sequentially, so they NEVER overlap.
# One systemd timer invokes this. `DU_JOBS_LOCK=wait` makes each `run-once` queue behind
# any concurrently-running job (e.g. a frequent poller timer) instead of overlapping it —
# and because the steps here run one after another, the batch never overlaps itself.
#
# Replaces the retired in-process interval scheduler (which fired everything on startup and
# let same-period jobs overlap, spiking DB + memory).
#
# Usage:
#   DATABASE_URL=postgres://... [DECODINGUS_JOBS_BIN=/opt/decoding-us/bin/decodingus-jobs] \
#     rust/scripts/nightly-maintenance.sh
#
# The frequent jobs (project-crawl, link-federated-subjects, exchange-expire) are NOT here —
# they run on their own short-interval timers with `DU_JOBS_LOCK=skip`.
set -uo pipefail   # deliberately NOT -e: one job failing must not abort the rest

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DU_JOBS_LOCK=wait
: "${DATABASE_URL:?set DATABASE_URL}"

if [ -n "${DECODINGUS_JOBS_BIN:-}" ]; then
  JOBS=("$DECODINGUS_JOBS_BIN")
else
  JOBS=(cargo run --manifest-path "$HERE/../Cargo.toml" -p du-jobs --release --)
fi

step() { echo ">>> $*" >&2; if "$@"; then echo "<<< ok" >&2; else echo "!!! FAILED (continuing): $*" >&2; fi; }
job()  { step "${JOBS[@]}" run-once "$@"; }

# 1. YBrowse catalog refresh (diff-based) + name the de-novo nodes it made namable.
#    variant-name-reconcile sits BETWEEN the two on purpose. A refresh that names a marker
#    the de-novo loader had already minted as a coordinate row (chrY:10249542C>G) leaves that
#    row unnamed and sitting in the curator naming queue, where the only offered action would
#    mint a DU id and fork the marker's identity. The reconcile folds it onto the name YBrowse
#    just published — and it must run before name-private-nodes, which picks node names from
#    the variants on a branch and can only see a name once the variant actually carries it.
step "$HERE/resync-ybrowse.sh"
job variant-name-reconcile
job name-private-nodes --apply

# 2. External metadata enrichment (OpenAlex + PubMed + ENA). Skipped internally if the
#    corresponding env (OPENALEX_MAILTO / NCBI_EMAIL) is unset — those jobs just error,
#    which `step` logs and continues past.
job publication-pubmed-update
job publication-update
job publication-discovery
job ena-study-enrichment

# 3. Tree sample placement, then branch ages (STR signatures + combined age).
job tree-samples-recompute
job branch-age

# 4. QA / consensus feeders (curator review queues).
job coverage-norms
job sequencer-consensus
job discovery-consensus

# 5. Discovery + duplicate-candidate mining.
job ibd-discovery-recompute
job dedup-candidates

echo "nightly maintenance complete" >&2
