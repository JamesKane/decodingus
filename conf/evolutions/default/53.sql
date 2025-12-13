# --- !Ups

-- ==============================================================================
-- VARIANT_V2: Consolidated variant table with JSONB coordinates and aliases
-- Replaces: variant, variant_alias tables
-- Reference: documents/proposals/variant-schema-simplification.md
-- ==============================================================================

-- mutation_type values:
--   Point mutations: SNP, INDEL, MNP
--   Repeat variations: STR
--   Structural variants: DEL, DUP, INS, INV, CNV, TRANS
-- naming_status values: UNNAMED, PENDING_REVIEW, NAMED
-- aliases structure: {common_names: [], rs_ids: [], sources: {ybrowse: [], isogg: [], ...}}
-- coordinates structure: {hs1: {contig, position, ref, alt}, GRCh38: {...}, ...}

CREATE TABLE variant_v2 (
    variant_id             SERIAL PRIMARY KEY,
    canonical_name         TEXT,
    mutation_type          TEXT NOT NULL DEFAULT 'SNP',
    naming_status          TEXT NOT NULL DEFAULT 'UNNAMED',
    aliases                JSONB DEFAULT '{}'::jsonb,
    coordinates            JSONB DEFAULT '{}'::jsonb,
    defining_haplogroup_id INTEGER REFERENCES tree.haplogroup(haplogroup_id) ON DELETE SET NULL,
    evidence               JSONB DEFAULT '{}'::jsonb,
    primers                JSONB DEFAULT '{}'::jsonb,
    notes                  TEXT,
    created_at             TIMESTAMPTZ DEFAULT NOW(),
    updated_at             TIMESTAMPTZ DEFAULT NOW()
);

COMMENT ON TABLE variant_v2 IS 'Consolidated variant table with JSONB coordinates supporting multiple reference genomes. One row per logical variant.';
COMMENT ON COLUMN variant_v2.canonical_name IS 'Primary variant name (e.g., M269, DYS456). NULL for unnamed/novel variants.';
COMMENT ON COLUMN variant_v2.mutation_type IS 'Variant type: SNP, INDEL, MNP (point) | STR (repeat) | DEL, DUP, INS, INV, CNV, TRANS (structural)';
COMMENT ON COLUMN variant_v2.aliases IS 'JSONB containing all known names: {common_names: [], rs_ids: [], sources: {source: [names]}}';
COMMENT ON COLUMN variant_v2.coordinates IS 'Per-assembly coordinates. Structure varies by mutation_type. hs1 is primary reference.';
COMMENT ON COLUMN variant_v2.defining_haplogroup_id IS 'Haplogroup this variant defines. Distinguishes parallel mutations (same name, different lineages).';

-- Unique constraint for named variants (allows parallel mutations with different haplogroups)
CREATE UNIQUE INDEX idx_variant_v2_name_haplogroup
    ON variant_v2(canonical_name, COALESCE(defining_haplogroup_id, -1))
    WHERE canonical_name IS NOT NULL;

-- For unnamed variants, uniqueness based on hs1 coordinates (primary reference)
CREATE UNIQUE INDEX idx_variant_v2_unnamed_coordinates
    ON variant_v2(
        (coordinates->'hs1'->>'contig'),
        ((coordinates->'hs1'->>'position')::int),
        (coordinates->'hs1'->>'ref'),
        (coordinates->'hs1'->>'alt')
    )
    WHERE canonical_name IS NULL AND coordinates ? 'hs1';

-- Performance indexes
CREATE INDEX idx_variant_v2_canonical ON variant_v2(canonical_name);
CREATE INDEX idx_variant_v2_aliases ON variant_v2 USING GIN(aliases);
CREATE INDEX idx_variant_v2_coordinates ON variant_v2 USING GIN(coordinates);
CREATE INDEX idx_variant_v2_mutation_type ON variant_v2(mutation_type);
CREATE INDEX idx_variant_v2_defining_haplogroup ON variant_v2(defining_haplogroup_id);

-- Search index for alias common_names array
CREATE INDEX idx_variant_v2_alias_common_names ON variant_v2
    USING GIN((aliases->'common_names') jsonb_path_ops);

-- ==============================================================================
-- SUPPORTING TABLES: ASR and branch mutation tracking
-- ==============================================================================

