-- Federated reporting mirror (atmosphere "Record Status Summary": the legacy
-- AppView's `✅ AppView Complete` ingest set). The AppView does NOT analyze — it
-- aggregates and reports. Navigator computes anonymized per-sample SUMMARIES at
-- the edge and publishes them as public PDS records; a Jetstream consumer
-- (du-jobs) mirrors them here so reports aggregate with local SQL.
--
-- This is the in-scope ingestion the v2.1 "scope reduction" wrongly dropped. It
-- is NOT the legacy full-CRUD raw-data mirror: summaries only, no raw reads/files,
-- and donor PII is never stored.
--
-- PRIVACY: PII-bearing records (biosample/sequencerun/project/workspace) are
-- reduced to typed, non-identifying columns — NO raw record JSONB is kept, so
-- donorIdentifier / sampleAccession / description / file paths can never leak.
-- Pure-analytics records (genotype/populationBreakdown/haplogroupReconciliation)
-- keep the computed payload as JSONB (with `files` stripped on ingest).
--
-- Every table is keyed (did, rkey) — one collection per table — for idempotent,
-- ordered (time_us) upsert from the firehose. `fed.coverage_summary` (alignment,
-- migration 0011) is the sibling already in place.

-- Biosample — anonymized: pseudonymous DID, sex, Y/mt haplogroup calls, center,
-- and join refs. Donor identifiers/accession/free-text description are dropped.
CREATE TABLE fed.biosample (
    did             TEXT NOT NULL,
    rkey            TEXT NOT NULL,
    at_uri          TEXT NOT NULL,
    cid             TEXT,
    sex             TEXT,                  -- Male/Female/Other/Unknown
    y_haplogroup    TEXT,                  -- haplogroups.yDna.haplogroupName
    mt_haplogroup   TEXT,                  -- haplogroups.mtDna.haplogroupName
    center_name     TEXT,                  -- sequencing center (not donor PII)
    population_breakdown_ref TEXT,
    str_profile_ref TEXT,
    sequence_run_count INTEGER NOT NULL DEFAULT 0,
    genotype_count  INTEGER NOT NULL DEFAULT 0,
    record_created_at TIMESTAMPTZ,
    time_us         BIGINT NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);
CREATE INDEX fed_biosample_y_idx ON fed.biosample (y_haplogroup) WHERE y_haplogroup IS NOT NULL;
CREATE INDEX fed_biosample_mt_idx ON fed.biosample (mt_haplogroup) WHERE mt_haplogroup IS NOT NULL;
CREATE INDEX fed_biosample_center_idx ON fed.biosample (center_name);

-- Sequence run — platform/instrument/test characterization (no files, no PII).
CREATE TABLE fed.sequencerun (
    did             TEXT NOT NULL,
    rkey            TEXT NOT NULL,
    at_uri          TEXT NOT NULL,
    cid             TEXT,
    biosample_ref   TEXT,
    platform_name   TEXT,                  -- ILLUMINA/PACBIO/NANOPORE/...
    instrument_model TEXT,
    instrument_id   TEXT,                  -- @RG instrument id (crowdsourced lab inference)
    test_type       TEXT,                  -- WGS/EXOME/TARGETED/...
    library_layout  TEXT,                  -- PAIRED/SINGLE
    total_reads     BIGINT,
    read_length     INTEGER,
    mean_insert_size DOUBLE PRECISION,
    record_created_at TIMESTAMPTZ,
    time_us         BIGINT NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);
CREATE INDEX fed_sequencerun_platform_idx ON fed.sequencerun (platform_name);
CREATE INDEX fed_sequencerun_testtype_idx ON fed.sequencerun (test_type);

-- Project — surname/research project grouping (project-level, not donor PII).
CREATE TABLE fed.project (
    did              TEXT NOT NULL,
    rkey             TEXT NOT NULL,
    at_uri           TEXT NOT NULL,
    cid              TEXT,
    project_name     TEXT,
    administrator_did TEXT,
    member_count     INTEGER NOT NULL DEFAULT 0,
    record_created_at TIMESTAMPTZ,
    time_us          BIGINT NOT NULL,
    indexed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);

-- Workspace — researcher container; counts only.
CREATE TABLE fed.workspace (
    did             TEXT NOT NULL,
    rkey            TEXT NOT NULL,
    at_uri          TEXT NOT NULL,
    cid             TEXT,
    sample_count    INTEGER NOT NULL DEFAULT 0,
    project_count   INTEGER NOT NULL DEFAULT 0,
    record_created_at TIMESTAMPTZ,
    time_us         BIGINT NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);

-- Genotype — chip/array summary stats (computed; raw genotypes stay local).
CREATE TABLE fed.genotype (
    did             TEXT NOT NULL,
    rkey            TEXT NOT NULL,
    at_uri          TEXT NOT NULL,
    cid             TEXT,
    biosample_ref   TEXT,
    provider        TEXT,                  -- 23andMe/AncestryDNA/...
    test_type_code  TEXT,
    chip_version    TEXT,
    total_markers_called   INTEGER,
    total_markers_possible INTEGER,
    no_call_rate    DOUBLE PRECISION,
    y_markers_called INTEGER,
    mt_markers_called INTEGER,
    autosomal_markers_called INTEGER,
    het_rate        DOUBLE PRECISION,
    build_version   TEXT,
    y_haplogroup    TEXT,                  -- derivedHaplogroups.yDna.haplogroupName
    mt_haplogroup   TEXT,
    population_breakdown_ref TEXT,
    record          JSONB NOT NULL DEFAULT '{}'::jsonb,  -- full record minus `files`
    record_created_at TIMESTAMPTZ,
    time_us         BIGINT NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);
CREATE INDEX fed_genotype_provider_idx ON fed.genotype (provider);

-- Population breakdown — ancestry composition (33 pops / 9 super-pops / PCA).
CREATE TABLE fed.population_breakdown (
    did             TEXT NOT NULL,
    rkey            TEXT NOT NULL,
    at_uri          TEXT NOT NULL,
    cid             TEXT,
    biosample_ref   TEXT,
    analysis_method TEXT,                  -- PCA_PROJECTION_GMM/ADMIXTURE/...
    panel_type      TEXT,                  -- aims/genome-wide
    reference_populations TEXT,
    snps_analyzed   INTEGER,
    snps_with_genotype INTEGER,
    snps_missing    INTEGER,
    confidence_level DOUBLE PRECISION,
    components      JSONB NOT NULL DEFAULT '[]'::jsonb,  -- sub-continental percentages
    super_population_summary JSONB NOT NULL DEFAULT '[]'::jsonb,  -- continental rollup
    pca_coordinates JSONB,
    record_created_at TIMESTAMPTZ,
    time_us         BIGINT NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);
CREATE INDEX fed_population_breakdown_panel_idx ON fed.population_breakdown (panel_type);

-- Haplogroup reconciliation — donor-level multi-run consensus call.
CREATE TABLE fed.haplogroup_reconciliation (
    did             TEXT NOT NULL,
    rkey            TEXT NOT NULL,
    at_uri          TEXT NOT NULL,
    cid             TEXT,
    specimen_donor_ref TEXT,
    dna_type        TEXT,                  -- Y_DNA / MT_DNA
    compatibility_level TEXT,             -- COMPATIBLE/MINOR_DIVERGENCE/...
    consensus_haplogroup TEXT,
    confidence      DOUBLE PRECISION,
    branch_compatibility_score DOUBLE PRECISION,
    snp_concordance DOUBLE PRECISION,
    run_count       INTEGER,
    record          JSONB NOT NULL DEFAULT '{}'::jsonb,  -- full record (runCalls, conflicts, ...)
    record_created_at TIMESTAMPTZ,
    time_us         BIGINT NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);
CREATE INDEX fed_reconciliation_consensus_idx ON fed.haplogroup_reconciliation (dna_type, consensus_haplogroup);
