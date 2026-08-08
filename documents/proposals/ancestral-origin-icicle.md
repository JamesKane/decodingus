# Ancestral-origin locality icicle on the public Y tree

**Status:** proposed (2026-08-06). **AppView half only** — the Navigator publisher is deferred by
the project owner, so this ships *dark*: schema, ingest, aggregate and view are built and tested
against seeded records, and the surface fills when a PDS starts publishing.
**Scope:** a new `com.decodingus.atmosphere.ancestralOrigin` lexicon, `fed.ancestral_origin`
(migration 0074), a jetstream arm, `du_db::origins`, and a server-rendered icicle at
`/ytree/node/:name/origins`. No change to `core.biosample`, no change to placement.
**Privacy posture:** unchanged from `biosample-identifier-dedup.md` — the corpus carries no
living-donor PII, and MDKA (surname / origin / birth-year of the earliest paternal-line ancestor) is
**genealogical context, not PII**. This design does not relax that; it *enforces* it (§2).

## 1. Why

The public Y tree renders as a cladogram (`du-web/src/tree_layout.rs`) with a per-clade Leaflet map
in the "Geography & Time" panel. Neither answers the question a surname project asks: **as this
branch splits, where do the lines go?** ytree.net answers it by putting geography onto the phylogeny
itself — depth down the page, a block's height its elapsed time, each block spanning its
descendants. This adds that view with locality as the fill.

It only means anything in the genealogical era. Deeper than ~1,500 ybp every block aggregates to
"Europe" and the counts explode, so the view is age-gated rather than offered tree-wide (§5).

**The data gap, measured on `decodingus_cutover` (2026-08-06).** The AppView's only locality datum
is `core.specimen_donor.geocoord`; there is no country and no place text, and legacy had none
either, so the ETL did not drop one. Of **9,642 placed Y samples, 1,380 carry a coordinate**, and
that coverage is entirely ancient/academic:

| source | placed | with geocoord |
|---|---:|---:|
| `EXTERNAL` (ancient + academic) | 1,760 | 1,377 |
| `STANDARD` (`cohort=bigy` D2C tips) | 7,882 | **3** |

The genealogical era — the only era this view is for — has effectively no locality data. That is
what the lexicon exists to supply.

## 2. What may cross the wire, and how it is enforced

The posture is already the project's (`biosample-identifier-dedup.md` §Privacy posture). What is new
is that this design **enforces** it at ingest rather than asserting it in prose. A record failing any
gate is **rejected**, not merely un-rendered:

1. **Surname only.** No given name, ever. The publisher derives it; the AppView independently
   rejects a `surname` containing whitespace or more than one name token, so a buggy or hostile
   client cannot leak a given name through a field labelled `surname`.
2. **Date ceiling — `birthYear <= 1900`.** A person born in 1900 is 126 today. This is the check
   that makes "not PII" verifiable rather than asserted.
3. **Precision ladder when the birth year is absent.** With a birth year: place text + coarsened
   coordinate. Without one: **country only** — place text and coordinate are dropped at ingest.
4. **Coordinates coarsened to 2 decimal places (~1 km).** Applied at publish *and* re-applied at
   ingest, because the client cannot be trusted to have done it. A county-scale view cannot use more
   precision; full precision plus a surname narrows to one family.
5. **The join key is never rendered.** Resolution runs through an FTDNA kit id (§4), and every vendor
   namespace is `is_public = false` (`du_db::identifier::is_public_namespace`). The icicle shows
   `Kane · Co. Clare`; the kit number must not reach any public projection.

**The bulk-load exclusion stands.** `du-jobs/src/import_kit_identifiers.rs:17` records the decision
that MDKA "enters only when a PDS publishes the sample, never from this bulk load." This design does
not create a manifest or curator path. It is the reason the view ships dark, and that is accepted.

