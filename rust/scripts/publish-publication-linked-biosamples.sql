-- Publish publication-linked biosamples that the cutover left private.
--
-- Background: core.biosample.is_public defaults to false (migration 0022 —
-- "nothing becomes public implicitly"). The cutover ETL upsert
-- (du-migrate/src/transform.rs BIOSAMPLE_UPSERT) never sets is_public, so every
-- migrated STANDARD-source biosample fell to that default and 404s for
-- non-curators (routes/samples.rs gates on is_public). But ~99.98% of the legacy
-- biosamples are linked to a publication: they are published, externally-derived
-- cohort samples (1000 Genomes, etc.), not private user genomes. Leaving them
-- private is a cutover omission, not a privacy decision.
--
-- Rule applied here: a biosample sourced from a publication is public data.
--   is_public := true  where the sample has any pubs.publication_biosample link.
-- This makes ~18,389 STANDARD pages visible and leaves the ~7,886 STANDARD
-- samples with no publication link (citizen / own genomes) private.
--
-- Idempotent (only flips currently-private rows). Does NOT touch EXTERNAL de-novo
-- tips (already public) or deleted rows.
--
-- NOTE — this is a stopgap for the already-loaded cutover DB. The durable fix is
-- to encode the same rule in the cutover transform so a re-run is self-correcting.
-- NOTE — this does not resolve biosample duplication: ~2,677 of these accessioned
-- records share a donor with an already-public de-novo tip, so publishing them
-- surfaces two public rows per individual. The accessioned page already carries
-- the tip's tree placement via donor_id; hiding/merging the redundant bare tip is
-- a separate dedup follow-up.
--
--   psql "$DATABASE_URL" -f scripts/publish-publication-linked-biosamples.sql

\set ON_ERROR_STOP on
BEGIN;

\echo '--- visibility BEFORE (by source) ---'
SELECT source, is_public, count(*) AS n
FROM core.biosample
WHERE deleted = false
GROUP BY source, is_public
ORDER BY source, is_public;

UPDATE core.biosample b
SET is_public = true, updated_at = now()
WHERE b.is_public = false
  AND b.deleted = false
  AND EXISTS (
      SELECT 1 FROM pubs.publication_biosample pb
      WHERE pb.sample_guid = b.sample_guid
  );

\echo '--- visibility AFTER (by source) ---'
SELECT source, is_public, count(*) AS n
FROM core.biosample
WHERE deleted = false
GROUP BY source, is_public
ORDER BY source, is_public;

\echo '--- residual: publication-linked samples still private (expect 0) ---'
SELECT count(*) AS still_private_but_published
FROM core.biosample b
WHERE b.is_public = false AND b.deleted = false
  AND EXISTS (SELECT 1 FROM pubs.publication_biosample pb WHERE pb.sample_guid = b.sample_guid);

COMMIT;
