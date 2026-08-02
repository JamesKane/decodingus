-- Precomputed corpus-wide Y-STR marker statistics (the /str-markers report).
--
-- The report was aggregating every profile on every request. Profiling the
-- 876-profile dev corpus put that at ~1.9s, and it scales linearly with the
-- number of profiles, so production was worse. The cost is NOT the JSONB
-- expansion (36ms for 514k observations) and it is NOT the reference joins
-- (13ms) — it is the aggregate machinery: mode() WITHIN GROUP, three ordered
-- array_agg()s and two DISTINCT counts, all over every expanded observation.
-- Indexes cannot touch any of that, and the obvious rewrites recovered only
-- ~25% (pre-aggregating by distinct value: 1.45s; raising work_mem: no change).
--
-- So it is precomputed instead, following the coverage-norms /
-- discovery-consensus / tree-samples-recompute pattern already in du-jobs: a
-- plain table refreshed by `du-jobs run-once str-marker-stats`, leaving the
-- report a ~829-row scan that no longer grows with the corpus. (A plain table
-- rather than a MATERIALIZED VIEW because this schema uses none, and because
-- the refresh needs to run inside the job's transaction alongside its logging.)
--
-- Refresh cadence: after the `ftdna-str` importer, and on a timer for the
-- federated path (fed.str_profile arrives continuously via Jetstream). The
-- content is a population summary, so brief staleness is acceptable — a newly
-- imported kit appears at the next refresh, not instantly.
--
-- Left empty here on purpose: the aggregation lives in du_db::ystr so it has one
-- definition, rather than being duplicated into this file where the two would
-- drift. du_db::ystr::marker_stats falls back to computing live while the table
-- is empty, so the page is correct before the first refresh — just slow.

CREATE TABLE genomics.str_marker_stat (
    marker_name       TEXT PRIMARY KEY,
    multi_copy        BOOLEAN NOT NULL,
    observations      BIGINT  NOT NULL,
    samples           BIGINT  NOT NULL,
    -- Simple markers carry repeat counts; multi-copy markers carry rendered
    -- copy vectors ("11-15"). Exactly one pair is populated per marker.
    min_value         INTEGER,
    modal_value       INTEGER,
    max_value         INTEGER,
    min_combination   TEXT,
    modal_combination TEXT,
    max_combination   TEXT,
    distinct_values   BIGINT  NOT NULL,
    null_alleles      BIGINT  NOT NULL,
    complex_count     BIGINT  NOT NULL,
    -- Denormalized from genomics.str_marker / str_mutation_rate at refresh time,
    -- so the read path is a single-table scan with no alias folding to redo.
    motif             TEXT,
    period            SMALLINT,
    coordinates       JSONB,
    mutation_rate     DOUBLE PRECISION,
    rate_ci_low       DOUBLE PRECISION,
    rate_ci_high      DOUBLE PRECISION,
    rate_method       TEXT,
    rate_source       TEXT,
    age_model_status  TEXT NOT NULL,
    refreshed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The report's default ordering.
CREATE INDEX str_marker_stat_observations_idx
    ON genomics.str_marker_stat (observations DESC, marker_name);

COMMENT ON TABLE genomics.str_marker_stat IS
    'Precomputed /str-markers report rows; refreshed by du-jobs run-once str-marker-stats';
