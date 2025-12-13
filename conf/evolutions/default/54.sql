# --- !Ups

-- =============================================================================
-- DU Naming Authority: Sequence and function for DecodingUs variant names
-- Per ISOGG guidelines: No zero padding (DU1, DU2, ... not DU00001)
-- =============================================================================

-- Sequence for DecodingUs variant names
CREATE SEQUENCE du_variant_name_seq START WITH 1;

-- Function to get next DU name (no zero padding per ISOGG request)
CREATE OR REPLACE FUNCTION next_du_name() RETURNS TEXT AS $$
BEGIN
    RETURN 'DU' || nextval('du_variant_name_seq')::TEXT;
END;
$$ LANGUAGE plpgsql;

-- Function to peek at current value without incrementing
CREATE OR REPLACE FUNCTION current_du_name() RETURNS TEXT AS $$
BEGIN
    RETURN 'DU' || currval('du_variant_name_seq')::TEXT;
END;
$$ LANGUAGE plpgsql;

-- Function to check if a name is a valid DU name
CREATE OR REPLACE FUNCTION is_du_name(name TEXT) RETURNS BOOLEAN AS $$
BEGIN
    RETURN name ~ '^DU[1-9][0-9]*$';
END;
$$ LANGUAGE plpgsql;

COMMENT ON SEQUENCE du_variant_name_seq IS 'Sequence for DecodingUs (DU) variant naming authority';
COMMENT ON FUNCTION next_du_name() IS 'Returns next available DU name (e.g., DU1, DU2, DU123)';
COMMENT ON FUNCTION current_du_name() IS 'Returns current DU name without incrementing sequence';
COMMENT ON FUNCTION is_du_name(TEXT) IS 'Validates if a name follows DU naming convention';

# --- !Downs

DROP FUNCTION IF EXISTS is_du_name(TEXT);
DROP FUNCTION IF EXISTS current_du_name();
DROP FUNCTION IF EXISTS next_du_name();
DROP SEQUENCE IF EXISTS du_variant_name_seq;
