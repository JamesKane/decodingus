-- !Ups

-- Simplify reference genome naming in genbank_contig table
-- - Remove patch versions: GRCh37.p13 -> GRCh37, GRCh38.p14 -> GRCh38
-- - Use UCSC convention for T2T-CHM13: T2T-CHM13v2.0 -> hs1

UPDATE genbank_contig SET reference_genome = 'GRCh37' WHERE reference_genome = 'GRCh37.p13';
UPDATE genbank_contig SET reference_genome = 'GRCh38' WHERE reference_genome = 'GRCh38.p14';
UPDATE genbank_contig SET reference_genome = 'hs1' WHERE reference_genome = 'T2T-CHM13v2.0';

-- !Downs

-- Restore original reference genome naming
UPDATE genbank_contig SET reference_genome = 'GRCh37.p13' WHERE reference_genome = 'GRCh37';
UPDATE genbank_contig SET reference_genome = 'GRCh38.p14' WHERE reference_genome = 'GRCh38';
UPDATE genbank_contig SET reference_genome = 'T2T-CHM13v2.0' WHERE reference_genome = 'hs1';
