-- !Ups

CREATE TABLE publication_candidates (
    id SERIAL PRIMARY KEY,
    openalex_id VARCHAR(255) UNIQUE NOT NULL,
    doi VARCHAR(255),
    title TEXT NOT NULL,
    abstract TEXT,
    publication_date DATE,
    journal_name VARCHAR(500),
    relevance_score DOUBLE PRECISION,
    discovery_date TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    status VARCHAR(50) DEFAULT 'pending', -- pending, accepted, rejected, deferred
    reviewed_by UUID,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    rejection_reason TEXT,
    raw_metadata JSONB, -- Full OpenAlex response
    FOREIGN KEY (reviewed_by) REFERENCES public.users(id) ON DELETE SET NULL
);

CREATE TABLE publication_search_configs (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    search_query TEXT NOT NULL, -- OpenAlex query string
    concepts JSONB, -- OpenAlex concept IDs to filter
    journals JSONB, -- Journal/source filters
    enabled BOOLEAN DEFAULT TRUE,
    last_run TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE publication_search_runs (
    id SERIAL PRIMARY KEY,
    config_id INT REFERENCES publication_search_configs(id) ON DELETE CASCADE,
    run_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    candidates_found INT,
    new_candidates INT, -- After deduplication
    query_used TEXT,
    duration_ms INT
);

CREATE INDEX idx_pub_candidates_status ON publication_candidates(status);
CREATE INDEX idx_pub_candidates_relevance ON publication_candidates(relevance_score DESC) WHERE status = 'pending';
CREATE INDEX idx_pub_candidates_openalex ON publication_candidates(openalex_id);
CREATE INDEX idx_pub_candidates_doi ON publication_candidates(doi);

-- Insert default search config
INSERT INTO publication_search_configs (name, search_query, enabled)
VALUES ('Y-DNA Haplogroup Discovery', 'Y-DNA haplogroup', TRUE);

-- !Downs

DROP TABLE IF EXISTS publication_search_runs;
DROP TABLE IF EXISTS publication_search_configs;
DROP TABLE IF EXISTS publication_candidates;