**Two migration headers say the opposite and are deliberately not edited.** Both repos use
`sqlx::migrate!`, which checksums applied migrations — editing a comment in
`0030_mdka.up.sql` (Navigator) or `0012_fed_reporting.sql` (AppView) would fail every existing
database with `VersionMismatch`. **This document is the amendment of record**, and migration 0074's
header points back to it.

The D4 assertion store's PII rail (`research.assertion` rejecting `MDKA_IS`) **stands unchanged**.
It governs assertions made *about a living research subject* within a project, which is a different
question from publishing a deceased ancestor's parish — and the rail is what keeps the two apart.

## 3. The record

`com.decodingus.atmosphere.ancestralOrigin`, one per `(biosample, lineage)`:

```jsonc
{
  "biosampleRef":  "at://did:plc:…/com.decodingus.atmosphere.biosample/…",  // when federated
  "externalIds":   [{ "namespace": "FTDNA", "value": "B5163" }],            // the join that fires
  "lineage":       "Y_DNA",                  // Y_DNA | MT_DNA
  "surname":       "Kane",                   // single token; never a given name
  "originPlace":   "Creegh South, Co. Clare, Ireland",   // as recorded; normalized server-side
  "originCountry": "Ireland",
  "birthYear":     1830,
  "deathYear":     1908,
  "lat":           52.75,                    // 2dp
  "lon":           -9.43,
  "createdAt":     "2026-08-06T…Z"
}
```

**Place text is published as recorded and normalized in the AppView**, not at the edge. One
implementation, fixable without a client release, and re-runnable over records already ingested. The
normalizer is a pure function in `du_db::place` with a country/admin synonym table — the corpus needs
it: `Ireland` / `Republic of Ireland` / `ireland`; `UK` / `United Kingdom` / `Scotland`; `Co. Cork`
vs `Cork`; `VA` vs `Virginia`; UK postcodes embedded mid-string (`Moulin, Pitlochry PH16 5EP, UK`).
705 distinct raw admin strings across the reference corpus.

## 4. Resolving a record to a placed sample

The obvious join — `core.biosample.atproto->>'uri' = biosample_ref`, as `discovery.rs:185` does —
**matches nothing**: zero placed samples carry an at-uri, because the tips were bulk-loaded rather
than federated.

