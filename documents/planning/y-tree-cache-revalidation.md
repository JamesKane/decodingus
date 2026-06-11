# Work Item: Tree-endpoint cache revalidation (ETag / version)

**Status:** Backlog (small API enhancement)
**Surface:** `du-web` — `GET /api/v1/y-tree/full` (and `mt-tree/full`)
**Filed:** 2026-06-11 · **Origin:** Navigator (Edge) tree-cache staleness incident

## Problem

The Navigator caches the full tree JSON on disk and (historically) served it
**cache-first forever**, with no way to know the AppView had a newer tree. This bit
a real placement: a Navigator cached the `/y-tree/full` payload on 2026-06-10 when
only **28.9%** of variants carried `hs1` (CHM13) coordinates; the AppView was later
enriched to **91.7%**, but the Edge kept using the stale copy and **under-placed a
low-coverage HiFi sample** (R-FGC29071 → K2b) because the deep R1b tips had no
`hs1` coordinate to genotype. Only a manual refetch fixed it.

The Edge has since added a **7-day TTL** (refetch weekly; fall back to the stale
copy if the AppView is unreachable). That bounds staleness but is coarse: it
re-downloads the full payload (~28 MB) on every expiry even when nothing changed,
and a curated tree update inside the 7-day window isn't seen until the window rolls.

## Ask

Let the Edge **revalidate cheaply** instead of blindly re-downloading on a timer:

1. Emit a stable **`ETag`** (and/or `Last-Modified`) on `GET /api/v1/y-tree/full`
   and `GET /api/v1/mt-tree/full`, derived from the tree's content/version — e.g.
   the active tree revision id + a hash of the serialized payload. The same input
   that changes the payload must change the ETag.
2. Honor **conditional GET**: `If-None-Match` (and `If-Modified-Since`) → return
   **`304 Not Modified`** with no body when unchanged.
3. (Optional, nice-to-have) expose the tree **version/revision** as a small JSON
   field (e.g. `GET /api/v1/y-tree/version` → `{ "revision": …, "etag": … }`) so
   clients can check version without fetching the tree at all.

Since the tree is temporal (bitemporal `tree.haplogroup_relationship`, per-revision
metadata), the ETag should key on the **current published revision** + the build's
coordinate-enrichment state, so a `hs1`-coordinate backfill (the exact thing that
caused the incident) bumps the ETag even if the topology is unchanged.

## Acceptance criteria

- `GET /api/v1/y-tree/full` returns an `ETag`; a subsequent request with a matching
  `If-None-Match` returns `304` with an empty body.
- Any change that alters the served payload (topology, variant set, **coordinate
  enrichment**, naming) changes the `ETag`.
- mt-tree parity.

## Edge-side counterpart (already done, for reference)

Navigator now: 7-day cache TTL (`NAVIGATOR_TREE_TTL_DAYS`), graceful fallback to the
stale copy on fetch failure, and a **scoring cache keyed on the tree's content hash**
(re-scores only when the tree content actually changes). With an ETag, the Edge would
switch from "re-download weekly" to "revalidate weekly via `If-None-Match`, download
only on `200`" — cutting the bandwidth and letting the TTL drop without cost.
