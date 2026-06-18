-- genomics schema: sequencing runs/files, coverage, callable loci, labs &
-- instruments, pangenome. De-sprawl (plan §2):
--   * sequence_file_checksum / _http_location / _atp_location -> JSONB on sequence_file
--   * alignment_coverage / pangenome_alignment_coverage -> coverage JSONB on metadata
--   * biosample_callable_loci polymorphic (sample_type, sample_id) -> sample_guid FK
--   * scattered at_uri/at_cid -> single `atproto` JSONB

-- ── Reference contigs ────────────────────────────────────────────────────────
CREATE TABLE genomics.genbank_contig (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    accession       TEXT NOT NULL UNIQUE,
    common_name     TEXT,
    reference_genome TEXT NOT NULL,          -- GRCh37/GRCh38/hs1
    seq_length      BIGINT
);

-- ── Labs & instruments ───────────────────────────────────────────────────────
CREATE TABLE genomics.sequencing_lab (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    is_d2c          BOOLEAN NOT NULL DEFAULT false,
    website_url     TEXT,
    description_markdown TEXT
);

CREATE TABLE genomics.sequencer_instrument (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instrument_id   TEXT NOT NULL UNIQUE,    -- e.g. 'A00123'
    model_name      TEXT,
    manufacturer    TEXT,
    year_introduced INTEGER,
    estimated_max_throughput BIGINT
);

CREATE TABLE genomics.instrument_observation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instrument_id   BIGINT REFERENCES genomics.sequencer_instrument(id),
    lab_name        TEXT,
    biosample_ref   TEXT,
    platform        TEXT,
    instrument_model TEXT,
    flowcell_id     TEXT,
    run_date        DATE,
    confidence      TEXT,                     -- KNOWN/INFERRED/GUESSED
    atproto         JSONB                     -- {uri, cid, repo_did}
);
CREATE UNIQUE INDEX instrument_observation_atproto_uri_key
    ON genomics.instrument_observation ((atproto->>'uri')) WHERE atproto IS NOT NULL;

CREATE TABLE genomics.instrument_association_proposal (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instrument_id   BIGINT REFERENCES genomics.sequencer_instrument(id),
    proposed_lab_name TEXT,
    proposed_model  TEXT,
    observation_count INTEGER NOT NULL DEFAULT 0,
    distinct_citizen_count INTEGER NOT NULL DEFAULT 0,
    confidence_score NUMERIC(5,4),
    status          TEXT NOT NULL DEFAULT 'PENDING',
    accepted_lab_id BIGINT REFERENCES genomics.sequencing_lab(id),
    accepted_instrument_id BIGINT REFERENCES genomics.sequencer_instrument(id)
);

-- ── Test types & coverage expectations ──────────────────────────────────────
CREATE TABLE genomics.test_type_definition (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    category        core.data_generation_method NOT NULL,
    vendor          TEXT,
    target_type     core.target_type,
    expected_min_depth DOUBLE PRECISION,
    supports_haplogroup_y  BOOLEAN NOT NULL DEFAULT false,
    supports_haplogroup_mt BOOLEAN NOT NULL DEFAULT false,
    supports_autosomal_ibd BOOLEAN NOT NULL DEFAULT false,
    supports_ancestry      BOOLEAN NOT NULL DEFAULT false,
    typical_file_formats   TEXT[] NOT NULL DEFAULT '{}',
    description     TEXT
);

CREATE TABLE genomics.coverage_expectation_profile (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    test_type_id    BIGINT NOT NULL REFERENCES genomics.test_type_definition(id) ON DELETE CASCADE,
    contig_name     TEXT,
    variant_class   TEXT,                     -- SNP/STR/INDEL
    min_depth_high  DOUBLE PRECISION,
    min_depth_medium DOUBLE PRECISION,
    min_depth_low   DOUBLE PRECISION,
    min_coverage_pct DOUBLE PRECISION,
    min_mapping_quality DOUBLE PRECISION
);

