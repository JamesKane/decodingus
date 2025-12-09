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
    build_version VARCHAR,                     -- GRCh37, GRCh38
    source_file_hash VARCHAR,                  -- SHA-256 for deduplication
    -- Metrics consolidated into JSONB to reduce column count
    -- Contains: totalMarkersCalled, totalMarkersPossible, callRate, noCallRate,
    --           yMarkersCalled, yMarkersTotal, mtMarkersCalled, mtMarkersTotal,
    --           autosomalMarkersCalled, hetRate, testDate, processedAt,
    --           derivedYHaplogroup, derivedMtHaplogroup, files
    metrics JSONB NOT NULL DEFAULT '{}',
    population_breakdown_id INT REFERENCES population_breakdown(id),
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
COMMENT ON COLUMN genotype_data.metrics IS 'JSONB containing: totalMarkersCalled, totalMarkersPossible, callRate, noCallRate, yMarkersCalled, yMarkersTotal, mtMarkersCalled, mtMarkersTotal, autosomalMarkersCalled, hetRate, testDate, processedAt, derivedYHaplogroup, derivedMtHaplogroup, files';

-- !Downs

DROP TABLE IF EXISTS genotype_data;
