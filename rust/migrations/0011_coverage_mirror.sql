-- Federated coverage mirror (atmosphere doc 08 §3, REVISED 2026-06).
--
-- Original re-scope said "no persistent mirror — aggregate coverage on demand."
-- That doesn't scale: a population coverage view would have to fan out an HTTP
-- fetch to every PDS at query time. So the AppView KEEPS a mirror of the public
-- coverage *summaries* (QC metrics only — never raw reads/files). A lightweight
-- Jetstream consumer (du-jobs) ingests com.decodingus.atmosphere.alignment
-- summary records; population views then aggregate this table with cheap local
-- SQL, exactly like the local genomics.alignment_metadata benchmark path.
--
-- This is NOT the old full-CRUD network mirror: one collection, summary metrics
-- only, no per-sample raw data, no orphan/sync machinery.

CREATE TABLE fed.coverage_summary (
    did             TEXT NOT NULL,                 -- owning PDS / citizen DID
    collection      TEXT NOT NULL,                 -- lexicon NSID (com.decodingus.atmosphere.alignment)
    rkey            TEXT NOT NULL,                 -- record key (tid)
    at_uri          TEXT NOT NULL,                 -- at://{did}/{collection}/{rkey}
    cid             TEXT,                          -- commit cid (provenance / refetch)
    biosample_ref   TEXT,                          -- alignment.biosampleRef (denormalized cohort key)
    sequence_run_ref TEXT,                         -- alignment.sequenceRunRef
    reference_build TEXT,                          -- GRCh38 / GRCh37 / T2T-CHM13 / ...
    aligner         TEXT,
    -- Extracted scalars for indexed aggregation; the authoritative copy (incl.
    -- per-contig callable bases) stays in `metrics`.
    mean_coverage   DOUBLE PRECISION,
    median_coverage DOUBLE PRECISION,
    pct_10x         DOUBLE PRECISION,
    pct_20x         DOUBLE PRECISION,
    pct_30x         DOUBLE PRECISION,
    metrics         JSONB NOT NULL DEFAULT '{}'::jsonb,  -- full alignmentMetrics (incl. contigs[])
    record_created_at TIMESTAMPTZ,                 -- record meta.createdAt, if present
    time_us         BIGINT NOT NULL,               -- Jetstream cursor of the event that wrote this row
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, collection, rkey)
);

CREATE INDEX coverage_summary_build_idx ON fed.coverage_summary (reference_build);
CREATE INDEX coverage_summary_mean_idx ON fed.coverage_summary (mean_coverage);
CREATE INDEX coverage_summary_biosample_idx
    ON fed.coverage_summary (biosample_ref) WHERE biosample_ref IS NOT NULL;

-- Singleton Jetstream cursor so the consumer resumes from where it left off after
-- a restart/reconnect (Jetstream replays from a `time_us` microsecond timestamp).
CREATE TABLE fed.jetstream_cursor (
    id          BOOLEAN PRIMARY KEY DEFAULT true,
    time_us     BIGINT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT jetstream_cursor_singleton CHECK (id)
);
