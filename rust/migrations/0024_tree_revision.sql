-- Tree revision marker — a persisted, monotonic counter the API serves as the
-- cache-revalidation token (ETag) for the haplogroup-tree endpoints. The Edge
-- (Navigator) caches the full tree JSON and needs a cheap way to tell whether the
-- AppView has a newer tree without re-downloading ~28 MB on a blind timer.
--
-- It is bumped explicitly (once) by every operation that changes a served tree
-- payload — change-set apply, coordinate enrichment (the hs1-backfill that caused
-- the staleness incident), the YBrowse reconcile, and the tree-init build — rather
-- than by a per-row trigger, to keep the hot per-variant write path free.
--
-- A single global row covers both Y and mt: variants (core.variant) aren't typed
-- Y/mt, so a coordinate enrichment can't be cheaply attributed to one tree. The
-- per-endpoint ETag folds in the dna type + root, so a global bump revalidates
-- both trees (a harmless over-invalidation, never a false 304).

CREATE TABLE tree.tree_revision (
    id          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    revision    BIGINT      NOT NULL DEFAULT 1,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Exactly one row; the CHECK + PK pin it as a singleton.
INSERT INTO tree.tree_revision (id) VALUES (1);
