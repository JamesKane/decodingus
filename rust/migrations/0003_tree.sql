-- tree schema: haplogroup phylogeny, temporal versioning, bulk-merge staging,
-- and the discovery pipeline. De-sprawl moves (plan §2):
--   * per-revision metadata tables (relationship_revision_metadata,
--     haplogroup_variant_metadata) fold into a `revision` JSONB column.
--   * discovery's polymorphic (sample_type, sample_id) collapses to one
--     core.biosample(sample_guid) FK.

-- ── Phylogeny ────────────────────────────────────────────────────────────────
CREATE TABLE tree.haplogroup (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            TEXT NOT NULL,
    haplogroup_type core.dna_type NOT NULL,
    lineage         TEXT,
    source          TEXT,
    confidence_level TEXT,
    formed_ybp      INTEGER,
    tmrca_ybp       INTEGER,
    -- multi-source attribution + age-estimate detail, formerly scattered columns.
    provenance      JSONB NOT NULL DEFAULT '{}'::jsonb,
    valid_from      TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until     TIMESTAMPTZ
);
CREATE UNIQUE INDEX haplogroup_name_type_key ON tree.haplogroup (name, haplogroup_type);
CREATE INDEX haplogroup_provenance_gin ON tree.haplogroup USING gin (provenance jsonb_path_ops);

-- Temporal parent/child edges. `revision` JSONB folds the old
-- relationship_revision_metadata (author/timestamp/comment/change_type).
CREATE TABLE tree.haplogroup_relationship (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    child_haplogroup_id  BIGINT NOT NULL REFERENCES tree.haplogroup(id),
    parent_haplogroup_id BIGINT REFERENCES tree.haplogroup(id),
    revision_id     INTEGER NOT NULL DEFAULT 1,
    source          TEXT,
    revision        JSONB NOT NULL DEFAULT '{}'::jsonb,
    valid_from      TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until     TIMESTAMPTZ
);
CREATE INDEX haplogroup_rel_child_idx ON tree.haplogroup_relationship (child_haplogroup_id);
CREATE INDEX haplogroup_rel_parent_idx ON tree.haplogroup_relationship (parent_haplogroup_id);
-- One currently-valid parent per child.
CREATE UNIQUE INDEX haplogroup_rel_current_child_key
    ON tree.haplogroup_relationship (child_haplogroup_id) WHERE valid_until IS NULL;

-- Defining variants per haplogroup. `revision` JSONB folds haplogroup_variant_metadata.
CREATE TABLE tree.haplogroup_variant (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    haplogroup_id   BIGINT NOT NULL REFERENCES tree.haplogroup(id),
    variant_id      BIGINT NOT NULL REFERENCES core.variant(id),
    revision        JSONB NOT NULL DEFAULT '{}'::jsonb,
    valid_from      TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until     TIMESTAMPTZ
);
CREATE UNIQUE INDEX haplogroup_variant_current_key
    ON tree.haplogroup_variant (haplogroup_id, variant_id) WHERE valid_until IS NULL;

