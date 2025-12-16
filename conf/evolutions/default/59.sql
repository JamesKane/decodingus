-- !Ups

-- Shadow/WIP tables for staging tree changes before production apply
-- Each table is scoped by change_set_id for easy cleanup on discard

-- WIP haplogroups - staged nodes not yet in production
CREATE TABLE tree.wip_haplogroup (
    wip_haplogroup_id    SERIAL PRIMARY KEY,
    change_set_id        INTEGER NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,

    -- Placeholder ID used during merge (negative numbers to avoid collision)
    placeholder_id       INTEGER NOT NULL,

    -- Haplogroup data (mirrors tree.haplogroup structure)
    name                 VARCHAR(255) NOT NULL,
    lineage              VARCHAR(255),
    description          TEXT,
    haplogroup_type      VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    source               VARCHAR(255) NOT NULL,
    confidence_level     VARCHAR(255) NOT NULL DEFAULT 'medium',

    -- Age estimates
    formed_ybp           INTEGER,
    formed_ybp_lower     INTEGER,
    formed_ybp_upper     INTEGER,
    tmrca_ybp            INTEGER,
    tmrca_ybp_lower      INTEGER,
    tmrca_ybp_upper      INTEGER,
    age_estimate_source  VARCHAR(255),

    -- Provenance tracking
    provenance           JSONB,

    -- Timestamps
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Unique within a change set
    UNIQUE (change_set_id, placeholder_id),
    UNIQUE (change_set_id, name)
);

CREATE INDEX idx_wip_haplogroup_change_set ON tree.wip_haplogroup(change_set_id);
CREATE INDEX idx_wip_haplogroup_name ON tree.wip_haplogroup(name);

-- WIP relationships - staged parent-child relationships
-- Can reference either production haplogroups (by real ID) or WIP haplogroups (by placeholder ID)
CREATE TABLE tree.wip_haplogroup_relationship (
    wip_relationship_id     SERIAL PRIMARY KEY,
    change_set_id           INTEGER NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,

    -- Child reference: either a real haplogroup ID or a placeholder (negative) ID
    child_haplogroup_id     INTEGER,           -- NULL if child is a WIP node
    child_placeholder_id    INTEGER,           -- NULL if child is a production node

    -- Parent reference: either a real haplogroup ID or a placeholder (negative) ID
    parent_haplogroup_id    INTEGER,           -- NULL if parent is a WIP node
    parent_placeholder_id   INTEGER,           -- NULL if parent is a production node

    -- Metadata
    source                  VARCHAR(255) NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Constraints
    CHECK (
        (child_haplogroup_id IS NOT NULL AND child_placeholder_id IS NULL) OR
        (child_haplogroup_id IS NULL AND child_placeholder_id IS NOT NULL)
    ),
    CHECK (
        (parent_haplogroup_id IS NOT NULL AND parent_placeholder_id IS NULL) OR
        (parent_haplogroup_id IS NULL AND parent_placeholder_id IS NOT NULL)
    )
);

CREATE INDEX idx_wip_relationship_change_set ON tree.wip_haplogroup_relationship(change_set_id);
CREATE INDEX idx_wip_relationship_child ON tree.wip_haplogroup_relationship(child_haplogroup_id);
CREATE INDEX idx_wip_relationship_parent ON tree.wip_haplogroup_relationship(parent_haplogroup_id);

-- WIP variant associations - staged variant links
CREATE TABLE tree.wip_haplogroup_variant (
    wip_haplogroup_variant_id  SERIAL PRIMARY KEY,
    change_set_id              INTEGER NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,

    -- Haplogroup reference: either real ID or placeholder
    haplogroup_id              INTEGER,        -- NULL if haplogroup is a WIP node
    haplogroup_placeholder_id  INTEGER,        -- NULL if haplogroup is a production node

    -- Variant reference (always a real variant ID from genomics.variant_v2)
    variant_id                 INTEGER NOT NULL,

    -- Metadata
    source                     VARCHAR(255),
    created_at                 TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Constraints
    CHECK (
        (haplogroup_id IS NOT NULL AND haplogroup_placeholder_id IS NULL) OR
        (haplogroup_id IS NULL AND haplogroup_placeholder_id IS NOT NULL)
    ),

    -- Unique variant per haplogroup within a change set
    UNIQUE (change_set_id, haplogroup_id, variant_id),
    UNIQUE (change_set_id, haplogroup_placeholder_id, variant_id)
);

CREATE INDEX idx_wip_variant_change_set ON tree.wip_haplogroup_variant(change_set_id);
CREATE INDEX idx_wip_variant_haplogroup ON tree.wip_haplogroup_variant(haplogroup_id);

-- WIP reparent operations - tracks existing nodes that should be moved
CREATE TABLE tree.wip_reparent (
    wip_reparent_id         SERIAL PRIMARY KEY,
    change_set_id           INTEGER NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,

    -- The existing production haplogroup to reparent
    haplogroup_id           INTEGER NOT NULL,

    -- Current parent in production (for rollback reference)
    old_parent_id           INTEGER,

    -- New parent: either real ID or placeholder
    new_parent_id           INTEGER,           -- NULL if new parent is a WIP node
    new_parent_placeholder_id INTEGER,         -- NULL if new parent is a production node

    -- Metadata
    source                  VARCHAR(255) NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Constraints
    CHECK (
        (new_parent_id IS NOT NULL AND new_parent_placeholder_id IS NULL) OR
        (new_parent_id IS NULL AND new_parent_placeholder_id IS NOT NULL)
    ),

    -- Only one reparent per haplogroup per change set
    UNIQUE (change_set_id, haplogroup_id)
);

CREATE INDEX idx_wip_reparent_change_set ON tree.wip_reparent(change_set_id);

-- !Downs

DROP TABLE IF EXISTS tree.wip_reparent;
DROP TABLE IF EXISTS tree.wip_haplogroup_variant;
DROP TABLE IF EXISTS tree.wip_haplogroup_relationship;
DROP TABLE IF EXISTS tree.wip_haplogroup;
