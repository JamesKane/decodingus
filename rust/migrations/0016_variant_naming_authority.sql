-- Variant Naming Authority (planning/variant-naming-authority.md).
-- DecodingUs owns the `DU` Y-variant name prefix. A variant may exist BEFORE it
-- has an official name — discovered by coordinates, awaiting curation — so
-- `canonical_name` becomes nullable (NULL = unnamed, identified by coordinates).
-- The unique constraint applies only to named variants (NULLs are not unique).

ALTER TABLE core.variant ALTER COLUMN canonical_name DROP NOT NULL;

DROP INDEX core.variant_canonical_name_key;
CREATE UNIQUE INDEX variant_canonical_name_key
    ON core.variant (canonical_name) WHERE canonical_name IS NOT NULL;

-- The DU name authority: a monotonic sequence behind `DUxxxxx` identifiers.
CREATE SEQUENCE core.du_variant_name_seq;

-- Mint the next DU name, e.g. DU00001. Zero-padded to 5 digits, then natural
-- width beyond that.
CREATE FUNCTION core.next_du_name() RETURNS text
    LANGUAGE sql AS
$$ SELECT 'DU' || lpad(nextval('core.du_variant_name_seq')::text, 5, '0') $$;