-- Historical/archaeological date constraints for age estimation.
CREATE TABLE tree.genealogical_anchor (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    haplogroup_id   BIGINT NOT NULL REFERENCES tree.haplogroup(id),
    anchor_type     TEXT NOT NULL,           -- KNOWN_MRCA / MDKA / ANCIENT_DNA
    date_ce         INTEGER,
    carbon_date_bp  INTEGER,
    confidence      NUMERIC(4,3),
    details         JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX genealogical_anchor_hg_idx ON tree.genealogical_anchor (haplogroup_id);

-- Modal STR haplotypes for STR-based age estimation.
CREATE TABLE tree.haplogroup_ancestral_str (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    haplogroup_id   BIGINT NOT NULL REFERENCES tree.haplogroup(id),
    marker_name     TEXT NOT NULL,
    ancestral_value INTEGER NOT NULL,
    confidence      NUMERIC(4,3),
    method          TEXT                      -- MODAL / PHYLOGENETIC / MANUAL
);
CREATE INDEX haplogroup_ancestral_str_hg_idx ON tree.haplogroup_ancestral_str (haplogroup_id);

-- ── Versioning / bulk merge staging ──────────────────────────────────────────
CREATE TABLE tree.change_set (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source          TEXT NOT NULL,           -- ISOGG, ytree.net, ...
    haplogroup_type core.dna_type,
    status          tree.change_set_status NOT NULL DEFAULT 'DRAFT',
    description     TEXT,
    change_count    INTEGER NOT NULL DEFAULT 0,
    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    promoted_by     TEXT,
    promoted_at     TIMESTAMPTZ
);
CREATE INDEX change_set_status_idx ON tree.change_set (status);

CREATE TABLE tree.tree_change (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_set_id   BIGINT NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,
    change_type     tree.tree_change_type NOT NULL,
    haplogroup_id   BIGINT REFERENCES tree.haplogroup(id),
    old_values      JSONB,
    new_values      JSONB,
    status          TEXT NOT NULL DEFAULT 'PENDING'   -- PENDING/APPROVED/REJECTED
);
CREATE INDEX tree_change_set_idx ON tree.tree_change (change_set_id, status);

CREATE TABLE tree.change_set_comment (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_set_id   BIGINT NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,
    commented_by    TEXT NOT NULL,
    comment         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- WIP shadow tables hold proposed structure before it is applied to production.
CREATE TABLE tree.wip_haplogroup (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_set_id   BIGINT NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,
    placeholder_id  INTEGER NOT NULL,        -- negative temp id within the change set
    name            TEXT NOT NULL,
    source          TEXT,
    formed_ybp      INTEGER,
    provenance      JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX wip_haplogroup_cs_idx ON tree.wip_haplogroup (change_set_id);

CREATE TABLE tree.wip_relationship (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_set_id   BIGINT NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,
    child_placeholder_id  INTEGER NOT NULL,
    parent_placeholder_id INTEGER,
    parent_production_id  BIGINT REFERENCES tree.haplogroup(id)
);

CREATE TABLE tree.wip_haplogroup_variant (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_set_id   BIGINT NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,
    wip_haplogroup_id BIGINT NOT NULL REFERENCES tree.wip_haplogroup(id) ON DELETE CASCADE,
    variant_id      BIGINT NOT NULL REFERENCES core.variant(id)
);

CREATE TABLE tree.wip_reparent (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_set_id   BIGINT NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,
    haplogroup_id   BIGINT NOT NULL REFERENCES tree.haplogroup(id),
    old_parent_id   BIGINT REFERENCES tree.haplogroup(id),
    new_parent_id   BIGINT REFERENCES tree.haplogroup(id)
);

CREATE TABLE tree.wip_resolution (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_set_id   BIGINT NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,
    wip_haplogroup_id BIGINT REFERENCES tree.wip_haplogroup(id) ON DELETE CASCADE,
    wip_reparent_id BIGINT REFERENCES tree.wip_reparent(id) ON DELETE CASCADE,
    resolution_type TEXT NOT NULL,           -- REPARENT/EDIT_VARIANTS/MERGE_EXISTING/DEFER
    new_parent_id   BIGINT REFERENCES tree.haplogroup(id),
    merge_target_id BIGINT REFERENCES tree.haplogroup(id),
    details         JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- ── Discovery pipeline ───────────────────────────────────────────────────────
-- Private variants found in a sample. Was polymorphic (sample_type, sample_id);
-- now one core.biosample(sample_guid) FK.
CREATE TABLE tree.biosample_private_variant (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_guid     UUID NOT NULL REFERENCES core.biosample(sample_guid),
    variant_id      BIGINT NOT NULL REFERENCES core.variant(id),
    haplogroup_type core.dna_type NOT NULL,
    terminal_haplogroup_id BIGINT REFERENCES tree.haplogroup(id),
    status          TEXT NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/PROMOTED/INVALIDATED
    discovered_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX bpv_sample_idx ON tree.biosample_private_variant (sample_guid);
CREATE INDEX bpv_variant_idx ON tree.biosample_private_variant (variant_id);

CREATE TABLE tree.proposed_branch (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposed_name   TEXT,
    parent_haplogroup_id BIGINT REFERENCES tree.haplogroup(id),
    discovery_sample_guids UUID[] NOT NULL DEFAULT '{}',
    evidence_count  INTEGER NOT NULL DEFAULT 0,
    confidence      NUMERIC(4,3),
    proposed_by     TEXT,
    status          TEXT NOT NULL DEFAULT 'PROPOSED'   -- PROPOSED/UNDER_REVIEW/REJECTED/ACCEPTED
);

CREATE TABLE tree.proposed_branch_variant (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposed_branch_id BIGINT NOT NULL REFERENCES tree.proposed_branch(id) ON DELETE CASCADE,
    variant_id      BIGINT NOT NULL REFERENCES core.variant(id),
    supporting_sample_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE tree.proposed_branch_evidence (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposed_branch_id BIGINT NOT NULL REFERENCES tree.proposed_branch(id) ON DELETE CASCADE,
    evidence_type   TEXT NOT NULL,           -- PRIVATE_VARIANT/SHARED_DERIVED/STRVAL_SIMILARITY/PUBLICATION
    evidence_detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    confidence      NUMERIC(4,3)
);

CREATE TABLE tree.curator_action (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposed_branch_id BIGINT REFERENCES tree.proposed_branch(id) ON DELETE CASCADE,
    action          TEXT NOT NULL,           -- APPROVE/DEFER/REJECT
    notes           TEXT,
    action_by       TEXT,
    action_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tree.discovery_config (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    config_key      TEXT NOT NULL UNIQUE,
    config_value    JSONB NOT NULL DEFAULT '{}'::jsonb,
    description     TEXT
);
