-- Sequencing laboratory display name published on the sequencerun record.
-- Navigator resolves each run's facility (e.g. "Dante Labs") and now publishes it as
-- `sequencingFacility`. Stored so the AppView can display it directly and feed the
-- crowd-sourced instrument_id → lab consensus (many serials, e.g. PacBio, aren't mapped).
-- Nullable + backward-compatible: existing rows stay NULL until re-published (Navigator
-- uses a deterministic rkey, so a re-publish overwrites in place). Table: 0012_fed_reporting.sql.
ALTER TABLE fed.sequencerun ADD COLUMN sequencing_facility TEXT;

-- We filter/group by it in the coverage benchmark (COALESCE(lab.name, sequencing_facility)).
CREATE INDEX fed_sequencerun_facility_idx ON fed.sequencerun (sequencing_facility);
