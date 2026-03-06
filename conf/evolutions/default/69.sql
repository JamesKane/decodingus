# --- !Ups

-- Vendor-specific targeted test types
INSERT INTO test_type_definition (
    code, display_name, category, vendor, target_type,
    expected_min_depth, expected_target_depth,
    supports_haplogroup_y, supports_haplogroup_mt, supports_autosomal_ibd, supports_ancestry,
    typical_file_formats, description
) VALUES
('BIG_Y_700', 'FTDNA Big Y-700', 'SEQUENCING', 'FamilyTreeDNA', 'Y_CHROMOSOME',
 30.0, 50.0,
 TRUE, FALSE, FALSE, FALSE,
 ARRAY['BAM', 'VCF', 'BED'], 'FamilyTreeDNA Big Y-700 targeted Y-chromosome sequencing covering ~700 STRs and ~200K Y-SNPs.'),

('Y_ELITE', 'Full Genomes Y Elite', 'SEQUENCING', 'Full Genomes', 'Y_CHROMOSOME',
 15.0, 30.0,
 TRUE, FALSE, FALSE, FALSE,
 ARRAY['BAM', 'CRAM', 'VCF'], 'Full Genomes Y Elite whole Y-chromosome sequencing at 30x depth.'),

('Y_PRIME', 'YSEQ Y-Prime', 'SEQUENCING', 'YSEQ', 'Y_CHROMOSOME',
 15.0, 30.0,
 TRUE, FALSE, FALSE, FALSE,
 ARRAY['BAM', 'VCF'], 'YSEQ Y-Prime Y-chromosome sequencing product.'),

('MT_FULL_SEQUENCE', 'mtDNA Full Sequence', 'SEQUENCING', 'FamilyTreeDNA', 'MT_DNA',
 500.0, 1000.0,
 FALSE, TRUE, FALSE, FALSE,
 ARRAY['BAM', 'FASTA', 'VCF'], 'Full mitochondrial genome sequencing (16,569 bp).'),

('MT_PLUS', 'FTDNA mtDNA Plus', 'SEQUENCING', 'FamilyTreeDNA', 'MT_DNA',
 200.0, 500.0,
 FALSE, TRUE, FALSE, FALSE,
 ARRAY['BAM', 'FASTA', 'VCF'], 'FamilyTreeDNA mtDNA Plus covering full mitochondrial genome.'),

('BIG_Y_500', 'FTDNA Big Y-500 (Legacy)', 'SEQUENCING', 'FamilyTreeDNA', 'Y_CHROMOSOME',
 20.0, 40.0,
 TRUE, FALSE, FALSE, FALSE,
 ARRAY['BAM', 'VCF', 'BED'], 'Legacy FamilyTreeDNA Big Y-500 product (superseded by Big Y-700).');

-- Link Big Y-500 successor to Big Y-700
UPDATE test_type_definition
SET successor_test_type_id = (SELECT id FROM test_type_definition WHERE code = 'BIG_Y_700')
WHERE code = 'BIG_Y_500';

-- Target region definitions for targeted tests
CREATE TABLE public.test_type_target_region (
    id SERIAL PRIMARY KEY,
    test_type_id INTEGER NOT NULL REFERENCES test_type_definition(id),
    contig_name VARCHAR(50) NOT NULL,
    start_position INTEGER,
    end_position INTEGER,
    region_name VARCHAR(100) NOT NULL,
    region_type VARCHAR(50) NOT NULL
        CHECK (region_type IN ('FULL', 'PARTIAL', 'TARGETED_SNPS')),
    expected_coverage_pct DOUBLE PRECISION,
    expected_min_depth DOUBLE PRECISION,
    UNIQUE(test_type_id, contig_name, start_position, end_position)
);

CREATE INDEX idx_tttr_test_type ON test_type_target_region(test_type_id);

-- Seed target regions for targeted tests
-- Big Y-700: Y chromosome combbed region
INSERT INTO test_type_target_region (test_type_id, contig_name, start_position, end_position, region_name, region_type, expected_coverage_pct, expected_min_depth)
SELECT id, 'chrY', 2781480, 56887903, 'Y Combbed Region', 'TARGETED_SNPS', 0.95, 30.0
FROM test_type_definition WHERE code = 'BIG_Y_700';

-- Big Y-500: Y chromosome combbed region (narrower)
INSERT INTO test_type_target_region (test_type_id, contig_name, start_position, end_position, region_name, region_type, expected_coverage_pct, expected_min_depth)
SELECT id, 'chrY', 2781480, 56887903, 'Y Combbed Region', 'TARGETED_SNPS', 0.90, 20.0
FROM test_type_definition WHERE code = 'BIG_Y_500';

-- Y Elite: Full Y chromosome
INSERT INTO test_type_target_region (test_type_id, contig_name, region_name, region_type, expected_coverage_pct, expected_min_depth)
SELECT id, 'chrY', 'Full Y Chromosome', 'FULL', 0.98, 15.0
FROM test_type_definition WHERE code = 'Y_ELITE';

-- Y Prime: Full Y chromosome
INSERT INTO test_type_target_region (test_type_id, contig_name, region_name, region_type, expected_coverage_pct, expected_min_depth)
SELECT id, 'chrY', 'Full Y Chromosome', 'FULL', 0.95, 15.0
FROM test_type_definition WHERE code = 'Y_PRIME';

-- MT Full Sequence: Full mitochondrial genome
INSERT INTO test_type_target_region (test_type_id, contig_name, start_position, end_position, region_name, region_type, expected_coverage_pct, expected_min_depth)
SELECT id, 'chrM', 1, 16569, 'Full Mitochondrial Genome', 'FULL', 0.999, 500.0
FROM test_type_definition WHERE code = 'MT_FULL_SEQUENCE';

-- MT Plus: Full mitochondrial genome
INSERT INTO test_type_target_region (test_type_id, contig_name, start_position, end_position, region_name, region_type, expected_coverage_pct, expected_min_depth)
SELECT id, 'chrM', 1, 16569, 'Full Mitochondrial Genome', 'FULL', 0.999, 200.0
FROM test_type_definition WHERE code = 'MT_PLUS';

# --- !Downs

DROP TABLE IF EXISTS public.test_type_target_region;

DELETE FROM test_type_definition WHERE code IN (
    'BIG_Y_700', 'Y_ELITE', 'Y_PRIME', 'MT_FULL_SEQUENCE', 'MT_PLUS', 'BIG_Y_500'
);
