# --- !Ups
--- Establish a sequence for citizen biosamples for acession generation
CREATE SEQUENCE IF NOT EXISTS citizen_biosample_seq START 1;

# --- !Downs
DROP SEQUENCE IF EXISTS citizen_biosample_seq;