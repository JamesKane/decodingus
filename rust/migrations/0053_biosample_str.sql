-- Locally-imported Y-STR profiles, keyed to a core.biosample (sample_guid).
--
-- The federation path (fed.str_profile → fed.biosample, mirrored from Navigator's
-- com.decodingus.atmosphere.strProfile) covers citizen samples that publish their
-- own STRs. It can't carry the academic / D2C cohorts: those live in
-- core.biosample (accession = the cohort subject_id) and are placed in the Y tree
-- via tree.haplogroup_sample, with no atproto identity. This table is their STR
-- home — bulk-loaded from vendor exports (FTDNA Y-DNA DYS results, …) and matched
-- to the cohort by accession.
--
-- Both sources feed the SAME STR-variance age model: du_db::ystr::build_str_inputs
-- UNIONs this table (joined live through tree.haplogroup_sample, so no stale
-- haplogroup-name copy) with fed.str_profile before grouping profiles per branch.
--
-- Markers are stored lossless as the lexicon's strMarkerValue[] JSONB — the same
-- shape parse_markers() reads — so simple + multi-copy score and complex is
-- preserved but unscored, exactly as for the federated profiles.

CREATE TABLE genomics.biosample_str_profile (
    sample_guid    UUID PRIMARY KEY REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    source         TEXT NOT NULL DEFAULT 'IMPORTED',  -- DIRECT_TEST / WGS_DERIVED / IMPORTED
    imported_from  TEXT,                              -- FTDNA / YSEQ / YFULL / ...
    panel          TEXT,                              -- vendor panel label (e.g. FTDNA Y-DNA DYS)
    total_markers  INTEGER NOT NULL DEFAULT 0,        -- non-null marker count in `markers`
    markers        JSONB NOT NULL DEFAULT '[]'::jsonb,-- lossless strMarkerValue[]
    source_file    TEXT,                              -- provenance: NAS path / kit dir
    imported_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX biosample_str_profile_source_idx ON genomics.biosample_str_profile (imported_from);
