-- Preseeded instrument → lab association.
--
-- The genomics redesign (mig 0004) dropped the legacy `sequencer_instrument.lab_id`
-- FK on the theory that instrument↔lab would resolve through the consensus path
-- (instrument_observation → instrument_association_proposal → accept). That
-- consensus/curation machinery is NOT live yet, so the only associations we have
-- are the **preseeded** curator ties carried over from the legacy catalog — and
-- they had nowhere to live in the new schema.
--
-- Re-add a nullable direct `lab_id`: the lookup API (GET /api/v1/sequencer/lab)
-- reads it directly, and the ETL backfills it from the legacy tie. When the
-- proposal/consensus path does go live, accepting a proposal sets this column;
-- the proposal tables (instrument_observation, instrument_association_proposal)
-- stay dormant until then.

ALTER TABLE genomics.sequencer_instrument
    ADD COLUMN lab_id BIGINT REFERENCES genomics.sequencing_lab(id);

CREATE INDEX sequencer_instrument_lab_idx
    ON genomics.sequencer_instrument (lab_id) WHERE lab_id IS NOT NULL;
