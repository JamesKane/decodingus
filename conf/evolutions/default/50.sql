# --- !Ups

-- Genome region structural annotations
-- References existing genbank_contig table for chromosome data

-- Version tracking for ETag generation
CREATE TABLE genome_region_version (
  id SERIAL PRIMARY KEY,
  reference_genome VARCHAR(20) NOT NULL UNIQUE,  -- GRCh37, GRCh38, hs1
  data_version VARCHAR(20) NOT NULL,  -- e.g., "2024.12.1"
  updated_at TIMESTAMP DEFAULT NOW()
);

-- Structural regions (centromere, telomere, PAR, XTR, ampliconic, etc.)
CREATE TABLE genome_region (
  id SERIAL PRIMARY KEY,
  genbank_contig_id INT NOT NULL REFERENCES genbank_contig(genbank_contig_id),
  region_type VARCHAR(30) NOT NULL,  -- Centromere, Telomere_P, Telomere_Q, PAR1, PAR2, XTR, Ampliconic, Palindrome, Heterochromatin, XDegenerate
  name VARCHAR(50),  -- For named regions (P1-P8 palindromes)
  start_pos BIGINT NOT NULL,
  end_pos BIGINT NOT NULL,
  modifier DECIMAL(3,2),  -- Quality modifier (0.1-1.0)
  UNIQUE(genbank_contig_id, region_type, name, start_pos)
);

-- Cytoband annotations for ideogram display
CREATE TABLE cytoband (
  id SERIAL PRIMARY KEY,
  genbank_contig_id INT NOT NULL REFERENCES genbank_contig(genbank_contig_id),
  name VARCHAR(20) NOT NULL,  -- p11.32, q11.21, etc.
  start_pos BIGINT NOT NULL,
  end_pos BIGINT NOT NULL,
  stain VARCHAR(10) NOT NULL,  -- gneg, gpos25, gpos50, gpos75, gpos100, acen, gvar, stalk
  UNIQUE(genbank_contig_id, name)
);

-- STR marker positions
CREATE TABLE str_marker (
  id SERIAL PRIMARY KEY,
  genbank_contig_id INT NOT NULL REFERENCES genbank_contig(genbank_contig_id),
  name VARCHAR(30) NOT NULL,  -- DYS389I, DYS456, etc.
  start_pos BIGINT NOT NULL,
  end_pos BIGINT NOT NULL,
  period INT NOT NULL,  -- Repeat unit length in bp
  verified BOOLEAN DEFAULT false,
  note TEXT,
  UNIQUE(genbank_contig_id, name)
);

-- Insert initial version records
INSERT INTO genome_region_version (reference_genome, data_version) VALUES
  ('GRCh37', '2024.12.1'),
  ('GRCh38', '2024.12.1'),
  ('hs1', '2024.12.1');

CREATE INDEX idx_genome_region_contig ON genome_region(genbank_contig_id);
CREATE INDEX idx_cytoband_contig ON cytoband(genbank_contig_id);
CREATE INDEX idx_str_marker_contig ON str_marker(genbank_contig_id);

# --- !Downs

DROP INDEX IF EXISTS idx_str_marker_contig;
DROP INDEX IF EXISTS idx_cytoband_contig;
DROP INDEX IF EXISTS idx_genome_region_contig;

DROP TABLE IF EXISTS str_marker;
DROP TABLE IF EXISTS cytoband;
DROP TABLE IF EXISTS genome_region;
DROP TABLE IF EXISTS genome_region_version;