The working key already exists. **All 7,548 placed bigy tips carry an `FTDNA` row in
`core.biosample_identifier`** (migrations 0059/0060, built precisely to "match a re-published donor
to its existing biosample"). So resolution is `(namespace, value)` against that table, with the
at-uri as a fallback for genuinely federated samples. No re-federation, no new identity work.

## 5. The view

`/ytree/node/:name/origins` (+ the mt sibling), a server-rendered inline SVG — no client layout
library, matching `tree_layout.rs`.

- **Geometry**: depth → y; each node a rect spanning its subtree's horizontal extent; children flush
  against the parent's underside, so containment carries descent and no connector is drawn.
- **A block shows its equivalent SNPs, and its height is their count** — one line each, nothing
  elided, exactly as the Big Tree draws it. The mutations on a branch are unordered, so the list
  *is* the block. Vertical position is therefore cumulative: how far down a block sits is the
  mutations accrued along the path to it, and the left gutter rules that off in SNPs.

  **Do not size blocks by the age model.** Both obvious forms were tried against real data and both
  fail:

  1. *A node's own `formed_ybp` → its own `tmrca_ybp`.* These are independent point estimates under
     no monotonicity constraint; they agree with the parent's TMRCA on only **898 of 10,252 edges**,
     and **4,243 (41%)** have the child forming *earlier* than its parent's split — so children
     draw on top of their parents. `R-A13318` (formed 1622) landed at exactly its parent
     `R-S764`'s y.
  2. *Parent TMRCA → own TMRCA.* Monotone, so containment holds — but degenerate:
     `formed_ybp == tmrca_ybp` on **41% of terminal branches and 26.5% of internal ones**, collapsing
     the branch to a point. On R-DF85 at depth 4 that left **30 of 75 blocks unable to show a single
     one of their SNPs** — `R-BY18328` got 3px of span for 9 mutations, `R-BY170664` 16 SNPs in an
     18px sliver.

  SNP count never degenerates, and it is still a time axis: measured on this tree, branch length
  tracks SNP count at **r = 0.975, ≈69 years per mutation**. Ages keep their two real jobs — gating
  the view to the genealogical era, and labelling each block — they simply do not drive geometry,
  because a per-branch estimate is precisely what is missing or degenerate when a block most needs
  a height.

- **Colour belongs to the men, not the branches.** An early cut tinted each block by the composition
  of its descendants' origins. That asserts something the data does not support: a branch has no
  locality, only the men standing on it do, and a modal-origin tint reads as a claim about the whole
  lineage. Colour lives on each man's box, keyed to his own MDKA; the legend and table carry the
  composition that explains those colours.
- **Tips**: one leaf box per placed sample carrying an origin — `Kane · Co. Clare`, coloured by his
  own locality. Never the kit id. A man too narrow to label is counted rather than drawn as an
  unreadable sliver, and the count is stated.
- **"No locality recorded" is a visible category**, in the legend and the table, never bare
  background. A view of who published is not a view of where a branch is from, and the difference
  must be on screen.
- **Colours**: categorical, colourblind-safe, legible in both themes; assigned by frequency rank
  *within the rendered subtree* (deterministic, tie-broken on name), top N distinct + a neutral
  "other".
- **Era gate**: serves nodes with `tmrca_ybp <= 1500` (adjustable within bounds). Above the cutoff it
  renders the breadcrumb, one line of explanation, and links down to eligible children rather than
  drawing a block that means nothing.
- **Every branch in the window is drawn, origins or not.** Origins are an overlay on the tree, not
  a filter of it: a branch with no locality data is still part of the clade's shape, and hiding it
  would misrepresent the phylogeny to make a sparse overlay look dense. Legibility is bounded by
  the depth selector instead (default 4 levels), which folds rather than drops — folded branches
  are marked, and their men still count in their nearest drawn ancestor.
- **A man gets a box only where his own branch is drawn.** Attributed upward from a folded branch
  he still counts in the composition, but is not given a box under a branch that is not his; on
  R-DF85 one boundary block had otherwise absorbed 179 men and stacked them 90 rows deep. Drilling
  in draws him where he belongs.
- **De-novo nodes stay hidden but their men still count.** A sample placed on an auto-named node is
  attributed to the nearest named ancestor, as the public tree already does for sample tips.
  Dropping it instead made every band above it understate its own composition.
- **No silent caps.** Pruned branches, samples with no published origin, and the placed total are
  all stated on the page.

## 6. Phasing

1. `du_db::place` normalizer — pure, unit-tested. *(No wire format, nothing published.)*
2. Migration 0074 + `fed::ancestral_origin` + the jetstream arm + every gate in §2.
3. `du_db::origins` aggregate + `origins_layout` + route + template + i18n.
4. **Deferred, Navigator:** the lexicon's client half — `AncestralOriginRecord`, the surname
   splitter, and the publish predicate (the workspace holds primary data for the subject **and**
   `ftdna_member.publicly_shares = 1` or there is no roster row). Measured on the reference
   workspace: 583 Y MDKA rows sit on subjects with primary data, 558 of them publicly-sharing —
   **the 25 that are not must never publish.**

## 7. Open items

- **The precision ladder (§2.3)** is a proposed default, not a derived rule. It withholds place-level
  detail for the ~57% of MDKA rows with no birth year.
- **The 1900 ceiling** is a round number, not a legal standard. Cheap to set now, expensive to lower
  once records exist.
- **Retraction.** A withdrawn consent needs the PDS record deleted *and* the mirror tombstoned. The
  jetstream `delete` path (`jetstream.rs:163`) is the mechanism; the workflow is unspecified.
- **The view ships empty** until the Navigator half lands. That is a consequence of §2's bulk-load
  exclusion, not a defect.
- **mtDNA** costs almost nothing extra (the lexicon is lineage-keyed). Ship Y first and validate there.
