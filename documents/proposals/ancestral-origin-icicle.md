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
- **Height = elapsed years**: a branch spans **its parent's TMRCA → its own TMRCA**, on one absolute
  calendar axis. This is the deliberate divergence from Navigator's SNP-count height, and the reason
  to build the view here: the AppView has ages (`tmrca_ybp` on 10,257 of 11,422 Y nodes) and the
  framing is temporal. Nodes with no age draw at a minimum height, hatched, and are excluded from
  the ruler — visible, not silently normal.

  **Do not use a node's own `formed_ybp` for the top of its band.** It is the obvious choice and it
  is wrong: `formed_ybp` and the parent's `tmrca_ybp` are independent point estimates under no
  monotonicity constraint, and on the live tree they agree on only **898 of 10,252 edges** while
  **4,243 (41%) have the child forming earlier than its parent's split**. Driving geometry from it
  draws children on top of their parents — caught by rendering the real tree, where `R-A13318`
  (formed 1622) landed at exactly its parent `R-S764`'s y. Parent-TMRCA → own-TMRCA has **zero**
  inversions over the same edges, so containment holds by construction.
- **Fill = stacked locality composition** of the placed samples at or below the block, at the
  selected level (Country / Admin1 / Place), with **"no locality recorded" always its own visible
  slice**. A view of who published is not a view of where a branch is from, and the difference must
  be on screen.
- **Tips**: one leaf box per placed sample carrying an origin — `Kane · Co. Clare`, coloured to
  match. Never the kit id.
- **Colours**: categorical, colourblind-safe, legible in both themes; assigned by frequency rank
  *within the rendered subtree* (deterministic, tie-broken on name), top N distinct + a neutral
  "other".
- **Era gate**: serves nodes with `tmrca_ybp <= 1500` (adjustable within bounds). Above the cutoff it
  renders the breadcrumb, one line of explanation, and links down to eligible children rather than
  drawing a block that means nothing.
- **Pruned to the branches that carry an origin**, with the count reported. A branch with no
  published origin beneath it is a column of width and no information: on `R-S764` the unpruned
  draw was 175 bands across a 7,944px canvas to show 10 origins; pruned it is 37 bands in 768px.
  The drawn depth therefore follows the data rather than a fixed window — which also means no
  sample is lost for sitting below a cut-off.
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
