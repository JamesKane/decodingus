# --- !Ups

-- Per-test-type coverage expectation profiles for variant calling confidence
CREATE TABLE public.coverage_expectation_profile (
    id                  SERIAL PRIMARY KEY,
    test_type_id        INTEGER NOT NULL REFERENCES public.test_type_definition(id),
    contig_name         VARCHAR(50) NOT NULL,
    variant_class       VARCHAR(50) NOT NULL DEFAULT 'SNP',  -- SNP, STR, INDEL
    min_depth_high      DOUBLE PRECISION NOT NULL,            -- minimum depth for HIGH confidence
    min_depth_medium    DOUBLE PRECISION NOT NULL,            -- minimum depth for MEDIUM confidence
    min_depth_low       DOUBLE PRECISION NOT NULL,            -- minimum depth for LOW confidence
    min_coverage_pct    DOUBLE PRECISION,                     -- minimum % bases covered at 1x
    min_mapping_quality DOUBLE PRECISION,                     -- minimum mean mapping quality
    min_callable_pct    DOUBLE PRECISION,                     -- minimum % callable bases
    notes               TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (test_type_id, contig_name, variant_class)
);

CREATE INDEX idx_cep_test_type ON public.coverage_expectation_profile (test_type_id);

-- Seed profiles for WGS
INSERT INTO coverage_expectation_profile (test_type_id, contig_name, variant_class, min_depth_high, min_depth_medium, min_depth_low, min_coverage_pct, min_mapping_quality, min_callable_pct, notes)
SELECT id, 'Y', 'SNP', 20.0, 10.0, 5.0, 0.95, 30.0, 0.90, 'WGS Y-chromosome SNP calling thresholds'
FROM test_type_definition WHERE code = 'WGS';

INSERT INTO coverage_expectation_profile (test_type_id, contig_name, variant_class, min_depth_high, min_depth_medium, min_depth_low, min_coverage_pct, min_mapping_quality, min_callable_pct, notes)
SELECT id, 'MT', 'SNP', 100.0, 50.0, 20.0, 0.99, 30.0, 0.95, 'WGS mtDNA SNP calling thresholds (high copy number)'
FROM test_type_definition WHERE code = 'WGS';

INSERT INTO coverage_expectation_profile (test_type_id, contig_name, variant_class, min_depth_high, min_depth_medium, min_depth_low, min_coverage_pct, min_mapping_quality, notes)
SELECT id, 'Y', 'STR', 30.0, 15.0, 8.0, 0.90, 20.0, 'WGS Y-chromosome STR calling thresholds (higher depth needed)'
FROM test_type_definition WHERE code = 'WGS';

-- Seed profiles for BIG_Y_700
INSERT INTO coverage_expectation_profile (test_type_id, contig_name, variant_class, min_depth_high, min_depth_medium, min_depth_low, min_coverage_pct, min_mapping_quality, min_callable_pct, notes)
SELECT id, 'Y', 'SNP', 50.0, 25.0, 10.0, 0.90, 25.0, 0.85, 'BIG-Y 700 Y-chromosome SNP calling thresholds'
FROM test_type_definition WHERE code = 'BIG_Y_700';

INSERT INTO coverage_expectation_profile (test_type_id, contig_name, variant_class, min_depth_high, min_depth_medium, min_depth_low, min_coverage_pct, min_mapping_quality, notes)
SELECT id, 'Y', 'STR', 60.0, 30.0, 15.0, 0.85, 20.0, 'BIG-Y 700 Y-chromosome STR calling thresholds'
FROM test_type_definition WHERE code = 'BIG_Y_700';

-- Seed profiles for BIG_Y_500
INSERT INTO coverage_expectation_profile (test_type_id, contig_name, variant_class, min_depth_high, min_depth_medium, min_depth_low, min_coverage_pct, min_mapping_quality, min_callable_pct, notes)
SELECT id, 'Y', 'SNP', 40.0, 20.0, 8.0, 0.85, 25.0, 0.80, 'BIG-Y 500 Y-chromosome SNP calling thresholds'
FROM test_type_definition WHERE code = 'BIG_Y_500';

-- Seed profiles for Y_ELITE
INSERT INTO coverage_expectation_profile (test_type_id, contig_name, variant_class, min_depth_high, min_depth_medium, min_depth_low, min_coverage_pct, min_mapping_quality, min_callable_pct, notes)
SELECT id, 'Y', 'SNP', 60.0, 30.0, 12.0, 0.92, 25.0, 0.88, 'Y-Elite Y-chromosome SNP calling thresholds'
FROM test_type_definition WHERE code = 'Y_ELITE';

-- Seed profiles for MT_FULL_SEQUENCE
INSERT INTO coverage_expectation_profile (test_type_id, contig_name, variant_class, min_depth_high, min_depth_medium, min_depth_low, min_coverage_pct, min_mapping_quality, min_callable_pct, notes)
SELECT id, 'MT', 'SNP', 200.0, 100.0, 30.0, 0.99, 30.0, 0.98, 'Full mtDNA sequence SNP calling thresholds'
FROM test_type_definition WHERE code = 'MT_FULL_SEQUENCE';

-- Seed profiles for SNP arrays (chip data — marker-based, not depth-based)
INSERT INTO coverage_expectation_profile (test_type_id, contig_name, variant_class, min_depth_high, min_depth_medium, min_depth_low, min_coverage_pct, notes)
SELECT id, 'Y', 'SNP', 0.0, 0.0, 0.0, 0.0, 'Chip-based: confidence from marker count, not depth'
FROM test_type_definition WHERE code = 'SNP_ARRAY_23ANDME';

INSERT INTO coverage_expectation_profile (test_type_id, contig_name, variant_class, min_depth_high, min_depth_medium, min_depth_low, min_coverage_pct, notes)
SELECT id, 'Y', 'SNP', 0.0, 0.0, 0.0, 0.0, 'Chip-based: confidence from marker count, not depth'
FROM test_type_definition WHERE code = 'SNP_ARRAY_ANCESTRY';

# --- !Downs

DROP TABLE IF EXISTS public.coverage_expectation_profile;
