# De-novo Y / mtDNA tree ingestion

**Status:** proposed (2026-06-17). Supersedes the ISOGG-import tree foundation.

## Why

The tree foundation is moving from **importing ISOGG** (a curated external
nomenclature) to **ingesting a tree we build ourselves** from genotypes. The
de-novo pipeline at `~/Genomics/ytree` joint-calls chrY + chrM across four
cohorts on CHM13v2 (hs1), builds an IQ-TREE ML tree, and runs marginal
ancestral-state reconstruction to derive the defining SNPs of every branch —
i.e. it produces an ISOGG-shaped tree (nodes + per-branch SNPs) but grounded in
real data on a modern reference.

This retires a whole class of problems at once: the ISOGG `~`-fold corruption,
the cross-source SNP-anchor graft, the YCC-longhand rename, the legacy GRCh37
coordinate frame, and the absence of an mt tree. The de-novo tree **is** the
foundation; nothing is grafted onto it. The clearing front-end is the
`du_db::haplogroup::reset_tree` / `tree-init --reset` work already in hand.

The current artifacts are a **1,742 chrY (male) + 3,344 chrM** workflow-correctness
batch. A more complete tree (adding the remaining HGDP + SGDP samples) follows;
ingestion must therefore be **re-runnable as a clean replace**, not a merge.

## Decisions (locked)

1. **Seam = pipeline-side JSON export + Rust loader.** A new `68_export_ingest.py`
   in `~/Genomics/ytree/bin` emits one normalized JSON per chromosome; a Rust
   loader consumes it. Same architecture as the ISOGG `isogg_to_json.rb → json →
   tree-init` seam — it decouples the phylo pipeline from our DB schema so neither
   side breaks the other. (The historical bug was the import *logic*, not this
   seam.)
2. **Node naming = ISOGG / PhyloTree label primary.** Display name is the
   compare-mapped label (`R-M269`, `B-Z43718`) where one exists; synthesized from
   the node's strongest defining SNP otherwise. The de-novo `NodeN` id and the
   ISOGG-clade mapping are retained in provenance.
3. **Initial scope = topology + defining SNPs first** (Y, then mt). Sample-leaf
   placement and the curation surface are follow-ups.
4. **Coordinate frame = CHM13v2 / hs1.** Variants link to `core.variant` by hs1
   coordinate, reusing the YBrowse-loaded catalog so known SNPs inherit their
   names; novel de-novo SNPs are created.
5. **Greenfield replace.** Each ingest clears `tree.*` (`reset_tree`) and loads
   the de-novo tree as the sole foundation. No ISOGG/decoding-us/FTDNA layers.

## Source artifacts (per chromosome)

All under `~/Genomics/ytree/`. Y shown; mt mirrors it (`chrM.asr.*`, `mt_*`).

| File | Role |
|---|---|
| `results/chrY.asr.treefile` | Newick; internal `NodeN/<UFBoot>`, tips = sample IDs, branch lengths. **The topology.** |
| `results/chrY.asr.branch_transitions.tsv` | per branch `parent→child`: `n_mut`, `n_reversion`, `chrY:pos anc>der` list |
| `results/chrY.asr.snp_assignments.tsv` | per (branch, SNP): `chrom,pos,ref,alt,ancestral,derived,parent,child,to_alt,reversion,anc_chimp,polarity` |
| `compare/internal_node_labels.tsv` | `our_node(NodeN) → isogg, label, markers_matched, markers_expected` — the **display name** + mapping |
| `compare/tip_haplogroup_calls.tsv` | `sample → terminal_isogg, terminal_label, balance, path_derived, path_ancestral, path` — leaf metadata |
| `compare/conflict_triage.tsv` | `isogg,label,n_tips,magnitude,members_away,foreign_in,home_node` — curation conflicts vs ISOGG |
| `results/chrY.callable_mask.chm13v2.bed` | Poznik-style call mask (region reliability) |
| `manifests/samples.tsv` | `sample, cohort, cram_path, sex` — tip → biosample provenance |

We ingest the **publication tree** (`results/chrY.asr.publication.treefile`): the
full ML tree collapsed under the builder's **keep-set rule** and QC-failed tips
pruned (HG02772). A node survives iff **(UFBoot ≥ 95 AND it carries ≥1 defining
mutation) OR it is a primary best-clade haplogroup placement** (`compare/chrY.keepset.tsv`).
The keep-set is essential: rapid Y expansions give real, named macro-clades (R, R1,
R1b — all UFBoot ≈ 80) only moderate bootstrap, so a pure UFBoot ≥ 95 rule **gutted
the named backbone** (R-CTS4466 dangled directly under IJK). The keep-set preserves
named clades even at moderate support while still collapsing anonymous weak nodes; it
also dedupes recurrent placements to one node per haplogroup (e.g. spurious DF13
`Node494` collapses; real DF13 `Node423` is kept). The **`n_mut ≥ 1` clause** (from
`*.asr.branch_transitions.tsv`) drops zero-mutation bifurcations that UFBoot
over-supported — the mtDNA "0 defining variant" placeholder nodes (`Node82`, `Node110`,
…); their named children reattach to the parent as polytomies, so no tips are lost and
every named clade survives. Because the exporter derives survival from the publication
treefile itself, this refinement needed **no loader/exporter change** — only a
re-export + reload once the builder regenerated the treefiles. The exporter reads the surviving `NodeN`
set **directly from the publication treefile** (all artifacts share the full-tree
`NodeN` namespace, so survivors keep their ids; SNPs/labels/tips still join by
`NodeN`) rather than re-deriving the collapse. For chrY: **1,203 internal nodes**
(from 1,740) + 1,741 tips; R-CTS4466 nests at depth 21.

