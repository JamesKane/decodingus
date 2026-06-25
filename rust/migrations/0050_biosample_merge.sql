-- Provenance-preserving biosample merge (Phase 3 of dedup).
--
-- When a curator (or auto-merge on a Tier-2-confirmed public duplicate) merges
-- two biosamples, du_db::dedup::merge_biosamples repoints every FK referencing
-- core.biosample(sample_guid) to the survivor, folds the merged row's metadata
-- into the survivor, tombstones the merged row (deleted = true, with a
-- source_attrs.merged_into pointer), and writes one audit row here.

CREATE TABLE core.biosample_merge (
    id             BIGSERIAL PRIMARY KEY,
    surviving_guid UUID NOT NULL REFERENCES core.biosample(sample_guid),
    -- No FK: the audit must outlive the tombstoned row even if it is later purged.
    merged_guid    UUID NOT NULL,
    -- The candidate that justified the merge (NULL for a manual/ad-hoc merge).
    candidate_id   BIGINT REFERENCES dedup.duplicate_candidate(id) ON DELETE SET NULL,
    -- Verdict, signals, accessions, and repoint counts captured at merge time.
    evidence       JSONB NOT NULL DEFAULT '{}'::jsonb,
    merged_by      TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT biosample_merge_distinct CHECK (surviving_guid <> merged_guid)
);

CREATE INDEX biosample_merge_surviving_idx ON core.biosample_merge (surviving_guid);
CREATE INDEX biosample_merge_merged_idx ON core.biosample_merge (merged_guid);
