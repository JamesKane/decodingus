-- =============================================================================
-- DU Naming Authority: Sequence and function for DecodingUs variant names
-- Per ISOGG guidelines: No zero padding (DU1, DU2, ... not DU00001)
-- =============================================================================



-- Function to get next DU name (no zero padding per ISOGG request)
CREATE OR REPLACE FUNCTION next_du_name() RETURNS TEXT AS $func$
BEGIN
    RETURN 'DU' || nextval('du_variant_name_seq')::TEXT;
END;
$func$ LANGUAGE plpgsql;

-- Function to peek at current value without incrementing
CREATE OR REPLACE FUNCTION current_du_name() RETURNS TEXT AS $func$
BEGIN
    RETURN 'DU' || currval('du_variant_name_seq')::TEXT;
END;
$func$ LANGUAGE plpgsql;

-- Function to check if a name is a valid DU name
CREATE OR REPLACE FUNCTION is_du_name(name TEXT) RETURNS BOOLEAN AS $func$
BEGIN
    RETURN name ~ '^DU[1-9][0-9]*$';
END;
$func$ LANGUAGE plpgsql;

COMMENT ON FUNCTION next_du_name() IS 'Returns next available DU name (e.g., DU1, DU2, DU123)';
COMMENT ON FUNCTION current_du_name() IS 'Returns current DU name without incrementing sequence';
COMMENT ON FUNCTION is_du_name(TEXT) IS 'Validates if a name follows DU naming convention';