-- Mock of the legacy DecodingUs schema (the subset the ETL reads), matching the
-- CURRENT production schema (/Users/jkane/db.schema) so du-migrate's transformers
-- are exercised with data. Lets the ETL be verified without prod access.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS tree;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS curator;

CREATE TYPE public.biological_sex AS ENUM ('male','female','intersex');
CREATE TYPE public.biosample_type AS ENUM ('Standard','PGP','Citizen','Ancient');

CREATE TABLE public.specimen_donor (
    id SERIAL PRIMARY KEY,
    donor_identifier varchar(255) NOT NULL,
    origin_biobank varchar(255) NOT NULL,
    sex public.biological_sex,
    geocoord geometry(Point,4326),
    date_range_start integer,
    date_range_end integer,
    donor_type public.biosample_type DEFAULT 'Standard' NOT NULL,
    pgp_participant_id varchar(50),
    at_uri varchar(255)
);

CREATE TABLE public.genbank_contig (
    genbank_contig_id SERIAL PRIMARY KEY,
    accession varchar(255) NOT NULL,
    common_name varchar(255),
    reference_genome varchar(255),
    seq_length integer NOT NULL
);

CREATE TABLE public.variant (
    variant_id SERIAL PRIMARY KEY,
    genbank_contig_id integer NOT NULL REFERENCES public.genbank_contig,
    "position" integer NOT NULL,
    reference_allele varchar(255) NOT NULL,
    alternate_allele varchar(255) NOT NULL,
    variant_type varchar(5) NOT NULL,
    rs_id varchar(255),
    common_name varchar(255)
);
CREATE TABLE public.variant_alias (
    id SERIAL PRIMARY KEY,
    variant_id integer NOT NULL REFERENCES public.variant,
    alias_type varchar(50) NOT NULL,
    alias_value varchar(255) NOT NULL,
    source varchar(255)
);

CREATE TABLE public.biosample (
    id SERIAL PRIMARY KEY,
    sample_accession varchar(255) NOT NULL,
    description text NOT NULL,
    alias varchar(255),
    center_name varchar(255) NOT NULL,
    specimen_donor_id integer REFERENCES public.specimen_donor,
    sample_guid uuid NOT NULL,
    locked boolean DEFAULT false NOT NULL,
    source_platform varchar(100)
);
CREATE TABLE public.biosample_original_haplogroup (
    id SERIAL PRIMARY KEY,
    biosample_id integer,
    publication_id integer,
    original_y_haplogroup varchar(255),
    original_mt_haplogroup varchar(255),
    notes text,
    y_haplogroup_result jsonb,
    mt_haplogroup_result jsonb
);

CREATE TABLE public.citizen_biosample (
    id SERIAL PRIMARY KEY,
    at_uri varchar(255),
    source_platform varchar(255),
    collection_date date,
    sex varchar(15),
    geocoord geometry(Point,4326),
    description text,
    sample_guid uuid NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    at_cid varchar(255),
    accession varchar(255),
    alias varchar(255),
    y_haplogroup jsonb,
    mt_haplogroup jsonb,
    specimen_donor_id integer REFERENCES public.specimen_donor
);
CREATE TABLE public.citizen_biosample_original_haplogroup (
    id SERIAL PRIMARY KEY,
    citizen_biosample_id integer,
    publication_id integer,
    y_haplogroup_result jsonb,
    mt_haplogroup_result jsonb,
    notes text
);

CREATE TABLE public.pgp_biosample (
    pgp_biosample_id SERIAL PRIMARY KEY,
    pgp_participant_id varchar(255) NOT NULL,
    ena_biosample_accession varchar(255),
    sex varchar(15),
    sample_guid uuid NOT NULL
);

