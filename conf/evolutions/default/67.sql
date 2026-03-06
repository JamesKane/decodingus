-- # --- !Ups

CREATE TABLE public.instrument_observation (
    id              SERIAL PRIMARY KEY,
    at_uri          VARCHAR(512) UNIQUE NOT NULL,
    at_cid          VARCHAR(128),
    instrument_id   VARCHAR(255) NOT NULL,
    lab_name        VARCHAR(255) NOT NULL,
    biosample_ref   VARCHAR(512) NOT NULL,
    sequence_run_ref VARCHAR(512),
    platform        VARCHAR(100),
    instrument_model VARCHAR(255),
    flowcell_id     VARCHAR(255),
    run_date        TIMESTAMP,
    confidence      VARCHAR(20) DEFAULT 'INFERRED' CHECK (confidence IN ('KNOWN', 'INFERRED', 'GUESSED')),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP
);

CREATE INDEX idx_instrument_obs_instrument ON public.instrument_observation (instrument_id);
CREATE INDEX idx_instrument_obs_lab ON public.instrument_observation (lab_name);
CREATE INDEX idx_instrument_obs_biosample ON public.instrument_observation (biosample_ref);
CREATE INDEX idx_instrument_obs_at_uri ON public.instrument_observation (at_uri);


-- # --- !Downs

DROP TABLE IF EXISTS public.instrument_observation;
