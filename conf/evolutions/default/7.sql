# --- !Ups
--- New tables to enable Pan Genome instead of traditional linear references

-- -----------------------------------------------------------
-- 1. New Tables: Pangenome Core & Reference Data
-- -----------------------------------------------------------

-- Table: public.pangenome_graph
-- Defines unique versions or builds of the pangenome graph itself.
CREATE TABLE public.pangenome_graph
(
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(255) NOT NULL UNIQUE,
    description   TEXT,
    creation_date TIMESTAMP    NOT NULL DEFAULT NOW(),
    checksum      VARCHAR(255)
);

-- Table: public.assembly_metadata
-- Stores metadata about the source assemblies (e.g., GRCh37, GRCh38, CHM13v2.0)
CREATE TABLE public.assembly_metadata
(
    id              BIGSERIAL PRIMARY KEY,
    assembly_name   VARCHAR(255) NOT NULL UNIQUE,
    accession       VARCHAR(255),
    release_date    DATE,
    source_organism VARCHAR(255),
    assembly_level  VARCHAR(50),
    metadata        JSONB
);

-- Table: public.pangenome_node
-- Represents the atomic, shared DNA segments that are the building blocks of the pangenome graph.
CREATE TABLE public.pangenome_node
(
    id            BIGSERIAL PRIMARY KEY,
    graph_id      INTEGER NOT NULL REFERENCES public.pangenome_graph (id),
    sequence      TEXT    NOT NULL,
    length        INTEGER NOT NULL,
    is_core       BOOLEAN,
    annotation_id INTEGER
);

-- Table: public.pangenome_edge
-- Defines the connections or adjacencies between pangenome_node's.
CREATE TABLE public.pangenome_edge
(
    id                 BIGSERIAL PRIMARY KEY,
    graph_id           INTEGER    NOT NULL REFERENCES public.pangenome_graph (id),
    source_node_id     INTEGER    NOT NULL REFERENCES public.pangenome_node (id),
    target_node_id     INTEGER    NOT NULL REFERENCES public.pangenome_node (id),
    source_orientation VARCHAR(1) NOT NULL CHECK (source_orientation IN ('+', '-')),
    target_orientation VARCHAR(1) NOT NULL CHECK (target_orientation IN ('+', '-')),
    type               VARCHAR(50),
    UNIQUE (graph_id, source_node_id, target_node_id, source_orientation, target_orientation)
);

-- Table: public.pangenome_path
-- Represents specific linear sequences (like GRCh38 chr1, Y-DNA reference) as ordered traversals through pangenome_nodes.
CREATE TABLE public.pangenome_path
(
    id                 BIGSERIAL PRIMARY KEY,
    graph_id           INTEGER      NOT NULL REFERENCES public.pangenome_graph (id),
    name               VARCHAR(255) NOT NULL,
    node_sequence      INTEGER[]    NOT NULL,
    length             BIGINT       NOT NULL,
    source_assembly_id INTEGER REFERENCES public.assembly_metadata (id),
    UNIQUE (graph_id, name)
);

-- Table: public.gene_annotation (Optional, if not existing or needs separate table)
-- Stores metadata about genes.
CREATE TABLE public.gene_annotation
(
    id                              BIGSERIAL PRIMARY KEY,
    gene_symbol                     VARCHAR(255),
    gene_id                         VARCHAR(255),
    description                     TEXT,
    representative_sequence_node_id INTEGER REFERENCES public.pangenome_node (id)
);

-- Add the foreign key to pangenome_node now that gene_annotation exists
ALTER TABLE public.pangenome_node
    ADD CONSTRAINT fk_pangenome_node_annotation FOREIGN KEY (annotation_id) REFERENCES public.gene_annotation (id);

-- -----------------------------------------------------------
-- 2. New Tables: Variant Representation & Linkage
-- -----------------------------------------------------------

