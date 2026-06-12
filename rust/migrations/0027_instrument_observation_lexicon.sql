-- D8: the instrumentObservation lexicon end-to-end. Citizens publish explicit
-- instrument→lab observations (com.decodingus.atmosphere.instrumentObservation)
-- carrying a real confidence level (KNOWN/INFERRED/GUESSED) and an observation
-- timestamp. The Jetstream consumer mirrors them here; recompute_consensus folds
-- them in alongside the implicit fed.sequencerun.center_name claims.

CREATE TABLE fed.instrument_observation (
    did              TEXT NOT NULL,
    rkey             TEXT NOT NULL,
    at_uri           TEXT NOT NULL,
    cid              TEXT,
    instrument_id    TEXT,                  -- @RG instrument id (e.g. 'A00123')
    lab_name         TEXT,                  -- the citizen's claimed lab
    biosample_ref    TEXT,                  -- at-uri of the biosample it came from
    platform         TEXT,                  -- ILLUMINA/PACBIO/ONT/...
    instrument_model TEXT,
    flowcell_id      TEXT,
    run_date         DATE,
    confidence       TEXT,                  -- KNOWN/INFERRED/GUESSED
    observed_at      TIMESTAMPTZ,           -- when the citizen recorded it (recency)
    record_created_at TIMESTAMPTZ,
    time_us          BIGINT NOT NULL,
    indexed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);
CREATE INDEX fed_instrument_observation_instrument_idx
    ON fed.instrument_observation (instrument_id);

-- The consensus working table gains an observation timestamp so the confidence
-- score's recency term is real (it was a constant). Implicit sequencerun-derived
-- observations leave it NULL (treated as neutral recency).
ALTER TABLE genomics.instrument_observation ADD COLUMN observed_at TIMESTAMPTZ;
