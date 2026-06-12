-- D7: empirical per-test-type coverage norms. Derived (not curated) from the
-- federated coverage cohort (fed.coverage_summary ⋈ fed.sequencerun.test_type) +
-- fed.genotype marker counts, by du_db::coverage::recompute_norms. Lets a sample's
-- actual coverage be compared against what samples of its test type typically
-- achieve ("you ordered 30x, the cohort median is 29x, you got 28x"), and tracks
-- vendors against the norm. Typical marker counts are captured for the (deferred)
-- age-contribution weighting.

CREATE TABLE genomics.test_type_coverage_norm (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    test_type         TEXT NOT NULL UNIQUE,        -- federated/catalog test-type code
    sample_count      INTEGER NOT NULL DEFAULT 0,
    median_mean_depth DOUBLE PRECISION,
    p25_mean_depth    DOUBLE PRECISION,
    p75_mean_depth    DOUBLE PRECISION,
    median_pct_10x    DOUBLE PRECISION,
    median_pct_20x    DOUBLE PRECISION,
    median_pct_30x    DOUBLE PRECISION,
    typical_y_markers  INTEGER,                     -- median fed.genotype.y_markers_called (for age, deferred)
    typical_mt_markers INTEGER,
    computed_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
