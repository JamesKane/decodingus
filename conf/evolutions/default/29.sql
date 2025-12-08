-- !Ups

-- Create ENUM types first
CREATE TYPE data_generation_method AS ENUM ('SEQUENCING', 'GENOTYPING');
CREATE TYPE target_type AS ENUM ('WHOLE_GENOME', 'Y_CHROMOSOME', 'MT_DNA', 'AUTOSOMAL', 'X_CHROMOSOME', 'MIXED');

-- Create the test_type_definition table
CREATE TABLE test_type_definition (
    id SERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,          -- Maps to TestTypeRow.code
    display_name VARCHAR(100) NOT NULL,        -- Maps to TestTypeRow.displayName
    category data_generation_method NOT NULL,  -- Maps to TestTypeRow.category
    vendor VARCHAR(100),                        -- Maps to TestTypeRow.vendor
    target_type target_type NOT NULL,          -- Maps to TestTypeRow.targetType
    expected_min_depth DOUBLE PRECISION,       -- Maps to TestTypeRow.expectedMinDepth
    expected_target_depth DOUBLE PRECISION,    -- Maps to TestTypeRow.expectedTargetDepth
    expected_marker_count INTEGER,             -- Maps to TestTypeRow.expectedMarkerCount
    supports_haplogroup_y BOOLEAN NOT NULL DEFAULT FALSE, -- Maps to TestTypeRow.supportsHaplogroupY
    supports_haplogroup_mt BOOLEAN NOT NULL DEFAULT FALSE, -- Maps to TestTypeRow.supportsHaplogroupMt
    supports_autosomal_ibd BOOLEAN NOT NULL DEFAULT FALSE, -- Maps to TestTypeRow.supportsAutosomalIbd
    supports_ancestry BOOLEAN NOT NULL DEFAULT FALSE,      -- Maps to TestTypeRow.supportsAncestry
    typical_file_formats TEXT[],               -- Maps to TestTypeRow.typicalFileFormats
    version VARCHAR(20),                       -- Maps to TestTypeRow.version
    release_date DATE,                         -- Maps to TestTypeRow.releaseDate
    deprecated_at DATE,                        -- Maps to TestTypeRow.deprecatedAt
    successor_test_type_id INTEGER REFERENCES test_type_definition(id), -- Maps to TestTypeRow.successorTestTypeId
    description TEXT,                          -- Maps to TestTypeRow.description
    documentation_url VARCHAR(500)             -- Maps to TestTypeRow.documentationUrl
);

-- Insert initial data for known test types
INSERT INTO test_type_definition (
    code, display_name, category, vendor, target_type, expected_target_depth,
    supports_haplogroup_y, supports_haplogroup_mt, supports_autosomal_ibd, supports_ancestry,
    typical_file_formats, description
) VALUES
('WGS', 'Whole Genome Sequencing', 'SEQUENCING', NULL, 'WHOLE_GENOME', 30.0,
 TRUE, TRUE, TRUE, TRUE, ARRAY['BAM', 'CRAM', 'VCF'], 'Standard whole genome sequencing.'),

('WES', 'Whole Exome Sequencing', 'SEQUENCING', NULL, 'AUTOSOMAL', 100.0,
 FALSE, FALSE, FALSE, FALSE, ARRAY['BAM', 'VCF'], 'Whole exome sequencing.'),

('TARGETED_Y', 'Targeted Y-DNA Sequencing', 'SEQUENCING', NULL, 'Y_CHROMOSOME', 50.0,
 TRUE, FALSE, FALSE, FALSE, ARRAY['BAM', 'VCF', 'BED'], 'Targeted sequencing of Y-chromosome.'),

('TARGETED_MT', 'Targeted mtDNA Sequencing', 'SEQUENCING', NULL, 'MT_DNA', 1000.0,
 FALSE, TRUE, FALSE, FALSE, ARRAY['BAM', 'FASTA', 'VCF'], 'Targeted sequencing of mitochondrial DNA.'),

('SNP_ARRAY_23ANDME', '23andMe v5 Chip', 'GENOTYPING', '23andMe', 'MIXED', NULL,
 TRUE, TRUE, TRUE, TRUE, ARRAY['TXT', 'CSV'], 'SNP Array data from 23andMe v5.'),

('SNP_ARRAY_ANCESTRY', 'AncestryDNA v2', 'GENOTYPING', 'AncestryDNA', 'MIXED', NULL,
 TRUE, TRUE, TRUE, TRUE, ARRAY['TXT', 'CSV'], 'SNP Array data from AncestryDNA v2.'),

('ARRAY_FTDNA_FF', 'FTDNA Family Finder', 'GENOTYPING', 'FamilyTreeDNA', 'AUTOSOMAL', NULL,
 FALSE, FALSE, TRUE, TRUE, ARRAY['CSV'], 'FTDNA Family Finder autosomal chip data.');

-- !Downs

-- Drop the test_type_definition table
DROP TABLE IF EXISTS test_type_definition CASCADE;
DROP TYPE IF EXISTS data_generation_method;
DROP TYPE IF EXISTS target_type;