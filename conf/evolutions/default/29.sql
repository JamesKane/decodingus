-- !Ups

-- Create the test_type_definition table
CREATE TABLE test_type_definition (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT
);

-- Insert initial data for known test types
INSERT INTO test_type_definition (name, description) VALUES
('WGS', 'Whole Genome Sequencing'),
('WES', 'Whole Exome Sequencing'),
('TARGETED_Y', 'Targeted Y-DNA Sequencing (e.g., Big Y)'),
('TARGETED_MT', 'Targeted mtDNA Sequencing (e.g., mtDNA Full Sequence)'),
('SNP_ARRAY_23ANDME', 'SNP Array data from 23andMe'),
('SNP_ARRAY_ANCESTRY', 'SNP Array data from AncestryDNA');

-- !Downs

-- Drop the test_type_definition table
DROP TABLE IF EXISTS test_type_definition CASCADE;
