-- STR-based branch-age estimation (a *contributing* refinement factor in the
-- combined age model — McDonald 2021, documents/proposals/branch-age-estimation.md).
-- Branch age combines independent evidence as P(t|e)=k·∏P(t|eᵢ): SNP counting,
-- Y-STR variance, genealogical/aDNA anchors. This migration adds the STR term's
-- inputs (per-marker mutation rates) and a labeled per-branch age-estimate store
-- (one row per method) — STR ages do NOT overwrite tree.haplogroup.tmrca_ybp
-- (the authoritative combined value); a future combiner aggregates the factors.

-- Per-marker Y-STR mutation rates (per generation). Sources: Ballantyne 2010,
-- Willems 2016. Ships empty — populated by a curator/import; until then the
-- age computation falls back to a documented average rate.
CREATE TABLE genomics.str_mutation_rate (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    marker_name         TEXT NOT NULL UNIQUE,
    panel_names         TEXT[],
    mutation_rate       NUMERIC(12,10) NOT NULL,   -- mutations / marker / generation
    mutation_rate_lower NUMERIC(12,10),            -- 95% CI lower
    mutation_rate_upper NUMERIC(12,10),            -- 95% CI upper
    omega_plus          NUMERIC(5,4) DEFAULT 0.5,  -- expansion bias
    omega_minus         NUMERIC(5,4) DEFAULT 0.5,  -- contraction bias
    multi_step_rate     NUMERIC(5,4),              -- ω±2 + ω±3 + …
    source              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Per-branch age estimates, one row per contributing method (STR_VARIANCE now;
-- SNP_POISSON / GENEALOGICAL / COMBINED later). Kept distinct from the
-- authoritative tree.haplogroup.{formed_ybp,tmrca_ybp}.
CREATE TABLE tree.haplogroup_age_estimate (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    haplogroup_id   BIGINT NOT NULL REFERENCES tree.haplogroup(id) ON DELETE CASCADE,
    method          TEXT NOT NULL,             -- STR_VARIANCE / SNP_POISSON / COMBINED / …
    estimate_ybp    INTEGER,
    ci_low_ybp      INTEGER,
    ci_high_ybp     INTEGER,
    sample_count    INTEGER,
    marker_count    INTEGER,
    generation_years NUMERIC(5,2),
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (haplogroup_id, method)
);
CREATE INDEX haplogroup_age_estimate_hg_idx ON tree.haplogroup_age_estimate (haplogroup_id);