**Naming** (owned import-side; the keep-set/labels provide the clade identity, we
format the display name). Per node, in order: **backbone/macro clade verbatim** if
the clade (keep-set / `isogg`) has no lowercase — `A`, `IJK`, `BT`, `CT`, `NO`,
`K2` (the comparison's `<clade[0]>-<SNP>` label mangles these: `IJK→I-M2696`);
else the `<major>-<SNP>` `label` from `internal_node_labels` for ISOGG subclade
longhands (`R1b1a…→R-L389`); else the node's own catalog-matched defining SNP
(resolves `Node423→DF13`, `Node341→P312`); else a synthetic `chrY:<pos><a>><d>`
coordinate name (≈106 genuinely novel de-novo clades with no known SNP); the root
keeps its `NodeN`. Result: 1,097/1,204 (91%) carry proper haplogroup/SNP names.

**Collapsed-branch SNPs (the one subtle point).** Collapsing 682 weak nodes
orphans 8,919 defining SNPs whose true MRCA node no longer exists. Policy
(decided): a collapsed branch's SNPs **lift to the nearest surviving ancestor** as
a tagged *unresolved* block in that node's provenance — **not** strict defining
links (the ancestor's other children don't carry them; exact placement in the
subtree is unresolved). Surviving nodes keep only their **own** branch SNPs as
defining links. This preserves every SNP exactly once (85,955 defining + 8,919
unresolved = 94,874) with no link bloat and no invented homoplasy.

## The contract — normalized ingest JSON

`68_export_ingest.py` joins the treefile + the three result TSVs + the two
compare TSVs + the manifest into one file per chromosome:

```jsonc
{
  "chromosome": "chrY",            // | "chrM"
  "haplogroupType": "Y_DNA",       // | "MT_DNA"
  "build": "chm13v2.0",
  "source": "decodingus-denovo",
  "root": "Node1408",
  "run": { "tips": 1742, "model": "GTR+ASC", "rooting": "polarize",
           "ufboot": 1000, "date": "2026-06-17" },
  "nodes": [
    {
      "id": "Node1409",            // stable de-novo NodeN id
      "parent": "Node1408",        // null at root
      "support": 100,              // UFBoot → confidence_level
      "branchLength": 0.007374,
      "label": "B-Z43718",         // mapped display name (null ⇒ synthesize)
      "isogg": "B3",               // mapped clade (provenance), nullable
      "markersMatched": 502, "markersExpected": 521,
      "nMut": 1924, "nReversion": 205,
      "definingVariants": [          // this node's OWN branch → haplogroup_variant links
        { "chrom":"chrY","pos":2472503,"ref":"A","alt":"T",
          "ancestral":"A","derived":"T","reversion":false,"polarity":"forward" }
      ],
      "unresolvedVariants": [        // collapsed sub-branch SNPs → provenance block, NOT links
        { "chrom":"chrY","pos":2480028,"ref":"C","alt":"T",
          "ancestral":"C","derived":"T","reversion":false,"polarity":"forward" }
      ]
    }
  ],
  "tips": [                        // phase 3 (leaf placement)
    { "sample":"Ale22","parentNode":"Node1640","cohort":"PRJEB9586","sex":"male",
      "terminalLabel":"J-Y27554","terminalIsogg":"J2a1a2b1~",
      "balance":388,"pathDerived":391,"pathAncestral":3 }
  ],
  "conflicts": [
    { "isogg":"A1b","label":"A-P108","nTips":1733,"magnitude":1,
      "homeNode":"Node1404","foreignIn":1,"membersAway":0 }
  ]
}
```

Notes for the exporter:
- A tip's **placement** is its parent `NodeN` in the Newick (not the ISOGG
  `path` — that is naming/validation metadata only).
- `definingVariants` come from `branch_transitions` cross-joined with
  `snp_assignments` (the latter supplies `reversion`/`polarity`/`anc_chimp`).
- Emit the full-tree node set; carry `support` so the AppView can collapse by
  UFBoot for display rather than us discarding low-support structure.

## Rust loader

`decodingus-tree-init --denovo-y <json> --apply` (and `--denovo-mt`), a new
foundation path beside the existing `--isogg`:

1. **Clear** — `reset_tree(pool)` (handles the `core.variant.defining_haplogroup_id`
   FK + derived recurrence rows).
