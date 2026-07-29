-- Mutation-rate provenance: which rate produced which branch age, and room for
-- rates we derive ourselves.
--
-- Two gaps this closes.
--
-- (1) genomics.str_mutation_rate was one row per marker (marker_name UNIQUE), so a
--     rate we derive from our own tree could only land by overwriting the published
--     Willems/YHRD value — destroying the comparison that makes the derived number
--     trustworthy. Re-keyed on (marker_name, method) so PUBLISHED and DERIVED
--     coexist, with a partial unique index guaranteeing exactly one *active* rate
--     per marker. du_db::ystr::load_marker_models reads `WHERE is_active`, which
--     makes promoting a derived rate a data operation rather than a migration.
--
-- (2) Nothing recorded which rates an age estimate actually used. Both
--     compute_str_age and propagate_str silently fall back to
--     ystr::DEFAULT_STR_RATE (0.0025) for any marker without a rate row — and only
--     114 of the 829 markers we observe have one, so most of the STR age signal
--     rests on that placeholder. tree.haplogroup_age_estimate now carries the
--     split, so a branch age states how much of it is measured.
--
-- The observation columns (mutations_observed / meioses_observed) exist so a
-- derived rate carries its own evidence and CI, and so `is_active` can stay false
-- until a marker has accumulated enough meioses to be worth trusting. The
-- derivation job itself is a follow-up; see docs — the estimator must take branch
-- lengths from method='SNP_POISSON' ages ONLY, never COMBINED or STR_VARIANCE,
-- which would feed STR-derived ages back into STR rate estimation.

ALTER TABLE genomics.str_mutation_rate
    ADD COLUMN method             TEXT NOT NULL DEFAULT 'PUBLISHED',  -- PUBLISHED | DERIVED
    ADD COLUMN mutations_observed NUMERIC,   -- fractional: multi-step branches contribute >1
    ADD COLUMN meioses_observed   NUMERIC,   -- Σ branch length in generations
    ADD COLUMN derived_at         TIMESTAMPTZ,
    ADD COLUMN tree_revision      BIGINT,    -- tree.tree_revision the derivation ran against
    ADD COLUMN is_active          BOOLEAN NOT NULL DEFAULT true;

-- The existing 137 rows are all PUBLISHED and active by virtue of the defaults.
ALTER TABLE genomics.str_mutation_rate
    DROP CONSTRAINT str_mutation_rate_marker_name_key;
CREATE UNIQUE INDEX str_mutation_rate_marker_method_key
    ON genomics.str_mutation_rate (marker_name, method);
-- At most one rate per marker may feed the age model.
CREATE UNIQUE INDEX str_mutation_rate_active_key
    ON genomics.str_mutation_rate (marker_name) WHERE is_active;

COMMENT ON COLUMN genomics.str_mutation_rate.method IS
    'PUBLISHED (literature: Willems 2016 / YHRD) or DERIVED (estimated from our own tree)';
COMMENT ON COLUMN genomics.str_mutation_rate.is_active IS
    'The single rate per marker that du_db::ystr::load_marker_models feeds to the age model';

-- Which rates produced this age estimate. Written by ystr::recompute_signatures.
ALTER TABLE tree.haplogroup_age_estimate
    ADD COLUMN rate_method           TEXT,     -- PUBLISHED / DERIVED / MIXED
    ADD COLUMN measured_rate_markers INTEGER,  -- markers scored with a real rate row
    ADD COLUMN default_rate_markers  INTEGER;  -- markers that fell back to DEFAULT_STR_RATE

COMMENT ON COLUMN tree.haplogroup_age_estimate.default_rate_markers IS
    'Markers scored at ystr::DEFAULT_STR_RATE for want of a rate row — high values mean the estimate is weakly grounded';
