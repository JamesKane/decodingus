-- Incremental OpenAlex discovery: per-config recency watermark + run history.
--
-- Prior discovery re-issued relevance-sorted searches capped at 50 results, so
-- brand-new (uncited) papers never ranked into view — the top 50 by OpenAlex
-- relevance are dominated by old, highly-cited works. Discovery now paginates by
-- publication_date DESC, filtered from a per-config high-water mark. These columns
-- persist that mark plus an audit trail of each run.

ALTER TABLE pubs.publication_search_config
    ADD COLUMN last_publication_date DATE,          -- max publication_date ingested so far
    ADD COLUMN last_run_at           TIMESTAMPTZ;    -- when discovery last completed this config

CREATE TABLE pubs.publication_search_run (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    config_id             BIGINT NOT NULL REFERENCES pubs.publication_search_config(id) ON DELETE CASCADE,
    ran_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    from_publication_date DATE,                      -- lower bound applied to this run's filter
    pages_fetched         INTEGER NOT NULL DEFAULT 0,
    candidates_seen       INTEGER NOT NULL DEFAULT 0,
    candidates_new        INTEGER NOT NULL DEFAULT 0, -- genuinely new inserts (vs re-seen)
    error                 TEXT
);
CREATE INDEX publication_search_run_config_idx ON pubs.publication_search_run (config_id, ran_at DESC);

-- Seed a default query set ONLY when no configs exist yet (fresh / greenfield /
-- dev DBs). Envs that already carry curator-defined queries are left untouched.
INSERT INTO pubs.publication_search_config (name, search_query)
SELECT seed.name, seed.search_query
FROM (VALUES
    ('Y-chromosome phylogeny',   'Y chromosome phylogeny haplogroup'),
    ('mitochondrial phylogeny',  'mitochondrial DNA phylogeny haplogroup'),
    ('ancient DNA population',   'ancient DNA population genetics'),
    ('Y-chromosome population',  'Y-chromosome haplogroup population genetics')
) AS seed(name, search_query)
WHERE NOT EXISTS (SELECT 1 FROM pubs.publication_search_config);
