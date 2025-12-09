-- !Ups

-- Population breakdown table for ancestry analysis results
-- Stores ADMIXTURE-style ancestry breakdowns at sub-continental granularity
CREATE TABLE population_breakdown (
    id SERIAL PRIMARY KEY,
    at_uri VARCHAR UNIQUE,
    at_cid VARCHAR,
    sample_guid UUID NOT NULL,
    analysis_method VARCHAR NOT NULL,           -- PCA_PROJECTION_GMM, ADMIXTURE, FASTSTRUCTURE, etc.
    panel_type VARCHAR,                         -- 'aims' (~5k SNPs) or 'genome-wide' (~500k SNPs)
    reference_populations VARCHAR,              -- '1000G_HGDP_v1', '1000G', 'HGDP', etc.
    snps_analyzed INT,                          -- Total SNPs in the analysis panel
    snps_with_genotype INT,                     -- SNPs with valid genotype calls
    snps_missing INT,                           -- SNPs with no call or missing data
    confidence_level DOUBLE PRECISION,          -- Overall confidence 0.0-1.0
    pca_coordinates JSONB,                      -- First 3 PCA coordinates [x, y, z]
    analysis_date TIMESTAMP,
    pipeline_version VARCHAR,
    reference_version VARCHAR,
    deleted BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_population_breakdown_sample_guid ON population_breakdown(sample_guid);
CREATE INDEX idx_population_breakdown_at_uri ON population_breakdown(at_uri) WHERE at_uri IS NOT NULL;

COMMENT ON TABLE population_breakdown IS 'Ancestry composition analysis results using PCA projection onto 1000G + HGDP reference populations';

-- Population components (sub-continental level, ~33 populations)
CREATE TABLE population_component (
    id SERIAL PRIMARY KEY,
    population_breakdown_id INT NOT NULL REFERENCES population_breakdown(id) ON DELETE CASCADE,
    population_code VARCHAR NOT NULL,           -- CEU, YRI, CHB, GIH, etc.
    population_name VARCHAR,                    -- Northwestern European, Yoruba, Han Chinese, etc.
    super_population VARCHAR,                   -- European, African, East Asian, South Asian, etc.
    percentage DOUBLE PRECISION NOT NULL,       -- 0.0-100.0
    confidence_lower DOUBLE PRECISION,          -- 95% CI lower bound
    confidence_upper DOUBLE PRECISION,          -- 95% CI upper bound
    rank INT                                    -- Display rank by percentage (1 = highest)
);

CREATE INDEX idx_population_component_breakdown ON population_component(population_breakdown_id);

COMMENT ON TABLE population_component IS 'Individual population components in an ancestry breakdown (~33 reference populations)';

-- Super-population summary (continental level, 9 super-populations)
CREATE TABLE super_population_summary (
    id SERIAL PRIMARY KEY,
    population_breakdown_id INT NOT NULL REFERENCES population_breakdown(id) ON DELETE CASCADE,
    super_population VARCHAR NOT NULL,          -- European, African, East Asian, etc.
    percentage DOUBLE PRECISION NOT NULL,       -- Combined percentage 0.0-100.0
    populations JSONB                           -- Array of contributing population codes
);

CREATE INDEX idx_super_population_breakdown ON super_population_summary(population_breakdown_id);

COMMENT ON TABLE super_population_summary IS 'Aggregated ancestry at continental level (9 super-populations)';

-- Seed reference populations lookup table if it doesn't exist with all codes
-- First check if population table exists and add missing populations
INSERT INTO population (population_name)
SELECT unnest(ARRAY[
    'CEU', 'FIN', 'GBR', 'IBS', 'TSI',           -- European
    'YRI', 'LWK', 'ESN', 'MSL', 'GWD',           -- African
    'CHB', 'JPT', 'KHV', 'CHS', 'CDX',           -- East Asian
    'GIH', 'PJL', 'BEB', 'STU', 'ITU',           -- South Asian
    'MXL', 'PUR', 'PEL', 'CLM',                  -- Americas
    'Druze', 'Palestinian', 'Bedouin',          -- West Asian (HGDP)
    'Papuan', 'Melanesian',                     -- Oceanian (HGDP)
    'Yakut',                                    -- Central Asian (HGDP)
    'Maya', 'Pima', 'Karitiana'                 -- Native American (HGDP)
])
ON CONFLICT (population_name) DO NOTHING;

-- !Downs

DROP TABLE IF EXISTS super_population_summary;
DROP TABLE IF EXISTS population_component;
DROP TABLE IF EXISTS population_breakdown;