-- Haplogroup character states (ASR reconstructed states at tree nodes)
CREATE TABLE haplogroup_character_state (
    id                  SERIAL PRIMARY KEY,
    haplogroup_id       INT NOT NULL REFERENCES tree.haplogroup(haplogroup_id) ON DELETE CASCADE,
    variant_id          INT NOT NULL REFERENCES variant_v2(variant_id) ON DELETE CASCADE,

    -- The inferred state at this node
    -- For SNPs: "ancestral" or "derived" (or the actual allele: "G", "A")
    -- For STRs: the repeat count as string (e.g., "15") or "NULL" for null alleles
    inferred_state      TEXT NOT NULL,

    -- Confidence from ASR algorithm
    confidence          DECIMAL(5,4),         -- 0.0000 to 1.0000

    -- For uncertain reconstructions: probability distribution over states
    state_probabilities JSONB,
    -- Example: {"13": 0.05, "14": 0.25, "15": 0.65, "16": 0.05}

    -- ASR metadata
    algorithm           TEXT,                 -- "parsimony", "ml", "bayesian"
    reconstructed_at    TIMESTAMPTZ DEFAULT NOW(),

    UNIQUE(haplogroup_id, variant_id)
);

CREATE INDEX idx_character_state_haplogroup ON haplogroup_character_state(haplogroup_id);
CREATE INDEX idx_character_state_variant ON haplogroup_character_state(variant_id);

COMMENT ON TABLE haplogroup_character_state IS 'ASR reconstructed character states at haplogroup nodes. Replaces haplogroup_ancestral_str concept.';
COMMENT ON COLUMN haplogroup_character_state.inferred_state IS 'Inferred state: SNP allele, STR repeat count, SV presence, etc.';

-- Branch mutations (state changes along tree branches)
CREATE TABLE branch_mutation (
    id                      SERIAL PRIMARY KEY,
    variant_id              INT NOT NULL REFERENCES variant_v2(variant_id) ON DELETE CASCADE,

    -- The branch where the mutation occurred (parent -> child)
    parent_haplogroup_id    INT NOT NULL REFERENCES tree.haplogroup(haplogroup_id) ON DELETE CASCADE,
    child_haplogroup_id     INT NOT NULL REFERENCES tree.haplogroup(haplogroup_id) ON DELETE CASCADE,

    -- State transition
    from_state              TEXT NOT NULL,        -- "G" or "15"
    to_state                TEXT NOT NULL,        -- "A" or "16"

    -- For STRs: direction of change (+1 = expansion, -1 = contraction, NULL for SNPs)
    step_direction          INT,

    -- Confidence from ASR
    confidence              DECIMAL(5,4),

    UNIQUE(variant_id, parent_haplogroup_id, child_haplogroup_id)
);

CREATE INDEX idx_branch_mutation_child ON branch_mutation(child_haplogroup_id);
CREATE INDEX idx_branch_mutation_parent ON branch_mutation(parent_haplogroup_id);
CREATE INDEX idx_branch_mutation_variant ON branch_mutation(variant_id);

COMMENT ON TABLE branch_mutation IS 'State transitions along tree branches for all variant types (SNP, STR, SV).';

-- Biosample variant calls (observed values from samples, input to ASR)
CREATE TABLE biosample_variant_call (
    id              SERIAL PRIMARY KEY,
    biosample_id    INT NOT NULL REFERENCES biosample(id) ON DELETE CASCADE,
    variant_id      INT NOT NULL REFERENCES variant_v2(variant_id) ON DELETE CASCADE,

    -- The observed state
    -- For SNPs: "ref", "alt", "het", or actual alleles
    -- For STRs: repeat count as string (e.g., "15") or "NULL"
    observed_state  TEXT NOT NULL,

    -- Call quality metrics
    quality_score   INT,
    read_depth      INT,
    confidence      TEXT,                 -- "high", "medium", "low"

    -- Source attribution
    source          TEXT,                 -- "ftdna", "yfull", "user_upload"
    created_at      TIMESTAMPTZ DEFAULT NOW(),

    UNIQUE(biosample_id, variant_id)
);

CREATE INDEX idx_biosample_variant_call_biosample ON biosample_variant_call(biosample_id);
CREATE INDEX idx_biosample_variant_call_variant ON biosample_variant_call(variant_id);

COMMENT ON TABLE biosample_variant_call IS 'Observed variant calls from biosamples. Input data for ASR.';

