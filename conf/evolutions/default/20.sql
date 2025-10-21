# --- !Ups

-- Create new alignment_metadata table for linear references
CREATE TABLE public.alignment_metadata
(
    id                    bigserial
        PRIMARY KEY,
    sequence_file_id      bigint                  NOT NULL
        REFERENCES public.sequence_file
            ON DELETE CASCADE,
    genbank_contig_id     integer                 NOT NULL
        REFERENCES public.genbank_contig
            ON DELETE CASCADE,
    metric_level          varchar(50)             NOT NULL
        CONSTRAINT alignment_metadata_metric_level_check
            CHECK ((metric_level)::text = ANY
                   ((ARRAY ['CONTIG_OVERALL'::character varying, 'REGION'::character varying])::text[])),
    region_name           varchar(255),
    region_start_pos      bigint,
    region_end_pos        bigint,
    region_length_bp      bigint,
    metrics_date          timestamp DEFAULT now() NOT NULL,
    analysis_tool         varchar(255)            NOT NULL,
    analysis_tool_version varchar(50),
    notes                 text,
    metadata              jsonb,
    CONSTRAINT valid_region_coordinates
        CHECK (
            (metric_level = 'CONTIG_OVERALL' AND region_start_pos IS NULL AND region_end_pos IS NULL)
                OR
            (metric_level = 'REGION' AND region_start_pos IS NOT NULL AND region_end_pos IS NOT NULL
                AND region_start_pos > 0 AND region_end_pos >= region_start_pos)
            )
);

-- Create new alignment_coverage table
CREATE TABLE public.alignment_coverage
(
    alignment_metadata_id     bigint NOT NULL
        PRIMARY KEY
        REFERENCES public.alignment_metadata
            ON DELETE CASCADE,
    mean_depth                double precision,
    median_depth              double precision,
    percent_coverage_at_1x    double precision,
    percent_coverage_at_5x    double precision,
    percent_coverage_at_10x   double precision,
    percent_coverage_at_20x   double precision,
    percent_coverage_at_30x   double precision,
    bases_no_coverage         bigint,
    bases_low_quality_mapping bigint,
    bases_callable            bigint,
    mean_mapping_quality      double precision
);

-- Create indices for efficient querying
CREATE INDEX idx_alignment_metadata_sequence_file
    ON public.alignment_metadata(sequence_file_id);

CREATE INDEX idx_alignment_metadata_genbank_contig
    ON public.alignment_metadata(genbank_contig_id);

CREATE INDEX idx_alignment_metadata_metric_level
    ON public.alignment_metadata(metric_level);

CREATE INDEX idx_alignment_metadata_region
    ON public.alignment_metadata(genbank_contig_id, region_start_pos, region_end_pos)
    WHERE metric_level = 'REGION';

-- Remove pangenome_path_id from genbank_contig
ALTER TABLE public.genbank_contig
    DROP COLUMN IF EXISTS pangenome_path_id;

-- Add comment explaining the migration
COMMENT ON TABLE public.alignment_metadata IS
    'Linear reference-based alignment statistics. Replaces pangenome_alignment_metadata.';

COMMENT ON TABLE public.alignment_coverage IS
    'Coverage statistics for linear reference alignments. Replaces pangenome_alignment_coverage.';

# --- !Downs

-- Restore pangenome_path_id to genbank_contig
ALTER TABLE public.genbank_contig
    ADD COLUMN IF NOT EXISTS pangenome_path_id integer;

-- Drop the new linear reference tables and their indices
DROP INDEX IF EXISTS public.idx_alignment_metadata_region;
DROP INDEX IF EXISTS public.idx_alignment_metadata_metric_level;
DROP INDEX IF EXISTS public.idx_alignment_metadata_genbank_contig;
DROP INDEX IF EXISTS public.idx_alignment_metadata_sequence_file;

DROP TABLE IF EXISTS public.alignment_coverage;
DROP TABLE IF EXISTS public.alignment_metadata;