CREATE TABLE tree.haplogroup (
    haplogroup_id SERIAL PRIMARY KEY,
    name varchar(255) NOT NULL,
    lineage varchar(255),
    description text,
    haplogroup_type varchar(10) NOT NULL,
    revision_id integer NOT NULL,
    source varchar(255) NOT NULL,
    confidence_level varchar(255) NOT NULL,
    valid_from timestamp NOT NULL DEFAULT now(),
    valid_until timestamp,
    formed_ybp integer,
    formed_ybp_lower integer,
    formed_ybp_upper integer,
    tmrca_ybp integer,
    tmrca_ybp_lower integer,
    tmrca_ybp_upper integer,
    age_estimate_source varchar(100)
);
CREATE TABLE tree.haplogroup_relationship (
    haplogroup_relationship_id SERIAL PRIMARY KEY,
    child_haplogroup_id integer NOT NULL,
    parent_haplogroup_id integer NOT NULL,
    revision_id integer NOT NULL,
    valid_from timestamp NOT NULL DEFAULT now(),
    valid_until timestamp,
    source varchar(255) NOT NULL
);
CREATE TABLE tree.haplogroup_variant (
    haplogroup_variant_id SERIAL PRIMARY KEY,
    haplogroup_id integer NOT NULL,
    variant_id integer NOT NULL
);

CREATE TABLE public.genomic_studies (
    id SERIAL PRIMARY KEY,
    accession varchar(50) NOT NULL,
    title varchar(255) NOT NULL,
    center_name varchar(255) NOT NULL,
    study_name varchar(255) NOT NULL,
    details text,
    source varchar(20) NOT NULL,
    submission_date date,
    last_update date,
    bio_project_id varchar(50),
    molecule varchar(50),
    topology varchar(50),
    taxonomy_id integer,
    version varchar(10)
);
CREATE TABLE public.publication (
    id SERIAL PRIMARY KEY,
    pubmed_id varchar(20),
    doi varchar(255),
    title text NOT NULL,
    journal varchar(255),
    publication_date date,
    url varchar(2048),
    authors varchar(1000),
    abstract_summary text,
    open_alex_id varchar(255),
    cited_by_count integer,
    open_access_status varchar(50)
);
CREATE TABLE public.publication_biosample (publication_id integer NOT NULL, biosample_id integer NOT NULL);
CREATE TABLE public.publication_citizen_biosample (publication_id integer NOT NULL, citizen_biosample_id integer NOT NULL);
CREATE TABLE public.publication_ena_study (publication_id integer NOT NULL, genomic_study_id integer NOT NULL);

-- ── ident / auth (UUID-keyed; AT Protocol OAuth, no passwords) ───────────────
CREATE TABLE public.users (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    email public.citext,
    did varchar(255) NOT NULL,
    handle varchar(255),
    display_name varchar(255),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    is_active boolean DEFAULT true NOT NULL
);
CREATE TABLE auth.roles (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    name varchar(255) NOT NULL,
    description text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);
CREATE TABLE auth.permissions (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    name varchar(255) NOT NULL,
    description text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);
CREATE TABLE auth.role_permissions (role_id uuid NOT NULL, permission_id uuid NOT NULL);
CREATE TABLE auth.user_roles (user_id uuid NOT NULL, role_id uuid NOT NULL);
CREATE TABLE auth.user_login_info (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    user_id uuid NOT NULL,
    provider_id varchar(255) NOT NULL,
    provider_key varchar(255) NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);
CREATE TABLE auth.user_oauth2_info (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    login_info_id uuid NOT NULL,
    access_token text NOT NULL,
    token_type varchar(50),
    expires_in bigint,
    refresh_token text,
    scope text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);
CREATE TABLE auth.user_pds_info (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    user_id uuid NOT NULL,
    pds_url varchar(512) NOT NULL,
    did varchar(255) NOT NULL,
    handle varchar(255),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);
CREATE TABLE auth.cookie_consents (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    user_id uuid,
    session_id varchar(255),
    ip_address_hash varchar(64),
    consent_given boolean DEFAULT false NOT NULL,
    consent_timestamp timestamp without time zone DEFAULT now() NOT NULL,
    policy_version varchar(20) DEFAULT '1.0' NOT NULL,
    user_agent text,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);
CREATE TABLE auth.atprotocol_authorization_servers (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    issuer_url varchar(255) NOT NULL,
    authorization_endpoint varchar(255),
    token_endpoint varchar(255),
    pushed_authorization_request_endpoint varchar(255),
    dpop_signing_alg_values_supported text,
    scopes_supported text,
    client_id_metadata_document_supported boolean,
    metadata_fetched_at timestamp without time zone DEFAULT now() NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);
