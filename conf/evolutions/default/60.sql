-- !Ups

-- Curator conflict resolution table
-- Allows curators to correct/override merge algorithm decisions before applying

CREATE TABLE tree.wip_resolution (
    resolution_id              SERIAL PRIMARY KEY,
    change_set_id              INTEGER NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,

    -- What we're resolving (at least one must be set)
    wip_haplogroup_id          INTEGER REFERENCES tree.wip_haplogroup(wip_haplogroup_id) ON DELETE CASCADE,
    wip_reparent_id            INTEGER REFERENCES tree.wip_reparent(wip_reparent_id) ON DELETE CASCADE,

    -- Resolution type
    resolution_type            VARCHAR(50) NOT NULL
                               CHECK (resolution_type IN ('REPARENT', 'EDIT_VARIANTS', 'MERGE_EXISTING', 'DEFER')),

    -- REPARENT: Change the parent of a node
    new_parent_id              INTEGER,              -- Production haplogroup ID
    new_parent_placeholder_id  INTEGER,              -- WIP haplogroup placeholder ID

    -- MERGE_EXISTING: Map WIP node to existing production node (don't create)
    merge_target_id            INTEGER,              -- Production haplogroup to merge into

    -- EDIT_VARIANTS: Add or remove variant associations
    variants_to_add            JSONB DEFAULT '[]',   -- Array of variant IDs to add
    variants_to_remove         JSONB DEFAULT '[]',   -- Array of variant IDs to remove

    -- DEFER: Move to manual review queue
    defer_reason               TEXT,
    defer_priority             VARCHAR(20) DEFAULT 'NORMAL'
                               CHECK (defer_priority IN ('LOW', 'NORMAL', 'HIGH', 'CRITICAL')),

    -- Curator tracking
    curator_id                 VARCHAR(100) NOT NULL,
    curator_notes              TEXT,

    -- Status tracking
    status                     VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING', 'APPLIED', 'CANCELLED')),

    -- Timestamps
    created_at                 TIMESTAMP NOT NULL DEFAULT NOW(),
    applied_at                 TIMESTAMP,

    -- At least one target must be specified
    CONSTRAINT wip_resolution_has_target CHECK (
        wip_haplogroup_id IS NOT NULL OR
        wip_reparent_id IS NOT NULL
    )
);

CREATE INDEX idx_wip_resolution_change_set ON tree.wip_resolution(change_set_id);
CREATE INDEX idx_wip_resolution_status ON tree.wip_resolution(status);
CREATE INDEX idx_wip_resolution_type ON tree.wip_resolution(resolution_type);
CREATE INDEX idx_wip_resolution_wip_haplogroup ON tree.wip_resolution(wip_haplogroup_id) WHERE wip_haplogroup_id IS NOT NULL;
CREATE INDEX idx_wip_resolution_wip_reparent ON tree.wip_resolution(wip_reparent_id) WHERE wip_reparent_id IS NOT NULL;

-- Add comment explaining the table
COMMENT ON TABLE tree.wip_resolution IS 'Curator corrections to merge algorithm decisions. Applied during change set promotion.';
COMMENT ON COLUMN tree.wip_resolution.resolution_type IS 'REPARENT=change parent, EDIT_VARIANTS=add/remove SNPs, MERGE_EXISTING=map to existing node, DEFER=needs manual review';

-- !Downs

DROP TABLE IF EXISTS tree.wip_resolution;
