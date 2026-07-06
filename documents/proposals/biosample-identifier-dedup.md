# Identifier-based biosample dedup (vendor kits + public accessions)

**Status:** proposed (2026-07-05). Extends `biosample-duplicate-detection.md` with the
**deterministic-identifier** path that proposal leaves to "trivial accession equality."
Companion, not replacement: genotype Tier‑1/Tier‑2 stays the fallback for samples with **no**
shared identifier.
**Scope:** biosample lexicon (`du-domain`), `fed.biosample`, a `core.biosample_identifier`
model, `link_federated_subjects`, transitive donor reconciliation, and a manifest backfill.
**Privacy posture (per project owner):** the corpus carries **no living-donor PII** — the only
"metadata" is the most-distant-known-ancestor (MDKA: surname/origin/birth-year of the earliest
*paternal-line* ancestor), and a **vendor kit id does not link to the donor directly** (it
resolves to a person only for someone who already holds that vendor's private roster). So kit ids
are treated as **opaque vendor tokens, stored plaintext but gated off the public read surface** —
no hashing scheme (see "Why not hashing").

## Why

A contributor (and, crucially, **many contributors**) will publish biosamples from their Navigator
PDS, and **the same physical donor is already on the tree**. Two shapes, one now dominant:

- **Public / open-consent samples** — 1000G/IGSR (`NA…`/`HG…`), PGP-Harvard (`hu…`). Already in
  the AppView as tree tips + catalog rows.
- **Vendor-kit genomes, heavily duplicated across analysts.** This is the volume case: in the way
  admins will use Navigator, **the same FTDNA kit is submitted by multiple project admins**, so one
  donor recurs many times, published by different analysts, each carrying some subset of the donor's
  identifiers.

One donor routinely spans **several vendor namespaces**. The worked example:

> Tree tip **`WGS229`** — the same person is PGP‑Harvard **`huF98AFD`**, FTDNA kit **`B5163`**,
> and YSEQ **`229`** (the tip's friendly accession literally encodes the YSEQ number).

Today a federated biosample lands via `link_federated_subjects` (`fed_subject.rs:42`) as a **new**
`core.biosample` keyed by record **URI**, donor keyed by **DID**, with **no accession and no
alias**. So the re-published donor cannot match its existing row:

| Facility | Keys on | Fires for the re-publish case? |
|---|---|---|
| Exact-accession dedup (`denovo.rs`, unique index) | `core.biosample.accession` | ❌ anchor has no accession |
| Donor consolidation (`donor::consolidate_denovo_donors`) | tip `accession` == ref `alias` | ❌ anchor has no alias |
| Duplicate-candidate engine (`dedup::recompute_candidates`, mig `0049`) | terminal Y+mt block → private Jaccard | ⚠️ fallback only |

The genotype engine is the **wrong tool** for an already-identified sample: it yields a *candidate*
needing Tier‑2 autosomal + a merge; its Y block is **fragile here** (`WGS229` is a precise de-novo
tip, a re-published copy is placed by its coarser published call → different blocks, never paired);
and at this volume it would bury curators. When we already know it *is* kit `B5163`, we should match
on that — not run a detective. The gap: the identifier isn't plumbed through federation, and for
vendor kits it isn't in the DB at all (it lives only in the manifest).

## Current state (grounded)

**Identity columns.** `core.biosample`: `accession TEXT`, `alias TEXT`, `source_attrs JSONB`,
`donor_id` → `core.specimen_donor`, `is_public`. `accession`/`alias` are single overloaded slots.

**The manifest = the authoritative cross-reference.** `/Volumes/nas/Genomics/d2c/cohort_manifest.tsv`
(10,312 rows). Columns: `subject_id, surname, origin, birth_yr, lab, kit, test_types, test_data_ids,
location, flat_gvcf, flat_callable_bed, bigy_chm13_bed, bigy_chm13_vcf, vcfonly_chm13_vcf,
nas_artifact_dir`.
- **`subject_id` is a UUID (all 10,312)** — and it is exactly the **`accession` of the de-novo tip
  on the tree** (the cutover's 7,870 UUID-accession tips). So the manifest joins to the tree by
  `subject_id == core.biosample.accession`.
- **`lab` / `kit` are comma-aligned lists** — one donor, one-or-many vendor kits. Labs present:
  FTDNA (9,885), Dante (162), YSEQ (148), FGC (125), Nebula (70).
- `surname/origin/birth_yr/location` describe the **MDKA**, not the donor → genealogical context,
  not PII. (Public ids — PGP `hu…`, IGSR `NA…/HG…` — arrive via the public-cohort path, not this
  vendor manifest; `test_data_ids` may carry some.)

So the vendor kit id is **deliberately absent from the DB today** — the tips are UUID-anonymized and
the kit lives only in the manifest. This design brings the kit *in* as a dedup key without changing
that anonymization.

## Design

### 1. A first-class external-identifier model

Replace the overloaded `accession`/`alias` with an explicit, multi-valued, namespaced table:

```sql
CREATE TABLE core.biosample_identifier (
    id           BIGSERIAL PRIMARY KEY,
    sample_guid  UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    namespace    TEXT NOT NULL,          -- FTDNA | YSEQ | DANTE | FGC | NEBULA | PGP | IGSR | ENA | ...
    value        TEXT NOT NULL,          -- the vendor/catalog id, normalized (upper, trimmed)
    -- Public/open-consent ids (PGP, IGSR, ENA) are displayable; vendor kit ids are NOT surfaced.
    is_public    BOOLEAN NOT NULL DEFAULT false,
    confidence   TEXT NOT NULL DEFAULT 'ASSERTED',   -- ASSERTED | VERIFIED
    source       TEXT NOT NULL,          -- 'cohort-manifest' | 'federation' | 'curator'
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- One (namespace,value) maps to one sample — the deterministic dedup key.
CREATE UNIQUE INDEX bsid_ns_value_key ON core.biosample_identifier (namespace, value);
CREATE INDEX bsid_sample_idx ON core.biosample_identifier (sample_guid);
```

- Vendor kit → `is_public=false` (indexed for matching, **never** in a public API/UI projection —
  gate every surfaced query with `WHERE is_public`, as the fed read paths already gate PII).
- PGP / IGSR / ENA → `is_public=true` (displayable, like a catalog accession).
- `accession`/`alias` stay as display/legacy fields; this table is the dedup index. (A later cleanup
  can demote them to views — out of scope.)

### Why not hashing

An earlier draft stored vendor kits as HMAC tokens. Dropped, for two reasons the clarifications made
decisive:
1. **Cross-analyst matching forbids a per-contributor pepper** — the same kit published by two admins
   must produce the *same* key, which forces one global pepper shared to every Navigator instance.
   A secret shared that widely, over low-entropy kit ids, isn't meaningfully secret.
2. **A kit id doesn't identify the donor** (it needs the vendor's private roster, which an attacker
   would hold — and could hash — regardless). Hashing buys almost nothing here.
So: store the kit **plaintext, `is_public=false`**. Confidentiality comes from **not surfacing it**,
not from a cipher. (If a future namespace *is* genuinely sensitive, add a per-namespace
`sensitivity` and hash only that one — the schema already isolates by namespace.)

### 2. Publish identifiers on the wire

Extend the biosample lexicon → `du_domain::fed::BiosampleRecord` → `fed.biosample`
(mirrors the `sequencingFacility` add) with an optional list, and mirror to a
`fed.biosample_identifier` shadow:

```jsonc
"externalIds": [
  { "namespace": "PGP",       "value": "huF98AFD" },   // open-consent, public
  { "namespace": "FTDNA",     "value": "B5163"    },   // vendor kit, is_public=false
  { "namespace": "YSEQ",      "value": "229"      }
]
```

Navigator emits whatever identifiers it holds for the donor; the AppView marks each `is_public` from
a namespace policy table (PGP/IGSR/ENA public; FTDNA/YSEQ/Dante/FGC/Nebula not).

### 3. Deterministic anchor dedup (the fix)

In `link_federated_subjects`, **before minting** a new `core.biosample`:

1. Look up each published `(namespace, value)` in `core.biosample_identifier`.
2. **Hit** → don't mint a disconnected anchor. Attach the federated record (its `atproto.uri`) to the
   matched sample's **donor**; register any *new* identifiers the record brought (so it becomes a
   bridge for the next publisher); raise a `dedup.duplicate_candidate` `tier='IDENTIFIER'`, route per
   §5.
3. **No hit** → mint as today, **and register its identifiers** so the next analyst's copy matches.

This collapses the re-publish onto the existing donor **at ingest, deterministically, no genotype** —
for every public sample and every vendor kit we hold.

### 4. Transitive donor reconciliation (the heavy-duplication core)

Because analysts publish **overlapping-but-partial** identifier subsets, matching must be
**transitive**. Analyst A publishes `{PGP:huF98AFD}`; analyst B publishes `{FTDNA:B5163}`. Neither
shares an id with the other — but both match the manifest-seeded tip `WGS229`, which carries *all
three*. The tip is the **hub**; identifiers form an identity graph and the donor is a connected
component.

Two cases:
- **Bridge to an existing hub** (the common case, once the manifest is imported): each publish just
  attaches to the hub's donor. Handled by §3.
- **Bridge between two previously-distinct donors** (no hub yet): A publishes `{PGP}` → mints Donor_A;
  later a record carrying `{PGP, FTDNA}` arrives → PGP hits Donor_A, FTDNA hits Donor_B → the new
  record **bridges** them. A `reconcile-donors` step then **merges Donor_A ⇐ Donor_B** (union-find
  over `biosample_identifier`), reusing `dedup::merge_biosamples`/`core.biosample_merge` (mig `0050`)
  at the donor level. Idempotent, declarative, advisory-locked — same discipline as the other
  recompute engines.

Importing the manifest **up front** (seed every tip with all its vendor ids) means most publishes hit
a fully-populated hub and the bridge-merge case is rare — but it must exist for ids not in the
manifest and for the ordering above.

### 5. Merge routing (Tier‑0 propose → signature confirm)

Identifier match is **Tier‑0**, above the genotype Tier‑1 — but "same id" ≠ "safe to merge." A naked
auto-merge on a self-asserted kit # is dangerous: a mistyped, reused, or mis-attributed kit would
**silently fuse two people**. So a vendor-kit match *proposes*; a **DNA-signature check confirms**
before any merge.

| Match namespace | On match |
|---|---|
| Public / open-consent (PGP, IGSR, ENA, BioSample) | **auto-merge** (`merge_biosamples`) — the catalog accession is authoritative and a re-release *is* the same sample |
| Vendor kit (FTDNA/YSEQ/…) | raise `IDENTIFIER` candidate → **DNA-signature confirmation** → merge; **never** merge on the id alone |
| id match **but signature conflict** | **never merge** — surface as a data-quality flag (probable mislabeled/reused kit) |

**The signature confirmation** reuses the existing uniparental engine (`dedup::evaluate_pair`): the
two samples must be genetically concordant — same terminal Y node (or high private-variant Jaccard)
**and** mt agreement, and/or a matching **Y‑STR profile** (we already import FTDNA DYS results,
`ftdna_str.rs`). This is *lighter* than Tier‑2 autosomal and, combined with the kit #, is actually
**more** robust than either signal alone:

- The **kit # rules out the sibling trap** — brothers/father-son share a uniparental signature but
  have *different* kit numbers, so a kit-# match can't be a close patrilineal relative.
- The **signature rules out a bad kit #** — a mistyped/reused/mis-attributed kit won't have a
  concordant Y signature.

So `kit# ∧ concordant-signature` ⇒ same individual, with confidence the pure-genotype path can't
reach (it can't separate brothers) and the pure-identifier path can't reach (it trusts the label).

Confirmation may **lag ingest**: a freshly federated copy must be resolved into `tree.*` before it
has a signature, so the `IDENTIFIER` candidate sits `CONFIRMED_PENDING` until the next dedup recompute
can evaluate it — or until a curator acts. No signature yet ⇒ **no merge** (hold, don't guess).

The genotype pipeline still runs independently and **corroborates**; an `IDENTIFIER`+signature merge
with a concordant autosomal verdict is unimpeachable, but autosomal is not required.

### 6. Manifest backfill — seed the hubs

One-off `du-jobs run-once import-kit-identifiers [--apply]`:

1. Read `cohort_manifest.tsv`; for each row resolve the biosample by `subject_id == accession`.
2. Zip the comma-aligned `lab`/`kit` lists → insert one `core.biosample_identifier`
   `(namespace=upper(lab), value=norm(kit), is_public=false, source='cohort-manifest')` per pair.
3. **Do not touch the UUID accession** — the tip stays anonymized; we only add the vendor tokens.
4. **Load imports identifiers only — MDKA is not touched.** The manifest's `surname/origin/birth_yr`
   are left where they are; MDKA context enters the AppView *only* when a researcher/owner **PDS
   publishes** the sample (the federation path carries it), never from this bulk load. So the backfill
   is purely the vendor-token index.

After this, `WGS229` carries `FTDNA:B5163` + `YSEQ:229`; the public-cohort import adds `PGP:huF98AFD`.

### The scenario, resolved

- Backfill seeds tip `WGS229` with `{FTDNA:B5163, YSEQ:229}`; public-cohort import adds `{PGP:huF98AFD}`.
- Analyst A publishes the donor with `{PGP:huF98AFD}` → hits the hub → attaches to `WGS229`'s donor.
- Analyst B publishes with `{FTDNA:B5163}` → hits the same hub → same donor. A and B converge though
  they share no id, via the hub. ✅
- A third analyst publishing `{PGP}` before the hub exists mints Donor_X; when any record later
  carries `{PGP, FTDNA}`, `reconcile-donors` merges Donor_X into the hub. ✅
- Deterministic, at ingest, no genotype, kit id never surfaced publicly.

## Privacy & threat model

- **No living-donor PII in the corpus** (owner-confirmed): manifest "metadata" is MDKA; identifiers
  are opaque vendor tokens that resolve to a person only via a vendor's private roster.
- **Vendor kits exist in the background but never on a public surface.** They must be stored to
  drive dedup, but `is_public=false` and are excluded from **every** public read projection — reports,
  UI, API, exports. Confidentiality by non-disclosure. Public/open-consent ids (PGP, IGSR) are
  displayable.
- **No shared secret to leak** (no pepper) — the earlier hashing model added a real distribution
  liability for negligible gain, given the above.
- MDKA context, if surfaced, is genealogical (earliest paternal ancestor), consistent with what
  YFull/FTDNA already display.

## Phasing

1. **Schema** — `core.biosample_identifier` (+ `fed.biosample_identifier` shadow) + a namespace
   `is_public` policy. Backfill existing plaintext `accession`/`alias` (public tier) so the index is
   complete.
2. **Manifest backfill** — `import-kit-identifiers` (vendor tokens onto tips; UUID untouched).
3. **Lexicon + mirror** — `externalIds` on the biosample record + `fed.biosample` ingest.
4. **Anchor dedup + reconciliation** — identifier lookup in `link_federated_subjects` (§3), Tier‑0
   routing (§5), `reconcile-donors` union-merge (§4).

Phases 1–2 are safe and independently useful (they make the *existing* corpus identifier-indexed and
let donor consolidation key on the token). 3–4 activate the federated re-publish path.

## Decisions

**Resolved (2026-07-05):**
1. **Vendor-kit merges are signature-gated, never naked auto-merge** (§5). Kit # proposes; a DNA-
   signature concordance check (uniparental terminal Y + mt, and/or Y‑STR profile) confirms; a
   kit-match-with-signature-conflict is surfaced as a probable mislabeled kit, never merged.
2. **Namespace `is_public` policy:** public/displayable = {PGP, IGSR, ENA, BioSample}; background-only
   (never on a public surface) = {FTDNA, YSEQ, DANTE, FGC, NEBULA}. No vendor kit is displayable.
3. **MDKA is not touched by the load.** The manifest backfill imports vendor identifiers only; MDKA
   enters only when a researcher/owner PDS publishes the sample (federation path).

**Still open:**
4. **`accession`/`alias` fate.** Keep as display now, schedule a view-migration onto the identifier
   table later — or migrate now?
5. **Reconcile-donor trigger cadence.** Run `reconcile-donors` on every identifier insert (immediate
   convergence) or as the hourly/declarative recompute (cheaper, eventually-consistent)? Leaning
   declarative, matching the other engines.

## Files this will touch

| Phase | Anchor |
|---|---|
| 1 schema | new `00NN_biosample_identifier.sql`; backfill from `core.biosample.accession/alias` |
| 2 backfill | new `du-jobs` run-once `import-kit-identifiers` (reads `cohort_manifest.tsv`; `du-db::identifier`) |
| 3 lexicon | `du-domain::fed::BiosampleRecord.external_ids`; `fed.biosample` + `jetstream.rs build_biosample` |
| 4 anchor | `du-db::fed_subject::link_federated_subjects` (id lookup + Tier‑0 routing); `reconcile-donors` |
| — reuse | `dedup::merge_biosamples` + `core.biosample_merge` (mig `0050`); `donor::consolidate_denovo_donors` |