CREATE TABLE auth.atprotocol_client_metadata (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    client_id_url varchar(255) NOT NULL,
    client_name varchar(255),
    client_uri varchar(255),
    logo_uri varchar(255),
    tos_uri varchar(255),
    policy_uri varchar(255),
    redirect_uris text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);
CREATE TABLE curator.audit_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    user_id uuid NOT NULL,
    entity_type varchar(50) NOT NULL,
    entity_id integer NOT NULL,
    action varchar(20) NOT NULL,
    old_value jsonb,
    new_value jsonb,
    comment text,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

-- ── genomics (sequencing, coverage, pangenome) ──────────────────────────────
CREATE TYPE public.data_generation_method AS ENUM ('SEQUENCING','GENOTYPING');
CREATE TYPE public.target_type AS ENUM ('WHOLE_GENOME','Y_CHROMOSOME','MT_DNA','AUTOSOMAL','X_CHROMOSOME','MIXED');

CREATE TABLE public.sequencing_lab (
    id SERIAL PRIMARY KEY,
    name varchar(255) NOT NULL,
    is_d2c boolean DEFAULT false NOT NULL,
    website_url varchar(255),
    description_markdown text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone
);
CREATE TABLE public.sequencer_instrument (
    id SERIAL PRIMARY KEY,
    instrument_id varchar(255) NOT NULL,
    lab_id integer NOT NULL,
    manufacturer varchar(255),
    model varchar(255),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone
);
CREATE TABLE public.test_type_definition (
    id SERIAL PRIMARY KEY,
    code varchar(50) NOT NULL,
    display_name varchar(100) NOT NULL,
    category public.data_generation_method NOT NULL,
    vendor varchar(100),
    target_type public.target_type NOT NULL,
    expected_min_depth double precision,
    supports_haplogroup_y boolean DEFAULT false NOT NULL,
    supports_haplogroup_mt boolean DEFAULT false NOT NULL,
    supports_autosomal_ibd boolean DEFAULT false NOT NULL,
    supports_ancestry boolean DEFAULT false NOT NULL,
    typical_file_formats text[],
    description text
);
CREATE TABLE public.pangenome_graph (
    id BIGSERIAL PRIMARY KEY,
    graph_name varchar(255) NOT NULL,
    source_gfa_file varchar(255),
    description text,
    creation_date timestamp without time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.pangenome_node (
    id BIGSERIAL PRIMARY KEY,
    graph_id bigint NOT NULL,
    node_name varchar(255) NOT NULL,
    sequence_length bigint
);
CREATE TABLE public.pangenome_path (
    id BIGSERIAL PRIMARY KEY,
    graph_id bigint NOT NULL,
    path_name varchar(255) NOT NULL,
    is_reference boolean DEFAULT false,
    length_bp bigint,
    description text
);
CREATE TABLE public.canonical_pangenome_variant (
    id BIGSERIAL PRIMARY KEY,
    pangenome_graph_id integer NOT NULL,
    variant_type varchar(50) NOT NULL,
    variant_nodes integer[] NOT NULL,
    variant_edges integer[] DEFAULT '{}' NOT NULL,
    reference_path_id integer,
    reference_start_position integer,
    reference_end_position integer,
    reference_allele_sequence text,
    alternate_allele_sequence text,
    canonical_hash varchar(255) NOT NULL,
    description text,
    creation_date timestamp without time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.sequence_library (
    id SERIAL PRIMARY KEY,
    sample_guid uuid NOT NULL,
    lab varchar(255) NOT NULL,
    run_date timestamp without time zone NOT NULL,
    instrument varchar(255) NOT NULL,
    reads bigint NOT NULL,
    read_length integer NOT NULL,
    paired_end boolean NOT NULL,
    insert_size integer,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    at_uri varchar(255),
    at_cid varchar(255),
    test_type_id integer NOT NULL
);
CREATE TABLE public.sequence_file (
    id SERIAL PRIMARY KEY,
    library_id integer NOT NULL,
    file_name varchar(255) NOT NULL,
    file_size_bytes bigint NOT NULL,
    file_format varchar(255) NOT NULL,
    aligner varchar(255) NOT NULL,
    target_reference varchar(255) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    pangenome_graph_id integer,
    checksums jsonb DEFAULT '[]'::jsonb,
    http_locations jsonb DEFAULT '[]'::jsonb,
    atp_location jsonb
);
CREATE TABLE public.alignment_metadata (
    id BIGSERIAL PRIMARY KEY,
    sequence_file_id bigint NOT NULL,
    genbank_contig_id integer NOT NULL,
    metric_level varchar(50) NOT NULL,
    region_name varchar(255),
    region_start_pos bigint,
    region_end_pos bigint,
    region_length_bp bigint,
    metrics_date timestamp without time zone DEFAULT now() NOT NULL,
    analysis_tool varchar(255) NOT NULL,
    analysis_tool_version varchar(50),
    notes text,
    metadata jsonb,
    reference_build varchar(255),
    variant_caller varchar(255),
    genome_territory bigint,
    mean_coverage double precision,
    median_coverage double precision,
    sd_coverage double precision,
    pct_exc_dupe double precision,
    pct_exc_mapq double precision,
    pct_10x double precision,
    pct_20x double precision,
    pct_30x double precision,
    het_snp_sensitivity double precision
);
CREATE TABLE public.alignment_coverage (
    alignment_metadata_id bigint NOT NULL,
    mean_depth double precision,
    median_depth double precision,
    percent_coverage_at_1x double precision,
    percent_coverage_at_5x double precision,
    percent_coverage_at_10x double precision,
    percent_coverage_at_20x double precision,
    percent_coverage_at_30x double precision,
    bases_no_coverage bigint,
    bases_low_quality_mapping bigint,
    bases_callable bigint,
    mean_mapping_quality double precision
);
CREATE TABLE public.pangenome_alignment_metadata (
    id BIGSERIAL PRIMARY KEY,
    sequence_file_id bigint NOT NULL,
    pangenome_graph_id integer NOT NULL,
    metric_level varchar(50) NOT NULL,
    pangenome_path_id integer,
    pangenome_node_id integer,
    region_start_node_id integer,
    region_end_node_id integer,
    region_name varchar(255),
    region_length_bp bigint,
    metrics_date timestamp without time zone DEFAULT now() NOT NULL,
    analysis_tool varchar(255) NOT NULL,
    analysis_tool_version varchar(50),
    notes text,
    metadata jsonb
);
CREATE TABLE public.pangenome_alignment_coverage (
    alignment_metadata_id bigint NOT NULL,
    mean_depth double precision,
    median_depth double precision,
    percent_coverage_at_1x double precision,
    percent_coverage_at_5x double precision,
    percent_coverage_at_10x double precision,
    percent_coverage_at_20x double precision,
    percent_coverage_at_30x double precision,
    bases_no_coverage bigint,
    bases_low_quality_mapping bigint,
    bases_callable bigint,
    mean_mapping_quality double precision
);
CREATE TABLE public.reported_variant_pangenome (
    id BIGSERIAL PRIMARY KEY,
    sample_guid uuid NOT NULL,
    graph_id integer NOT NULL,
    variant_type varchar(50) NOT NULL,
    reference_path_id integer,
    reference_start_position integer,
    reference_end_position integer,
    variant_nodes integer[] NOT NULL,
    variant_edges integer[] DEFAULT '{}' NOT NULL,
    alternate_allele_sequence text,
    reference_allele_sequence text,
    reference_repeat_count integer,
    alternate_repeat_count integer,
    allele_fraction double precision,
    depth integer,
    reported_date timestamp without time zone DEFAULT now() NOT NULL,
    provenance varchar(255) NOT NULL,
    confidence_score double precision NOT NULL,
    notes text,
    status varchar(255) NOT NULL,
    zygosity varchar(10),
    haplotype_information jsonb
);
CREATE TABLE public.genotype_data (
    id SERIAL PRIMARY KEY,
    at_uri varchar,
    at_cid varchar,
    sample_guid uuid NOT NULL,
    test_type_id integer,
    provider varchar,
    chip_version varchar,
    build_version varchar,
    source_file_hash varchar,
    metrics jsonb DEFAULT '{}'::jsonb NOT NULL,
    population_breakdown_id integer,
    deleted boolean DEFAULT false,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now()
);

-- ── seed ─────────────────────────────────────────────────────────────────────
INSERT INTO public.specimen_donor (donor_identifier, origin_biobank, sex, donor_type, geocoord) VALUES
 ('D1','Biobank A','male','Standard', ST_SetSRID(ST_MakePoint(-0.12,51.50),4326)),
 ('D2','Biobank B','female','Ancient', ST_SetSRID(ST_MakePoint(35.0,47.0),4326));

INSERT INTO public.genbank_contig (accession, common_name, reference_genome, seq_length) VALUES
 ('CM000686.2','chrY','GRCh38', 57227415);

INSERT INTO public.variant (genbank_contig_id, "position", reference_allele, alternate_allele, variant_type, rs_id, common_name) VALUES
 (1, 22739367, 'T', 'C', 'SNP', 'rs9786153', 'M269'),
 (1, 13668077, 'G', 'A', 'SNP', NULL, NULL);
INSERT INTO public.variant_alias (variant_id, alias_type, alias_value, source) VALUES
 (1, 'isogg', 'R-M269', 'ISOGG'),
 (1, 'rsid', 'rs9786153', 'dbSNP');

INSERT INTO public.biosample (sample_accession, description, alias, center_name, specimen_donor_id, sample_guid, source_platform) VALUES
 ('SAMN001','Standard sample','std-1','Center X', 1, '11111111-1111-1111-1111-111111111111', 'Illumina'),
 ('SAMN002','Ancient sample','anc-1','Center Y', 2, '22222222-2222-2222-2222-222222222222', NULL);
INSERT INTO public.biosample_original_haplogroup (biosample_id, publication_id, original_y_haplogroup, y_haplogroup_result) VALUES
 (1, 1, 'R-M269', '{"call":"R-M269","conf":0.99}'::jsonb);

INSERT INTO public.citizen_biosample (at_uri, at_cid, source_platform, sample_guid, deleted, accession, alias, y_haplogroup) VALUES
 ('at://did:plc:abc123/app.decodingus.biosample/xyz','bafyreigh2akiscaildc','Navigator','33333333-3333-3333-3333-333333333333', false, 'CIT001','cit-1','{"terminal":"R-L21"}'::jsonb);
INSERT INTO public.citizen_biosample_original_haplogroup (citizen_biosample_id, publication_id, y_haplogroup_result) VALUES
 (1, 2, '{"call":"R-L21"}'::jsonb);

INSERT INTO public.pgp_biosample (pgp_participant_id, ena_biosample_accession, sample_guid) VALUES
 ('hu1A2B3C','ERS999','44444444-4444-4444-4444-444444444444');

INSERT INTO tree.haplogroup (name, lineage, haplogroup_type, revision_id, source, confidence_level, formed_ybp, age_estimate_source) VALUES
 ('R','R','Y',1,'ISOGG','high',28200,'SNP'),
 ('R1b','R>R1b','Y',1,'ISOGG','high',22800,'SNP');
INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id, revision_id, source) VALUES (2,1,1,'ISOGG');
INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES (2,1);

INSERT INTO public.genomic_studies (accession, title, center_name, study_name, source, taxonomy_id, version, details) VALUES
 ('PRJEB12345','Steppe ancient genomes','Inst','steppe-study','ENA',9606,'1','free text notes');

INSERT INTO public.publication (pubmed_id, doi, title, journal, publication_date, cited_by_count) VALUES
 ('30001','10.1000/euro1','Peopling of Europe','Nature','2021-03-15',142),
 ('30002','10.1000/steppe2','Steppe Y diversity','Cell','2019-07-01',88);
INSERT INTO public.publication_biosample (publication_id, biosample_id) VALUES (1, 1);
INSERT INTO public.publication_citizen_biosample (publication_id, citizen_biosample_id) VALUES (2, 1);
INSERT INTO public.publication_ena_study (publication_id, genomic_study_id) VALUES (1, 1);

-- ident / auth seed (fixed UUIDs so FKs line up).
INSERT INTO public.users (id, email, did, handle, display_name) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','curator@decodingus.org','did:plc:curator1','curator.bsky.social','Curator One'),
 ('aaaaaaaa-0000-0000-0000-000000000002', NULL,'did:plc:admin1','admin.decodingus.com','Admin');
INSERT INTO auth.roles (id, name, description) VALUES
 ('bbbbbbbb-0000-0000-0000-000000000001','Admin','Full administrative access'),
 ('bbbbbbbb-0000-0000-0000-000000000002','Curator','Content curation'),
 ('bbbbbbbb-0000-0000-0000-000000000003','TreeCurator','Haplogroup tree curation');
INSERT INTO auth.permissions (id, name, description) VALUES
 ('cccccccc-0000-0000-0000-000000000001','variant.edit','Edit variants'),
 ('cccccccc-0000-0000-0000-000000000002','tree.edit','Edit the haplogroup tree');
INSERT INTO auth.role_permissions (role_id, permission_id) VALUES
 ('bbbbbbbb-0000-0000-0000-000000000002','cccccccc-0000-0000-0000-000000000001'),
 ('bbbbbbbb-0000-0000-0000-000000000003','cccccccc-0000-0000-0000-000000000002');
INSERT INTO auth.user_roles (user_id, role_id) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','bbbbbbbb-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-000000000002','bbbbbbbb-0000-0000-0000-000000000001');
INSERT INTO auth.user_login_info (id, user_id, provider_id, provider_key) VALUES
 ('dddddddd-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','oauth2','did:plc:curator1'),
 ('dddddddd-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000002','oauth2','did:plc:admin1');
INSERT INTO auth.user_oauth2_info (id, login_info_id, access_token, token_type, expires_in, refresh_token, scope) VALUES
 ('eeeeeeee-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-000000000001','tok-access-1','DPoP',3600,'tok-refresh-1','atproto transition:generic');
INSERT INTO auth.user_pds_info (id, user_id, pds_url, did, handle) VALUES
 ('ffffffff-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','https://pds.decodingus.com','did:plc:curator1','curator.bsky.social');
INSERT INTO auth.cookie_consents (user_id, session_id, ip_address_hash, consent_given, policy_version) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001', NULL,'abc123hash', true,'1.0'),
 (NULL,'anon-session-1','def456hash', true,'1.0');
INSERT INTO auth.atprotocol_authorization_servers (issuer_url, authorization_endpoint, token_endpoint, scopes_supported, client_id_metadata_document_supported) VALUES
 ('https://bsky.social','https://bsky.social/oauth/authorize','https://bsky.social/oauth/token','atproto transition:generic', true);
INSERT INTO auth.atprotocol_client_metadata (client_id_url, client_name, client_uri, redirect_uris) VALUES
 ('https://decodingus.com/oauth/client-metadata.json','DecodingUs','https://decodingus.com','https://decodingus.com/oauth/callback');
INSERT INTO curator.audit_log (user_id, entity_type, entity_id, action, old_value, new_value, comment) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','variant',1,'UPDATE','{"common_name":null}'::jsonb,'{"common_name":"M269"}'::jsonb,'Named terminal SNP'),
 ('aaaaaaaa-0000-0000-0000-000000000002','haplogroup',2,'CREATE',NULL,'{"name":"R1b"}'::jsonb,'Added R1b');

-- genomics seed (sequencing run on the standard biosample 1111...).
INSERT INTO public.sequencing_lab (name, is_d2c, website_url) VALUES
 ('Dante Labs', true, 'https://dantelabs.com');
-- two rows, same instrument_id across labs -> dedups to one on migration.
INSERT INTO public.sequencer_instrument (instrument_id, lab_id, manufacturer, model) VALUES
 ('A00123', 1, 'Illumina', 'NovaSeq 6000'),
 ('A00123', 1, 'Illumina', 'NovaSeq 6000');
INSERT INTO public.test_type_definition (code, display_name, category, vendor, target_type, expected_min_depth, supports_haplogroup_y, typical_file_formats, description) VALUES
 ('WGS30','Whole Genome 30x','SEQUENCING','Dante','WHOLE_GENOME',30,true,'{BAM,VCF}','30x WGS');
INSERT INTO public.pangenome_graph (graph_name, source_gfa_file, description) VALUES
 ('HPRC-v1','hprc-v1.gfa','Human Pangenome Reference Consortium v1');
INSERT INTO public.pangenome_node (graph_id, node_name, sequence_length) VALUES (1,'n1',128),(1,'n2',64);
INSERT INTO public.pangenome_path (graph_id, path_name, is_reference, length_bp) VALUES (1,'GRCh38#chrY', true, 57227415);
INSERT INTO public.canonical_pangenome_variant (pangenome_graph_id, variant_type, variant_nodes, variant_edges, reference_path_id, reference_allele_sequence, canonical_hash) VALUES
 (1,'SNP','{1,2}','{1}',1,'T','hash-canon-1');
INSERT INTO public.sequence_library (sample_guid, lab, run_date, instrument, reads, read_length, paired_end, insert_size, created_at, at_uri, at_cid, test_type_id) VALUES
 ('11111111-1111-1111-1111-111111111111','Dante Labs','2023-05-01 00:00:00','A00123',900000000,150,true,350,'2023-05-02 00:00:00','at://did:plc:lab/app.decodingus.seqlib/abc','bafyseqlib1',1);
INSERT INTO public.sequence_file (library_id, file_name, file_size_bytes, file_format, aligner, target_reference, created_at, pangenome_graph_id, checksums, http_locations, atp_location) VALUES
 (1,'sample1.bam',64000000000,'BAM','bwa-mem2','GRCh38','2023-05-02 00:00:00',1,
  '[{"algorithm":"sha256","checksum":"deadbeef","verified_at":"2023-05-03"}]'::jsonb,
  '[{"file_url":"https://store/sample1.bam","file_index_url":"https://store/sample1.bam.bai"}]'::jsonb,
  '{"repo_did":"did:plc:lab","record_cid":"bafyfile1","record_path":"app.decodingus.file/1"}'::jsonb);
INSERT INTO public.alignment_metadata (sequence_file_id, genbank_contig_id, metric_level, reference_build, variant_caller, analysis_tool, analysis_tool_version, genome_territory, mean_coverage, median_coverage, pct_10x, pct_20x, pct_30x) VALUES
 (1,1,'CONTIG_OVERALL','GRCh38','DeepVariant','Picard','3.0',57227415,31.2,30.0,99.1,97.5,90.2);
INSERT INTO public.alignment_coverage (alignment_metadata_id, mean_depth, median_depth, percent_coverage_at_1x, percent_coverage_at_10x, percent_coverage_at_30x, bases_callable, mean_mapping_quality) VALUES
 (1,32.5,31.0,99.9,98.7,91.0,56000000,58.4);
INSERT INTO public.pangenome_alignment_metadata (sequence_file_id, pangenome_graph_id, metric_level, pangenome_path_id, region_name, analysis_tool, analysis_tool_version, metadata) VALUES
 (1,1,'GRAPH_OVERALL',1,'whole-graph','vg','1.50','{"graphAlignedReads":880000000}'::jsonb);
INSERT INTO public.pangenome_alignment_coverage (alignment_metadata_id, mean_depth, percent_coverage_at_10x) VALUES
 (1,29.8,97.3);
INSERT INTO public.reported_variant_pangenome (sample_guid, graph_id, variant_type, reference_path_id, variant_nodes, variant_edges, allele_fraction, depth, provenance, confidence_score, status, zygosity, haplotype_information) VALUES
 ('11111111-1111-1111-1111-111111111111',1,'SNP',1,'{1,2}','{1}',0.99,34,'Navigator',0.97,'CONFIRMED','HET','{"phase_set":12}'::jsonb);
INSERT INTO public.genotype_data (at_uri, at_cid, sample_guid, test_type_id, provider, chip_version, build_version, source_file_hash, metrics, deleted) VALUES
 ('at://did:plc:chip/app.decodingus.genotype/g1','bafygeno1','11111111-1111-1111-1111-111111111111',1,'23andMe','v5','GRCh37','sha256:chip1','{"callRate":0.987,"totalMarkersCalled":630000}'::jsonb, false),
 (NULL,NULL,'11111111-1111-1111-1111-111111111111',1,'DeletedProvider','v4','GRCh37','sha256:chip2','{}'::jsonb, true);
