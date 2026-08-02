-- Carry OpenAlex open-access / citation metadata through the discovery review
-- queue, and store DOIs in bare form.
--
-- The discovery search response already contains `open_access.oa_status` and
-- `cited_by_count`, but the candidate queue had nowhere to put them, so a
-- promoted candidate landed as a publication with NULL open_access_status —
-- no "Open access" badge and no citation count on /references until the nightly
-- by-DOI enrichment job happened to re-fetch the work.
--
-- OpenAlex also returns `doi` as a resolver URL (https://doi.org/10.x). That was
-- stored verbatim, which (a) rendered the reference list's DOI link as
-- https://doi.org/https://doi.org/… and (b) made exists_by_doi() miss, so a
-- paper already in the catalog could be queued a second time from the public
-- "suggest a paper" form. Ingest now normalizes; this backfills what is stored.

ALTER TABLE pubs.publication_candidate
    ADD COLUMN cited_by_count     INTEGER,
    ADD COLUMN open_access_status TEXT;

UPDATE pubs.publication_candidate
   SET doi = regexp_replace(doi, '^(https?://(dx\.)?doi\.org/|doi:)', '', 'i')
 WHERE doi ~* '^(https?://(dx\.)?doi\.org/|doi:)';

-- publication.doi is UNIQUE: skip any row whose bare form is already taken by
-- another publication (a pre-existing duplicate) rather than aborting the
-- migration. None exist in dev/cutover; this is a guard for prod.
UPDATE pubs.publication p
   SET doi = regexp_replace(p.doi, '^(https?://(dx\.)?doi\.org/|doi:)', '', 'i')
 WHERE p.doi ~* '^(https?://(dx\.)?doi\.org/|doi:)'
   AND NOT EXISTS (
       SELECT 1 FROM pubs.publication o
        WHERE o.id <> p.id
          AND o.doi = regexp_replace(p.doi, '^(https?://(dx\.)?doi\.org/|doi:)', '', 'i')
   );
