# --- !Ups

-- ============================================================================
-- Evolution 57: Tree Versioning System
-- ============================================================================
-- Introduces Production/WIP tree versioning for bulk merge operations.
-- Change sets track groups of changes from external sources (ISOGG, ytree.net).
-- Individual changes are recorded for curator review before promotion.
-- ============================================================================

-- Change set status enum
CREATE TYPE tree.change_set_status AS ENUM (
    'DRAFT',           -- Being built (merge in progress)
    'READY_FOR_REVIEW', -- Merge complete, awaiting curator
    'UNDER_REVIEW',    -- Curator actively reviewing
    'APPLIED',         -- Changes applied to Production
    'DISCARDED'        -- Changes abandoned
);

-- Tree change type enum
CREATE TYPE tree.tree_change_type AS ENUM (
    'CREATE',          -- New haplogroup created
    'UPDATE',          -- Haplogroup metadata updated
    'DELETE',          -- Haplogroup deleted (soft)
    'REPARENT',        -- Parent relationship changed
    'ADD_VARIANT',     -- Variant associated with haplogroup
    'REMOVE_VARIANT'   -- Variant disassociated from haplogroup
);

-- Change status enum
CREATE TYPE tree.change_status AS ENUM (
    'PENDING',         -- Not yet applied
    'APPLIED',         -- Successfully applied to Production
    'REVERTED',        -- Undone by curator
    'SKIPPED'          -- Excluded from promotion by curator
);

-- ============================================================================
-- Change Sets: Groups of related changes from a single merge operation
-- ============================================================================

CREATE TABLE tree.change_set (
    id SERIAL PRIMARY KEY,
    haplogroup_type VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    source_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255) NOT NULL,
    finalized_at TIMESTAMP,
    applied_at TIMESTAMP,
    applied_by VARCHAR(255),
    discarded_at TIMESTAMP,
    discarded_by VARCHAR(255),
    discard_reason TEXT,
    status tree.change_set_status NOT NULL DEFAULT 'DRAFT',

    -- Statistics snapshot from merge
    nodes_processed INTEGER DEFAULT 0,
    nodes_created INTEGER DEFAULT 0,
    nodes_updated INTEGER DEFAULT 0,
    nodes_unchanged INTEGER DEFAULT 0,
    variants_added INTEGER DEFAULT 0,
    relationships_created INTEGER DEFAULT 0,
    relationships_updated INTEGER DEFAULT 0,
    split_operations INTEGER DEFAULT 0,
    ambiguity_count INTEGER DEFAULT 0,

    -- Path to generated ambiguity report
    ambiguity_report_path VARCHAR(500),

    -- Additional metadata
    metadata JSONB DEFAULT '{}',

    UNIQUE(haplogroup_type, name)
);

CREATE INDEX idx_change_set_type ON tree.change_set(haplogroup_type);
CREATE INDEX idx_change_set_status ON tree.change_set(status);
CREATE INDEX idx_change_set_source ON tree.change_set(source_name);
CREATE INDEX idx_change_set_created ON tree.change_set(created_at);

-- ============================================================================
-- Tree Changes: Individual changes within a change set
-- ============================================================================

CREATE TABLE tree.tree_change (
    id SERIAL PRIMARY KEY,
    change_set_id INTEGER NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,
    change_type tree.tree_change_type NOT NULL,

    -- Target identification (for UPDATE/DELETE/REPARENT)
    haplogroup_id INTEGER REFERENCES tree.haplogroup(haplogroup_id),

    -- For variant operations
    variant_id INTEGER REFERENCES variant(variant_id),

    -- Parent tracking (for CREATE and REPARENT)
    old_parent_id INTEGER REFERENCES tree.haplogroup(haplogroup_id),
    new_parent_id INTEGER REFERENCES tree.haplogroup(haplogroup_id),

    -- Full data snapshots (JSONB for flexibility)
    haplogroup_data JSONB,       -- Full haplogroup for CREATE, new values for UPDATE
    old_data JSONB,              -- Previous state for UPDATE (audit trail)

    -- For newly created haplogroups, track the assigned ID after apply
    created_haplogroup_id INTEGER REFERENCES tree.haplogroup(haplogroup_id),

    -- Ordering and status
    sequence_num INTEGER NOT NULL,
    status tree.change_status NOT NULL DEFAULT 'PENDING',

    -- Curator review
    reviewed_at TIMESTAMP,
    reviewed_by VARCHAR(255),
    review_notes TEXT,

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    applied_at TIMESTAMP,

    -- Ambiguity reference (if this change relates to an ambiguous placement)
    ambiguity_type VARCHAR(50),
    ambiguity_confidence DOUBLE PRECISION
);

