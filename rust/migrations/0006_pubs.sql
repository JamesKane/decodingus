-- pubs schema: publications, genomic studies, and their links to samples.
-- De-sprawl: publication_biosample + publication_citizen_biosample collapse into
-- one link table now that biosamples are unified under core.biosample.

CREATE TYPE pubs.study_source AS ENUM ('ENA', 'NCBI_BIOPROJECT', 'NCBI_GENBANK');

CREATE TABLE pubs.genomic_study (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    accession       TEXT NOT NULL UNIQUE,
    title           TEXT,
    center_name     TEXT,
    study_name      TEXT,
    source          pubs.study_source NOT NULL DEFAULT 'ENA',
    bio_project_id  TEXT,
    molecule        TEXT,
    topology        TEXT,
    taxonomy_id     INTEGER,
    version         TEXT,            -- legacy genomic_studies.version is varchar
    submission_date DATE,
    details         JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE pubs.publication (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pubmed_id       TEXT UNIQUE,
    doi             TEXT UNIQUE,
    open_alex_id    TEXT UNIQUE,
    title           TEXT NOT NULL,
    journal         TEXT,
    publication_date DATE,
    url             TEXT,
    authors         TEXT,
    abstract_summary TEXT,
    citation_normalized_percentile NUMERIC,
    cited_by_count  INTEGER,
    open_access_status TEXT,
    open_access_url TEXT,
    primary_topic   TEXT,
    publication_type TEXT,
    publisher       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Unified publication<->biosample link (both standard and citizen samples).
CREATE TABLE pubs.publication_biosample (
    publication_id  BIGINT NOT NULL REFERENCES pubs.publication(id) ON DELETE CASCADE,
    sample_guid     UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    PRIMARY KEY (publication_id, sample_guid)
);

CREATE TABLE pubs.publication_study (
    publication_id  BIGINT NOT NULL REFERENCES pubs.publication(id) ON DELETE CASCADE,
    study_id        BIGINT NOT NULL REFERENCES pubs.genomic_study(id) ON DELETE CASCADE,
    PRIMARY KEY (publication_id, study_id)
);

-- Editorial review queue from OpenAlex discovery.
CREATE TABLE pubs.publication_candidate (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    openalex_id     TEXT NOT NULL UNIQUE,
    doi             TEXT,
    title           TEXT,
    abstract        TEXT,
    publication_date DATE,
    journal_name    TEXT,
    relevance_score NUMERIC,
    status          TEXT NOT NULL DEFAULT 'pending',   -- pending/accepted/rejected/deferred
    reviewed_by     UUID REFERENCES ident.users(id),
    raw_metadata    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX publication_candidate_status_idx ON pubs.publication_candidate (status);

CREATE TABLE pubs.publication_search_config (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    search_query    TEXT,
    concepts        JSONB NOT NULL DEFAULT '[]'::jsonb,
    enabled         BOOLEAN NOT NULL DEFAULT true
);
