-- # --- !Ups

-- Phase 2: Consolidate alignment_coverage into alignment_metadata as JSONB
ALTER TABLE public.alignment_metadata ADD COLUMN coverage JSONB;

-- Migrate existing coverage data into the new JSONB column
UPDATE public.alignment_metadata am
SET coverage = jsonb_build_object(
    'meanDepth', ac.mean_depth,
    'medianDepth', ac.median_depth,
    'percentCoverageAt1x', ac.percent_coverage_at_1x,
    'percentCoverageAt5x', ac.percent_coverage_at_5x,
    'percentCoverageAt10x', ac.percent_coverage_at_10x,
    'percentCoverageAt20x', ac.percent_coverage_at_20x,
    'percentCoverageAt30x', ac.percent_coverage_at_30x,
    'basesNoCoverage', ac.bases_no_coverage,
    'basesLowQualityMapping', ac.bases_low_quality_mapping,
    'basesCallable', ac.bases_callable,
    'meanMappingQuality', ac.mean_mapping_quality
)
FROM public.alignment_coverage ac
WHERE ac.alignment_metadata_id = am.id;

-- Expression indexes for aggregation queries on JSONB coverage fields
CREATE INDEX idx_am_coverage_mean_depth ON public.alignment_metadata (((coverage->>'meanDepth')::double precision)) WHERE coverage IS NOT NULL;
CREATE INDEX idx_am_coverage_bases_callable ON public.alignment_metadata (((coverage->>'basesCallable')::bigint)) WHERE coverage IS NOT NULL;
CREATE INDEX idx_am_coverage_mean_mapping_quality ON public.alignment_metadata (((coverage->>'meanMappingQuality')::double precision)) WHERE coverage IS NOT NULL;

-- Drop the old table
DROP TABLE public.alignment_coverage;


-- # --- !Downs

-- Recreate alignment_coverage table
CREATE TABLE public.alignment_coverage (
    alignment_metadata_id BIGINT PRIMARY KEY REFERENCES alignment_metadata(id) ON DELETE CASCADE,
    mean_depth DOUBLE PRECISION,
    median_depth DOUBLE PRECISION,
    percent_coverage_at_1x DOUBLE PRECISION,
    percent_coverage_at_5x DOUBLE PRECISION,
    percent_coverage_at_10x DOUBLE PRECISION,
    percent_coverage_at_20x DOUBLE PRECISION,
    percent_coverage_at_30x DOUBLE PRECISION,
    bases_no_coverage BIGINT,
    bases_low_quality_mapping BIGINT,
    bases_callable BIGINT,
    mean_mapping_quality DOUBLE PRECISION
);

-- Migrate data back from JSONB to separate table
INSERT INTO public.alignment_coverage (
    alignment_metadata_id, mean_depth, median_depth,
    percent_coverage_at_1x, percent_coverage_at_5x, percent_coverage_at_10x,
    percent_coverage_at_20x, percent_coverage_at_30x,
    bases_no_coverage, bases_low_quality_mapping, bases_callable,
    mean_mapping_quality
)
SELECT id,
    (coverage->>'meanDepth')::double precision,
    (coverage->>'medianDepth')::double precision,
    (coverage->>'percentCoverageAt1x')::double precision,
    (coverage->>'percentCoverageAt5x')::double precision,
    (coverage->>'percentCoverageAt10x')::double precision,
    (coverage->>'percentCoverageAt20x')::double precision,
    (coverage->>'percentCoverageAt30x')::double precision,
    (coverage->>'basesNoCoverage')::bigint,
    (coverage->>'basesLowQualityMapping')::bigint,
    (coverage->>'basesCallable')::bigint,
    (coverage->>'meanMappingQuality')::double precision
FROM public.alignment_metadata
WHERE coverage IS NOT NULL;

-- Drop indexes and column
DROP INDEX IF EXISTS idx_am_coverage_mean_depth;
DROP INDEX IF EXISTS idx_am_coverage_bases_callable;
DROP INDEX IF EXISTS idx_am_coverage_mean_mapping_quality;
ALTER TABLE public.alignment_metadata DROP COLUMN coverage;
