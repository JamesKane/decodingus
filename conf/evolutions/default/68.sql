# --- !Ups

-- Instrument association proposals for consensus-based lab inference
CREATE TABLE public.instrument_association_proposal (
    id SERIAL PRIMARY KEY,
    instrument_id VARCHAR(255) NOT NULL,
    proposed_lab_name VARCHAR(255) NOT NULL,
    proposed_manufacturer VARCHAR(255),
    proposed_model VARCHAR(255),
    existing_lab_id INTEGER REFERENCES public.sequencing_lab(id),
    observation_count INTEGER NOT NULL DEFAULT 0,
    distinct_citizen_count INTEGER NOT NULL DEFAULT 0,
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    earliest_observation TIMESTAMP,
    latest_observation TIMESTAMP,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'READY_FOR_REVIEW', 'UNDER_REVIEW',
                          'ACCEPTED', 'REJECTED', 'SUPERSEDED')),
    reviewed_at TIMESTAMP,
    reviewed_by VARCHAR(255),
    review_notes TEXT,
    accepted_lab_id INTEGER REFERENCES public.sequencing_lab(id),
    accepted_instrument_id INTEGER REFERENCES public.sequencer_instrument(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_iap_status ON instrument_association_proposal(status);
CREATE INDEX idx_iap_instrument ON instrument_association_proposal(instrument_id);
CREATE UNIQUE INDEX idx_iap_active_instrument ON instrument_association_proposal(instrument_id)
    WHERE status NOT IN ('ACCEPTED', 'REJECTED', 'SUPERSEDED');

-- Add observation tracking columns to existing sequencer_instrument table
ALTER TABLE public.sequencer_instrument
    ADD COLUMN source VARCHAR(30) DEFAULT 'CURATOR'
        CHECK (source IN ('CURATOR', 'CONSENSUS', 'PUBLICATION')),
    ADD COLUMN observation_count INTEGER DEFAULT 0,
    ADD COLUMN confidence_score DOUBLE PRECISION DEFAULT 1.0,
    ADD COLUMN last_observed_at TIMESTAMP;

CREATE INDEX idx_si_confidence ON public.sequencer_instrument(confidence_score DESC);

# --- !Downs

DROP INDEX IF EXISTS idx_si_confidence;

ALTER TABLE public.sequencer_instrument
    DROP COLUMN IF EXISTS source,
    DROP COLUMN IF EXISTS observation_count,
    DROP COLUMN IF EXISTS confidence_score,
    DROP COLUMN IF EXISTS last_observed_at;

DROP TABLE IF EXISTS public.instrument_association_proposal;