CREATE INDEX idx_tree_change_set ON tree.tree_change(change_set_id);
CREATE INDEX idx_tree_change_hg ON tree.tree_change(haplogroup_id);
CREATE INDEX idx_tree_change_type ON tree.tree_change(change_type);
CREATE INDEX idx_tree_change_status ON tree.tree_change(status);
CREATE INDEX idx_tree_change_seq ON tree.tree_change(change_set_id, sequence_num);

-- ============================================================================
-- Change Set Comments: Discussion thread for curator collaboration
-- ============================================================================

CREATE TABLE tree.change_set_comment (
    id SERIAL PRIMARY KEY,
    change_set_id INTEGER NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,
    tree_change_id INTEGER REFERENCES tree.tree_change(id) ON DELETE CASCADE,
    author VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP
);

CREATE INDEX idx_change_set_comment_set ON tree.change_set_comment(change_set_id);
CREATE INDEX idx_change_set_comment_change ON tree.change_set_comment(tree_change_id);

-- ============================================================================
-- Views for easy querying
-- ============================================================================

-- Active (non-applied, non-discarded) change sets
CREATE VIEW tree.active_change_sets AS
SELECT *
FROM tree.change_set
WHERE status NOT IN ('APPLIED', 'DISCARDED');

-- Change set summary with review progress
CREATE VIEW tree.change_set_summary AS
SELECT
    cs.id,
    cs.haplogroup_type,
    cs.name,
    cs.source_name,
    cs.status,
    cs.created_at,
    cs.created_by,
    cs.nodes_created,
    cs.nodes_updated,
    cs.ambiguity_count,
    COUNT(tc.id) AS total_changes,
    COUNT(tc.id) FILTER (WHERE tc.status = 'PENDING') AS pending_changes,
    COUNT(tc.id) FILTER (WHERE tc.status = 'APPLIED') AS applied_changes,
    COUNT(tc.id) FILTER (WHERE tc.status = 'SKIPPED') AS skipped_changes,
    COUNT(tc.id) FILTER (WHERE tc.reviewed_at IS NOT NULL) AS reviewed_changes
FROM tree.change_set cs
LEFT JOIN tree.tree_change tc ON tc.change_set_id = cs.id
GROUP BY cs.id;

-- Pending changes requiring review (high priority = low confidence)
CREATE VIEW tree.pending_review_changes AS
SELECT
    tc.*,
    cs.name AS change_set_name,
    cs.source_name,
    h.name AS haplogroup_name
FROM tree.tree_change tc
JOIN tree.change_set cs ON cs.id = tc.change_set_id
LEFT JOIN tree.haplogroup h ON h.haplogroup_id = tc.haplogroup_id
WHERE tc.status = 'PENDING'
  AND cs.status IN ('READY_FOR_REVIEW', 'UNDER_REVIEW')
ORDER BY tc.ambiguity_confidence ASC NULLS LAST, tc.sequence_num;


# --- !Downs

DROP VIEW IF EXISTS tree.pending_review_changes;
DROP VIEW IF EXISTS tree.change_set_summary;
DROP VIEW IF EXISTS tree.active_change_sets;
DROP TABLE IF EXISTS tree.change_set_comment;
DROP TABLE IF EXISTS tree.tree_change;
DROP TABLE IF EXISTS tree.change_set;
DROP TYPE IF EXISTS tree.change_status;
DROP TYPE IF EXISTS tree.tree_change_type;
DROP TYPE IF EXISTS tree.change_set_status;
