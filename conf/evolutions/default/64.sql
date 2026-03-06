# --- !Ups

-- Ancestral STR motifs (modal haplotypes) per haplogroup branch
-- Used for STR-based age estimation and group project modal computations
CREATE TABLE tree.haplogroup_ancestral_str (
    id SERIAL PRIMARY KEY,
    haplogroup_id INTEGER NOT NULL REFERENCES tree.haplogroup(haplogroup_id) ON DELETE CASCADE,
    marker_name VARCHAR(50) NOT NULL,
    ancestral_value INTEGER,
    ancestral_value_alt INTEGER[],
    confidence NUMERIC(3,2),
    supporting_samples INTEGER,
    variance NUMERIC(8,4),
    computed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    method VARCHAR(50) NOT NULL DEFAULT 'MODAL',
    UNIQUE(haplogroup_id, marker_name),
    CHECK (method IN ('MODAL', 'PHYLOGENETIC', 'MANUAL')),
    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX idx_hg_ancestral_str_haplogroup ON tree.haplogroup_ancestral_str(haplogroup_id);
CREATE INDEX idx_hg_ancestral_str_marker ON tree.haplogroup_ancestral_str(marker_name);

# --- !Downs

DROP INDEX IF EXISTS tree.idx_hg_ancestral_str_marker;
DROP INDEX IF EXISTS tree.idx_hg_ancestral_str_haplogroup;
DROP TABLE IF EXISTS tree.haplogroup_ancestral_str;
