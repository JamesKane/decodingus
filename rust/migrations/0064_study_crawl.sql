-- Project (ENA study / NCBI BioProject) crawl bookkeeping.
--
-- A curator attaches a project accession to a publication; a background job then
-- crawls the project's runs (ENA filereport), creating/linking biosamples and
-- their read files. These columns track the per-study crawl lifecycle so the job
-- can drain a pending queue and the curator UI can show status.

ALTER TABLE pubs.genomic_study
    ADD COLUMN crawl_status       TEXT NOT NULL DEFAULT 'none',  -- none | pending | done | error
    ADD COLUMN crawl_requested_at TIMESTAMPTZ,
    ADD COLUMN crawled_at         TIMESTAMPTZ,
    ADD COLUMN crawl_error        TEXT,
    ADD COLUMN sample_count       INTEGER,                       -- distinct samples last crawl
    ADD COLUMN run_count          INTEGER;                       -- runs seen last crawl

-- Partial index: the drainer only ever scans the (usually empty) pending set.
CREATE INDEX genomic_study_crawl_pending_idx
    ON pubs.genomic_study (crawl_requested_at) WHERE crawl_status = 'pending';
