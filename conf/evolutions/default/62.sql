# --- !Ups

-- Per-biosample callable loci storage for accurate mutation rate calculation
-- Uses polymorphic reference pattern (consistent with tree.biosample_private_variant)
CREATE TABLE genomics.biosample_callable_loci (
    id SERIAL PRIMARY KEY,
    sample_type VARCHAR(20) NOT NULL,
    sample_id INTEGER NOT NULL,
    sample_guid UUID,
    chromosome VARCHAR(20) NOT NULL,
    total_callable_bp BIGINT NOT NULL,
    region_count INTEGER,
    bed_file_hash VARCHAR(64),
    computed_at TIMESTAMP NOT NULL,
    source_test_type_id INTEGER REFERENCES test_type_definition(id),
    y_xdegen_callable_bp BIGINT,
    y_ampliconic_callable_bp BIGINT,
    y_palindromic_callable_bp BIGINT,
    UNIQUE(sample_type, sample_id, chromosome),
    CHECK (sample_type IN ('citizen', 'external'))
);

CREATE INDEX idx_bcl_sample ON genomics.biosample_callable_loci(sample_type, sample_id);
CREATE INDEX idx_bcl_guid ON genomics.biosample_callable_loci(sample_guid) WHERE sample_guid IS NOT NULL;

# --- !Downs

DROP INDEX IF EXISTS idx_bcl_guid;
DROP INDEX IF EXISTS idx_bcl_sample;
DROP TABLE IF EXISTS genomics.biosample_callable_loci;