-- ── Pangenome ───────────────────────────────────────────────────────────────
CREATE TABLE genomics.pangenome_graph (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    graph_name      TEXT NOT NULL UNIQUE,
    source_gfa_file TEXT,
    description     TEXT,
    creation_date   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE genomics.pangenome_path (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    graph_id        BIGINT NOT NULL REFERENCES genomics.pangenome_graph(id) ON DELETE CASCADE,
    path_name       TEXT NOT NULL,
    is_reference    BOOLEAN NOT NULL DEFAULT false,
    length_bp       BIGINT,
    UNIQUE (graph_id, path_name)
);

CREATE TABLE genomics.canonical_pangenome_variant (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pangenome_graph_id BIGINT NOT NULL REFERENCES genomics.pangenome_graph(id) ON DELETE CASCADE,
    variant_type    TEXT,
    variant_nodes   INTEGER[] NOT NULL DEFAULT '{}',
    variant_edges   INTEGER[] NOT NULL DEFAULT '{}',
    reference_path_id BIGINT REFERENCES genomics.pangenome_path(id),
    reference_allele_sequence TEXT,
    canonical_hash  TEXT NOT NULL UNIQUE
);

-- ── Sequencing runs & files ─────────────────────────────────────────────────
CREATE TABLE genomics.sequence_library (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_guid     UUID NOT NULL REFERENCES core.biosample(sample_guid),
    test_type_id    BIGINT REFERENCES genomics.test_type_definition(id),
    lab_id          BIGINT REFERENCES genomics.sequencing_lab(id),
    run_date        DATE,
    instrument      TEXT,
    reads           BIGINT,
    read_length     INTEGER,
    paired_end      BOOLEAN,
    insert_size     INTEGER,
    atproto         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX sequence_library_sample_idx ON genomics.sequence_library (sample_guid);

CREATE TABLE genomics.sequence_file (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    library_id      BIGINT NOT NULL REFERENCES genomics.sequence_library(id) ON DELETE CASCADE,
    file_name       TEXT NOT NULL,
    file_size_bytes BIGINT,
    file_format     TEXT,                     -- BAM/CRAM/VCF/...
    aligner         TEXT,
    target_reference TEXT,
    pangenome_graph_id BIGINT REFERENCES genomics.pangenome_graph(id),
    checksums       JSONB NOT NULL DEFAULT '[]'::jsonb,   -- [{algorithm, checksum, verified_at}]
    http_locations  JSONB NOT NULL DEFAULT '[]'::jsonb,   -- [{file_url, file_index_url}]
    atp_location    JSONB,                                 -- {repo_did, record_cid, record_path}
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX sequence_file_library_idx ON genomics.sequence_file (library_id);

-- Linear-reference alignment stats. coverage JSONB replaces alignment_coverage;
-- expression indexes target the hot aggregation paths.
CREATE TABLE genomics.alignment_metadata (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sequence_file_id BIGINT NOT NULL REFERENCES genomics.sequence_file(id) ON DELETE CASCADE,
    genbank_contig_id BIGINT REFERENCES genomics.genbank_contig(id),
    metric_level    TEXT NOT NULL,            -- CONTIG_OVERALL/REGION
    region_name     TEXT,
    region_start_pos BIGINT,
    region_end_pos  BIGINT,
    reference_build TEXT,
    variant_caller  TEXT,
    coverage        JSONB NOT NULL DEFAULT '{}'::jsonb     -- {meanDepth, medianDepth, percent_coverage_at_*x}
);
CREATE INDEX alignment_metadata_file_idx ON genomics.alignment_metadata (sequence_file_id);
CREATE INDEX alignment_metadata_meandepth_idx
    ON genomics.alignment_metadata (((coverage->>'meanDepth')::double precision));

CREATE TABLE genomics.pangenome_alignment_metadata (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sequence_file_id BIGINT NOT NULL REFERENCES genomics.sequence_file(id) ON DELETE CASCADE,
    pangenome_graph_id BIGINT NOT NULL REFERENCES genomics.pangenome_graph(id),
    metric_level    TEXT NOT NULL,            -- GRAPH_OVERALL/PATH/NODE/REGION
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb     -- includes coverage
);

CREATE TABLE genomics.reported_variant_pangenome (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_guid     UUID NOT NULL REFERENCES core.biosample(sample_guid),
    graph_id        BIGINT NOT NULL REFERENCES genomics.pangenome_graph(id),
    variant_type    TEXT,
    variant_nodes   INTEGER[] NOT NULL DEFAULT '{}',
    variant_edges   INTEGER[] NOT NULL DEFAULT '{}',
    allele_fraction DOUBLE PRECISION,
    depth           INTEGER,
    zygosity        TEXT,                     -- HOM_REF/HET/HOM_ALT
    haplotype_information JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX reported_variant_pangenome_sample_idx ON genomics.reported_variant_pangenome (sample_guid);

-- ── Chip genotype data ───────────────────────────────────────────────────────
CREATE TABLE genomics.genotype_data (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_guid     UUID NOT NULL REFERENCES core.biosample(sample_guid),
    test_type_id    BIGINT REFERENCES genomics.test_type_definition(id),
    provider        TEXT,
    metrics         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX genotype_data_sample_idx ON genomics.genotype_data (sample_guid);

-- ── Callable loci (was polymorphic; now sample_guid FK) ──────────────────────
CREATE TABLE genomics.biosample_callable_loci (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_guid     UUID NOT NULL REFERENCES core.biosample(sample_guid),
    chromosome      TEXT NOT NULL,
    total_callable_bp BIGINT NOT NULL DEFAULT 0,
    region_count    INTEGER NOT NULL DEFAULT 0,
    bed_file_hash   TEXT,
    source_test_type_id BIGINT REFERENCES genomics.test_type_definition(id),
    y_xdegen_callable_bp    BIGINT,
    y_ampliconic_callable_bp BIGINT,
    y_palindromic_callable_bp BIGINT,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX callable_loci_sample_idx ON genomics.biosample_callable_loci (sample_guid);
