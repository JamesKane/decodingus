# --- !Ups

DROP TABLE IF EXISTS genome_region;
DROP TABLE IF EXISTS cytoband;

CREATE TABLE genome_region_v2 (
    region_id       SERIAL PRIMARY KEY,
    region_type     TEXT NOT NULL,
    name            TEXT,
    coordinates     JSONB NOT NULL,
    properties      JSONB DEFAULT '{}',
    UNIQUE(region_type, name)
);

CREATE INDEX idx_genome_region_v2_coords ON genome_region_v2 USING GIN(coordinates);

-- Efficient lookup: "What region contains GRCh38:chrY:15000000?"
CREATE INDEX idx_genome_region_v2_grch38_range ON genome_region_v2 (
    (coordinates->'GRCh38'->>'contig'),
    ((coordinates->'GRCh38'->>'start')::bigint),
    ((coordinates->'GRCh38'->>'end')::bigint)
);

# --- !Downs

DROP TABLE IF EXISTS genome_region_v2;

CREATE TABLE genome_region (
    id SERIAL PRIMARY KEY,
    genbank_contig_id INT NOT NULL,
    region_type TEXT NOT NULL,
    name TEXT,
    start_pos BIGINT NOT NULL,
    end_pos BIGINT NOT NULL,
    modifier NUMERIC
);

CREATE TABLE cytoband (
    id SERIAL PRIMARY KEY,
    genbank_contig_id INT NOT NULL,
    name TEXT NOT NULL,
    start_pos BIGINT NOT NULL,
    end_pos BIGINT NOT NULL,
    stain TEXT NOT NULL
);
