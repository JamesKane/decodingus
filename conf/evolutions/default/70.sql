# --- !Ups

-- Additional vendor-specific chip test types
INSERT INTO test_type_definition (
    code, display_name, category, vendor, target_type,
    expected_marker_count,
    supports_haplogroup_y, supports_haplogroup_mt, supports_autosomal_ibd, supports_ancestry,
    typical_file_formats, description
) VALUES
('ARRAY_23ANDME_V4', '23andMe v4 Chip', 'GENOTYPING', '23andMe', 'MIXED',
 570000,
 TRUE, TRUE, TRUE, TRUE,
 ARRAY['TXT', 'CSV'], '23andMe v4 chip (~570K markers). Superseded by v5.'),

('ARRAY_ANCESTRY_V1', 'AncestryDNA v1', 'GENOTYPING', 'AncestryDNA', 'MIXED',
 700000,
 TRUE, TRUE, TRUE, TRUE,
 ARRAY['TXT', 'CSV'], 'AncestryDNA v1 chip. Superseded by v2.'),

('ARRAY_MYHERITAGE', 'MyHeritage DNA', 'GENOTYPING', 'MyHeritage', 'MIXED',
 700000,
 TRUE, TRUE, TRUE, TRUE,
 ARRAY['CSV'], 'MyHeritage DNA chip (~700K markers).'),

('ARRAY_LIVINGDNA', 'LivingDNA', 'GENOTYPING', 'LivingDNA', 'MIXED',
 630000,
 TRUE, TRUE, TRUE, TRUE,
 ARRAY['CSV', 'TXT'], 'LivingDNA chip (~630K markers).'),

('ARRAY_CUSTOM', 'Custom SNP Array', 'GENOTYPING', NULL, 'MIXED',
 NULL,
 TRUE, TRUE, FALSE, FALSE,
 ARRAY['TXT', 'CSV', 'VCF'], 'Custom or unrecognized SNP array data.');

-- Link deprecated versions to successors
UPDATE test_type_definition
SET successor_test_type_id = (SELECT id FROM test_type_definition WHERE code = 'SNP_ARRAY_23ANDME'),
    deprecated_at = '2017-08-01'
WHERE code = 'ARRAY_23ANDME_V4';

UPDATE test_type_definition
SET successor_test_type_id = (SELECT id FROM test_type_definition WHERE code = 'SNP_ARRAY_ANCESTRY'),
    deprecated_at = '2019-01-01'
WHERE code = 'ARRAY_ANCESTRY_V1';

# --- !Downs

DELETE FROM test_type_definition WHERE code IN (
    'ARRAY_23ANDME_V4', 'ARRAY_ANCESTRY_V1', 'ARRAY_MYHERITAGE', 'ARRAY_LIVINGDNA', 'ARRAY_CUSTOM'
);
