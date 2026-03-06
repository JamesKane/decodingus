# --- !Ups

-- Genealogical anchors for historical age constraints on haplogroup branches
-- Supports known MRCAs, most distant known ancestors, and ancient DNA calibration points
CREATE TABLE tree.genealogical_anchor (
    id SERIAL PRIMARY KEY,
    haplogroup_id INTEGER NOT NULL REFERENCES tree.haplogroup(haplogroup_id) ON DELETE CASCADE,
    anchor_type VARCHAR(50) NOT NULL,
    date_ce INTEGER NOT NULL,
    date_uncertainty_years INTEGER,
    confidence NUMERIC(3,2),
    description TEXT,
    source VARCHAR(500),
    carbon_date_bp INTEGER,
    carbon_date_sigma INTEGER,
    created_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CHECK (anchor_type IN ('KNOWN_MRCA', 'MDKA', 'ANCIENT_DNA')),
    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX idx_genealogical_anchor_haplogroup ON tree.genealogical_anchor(haplogroup_id);
CREATE INDEX idx_genealogical_anchor_type ON tree.genealogical_anchor(anchor_type);

# --- !Downs

DROP INDEX IF EXISTS tree.idx_genealogical_anchor_type;
DROP INDEX IF EXISTS tree.idx_genealogical_anchor_haplogroup;
DROP TABLE IF EXISTS tree.genealogical_anchor;