-- STR mutation rates (reference data for ASR and age estimation)
CREATE TABLE str_mutation_rate (
    id                      SERIAL PRIMARY KEY,
    marker_name             TEXT NOT NULL UNIQUE,         -- DYS456, DYS389I, etc.
    panel_names             TEXT[],                       -- PowerPlex, YHRD, BigY, etc.

    -- Mutation rate per generation
    mutation_rate           DECIMAL(12,10) NOT NULL,
    mutation_rate_lower     DECIMAL(12,10),               -- 95% CI lower
    mutation_rate_upper     DECIMAL(12,10),               -- 95% CI upper

    -- Directional bias (for stepwise mutation model)
    omega_plus              DECIMAL(5,4) DEFAULT 0.5,     -- Probability of expansion
    omega_minus             DECIMAL(5,4) DEFAULT 0.5,     -- Probability of contraction

    -- Multi-step mutation frequencies
    multi_step_rate         DECIMAL(5,4),                 -- omega_2 + omega_3 + ...

    source                  TEXT,                         -- Ballantyne 2010, Willems 2016, etc.
    created_at              TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_str_mutation_rate_marker ON str_mutation_rate(marker_name);

COMMENT ON TABLE str_mutation_rate IS 'Per-marker STR mutation rates for ASR and age estimation. Sources: Ballantyne 2010, Willems 2016.';

-- ==============================================================================
-- NOTE: Old tables (variant, variant_alias, str_marker) are NOT dropped here.
-- Data migration and cleanup should be done manually:
--   1. Run migration script to consolidate data into variant_v2
--   2. Update haplogroup_variant FK references
--   3. Drop old tables after verification
-- ==============================================================================

# --- !Downs

-- Recreate old tables (structure only - data would need restoration from backup)
CREATE TABLE variant (
    variant_id        SERIAL PRIMARY KEY,
    genbank_contig_id INT NOT NULL,
    position          INTEGER NOT NULL,
    reference_allele  VARCHAR(255) NOT NULL,
    alternate_allele  VARCHAR(255) NOT NULL,
    variant_type      VARCHAR(10) NOT NULL CHECK (variant_type IN ('SNP', 'INDEL')),
    rs_id             VARCHAR(255),
    common_name       VARCHAR(255),
    FOREIGN KEY (genbank_contig_id) REFERENCES genbank_contig(genbank_contig_id) ON DELETE CASCADE,
    UNIQUE (genbank_contig_id, position, reference_allele, alternate_allele)
);

CREATE INDEX idx_variant_common_name ON variant(common_name);
CREATE INDEX idx_variant_rs_id ON variant(rs_id);
CREATE INDEX idx_variant_position ON variant(genbank_contig_id, position);

CREATE TABLE variant_alias (
    id SERIAL PRIMARY KEY,
    variant_id INT NOT NULL REFERENCES variant(variant_id) ON DELETE CASCADE,
    alias_type VARCHAR(50) NOT NULL,
    alias_value VARCHAR(255) NOT NULL,
    source VARCHAR(255),
    is_primary BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW() NOT NULL,
    UNIQUE(variant_id, alias_type, alias_value)
);

CREATE INDEX idx_variant_alias_variant ON variant_alias(variant_id);
CREATE INDEX idx_variant_alias_value ON variant_alias(alias_value);
CREATE INDEX idx_variant_alias_type_value ON variant_alias(alias_type, alias_value);

CREATE TABLE str_marker (
    id SERIAL PRIMARY KEY,
    genbank_contig_id INT NOT NULL REFERENCES genbank_contig(genbank_contig_id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL,
    start_pos BIGINT NOT NULL,
    end_pos BIGINT NOT NULL,
    period INT NOT NULL,
    verified BOOLEAN DEFAULT FALSE,
    note TEXT,
    UNIQUE(genbank_contig_id, name)
);

CREATE INDEX idx_str_marker_contig ON str_marker(genbank_contig_id);

-- Drop new tables
DROP TABLE IF EXISTS str_mutation_rate CASCADE;
DROP TABLE IF EXISTS biosample_variant_call CASCADE;
DROP TABLE IF EXISTS branch_mutation CASCADE;
DROP TABLE IF EXISTS haplogroup_character_state CASCADE;

-- Restore FK on haplogroup_variant (will need manual data restoration)
ALTER TABLE tree.haplogroup_variant DROP CONSTRAINT IF EXISTS haplogroup_variant_variant_id_fkey;
ALTER TABLE tree.haplogroup_variant
ADD CONSTRAINT haplogroup_variant_variant_id_fkey
FOREIGN KEY (variant_id) REFERENCES variant(variant_id) ON DELETE CASCADE;

DROP TABLE IF EXISTS variant_v2 CASCADE;
