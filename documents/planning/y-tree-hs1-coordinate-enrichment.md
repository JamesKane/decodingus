# Issue: Y-tree needs complete `hs1` (CHM13) coordinates for native CHM13 placement

Created 2026-06-10. Repo: decodingus (AppView), branch `rust-rewrite-foundation`. Companion to
the Navigator (DUNavigator) DecodingUs Y-tree provider — see that repo's
`documents/design/DecodingUsTreeProvider.md` + `memory/decodingus-tree-provider.md`.

## TL;DR

The Navigator desktop app now places Y haplogroups against **our** DecodingUs tree (served by
`GET /api/v1/y-tree/full`, added in commit cd97864) instead of FTDNA. It uses each variant's
**native build coordinate** — for a CHM13 alignment, the `hs1` coordinate — so placement needs
**no liftover**. That's the intended architecture: the AppView owns multi-build coordinates;
Navigator stays liftover-free.

**The gap:** `hs1` coordinates today cover only the **decoding-us backbone**, not the
FTDNA-grafted tips. So a CHM13 sample places correctly down the backbone but **stops at K2b
instead of reaching its terminal (R-FGC29071)**. The AppView needs to provide `hs1` coordinates
for **every** tree variant.

## Evidence (live, GFX0457637 CHM13 HiFi BAM)

Validated against a locally-running AppView (`/api/v1/y-tree/full`), Navigator test
`validate_gfx_decodingus_y`:

| Path | Coords used | SNPs matched | Placement |
|------|-------------|-------------:|-----------|
| DecodingUs **native hs1** (no liftover) | `hs1` | 101 / 119 | **K2b** (backbone only) |
| FTDNA GRCh38 + liftover (reference) | GRCh38 | 1592 / 1919 | **R-FGC29071** (correct terminal) |

Coordinate coverage across the **79,602** tree variant-links in `/api/v1/y-tree/full`:

| Build | variant-links with this coordinate | % |
|-------|-----------------------------------:|---|
| GRCh38 | 70,294 | 88% |
| GRCh37 | 22,300 | 28% |
| **hs1 (CHM13)** | **22,988** | **29%** |

So ~47k tree variants have a GRCh38 coordinate but **no hs1** — and those are the deeper
(FTDNA-grafted) tips. M207 (R root) *does* have hs1, but the R-subclade tips below K2b largely
don't, so descent halts. (Across all 3M `core.variant` rows, GRCh38-with-derived = ~2.99M,
hs1-with-derived = ~72.9k — `hs1` is sparse globally too.)

## What's needed (the ask)

Populate `hs1` (and ideally `GRCh37`) coordinates for **all** Y-tree variants, by lifting their
GRCh38 coordinate to `hs1`. Two viable shapes (the user is open to either):

1. **Ingest / enrichment phase (persistent).** A `decodingus-tree-init` / enrichment step that,
   for every `core.variant` with a GRCh38 coordinate but no `hs1`, lifts GRCh38→hs1 and writes
   the `hs1` entry into `core.variant.coordinates`. The AppView already has GRCh38→hs1 liftover
   infrastructure — see `rust/crates/du-jobs/src/ybrowse.rs` ("GRCh38 -> hs1 (T2T-CHM13) chain
   file") and `rust/crates/du-migrate/src/bin/tree_init.rs` (`prod_build` maps `hs1`). Reuse the
   same chain. **Scale/constraint:** the dev Postgres container is RAM-limited (1 GB) and was
   OOM-killed by a single 3M-row `UPDATE` (migration 0021) — **batch** the enrichment (commit per
   chunk) or scope it to tree-linked variants (~47k missing hs1) rather than all 3M.

2. **On the fly (in the API).** In the `/api/v1/y-tree/full` handler, for any variant lacking an
   `hs1` coordinate, lift its GRCh38 coordinate to `hs1` at response time (chain loaded at
   startup). No DB mutation; pairs naturally with the existing `du-jobs` liftover. Heavier per
   request, but the tree response is already cached on the Navigator side.

Either way the goal is identical: every Y-tree variant carries a usable `hs1` coordinate (contig
`chrY`, position, ancestral, derived) so Navigator's native-CHM13 path reaches terminals.

## Interaction with the FTDNA-merge descope decision

The user is separately deciding to **descope the FTDNA merge and build the tree from only the
ISOGG + decoding-us prod trees**. That decision directly bears on this issue:

- The `hs1` gap is concentrated in the **FTDNA-grafted** tips (decoding-us variants already carry
  multi-build coords incl. `hs1`). If FTDNA tips are dropped, the remaining tree is
  ISOGG + decoding-us — so check whether **ISOGG** coordinates include `hs1`/CHM13 (if ISOGG is
  GRCh38-only, those nodes still need the GRCh38→hs1 lift).
- Net: the enrichment lift is still likely needed for ISOGG-sourced nodes, but the volume and the
  "which tips exist at all" both change. **Sequence this issue after (or alongside) the
  FTDNA-descope decision** so we don't lift coordinates for variants we're about to drop.

## Verification

Once `hs1` is complete for the Y tree:
- `GET /api/v1/y-tree/full` → most/all variants carry an `hs1` coordinate with `derived`.
- Navigator `validate_gfx_decodingus_y` (in DUNavigator, against the AppView) reaches
  **R-FGC29071** via the native-hs1 path (no liftover), matching the FTDNA reference result.

## Local dev-DB state note (for whoever picks this up)

To get the AppView running locally on 2026-06-10, migration **0021_ancestral_state** was applied
**manually** to the dev DB (`du-pg` container, `postgres://postgres:dev@192.168.64.2:5432/
decodingus`): its 3M-row `UPDATE` OOM-killed the 1 GB container, so the relabel was run in
**100k-id batches** (committing each), then the part-2 `ALTER` ran, then the row was recorded in
`_sqlx_migrations` (version 21, checksum `f78640156ad4…`) so `du-web` startup skips it. The DB is
now at migration 21, clean. If you `reset` the container you'll need to re-apply 0021 the same
batched way (or bump the container's memory).

## Secondary (Navigator-side) observation — not blocking

Navigator also has a GRCh38-coords + liftover fallback path for the DecodingUs tree. On the DU
tree it currently under-matches (the GFX sample's M207 came back as a no-call; carried SNPs
scattered → shallow placement) even though the DU GRCh38 coordinates match FTDNA's
(55,293/55,354 same position). This looks like a back-map collision when many DU variants share
or recur at the same lifted CHM13 position (the tree has recurrent-SNP/homoplasy structure — cf.
migration 0021's per-branch allele columns). It's not on the critical path (native-hs1 is the
intended route), but worth a look on the Navigator side if the GRCh38+lift fallback is kept.
