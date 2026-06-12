# D3 — IBD Matching: Rust Implementation Spec

**Status:** Design (v0.1, 2026-06-12 — added §3.0/3d/3e scope control: ancestry
blocking + matches-of-matches graph expansion + query-vs-panel cold start, so
candidate generation is never N:N and the AppView emits only bounded top-K lists;
flagged the D1-independent first slice). AppView roadmap §5 D3. **Implements** the
original IBD requirements **on top of D1**
(`d1-encrypted-edge-exchange.md`) and the actual Rust schema; **supplies** D2's
genetic resolver (`d2-research-subject-registry.md` §4.2). This doc now carries the
requirements (the standalone planning doc was removed as superseded) and the build
spec. **Cross-repo:** AppView coordinator +
Navigator Edge analysis (`navigator-analysis/src/ibd.rs` already exists).

## 1. What changed since the planning doc (the refresh)

The original doc (Scala/Tapir era) invented its own crypto, key exchange, and P2P
channel. Three things change:

1. **The channel is D1, not bespoke.** IBD is now just a **consumer of the
   `exchange.*` substrate** with `purpose ∈ {IBD_AUTOSOMAL, IBD_X, IBD_Y, IBD_MT}`.
   Drop the IBD-specific ECDH/relay invention (D1 owns it). The planning doc's
   `ibd.match_request`/`match_consent` **fold into `exchange.exchange_request`/
   `exchange_consent`** (D1 §8); IBD-specific tables (`ibd_discovery_index`,
   `ibd_pds_attestation`, `match_suggestion`) stay.
2. **Rust, not Scala.** The four service traits become `du-db` query modules +
   `du-web` axum handlers + a `du-jobs` discovery job. Concrete SQL below.
