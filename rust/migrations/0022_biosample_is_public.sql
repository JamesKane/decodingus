-- Public-report visibility gate on the canonical biosample. Curators opt a
-- sample in (Navigator analysis → linked to a publication → core.biosample as the
-- canonical record). Default false: nothing becomes public implicitly. This is
-- the single visibility authority the eventual full core/fed consolidation relies
-- on — the per-sample public report (`/sample/:id`) filters on it.

ALTER TABLE core.biosample
    ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT false;

-- Partial index: the public report only ever filters on the true side, and the
-- public set is small relative to the table, so a partial index stays tiny.
-- Combined with `deleted = false` to match the existing biosample SELECT guard.
CREATE INDEX biosample_is_public_idx
    ON core.biosample (sample_guid) WHERE is_public AND deleted = false;
