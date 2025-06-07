-- !Ups

-- First, drop existing tables in correct order
DROP TABLE IF EXISTS public.pangenome_alignment_coverage CASCADE;
DROP TABLE IF EXISTS public.pangenome_alignment_metadata CASCADE;
DROP TABLE IF EXISTS public.pangenome_variant_link CASCADE;
DROP TABLE IF EXISTS public.pangenome_edge CASCADE;
DROP TABLE IF EXISTS public.pangenome_path CASCADE;
DROP TABLE IF EXISTS public.pangenome_node CASCADE;
DROP TABLE IF EXISTS public.canonical_pangenome_variant CASCADE;
DROP TABLE IF EXISTS public.pangenome_graph CASCADE;

-- Create new simplified tables
CREATE TABLE public.pangenome_graph (
                                        id             BIGSERIAL PRIMARY KEY,
                                        graph_name     VARCHAR(255) NOT NULL,
                                        source_gfa_file VARCHAR(255),
                                        description    TEXT,
                                        creation_date  TIMESTAMP DEFAULT now() NOT NULL
);

CREATE TABLE public.pangenome_path (
                                       id             BIGSERIAL PRIMARY KEY,
                                       graph_id       BIGINT NOT NULL REFERENCES public.pangenome_graph(id),
                                       path_name      VARCHAR(255) NOT NULL,
                                       is_reference   BOOLEAN DEFAULT FALSE,
                                       length_bp      BIGINT,
                                       description    TEXT
);

CREATE TABLE public.pangenome_node (
                                       id              BIGSERIAL PRIMARY KEY,
                                       graph_id        BIGINT NOT NULL REFERENCES public.pangenome_graph(id),
                                       node_name       VARCHAR(255) NOT NULL,
                                       sequence_length BIGINT
);

CREATE TABLE public.canonical_pangenome_variant (
                                                    id                        BIGSERIAL PRIMARY KEY,
                                                    pangenome_graph_id       INTEGER NOT NULL REFERENCES public.pangenome_graph(id),
                                                    variant_type             VARCHAR(50) NOT NULL,
                                                    variant_nodes            INTEGER[] NOT NULL,
                                                    variant_edges            INTEGER[] DEFAULT '{}'::INTEGER[] NOT NULL,
                                                    reference_path_id        INTEGER REFERENCES public.pangenome_path(id),
                                                    reference_start_position INTEGER,
                                                    reference_end_position   INTEGER,
                                                    reference_allele_sequence TEXT,
                                                    alternate_allele_sequence TEXT,
                                                    canonical_hash           VARCHAR(255) NOT NULL UNIQUE,
                                                    description             TEXT,
                                                    creation_date           TIMESTAMP DEFAULT now() NOT NULL
);

CREATE TABLE public.pangenome_alignment_metadata (
                                                     id                    BIGSERIAL PRIMARY KEY,
                                                     sequence_file_id      BIGINT NOT NULL REFERENCES public.sequence_file(id) ON DELETE CASCADE,
                                                     pangenome_graph_id    INTEGER NOT NULL REFERENCES public.pangenome_graph(id),
                                                     metric_level          VARCHAR(50) NOT NULL CHECK (metric_level IN ('GRAPH_OVERALL', 'PATH', 'NODE', 'REGION')),
                                                     pangenome_path_id     INTEGER REFERENCES public.pangenome_path(id),
                                                     pangenome_node_id     INTEGER REFERENCES public.pangenome_node(id),
                                                     region_start_node_id  INTEGER REFERENCES public.pangenome_node(id),
                                                     region_end_node_id    INTEGER REFERENCES public.pangenome_node(id),
                                                     region_name           VARCHAR(255),
                                                     region_length_bp      BIGINT,
                                                     metrics_date          TIMESTAMP NOT NULL DEFAULT NOW(),
                                                     analysis_tool         VARCHAR(255) NOT NULL,
                                                     analysis_tool_version VARCHAR(50),
                                                     notes                TEXT,
                                                     metadata             JSONB
);

CREATE TABLE public.pangenome_alignment_coverage (
                                                     alignment_metadata_id     BIGINT PRIMARY KEY REFERENCES public.pangenome_alignment_metadata(id) ON DELETE CASCADE,
                                                     mean_depth               DOUBLE PRECISION,
                                                     median_depth             DOUBLE PRECISION,
                                                     percent_coverage_at_1x   DOUBLE PRECISION,
                                                     percent_coverage_at_5x   DOUBLE PRECISION,
                                                     percent_coverage_at_10x  DOUBLE PRECISION,
                                                     percent_coverage_at_20x  DOUBLE PRECISION,
                                                     percent_coverage_at_30x  DOUBLE PRECISION,
                                                     bases_no_coverage        BIGINT,
                                                     bases_low_quality_mapping BIGINT,
                                                     bases_callable           BIGINT,
                                                     mean_mapping_quality     DOUBLE PRECISION
);

-- !Downs

-- Re-create original tables in reverse order
DROP TABLE IF EXISTS public.pangenome_alignment_coverage CASCADE;
DROP TABLE IF EXISTS public.pangenome_alignment_metadata CASCADE;
DROP TABLE IF EXISTS public.canonical_pangenome_variant CASCADE;
DROP TABLE IF EXISTS public.pangenome_node CASCADE;
DROP TABLE IF EXISTS public.pangenome_path CASCADE;
DROP TABLE IF EXISTS public.pangenome_graph CASCADE;