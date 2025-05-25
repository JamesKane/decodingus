# --- !Ups
--- Normalizing the metrics since Slick can't deal with that many columns
DROP TABLE IF EXISTS public.pangenome_alignment_metrics;

-- New Table 1: public.pangenome_alignment_metadata
-- Stores general metadata and region info about the alignment metrics
CREATE TABLE public.pangenome_alignment_metadata
(
    id                        BIGSERIAL PRIMARY KEY,
    sequence_file_id          BIGINT       NOT NULL REFERENCES public.sequence_file (id) ON DELETE CASCADE,
    pangenome_graph_id        INTEGER      NOT NULL REFERENCES public.pangenome_graph (id),
    metric_level              VARCHAR(50)  NOT NULL CHECK (metric_level IN ('GRAPH_OVERALL', 'PATH', 'NODE', 'REGION')),
    pangenome_path_id         INTEGER REFERENCES public.pangenome_path (id),
    pangenome_node_id         INTEGER REFERENCES public.pangenome_node (id),
    region_start_node_id      INTEGER REFERENCES public.pangenome_node (id), -- For 'REGION' level, start of the specific segment
    region_end_node_id        INTEGER REFERENCES public.pangenome_node (id), -- For 'REGION' level, end of the specific segment
    region_name               VARCHAR(255),
    region_length_bp          BIGINT,
    metrics_date              TIMESTAMP    NOT NULL DEFAULT NOW(),
    analysis_tool             VARCHAR(255) NOT NULL,
    analysis_tool_version     VARCHAR(50),
    notes                     TEXT,
    metadata                  JSONB
);

-- New Table 2: public.pangenome_alignment_coverage
-- Stores detailed coverage and quality metrics, linked to pangenome_alignment_metadata
CREATE TABLE public.pangenome_alignment_coverage
(
    alignment_metadata_id     BIGINT PRIMARY KEY REFERENCES public.pangenome_alignment_metadata (id) ON DELETE CASCADE,
    mean_depth                DOUBLE PRECISION,
    median_depth              DOUBLE PRECISION,
    percent_coverage_at_1x    DOUBLE PRECISION,
    percent_coverage_at_5x    DOUBLE PRECISION,
    percent_coverage_at_10x   DOUBLE PRECISION,
    percent_coverage_at_20x   DOUBLE PRECISION,
    percent_coverage_at_30x   DOUBLE PRECISION,
    bases_no_coverage         BIGINT,
    bases_low_quality_mapping BIGINT,
    bases_callable            BIGINT,
    mean_mapping_quality      DOUBLE PRECISION
);

# --- !Downs

drop table public.pangenome_alignment_coverage;
drop table public.pangenome_alignment_metadata;