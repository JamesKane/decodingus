# --- !Ups

-- Variant Alias Table
-- Stores alternative names for variants from different sources (YBrowse, ISOGG, YFull, publications, etc.)
-- A single variant may be known by multiple names across different research groups.

CREATE TABLE variant_alias (
    id SERIAL PRIMARY KEY,
    variant_id INT NOT NULL REFERENCES variant(variant_id) ON DELETE CASCADE,
    alias_type VARCHAR(50) NOT NULL,      -- 'common_name', 'rs_id', 'isogg', 'yfull', 'ftdna', etc.
    alias_value VARCHAR(255) NOT NULL,
    source VARCHAR(255),                   -- Origin: 'ybrowse', 'isogg', 'curator', 'yfull', etc.
    is_primary BOOLEAN DEFAULT FALSE,      -- Primary alias for this type (for display preference)
    created_at TIMESTAMP DEFAULT NOW() NOT NULL,
    UNIQUE(variant_id, alias_type, alias_value)
);

CREATE INDEX idx_variant_alias_variant ON variant_alias(variant_id);
CREATE INDEX idx_variant_alias_value ON variant_alias(alias_value);
CREATE INDEX idx_variant_alias_type_value ON variant_alias(alias_type, alias_value);

-- Migrate existing names to alias table
-- This preserves the current common_name and rs_id as aliases
INSERT INTO variant_alias (variant_id, alias_type, alias_value, source, is_primary)
SELECT variant_id, 'common_name', common_name, 'migration', TRUE
FROM variant
WHERE common_name IS NOT NULL;

INSERT INTO variant_alias (variant_id, alias_type, alias_value, source, is_primary)
SELECT variant_id, 'rs_id', rs_id, 'migration', TRUE
FROM variant
WHERE rs_id IS NOT NULL;

# --- !Downs

DROP TABLE IF EXISTS variant_alias;
