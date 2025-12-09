-- !Ups

-- Genotype data table for chip/array-based genetic data
-- Stores metadata about SNP array files and their quality metrics
CREATE TABLE genotype_data (
    id SERIAL PRIMARY KEY,
    at_uri VARCHAR UNIQUE,
    at_cid VARCHAR,
    sample_guid UUID NOT NULL,
    test_type_id INT REFERENCES test_type_definition(id),
    provider VARCHAR,                          -- 23andMe, AncestryDNA, FTDNA, LivingDNA, MyHeritage
    chip_version VARCHAR,
    total_markers_called INT,                  -- Markers with valid calls
    total_markers_possible INT,                -- Total markers on chip
    call_rate DOUBLE PRECISION,                -- % markers with valid call
    no_call_rate DOUBLE PRECISION,             -- % markers with no call
    y_markers_called INT,                      -- Y-DNA markers with calls
    y_markers_total INT,                       -- Total Y-DNA markers
    mt_markers_called INT,                     -- mtDNA markers with calls
    mt_markers_total INT,                      -- Total mtDNA markers
    autosomal_markers_called INT,              -- Autosomal markers called
    het_rate DOUBLE PRECISION,                 -- Heterozygosity rate (QC metric)
    test_date TIMESTAMP,                       -- When test was taken
    processed_at TIMESTAMP,                    -- When processed by Navigator
    build_version VARCHAR,                     -- GRCh37, GRCh38
    source_file_hash VARCHAR,                  -- SHA-256 for deduplication
    derived_y_haplogroup JSONB,                -- HaplogroupResult from chip data
    derived_mt_haplogroup JSONB,               -- HaplogroupResult from chip data
    population_breakdown_id INT REFERENCES population_breakdown(id),
    files JSONB,                               -- Array of FileInfo metadata
    deleted BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_genotype_sample_guid ON genotype_data(sample_guid);
CREATE INDEX idx_genotype_test_type ON genotype_data(test_type_id);
CREATE INDEX idx_genotype_at_uri ON genotype_data(at_uri) WHERE at_uri IS NOT NULL;
CREATE INDEX idx_genotype_provider ON genotype_data(provider);

COMMENT ON TABLE genotype_data IS 'SNP array/chip genotype data with quality metrics and derived haplogroups';
COMMENT ON COLUMN genotype_data.source_file_hash IS 'SHA-256 hash for file deduplication';
COMMENT ON COLUMN genotype_data.het_rate IS 'Heterozygosity rate - useful for QC (detecting contamination or relatedness)';

-- !Downs

DROP TABLE IF EXISTS genotype_data;
