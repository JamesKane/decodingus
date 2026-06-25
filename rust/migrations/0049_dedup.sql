-- Biosample duplicate-detection — Tier-1 candidate store.
--
-- The dedup engine (du_db::dedup) blocks biosamples by terminal Y + mt
-- haplogroup and refines with private-variant Jaccard to surface *candidate*
-- duplicate pairs. A candidate is NOT a confirmed duplicate: uniparental markers
-- plus shared private SNPs cannot separate a duplicate from a sibling/patriline
-- (empirically demonstrated — see documents/proposals/biosample-duplicate-detection.md).
-- Confirmation is the autosomal Tier-2 step (IBS0/IBD2), recorded later in `verdict`.

CREATE SCHEMA IF NOT EXISTS dedup;

CREATE TABLE dedup.duplicate_candidate (
    id          BIGSERIAL PRIMARY KEY,
    -- Canonical unordered pair: sample_a < sample_b (enforced) so a pair = one row.
    sample_a    UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    sample_b    UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    -- Evidence class that generated the candidate. 'UNIPARENTAL' = Tier 1
    -- (Y+mt block ± private-variant Jaccard); leaves room for future tiers.
    tier        TEXT NOT NULL DEFAULT 'UNIPARENTAL',
    -- The block that produced the pair (e.g. 'Y=146291;MT=27232'), for triage.
    block_key   TEXT,
    -- Suspicion score in [0,1] — a RANKING, not a probability of duplication.
    score       DOUBLE PRECISION NOT NULL DEFAULT 0,
    -- Per-signal detail (mt match, shared-private count, jaccard, set sizes,
    -- accessions, public flags) — drives curator triage + Tier-2 routing.
    signals     JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Lifecycle. The engine writes/refreshes CANDIDATE only; every other status is
    -- a human/Tier-2 decision the engine must never overwrite.
    --   CANDIDATE                | Tier-1 suspicion, unconfirmed
    --   CONFIRMED_DUPLICATE      | Tier-2 IBS0 ~= 0 & high kinship
    --   RELATIVE                 | Tier-2 found kin (IBS0 > 0) — not a duplicate
    --   DISTINCT                 | Tier-2 low kinship — dismiss
    --   SUSPECTED_UNCONFIRMABLE  | no autosomal data to confirm/refute
    --   MERGED                   | curator merged the pair
    --   DISMISSED                | curator dismissed
    status      TEXT NOT NULL DEFAULT 'CANDIDATE',
    -- Tier-2 autosomal result (relatedness, ibs0_rate, panel, n_sites, verdict).
    verdict     JSONB,
    resolved_by TEXT,
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT dedup_candidate_ordered CHECK (sample_a < sample_b),
    CONSTRAINT dedup_candidate_pair_key UNIQUE (sample_a, sample_b)
);

CREATE INDEX dedup_candidate_status_idx ON dedup.duplicate_candidate (status);
CREATE INDEX dedup_candidate_a_idx ON dedup.duplicate_candidate (sample_a);
CREATE INDEX dedup_candidate_b_idx ON dedup.duplicate_candidate (sample_b);
