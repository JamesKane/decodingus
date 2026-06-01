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
