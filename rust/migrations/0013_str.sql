-- Y-STR profiles + per-branch modal signatures.
--
-- Product goal: aggregate STR "signatures" per SNP-defined branch (haplogroup)
-- for STR→branch prediction, and surface a nudge for STR-only testers to upgrade
-- to WGS (which resolves the branch at SNP level). STR is Y-DNA only.
--
-- Federation: Navigator publishes com.decodingus.atmosphere.strProfile records;
-- the Jetstream consumer mirrors them here (summaries only, like the other fed.*
-- tables). Markers are stored lossless as JSONB (the lexicon's union of simple /
-- multi-copy / complex values); scoring (modal + distance) handles simple +
-- multi-copy, complex is preserved but unscored in v1.

CREATE TABLE fed.str_profile (
    did              TEXT NOT NULL,
    rkey             TEXT NOT NULL,
    at_uri           TEXT NOT NULL,
    cid              TEXT,
    biosample_ref    TEXT,                  -- at-uri of the parent biosample (join key)
    sequence_run_ref TEXT,                  -- set when STRs were WGS-derived
    source           TEXT,                  -- DIRECT_TEST / WGS_DERIVED / BIG_Y_DERIVED / IMPORTED / MANUAL_ENTRY
    imported_from    TEXT,                  -- FTDNA / YSEQ / YFULL / ...
    derivation_method TEXT,                 -- HIPSTR / GANGSTR / ... (WGS-derived)
    total_markers    INTEGER,
    markers          JSONB NOT NULL DEFAULT '[]'::jsonb,  -- lossless strMarkerValue[]
    record_created_at TIMESTAMPTZ,
    time_us          BIGINT NOT NULL,
    indexed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);
CREATE INDEX str_profile_biosample_idx ON fed.str_profile (biosample_ref) WHERE biosample_ref IS NOT NULL;
-- "WGS-derived?" — drives the STR-only→WGS upgrade nudge (Phase 2).
CREATE INDEX str_profile_source_idx ON fed.str_profile (source);

-- Widen the per-branch ancestral/modal signature for multi-copy/complex values
-- and aggregation provenance. `ancestral_value` stays as the fast integer path
-- for simple markers (now nullable: multi-copy/complex live in `ancestral_json`).
ALTER TABLE tree.haplogroup_ancestral_str
    ALTER COLUMN ancestral_value DROP NOT NULL,
    ADD COLUMN ancestral_json   JSONB,        -- the modal strValue (simple/multiCopy)
    ADD COLUMN supporting_samples INTEGER,    -- observations backing this marker's modal
    ADD COLUMN recomputed_at     TIMESTAMPTZ;
-- One signature row per (haplogroup, marker) so the recompute job can upsert.
ALTER TABLE tree.haplogroup_ancestral_str
    ADD CONSTRAINT haplogroup_ancestral_str_hg_marker_uniq UNIQUE (haplogroup_id, marker_name);
