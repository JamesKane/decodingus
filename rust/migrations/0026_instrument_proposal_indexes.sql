-- D8 consensus hardening: stable proposal identity + aggregation/queue indexes.
-- The hourly recompute now UPSERTs the single active proposal per unresolved
-- instrument (instead of DELETE + re-INSERT), so a curator's open proposal id
-- stays stable across background runs. This partial unique index is the upsert
-- conflict arbiter (one active proposal per instrument).
CREATE UNIQUE INDEX instrument_association_proposal_active_key
    ON genomics.instrument_association_proposal (instrument_id)
    WHERE status IN ('PENDING', 'READY_FOR_REVIEW');

-- recompute aggregates observations by instrument; the curator queue filters and
-- sorts by status. Both are seq-scans without these as the federation grows.
CREATE INDEX instrument_observation_instrument_idx
    ON genomics.instrument_observation (instrument_id);
CREATE INDEX instrument_association_proposal_status_idx
    ON genomics.instrument_association_proposal (status);