3. **AppView mines candidates from `fed.*` anonymized aggregates only.** It never
   touches raw genotypes; the actual IBD segment detection is **Edge-to-Edge** over
   D1. (Unchanged in spirit from the doc's security section; made concrete here.)

## 2. Architecture (one line each)

- **AppView (coordinator, PII/genotype-free):** mine candidate pairs from `fed.*`
  → `match_suggestion`; broker request + **dual-consent** (via `exchange.*`/D1);
  notify both Edges "match-ready"; verify + index **attestations** (match *summaries*
  only); serve match-list API. Never sees a genotype.
- **Edge (Navigator, holds genotypes):** establish the D1 session; exchange encrypted
  variant positions / segment boundaries; run the IBD algorithm
  (`navigator-analysis::ibd`); cross-verify; sign + attest; classify relationship.

## 3. Candidate mining (the discovery engine) — `du-jobs` + `du-db::ibd`

A scheduled `du-jobs` job (`ibd-discovery-recompute`, alongside the existing
`branch-age-recompute`), incremental per sample. Three signals → `ibd.match_suggestion`
rows (existing table: `target_sample_guid`, `suggested_sample_guid`, `suggestion_type`,
`score`, `status`), ranked. All inputs are **anonymized `fed.*` / `ibd.*` aggregates**.

### 3.0. Scope control — block, don't pair (the load-bearing principle)

The AppView must **never materialize an N×N pair list**, and must **never hand a
Navigator client "everyone."** Each sample gets a **bounded, ranked, top-K candidate
list**; the Edge then runs IBD (or a query-vs-panel search) only against that K — so
each client is O(K), not O(N). Two cheap mechanisms keep candidate generation
near-linear (this is record-linkage *blocking* + graph expansion, not all-pairs
scoring):

- **Block by ancestry before scoring.** Bucket samples by a cheap key and only score
  *within* buckets: a coarse block on the continental rollup
  (`fed.population_breakdown.super_population_summary`) drops cross-continental pairs
  outright, and a finer block on the published **PCA coordinates**
  (`fed.population_breakdown.pca_coordinates` — grid-bin or LSH the PCA space) restricts
  the overlap computation to near neighbours. O(N²) → ~O(N·k).
- **Expand the match graph (matches-of-matches) as the steady state.** Once a sample
  has any confirmed edge in `ibd_discovery_index`, its best new candidates are its
  **2-hop neighbourhood** — cheap graph traversal, not pairing. Ancestry-blocking is
  only the **cold-start seeder**; graph expansion is the primary generator thereafter.

Cap to **top-K per sample** by combined score (`expires_at` ages out the rest). The
existing `ibd.population_overlap_score` table is therefore populated **only for
within-block pairs, incrementally as samples arrive** — never the full N². Research
backing: §3e.

### 3a. Haplogroup match (cheapest; gates the rest for Y/MT)
Same terminal Y or mt haplogroup ⇒ candidate patriline/matriline match.
```sql
-- suggestion_type = 'HAPLOGROUP'; region from which haplotype matched
INSERT INTO ibd.match_suggestion (target_sample_guid, suggested_sample_guid, suggestion_type, score, metadata)
SELECT a.sample_guid, b.sample_guid, 'HAPLOGROUP',
       depth_score(a.haplogroup, b.haplogroup),               -- deeper shared terminal = higher
       jsonb_build_object('region', 'Y', 'haplogroup', a.haplogroup)
FROM fed.haplogroup_reconciliation a
JOIN fed.haplogroup_reconciliation b
  ON a.dna_type = b.dna_type AND a.haplogroup = b.haplogroup AND a.sample_guid < b.sample_guid
WHERE a.dna_type = 'Y';   -- and again for 'Mt'
```

### 3b. Population overlap (autosomal candidate gate)
`Σ min(A[pop], B[pop])` over `ibd.population_breakdown`; **cached** in
`ibd.population_overlap_score`. Never compute the full N²: the `gated_pairs` set is the
**ancestry block** from §3.0 — same `super_population_summary` bucket **and** the same
PCA grid/LSH cell (`fed.population_breakdown.pca_coordinates`), plus the haplogroup
bucket for Y/mt-line requests. Score only those within-block pairs; persist
incrementally as samples join.
```sql
-- overlap from the cached breakdown JSONB; only for pre-gated pairs
WITH pair_overlap AS (
  SELECT s1, s2, SUM(LEAST(p1.frac, p2.frac)) AS score
  FROM gated_pairs g
  JOIN ibd.population_breakdown_cache c1 ON c1.sample_guid = g.s1, jsonb_each_text(c1.breakdown) p1(pop, frac_t)
  JOIN ibd.population_breakdown_cache c2 ON c2.sample_guid = g.s2, jsonb_each_text(c2.breakdown) p2(pop, frac_t)
  WHERE p1.pop = p2.pop  -- (frac cast to double)
  GROUP BY s1, s2)
INSERT INTO ibd.match_suggestion (...) SELECT s1, s2, 'POPULATION_OVERLAP', score, ...
FROM pair_overlap WHERE score >= :min_overlap;   -- default 0.6
```

### 3c. Shared-match (the **primary** generator once the graph is seeded)
The "in-common-with" / shared-match principle (the basis of every consumer clustering
tool — the Leeds Method, AutoClusters): samples that match the same third parties share
common ancestors. This is **2-hop graph expansion** over `ibd_discovery_index`, not
all-pairs scoring — cheap and high-yield, so it is the steady-state generator (§3.0).
```sql
-- over confirmed matches in ibd_discovery_index (the match graph)
SELECT a.other AS s1, b.other AS s2, COUNT(*) AS shared
FROM matches_of a JOIN matches_of b ON a.match = b.match AND a.other < b.other
GROUP BY a.other, b.other HAVING COUNT(*) >= :min_shared   -- default 2
-- → suggestion_type = 'SHARED_MATCH', score = shared count
```
(`matches_of` = a view unnesting `ibd_discovery_index` into (sample, matched-sample).)
**Endogamy caveat:** pedigree collapse / endogamous ancestries smear clusters together
(everyone shares everyone), inflating false candidates. Detect via PCA-cell density /
ancestry tag and **cap + down-weight** `SHARED_MATCH` there (and prefer larger
`min_shared`).

### 3d. Cold start = query-vs-panel, not panel-vs-panel
A brand-new sample has no graph edges to expand (§3c) — seed it from the **ancestry
block** (§3.0/3b) only. Critically, the Edge then does a **one-vs-many query against
that block as a panel**, not an N:N comparison (RaPID-Query-class search: a single
query against a biobank-scale panel in seconds, error-tolerant). The AppView's job is
to **supply the right panel subset** (the block) — never an all-pairs list. After the
first few confirmed matches land, the sample switches to graph expansion.

**Ranking & lifecycle:** combine the three scores (weighted), dedupe per pair, **cap to
top-K per target**, expire stale suggestions (`status` ACTIVE/DISMISSED/EXPIRED/
CONVERTED, `expires_at`). `du-db::ibd::suggestions_for(sample|did, limit)` serves them
ranked. The AppView emits only this bounded list — the no-N:N guarantee (§3.0).

### 3e. Research backing
- **Don't conflate detection with selection.** Genotype-level all-pairs IBD *detection*
  (PBWT family: RaPID, hap-IBD, 23andMe/Ancestry TPBWT, the newer kL-SMEM/PBML work) is
  the **Edge's** job — the AppView holds no genotypes. The AppView solves *candidate
  selection* (metadata blocking + graph expansion).
- **Query-vs-panel** ([RaPID-Query](https://pmc.ncbi.nlm.nih.gov/articles/PMC10244210/),
  [L-PBWT-Query](https://pmc.ncbi.nlm.nih.gov/articles/PMC6612857/)) is the Edge-side
  one-vs-many that makes a new joiner O(panel-query), not O(N²) — the basis of §3d.
- **Ancestry/PCA blocking** is standard record-linkage blocking (Christen, *Data
  Matching*) — the basis of §3.0/3b.
- **Shared-match clustering** ([Leeds Method](https://www.pricegen.com/dna-shared-matches-and-clustering/),
  [AutoClusters](https://www.gedmatch.com/blog/what-are-dna-autoclusters/)) is the basis
  of §3c, including the documented [endogamy failure mode](https://dna-explained.com/2025/07/10/how-to-use-ancestrys-new-match-clusters-and-what-they-mean/).

## 4. Request + dual-consent (on `exchange.*` / D1)

The planning doc's Phase 2 maps directly onto D1's request→consent gate; IBD adds only
the `purpose` and the discovery reason:

1. Requester writes a signed `exchange_request` PDS record (`purpose=IBD_*`,
   `details = {requesterSampleUri, discoveryReason, regionType}`) → AppView mirrors it
   (`exchange.exchange_request`), notifies target.
2. Both parties sign `exchange_consent`; AppView **verifies both signatures** (the
   dual-consent gate, D1 Invariant 2) and flips the request to `CONSENTED`.
3. AppView emits **exchange-ready** to both Edges (D1 §5 step 4) with `partnerDid` +
   `partnerExchangeKeyUri`.

`du-db::ibd` (or `du-db::exchange`): `create_request`, `record_consent`,
`mutual_consent(request_uri)`, `pending_for(did)`. `du-web` routes under
`/api/v1/exchange/*` (shared) with IBD-specific discovery context.

## 5. Edge handoff = a D1 session (the only IBD-specific Edge logic)

Once exchange-ready, Phase 3 *is* a D1 session — no bespoke channel:

1. D1 ECDH session (`purpose=IBD_Y` etc.), per D1 §4–5.
2. Exchange `payload_type ∈ {VARIANT_POSITIONS, SEGMENT_BOUNDARIES}` (D1 §7) over the
   blind relay.
3. **Both Edges run the IBD algorithm locally** (`navigator-analysis::ibd`):
   - **Autosomal/X:** IBD *segment* detection over shared positions → `{totalSharedCm,
     numSegments, largestSegmentCm}`.
   - **Y:** STR genetic distance + terminal-SNP concordance (patriline TMRCA estimate).
   - **MT:** HVR/coding mutation distance (matriline).
4. **Cross-verify:** both hash the canonical summary (SHA-256); matching hashes confirm
   a valid, agreed result (D1 §5 step 7a; planning doc Phase 3.3).
5. **Attest:** each signs the summary with its Ed25519 PDS key, writes an attestation
   record to its PDS.

The IBD algorithm itself is **Edge analysis** (Navigator), out of scope for AppView;
`navigator-analysis/src/ibd.rs` is its home. AppView only ever sees the *summary*.

## 6. Attestation indexing (`du-jobs` Jetstream + `du-db::ibd`)

AppView's Jetstream consumer already ingests `fed.*`; add the IBD attestation
collection. On both attestations for a request:
- `verify_attestations`: both Ed25519 signatures valid (`du-atproto::signature`) **and**
  `matchSummaryHash == partnerSummaryHash` (the two Edges agreed).
- Index `ibd.ibd_discovery_index` (pair, `match_region_type`, `total_shared_cm_approx`,
  `num_shared_segments_approx`, `consensus_status`) + two `ibd.ibd_pds_attestation`
  rows. Mark the `match_suggestion` `CONVERTED`.
- `update_consensus_status`: INITIAL_REPORT → CONFIRMED on matching dual attestation;
  DISPUTE on mismatch.

**Only summaries are indexed** — never positions, never genotypes (planning doc
Security; D1 Invariant 1).

## 7. Relationship classification → feeds D2's resolver

The IBD summary is classified into a relationship band (standard autosomal cM ranges),
Edge-side, and the band drives **both** the match UI **and** D2:

| Band | ~Shared | Action |
| --- | --- | --- |
| **Same person / identical** | ~full genome (autosomal) · Y+mt identical | **→ D2 §4.2 merge suggestion** (`subject_link method=GENETIC`), never auto |
| Parent/child, full sib | ~2550 / ~2550 cM | close-kin match; surface prominently |
| 2nd–4th cousin … | banded by cM | normal match list |
| Y-only / MT-only | patriline/matriline | lineage match (no autosomal claim) |

So D3 *is* D2's genetic resolver: a confirmed **same-person** (or near-identical)
classification emits a pseudonymous merge suggestion into the ResearchSubject layer
(`research.subject_link`, method `GENETIC`, with the confidence), which the group
accepts via a `same_person` assertion (D4). Close-kin bands stay in the match list,
not the subject-merge path.

## 8. API surface (`du-web`, axum + utoipa)

```
GET  /api/v1/ibd/suggestions?limit=         -> ranked match_suggestion[]   (auth: owner DID)
POST /api/v1/ibd/suggestions/:id/dismiss
POST /api/v1/exchange/requests              -> create (purpose=IBD_*)       (shared w/ D1)
GET  /api/v1/exchange/requests/pending
POST /api/v1/exchange/consent
POST /api/v1/ibd/attestation                -> Edge submits signed summary  (planning doc §4.4)
GET  /api/v1/ibd/matches?sample=            -> confirmed matches (summaries only)
GET  /api/v1/ibd/matches/:a/:b
```
DTOs: `MatchSuggestionDto`, `MatchDto {totalSharedCm, numSegments, largestSegmentCm,
regionType, consensusStatus}`, `AttestationSubmission`. No genotype/position DTOs exist
by construction.

## 9. Schema deltas

- `ibd.*` (mig 0007) mostly stands: `ibd_discovery_index`, `ibd_pds_attestation`,
  `match_suggestion`, `population_*`, `validation_service` — keep.
- **Generalize** `ibd.match_request`/`ibd.match_consent` → `exchange.exchange_request`/
  `exchange_consent` (D1 §8) via a migration; an `ibd` *view* over `purpose='IBD_*'`
  preserves call sites if useful.
- Add the **IBD attestation Jetstream collection** to the consumer's
  `INGEST_COLLECTIONS`.
- `match_suggestion` already has `metadata JSONB` for discovery reason — no change.

## 10. Module placement

- **AppView:** `du-db::ibd` (mining SQL, suggestions, match indexing, attestation
  verify), `du-web::routes::ibd` (+ shared `exchange` routes), `du-jobs`
  `ibd-discovery-recompute` job + attestation ingest in the Jetstream consumer.
- **Navigator (Edge):** `navigator-analysis::ibd` (the segment/distance algorithms),
  `navigator-sync` (D1 session driver + attestation publish), reusing D1's
  `du-exchange`.
- **Shared:** `du-domain` for the relationship-band thresholds + canonical
  summary-hash format (so Edge and AppView agree on what's signed).

## 11. Privacy invariants (restate, they're load-bearing)

- AppView mines candidates from **anonymized `fed.*` aggregates** only (haplogroups,
  population breakdowns, the match graph). No raw genotype ever reaches it.
- Edge-to-Edge exchange carries positions/segments **encrypted via D1**; AppView sees
  only signed **summaries** (cM, segment counts).
- Same-person merge suggestions to D2 are **pseudonymous** (`research_subject_id`s),
  never carrying an identifier.

## 12. Open questions / decisions

1. **IBD algorithm provenance** — does `navigator-analysis::ibd` implement segment
   detection from scratch, or wrap a known method? (Affects Edge effort, not AppView.)
2. **Phasing requirement** — autosomal IBD wants phased haplotypes; do we require
   phasing on the Edge, or do unphased segment detection (lower precision)?
3. **Shared-match cold start** — 3c needs an existing match graph; bootstrap from
   3a/3b only until the graph fills. Confirm acceptable.
4. **Population-overlap N² control** — the pre-gate (3b) must keep pair counts sane;
   define the gate (haplogroup/region bucket) precisely.
5. **`match_request`→`exchange_request` migration timing** — do it with D1's schema or
   lazily. Recommend with D1 (one migration).

## 13. Next step

D3 closes the **Match track** (D1→D3). **D1-independent first slice:** candidate
generation (§3) needs *no* exchange channel — it reads `fed.population_breakdown`
(PCA coords + components) and the `ibd_discovery_index` graph and writes ranked
`match_suggestion` rows. So `du-jobs ibd-discovery-recompute` (the §3.0 ancestry-block
+ §3c graph-expansion engine, top-K capped) is **buildable now**, ahead of D1, and
testable on seeded `fed.*` rows. The exchange/consent reuse of D1 + the
attestation-ingest/index path layer on once D1's channel exists. Then the **Platform
track** continues at **D4 (assertion store, split rails)**, which consumes D3's
same-person output (§7) and D1's PII channel.
