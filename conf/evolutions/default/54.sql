# --- !Ups

-- Sequence for DecodingUs variant names
CREATE SEQUENCE IF NOT EXISTS du_variant_name_seq START WITH 1;

COMMENT ON SEQUENCE du_variant_name_seq IS 'Sequence for DecodingUs (DU) variant naming authority';

# --- !Downs

DROP FUNCTION IF EXISTS is_du_name(TEXT);
DROP FUNCTION IF EXISTS current_du_name();
DROP FUNCTION IF EXISTS next_du_name();
DROP SEQUENCE IF EXISTS du_variant_name_seq;
