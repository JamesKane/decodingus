# --- !Ups
--- New tables to enable Pan Genome instead of traditional linear references

-- New Table: public.pangenome_alignment_metrics
-- Tracks quality metrics for alignment against a pangenome graph or its specific paths/nodes.
CREATE TABLE public.pangenome_alignment_metrics
(
    id                        BIGSERIAL PRIMARY KEY,
    sequence_file_id          BIGINT       NOT NULL REFERENCES public.sequence_file (id) ON DELETE CASCADE,
    pangenome_graph_id        INTEGER      NOT NULL REFERENCES public.pangenome_graph (id),
    metric_level              VARCHAR(50)  NOT NULL CHECK (metric_level IN ('GRAPH_OVERALL', 'PATH', 'NODE', 'REGION')),
    pangenome_path_id         INTEGER REFERENCES public.pangenome_path (id), -- Null if metric_level is GRAPH_OVERALL or NODE
    pangenome_node_id         INTEGER REFERENCES public.pangenome_node (id), -- Null if metric_level is GRAPH_OVERALL or PATH/REGION
    region_start_node_id      INTEGER REFERENCES public.pangenome_node (id), -- For 'REGION' level, start of the specific segment
    region_end_node_id        INTEGER REFERENCES public.pangenome_node (id), -- For 'REGION' level, end of the specific segment
    region_name               VARCHAR(255),
    region_length_bp          BIGINT,

    -- Core Coverage Metrics
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

    -- Mapping Quality Metrics
    mean_mapping_quality      DOUBLE PRECISION,

    -- Metadata and Provenance
    metrics_date              TIMESTAMP    NOT NULL DEFAULT NOW(),
    analysis_tool             VARCHAR(255) NOT NULL,
    analysis_tool_version     VARCHAR(50),
    notes                     TEXT,
    metadata                  JSONB
);

-- Indexes for performance
CREATE INDEX idx_pam_sequence_file_id ON public.pangenome_alignment_metrics (sequence_file_id);
CREATE INDEX idx_pam_pangenome_graph_id ON public.pangenome_alignment_metrics (pangenome_graph_id);
CREATE INDEX idx_pam_metric_level ON public.pangenome_alignment_metrics (metric_level);
CREATE INDEX idx_pam_pangenome_path_id ON public.pangenome_alignment_metrics (pangenome_path_id);
CREATE INDEX idx_pam_metrics_date ON public.pangenome_alignment_metrics (metrics_date);

ALTER TABLE public.sequence_file
    ADD COLUMN pangenome_graph_id INTEGER REFERENCES public.pangenome_graph(id);

-- UNUSED Table
DROP TABLE public.quality_metrics;

# --- !Downs

ALTER TABLE public.sequence_file DROP COLUMN pangenome_graph_id;

DROP TABLE public.pangenome_alignment_metrics;