-- Table: public.canonical_pangenome_variant
-- Represents a unique, abstract variant (SNP, INDEL, Structural Variant) as defined within a specific pangenome graph.
CREATE TABLE public.canonical_pangenome_variant
(
    id                        BIGSERIAL PRIMARY KEY,
    pangenome_graph_id        INTEGER      NOT NULL REFERENCES public.pangenome_graph (id),
    variant_type              VARCHAR(50)  NOT NULL,
    variant_nodes             INTEGER[]    NOT NULL,
    variant_edges             INTEGER[]    NOT NULL DEFAULT '{}',
    reference_path_id         INTEGER REFERENCES public.pangenome_path (id),
    reference_start_position  INTEGER,
    reference_end_position    INTEGER,
    reference_allele_sequence TEXT,
    alternate_allele_sequence TEXT,
    canonical_hash            VARCHAR(255) NOT NULL UNIQUE,
    description               TEXT,
    creation_date             TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Table: public.pangenome_variant_link
-- Bridges existing public.variant (legacy marker) to its canonical pangenome representation.
CREATE TABLE public.pangenome_variant_link
(
    pangenome_variant_link_id      BIGSERIAL PRIMARY KEY,
    variant_id                     INTEGER      NOT NULL REFERENCES public.variant (variant_id) ON DELETE CASCADE,
    canonical_pangenome_variant_id INTEGER      NOT NULL REFERENCES public.canonical_pangenome_variant (id) ON DELETE CASCADE,
    pangenome_graph_id             INTEGER      NOT NULL REFERENCES public.pangenome_graph (id),
    description                    TEXT,
    mapping_source                 VARCHAR(255) NOT NULL,
    mapping_date                   TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (variant_id, canonical_pangenome_variant_id)
);

-- -----------------------------------------------------------
-- 3. New Table: Sample-Specific Variant Calls
-- -----------------------------------------------------------

-- Table: public.reported_variant_pangenome
-- Stores the detailed variant calls detected for each sample_guid against a pangenome graph.
CREATE TABLE public.reported_variant_pangenome
(
    id                        BIGSERIAL PRIMARY KEY,
    sample_guid               UUID             NOT NULL,
    graph_id                  INTEGER          NOT NULL REFERENCES public.pangenome_graph (id),
    variant_type              VARCHAR(50)      NOT NULL CHECK (variant_type IN (
                                                                                'SNP', 'INDEL', 'SV_INSERTION',
                                                                                'SV_DELETION', 'SV_INVERSION',
                                                                                'SV_DUPLICATION', 'SV_TRANSLOCATION',
                                                                                'PAV_GENE', 'STR', 'CNV', 'COMPLEX'
        )),
    reference_path_id         INTEGER REFERENCES public.pangenome_path (id),
    reference_start_position  INTEGER,
    reference_end_position    INTEGER,
    variant_nodes             INTEGER[]        NOT NULL,
    variant_edges             INTEGER[]        NOT NULL DEFAULT '{}',
    alternate_allele_sequence TEXT,
    reference_allele_sequence TEXT,
    reference_repeat_count    INTEGER,
    alternate_repeat_count    INTEGER,
    allele_fraction           DOUBLE PRECISION,
    depth                     INTEGER,
    reported_date             TIMESTAMP        NOT NULL DEFAULT NOW(),
    provenance                VARCHAR(255)     NOT NULL,
    confidence_score          DOUBLE PRECISION NOT NULL,
    notes                     TEXT,
    status                    VARCHAR(255)     NOT NULL,
    zygosity                  VARCHAR(10) CHECK (zygosity IN ('HOM_REF', 'HET', 'HOM_ALT', 'UNKNOWN')),
    haplotype_information     JSONB
);

-- IBD Discovery and Consensus

-- -----------------------------------------------------------
-- 4. New Table: public.validation_service
-- -----------------------------------------------------------
CREATE TABLE public.validation_service
(
    id          BIGSERIAL PRIMARY KEY,
    guid        UUID         NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    trust_level VARCHAR(50)
);

-- -----------------------------------------------------------
-- 5. New Table: public.ibd_discovery_index
-- A central, privacy-preserving index for IBD matches.
-- This table represents the *match event itself*.
-- -----------------------------------------------------------
CREATE TABLE public.ibd_discovery_index
(
    id                         BIGSERIAL PRIMARY KEY,
    sample_guid_1              UUID        NOT NULL,
    sample_guid_2              UUID        NOT NULL,
    pangenome_graph_id         INTEGER     NOT NULL REFERENCES public.pangenome_graph (id),
    match_region_type          VARCHAR(50) NOT NULL CHECK (match_region_type IN
                                                           ('AUTOSOMAL', 'X_CHROMOSOME', 'Y_CHROMOSOME', 'MT_DNA',
                                                            'ALL_CHROMOSOMES')),
    total_shared_cm_approx     DOUBLE PRECISION,
    num_shared_segments_approx INTEGER,
    is_publicly_discoverable   BOOLEAN     NOT NULL DEFAULT FALSE,
    consensus_status           VARCHAR(50) NOT NULL DEFAULT 'INITIATED',
    last_consensus_update      TIMESTAMP   NOT NULL DEFAULT NOW(),
    validation_service_guid    UUID REFERENCES public.validation_service (guid),
    validation_timestamp       TIMESTAMP,
    indexed_by_service         VARCHAR(255),
    indexed_date               TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Unique constraint for IBD pairs (order-independent)
CREATE UNIQUE INDEX idx_unique_ibd_discovery_pair ON public.ibd_discovery_index (
                                                                                 LEAST(sample_guid_1, sample_guid_2),
                                                                                 GREATEST(sample_guid_1, sample_guid_2),
                                                                                 pangenome_graph_id,
                                                                                 match_region_type
    );

-- Indexes for ibd_discovery_index
CREATE INDEX idx_ibd_discovery_sample1 ON public.ibd_discovery_index (sample_guid_1);
CREATE INDEX idx_ibd_discovery_sample2 ON public.ibd_discovery_index (sample_guid_2);
CREATE INDEX idx_ibd_discovery_graph_id ON public.ibd_discovery_index (pangenome_graph_id);
CREATE INDEX idx_ibd_discovery_region_type ON public.ibd_discovery_index (match_region_type);
CREATE INDEX idx_ibd_discovery_cm_approx ON public.ibd_discovery_index (total_shared_cm_approx);
CREATE INDEX idx_ibd_discovery_public_status ON public.ibd_discovery_index (is_publicly_discoverable);
CREATE INDEX idx_ibd_discovery_consensus_status ON public.ibd_discovery_index (consensus_status);


-- -----------------------------------------------------------
-- 6. New Table: public.ibd_pds_attestation
-- Records a specific PDS's attestation or validation for an IBD match.
-- -----------------------------------------------------------
CREATE TABLE public.ibd_pds_attestation
(
    id                     BIGSERIAL PRIMARY KEY,
    ibd_discovery_index_id BIGINT       NOT NULL REFERENCES public.ibd_discovery_index (id) ON DELETE CASCADE,
    attesting_pds_guid     UUID         NOT NULL,
    attesting_sample_guid  UUID         NOT NULL,
    attestation_timestamp  TIMESTAMP    NOT NULL DEFAULT NOW(),
    attestation_signature  TEXT         NOT NULL,

    match_summary_hash     VARCHAR(255) NOT NULL,
    attestation_type       VARCHAR(50)  NOT NULL CHECK (attestation_type IN
                                                        ('INITIAL_REPORT', 'CONFIRMATION', 'DISPUTE', 'REVOCATION',
                                                         'THIRD_PARTY_VALIDATION')),
    attestation_notes      TEXT,
    UNIQUE (ibd_discovery_index_id, attesting_pds_guid, attestation_type)
);

-- Indexes for ibd_pds_attestation
CREATE INDEX idx_ibd_attestation_index_id ON public.ibd_pds_attestation (ibd_discovery_index_id);
CREATE INDEX idx_ibd_attestation_pds_guid ON public.ibd_pds_attestation (attesting_pds_guid);
CREATE INDEX idx_ibd_attestation_type ON public.ibd_pds_attestation (attestation_type);


-- -----------------------------------------------------------
-- 6. Revisions to Existing Genetic Genealogy Tables
-- -----------------------------------------------------------

-- Table: public.genbank_contig
-- Add columns to link existing contigs to their pangenome context.
ALTER TABLE public.genbank_contig
    ADD COLUMN pangenome_path_id        INTEGER REFERENCES public.pangenome_path (id);

-- -----------------------------------------------------------
-- 7. Indexes for Performance
-- -----------------------------------------------------------

-- Indexes for public.pangenome_node
CREATE INDEX idx_pangenome_node_graph_id ON public.pangenome_node (graph_id);

-- Indexes for public.pangenome_path
CREATE INDEX idx_pangenome_path_graph_id ON public.pangenome_path (graph_id);
CREATE INDEX idx_pangenome_path_assembly_id ON public.pangenome_path (source_assembly_id);

-- Indexes for public.canonical_pangenome_variant
CREATE INDEX idx_cpv_graph_id ON public.canonical_pangenome_variant (pangenome_graph_id);
CREATE INDEX idx_cpv_variant_type ON public.canonical_pangenome_variant (variant_type);
CREATE INDEX idx_cpv_ref_path_pos ON public.canonical_pangenome_variant (reference_path_id, reference_start_position);
CREATE INDEX idx_cpv_variant_nodes ON public.canonical_pangenome_variant USING GIN (variant_nodes);
CREATE INDEX idx_cpv_variant_edges ON public.canonical_pangenome_variant USING GIN (variant_edges);

-- Indexes for public.pangenome_variant_link
CREATE INDEX idx_pvlink_variant_id ON public.pangenome_variant_link (variant_id);
CREATE INDEX idx_pvlink_canonical_id ON public.pangenome_variant_link (canonical_pangenome_variant_id);
CREATE INDEX idx_pvlink_graph_id ON public.pangenome_variant_link (pangenome_graph_id);

-- Indexes for public.reported_variant_pangenome
CREATE INDEX idx_rvp_sample_guid ON public.reported_variant_pangenome (sample_guid);
CREATE INDEX idx_rvp_graph_id ON public.reported_variant_pangenome (graph_id);
CREATE INDEX idx_rvp_variant_type ON public.reported_variant_pangenome (variant_type);
CREATE INDEX idx_rvp_ref_path_pos ON public.reported_variant_pangenome (reference_path_id, reference_start_position);
CREATE INDEX idx_rvp_variant_nodes ON public.reported_variant_pangenome USING GIN (variant_nodes);
CREATE INDEX idx_rvp_variant_edges ON public.reported_variant_pangenome USING GIN (variant_edges);
CREATE INDEX idx_rvp_confidence_score ON public.reported_variant_pangenome (confidence_score);


-- This is destructive, but the tables were never actually used in the application code
DROP TABLE reported_variant;
DROP TABLE reported_negative_variant;

# --- !Downs
ALTER TABLE public.genbank_contig
    DROP COLUMN pangenome_path_id;

DROP TABLE public.ibd_pds_attestation;
DROP TABLE public.ibd_discovery_index;
DROP TABLE public.validation_service;
DROP TABLE public.reported_variant_pangenome;
DROP TABLE public.pangenome_variant_link;
DROP TABLE public.canonical_pangenome_variant;
DROP TABLE public.pangenome_node;
DROP TABLE public.gene_annotation;
DROP TABLE public.pangenome_path;
DROP TABLE public.pangenome_edge;
DROP TABLE public.pangenome_node;
DROP TABLE public.assembly_metadata;
DROP TABLE public.pangenome_graph;