2. **Nodes** → `tree.haplogroup`: `name` = `label` (disambiguated with `NodeN`/top
   SNP on collision), `haplogroup_type`, `source='decodingus-denovo'`,
   `confidence_level` from UFBoot, `provenance` = `{ node_id, isogg, markers_matched,
   markers_expected, support, branch_length, n_mut, n_reversion }`.
3. **Edges** → `tree.haplogroup_relationship` (parent→child, `source`).
4. **Variants** → `core.variant` get-or-create **by hs1 coordinate**
   (`coordinates` = `{build:'chm13v2.0', chrom, position, ancestral, derived}`):
   reuse the YBrowse/ISOGG catalog row when (chrom,pos,ref,alt) matches so known
   SNPs keep their `canonical_name`; else create a de-novo-named variant.
   Link via `tree.haplogroup_variant` (`ancestral_allele`/`derived_allele`);
   `reversion`/`polarity` → `annotations`.
5. **Post** — `recompute_backbone`; bump `tree_revision`. (No `reconcile_tilde_twins`,
   no graft, no rename — all ISOGG-specific.)

Reuses the existing engines: `tree_revision` (cache ETag), `recompute_backbone`,
and — for phase 3 — `tree_sample` (mig 0037) for the tip leaves.

## Phasing

1. **Y topology + SNPs — DONE.** exporter + loader; 1,204 nodes; validated against
   `compare/summary.md` anchors (A–T at best-clade F1 ≈ 1.00; R-CTS4466 spine at depth 21).
2. **mt tree — DONE.** Exporter generalized to `build(chrom)` + `CONFIGS`. mt differs:
   rooted at the human MRCA `Node1767` (RSRS), the **CHIMP outgroup tip is dropped**,
   there is **no `internal_node_labels`** (mt clade names `L0`/`H1a1`/`U5b2a1` are
   the display form, taken verbatim from `chrM.keepset.tsv`), and the tip/conflict
   TSVs use `mt_haplogroup` columns. **1,765 nodes / 3,344 tips** (after the `n_mut ≥ 1`
   keep-rule refinement dropped the empty 0-mutation placeholders; was 2,015); catalog has no mt
   variants so all SNPs mint. Loader uses **`clear_dna(dna)`** (dna-scoped, FKs are
   NO ACTION → delete dependents first) so **Y and mt coexist**;
   `tree-init --denovo-mt <json> --apply`. Verified: `H1→H→HV→R→N→L3→…→RSRS`,
   served at `/api/v1/mt-tree`.
3. **Sample leaves — DONE.** `tips[]` → get-or-create `core.biosample` **by accession**
   (deduped across lineages: a male is one biosample with a Y *and* an mt placement)
   → `tree.haplogroup_sample` under the known `parentNode` (direct placement, not
   call-resolution). `PRJEB*` cohorts EXTERNAL/public, own genome STANDARD/private.
   3,344 biosamples; Y 1,741 + mt 3,344 placements; reuses the mig-0037 leaf machinery
   (`sample_count` + `…/node/{name}/samples`). WGS229 → `R-S1128`/`U5a1b1`; R-S1128 leaf
   set = {NA20278, NA20279, WGS229} (matches the SCALEUP.md anchor).
4. **Curation — DONE (conflicts).** `conflicts[]` → `tree.denovo_conflict` (mig 0039),
   populated by the loader and replaced per-lineage (cleared by `clear_dna`/`reset_tree`).
   Read-only Curator queue at **`/curator/denovo-conflicts`** (page + HTMX fragment,
   lineage filter, worst-magnitude first) via `du_db::denovo::list_conflicts`; dashboard
   card + i18n (en/es/fr). 88 Y + 37 mt conflicts.
   **Call mask — deferred.** The chrY Poznik mask is **12,986 fine intervals** — a poor
   fit for `core.genome_region`'s ~85-row named-region model, and de-novo variants are all
   *in-mask* by construction (redundant). The right home is a dedicated callable-interval
   representation / coverage-norm, designed separately.

## Validation gates

- Node + edge counts match the treefile; single root; fully reachable; no
  multi-parent edges.
- Macro clades A–T present and monophyletic-ish (F1 ≈ 1.00 per `summary.md`).
- Spot anchors: `R-M269` clade size ≈ 289; `WGS229` terminal `R-FGC29076` on the
  L21 path; mt `WGS229 → U5a1b1g`.
- Defining-SNP reuse rate against the hs1 catalog (how many known vs novel).

## Deferred / open

- **HGDP + SGDP scale-up** — re-export + re-ingest when the fuller tree lands
  (`SCALEUP.md`); ingestion is a clean replace by design.
- **Node-name uniqueness policy** — when two de-novo nodes map to the same ISOGG
  label (finer de-novo splits), the disambiguation rule (suffix `NodeN` vs top
  SNP) needs a final call during phase 1.
- **Novel-SNP naming** — convention for de-novo SNPs absent from the catalog
  (position-based vs node-anchored).
- **Branch lengths / ages** — `branchLength` is substitutions/site; feeding the
  branch-age-estimation model is a later concern.
