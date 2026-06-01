-- Mock of the legacy DecodingUs schema (subset the ETL reads), for verifying
-- du-migrate without production access. Shapes follow the reconstructed legacy
-- layout; the real EC2 schema is the contract for the production run.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA IF NOT EXISTS tree;

CREATE TABLE specimen_donor (
    id SERIAL PRIMARY KEY,
    donor_identifier TEXT,
    origin_biobank TEXT,
    sex TEXT,                 -- legacy biological_sex (lowercase)
    donor_type TEXT,          -- legacy biosample_type: Standard/External/Ancient/...
    geocoord geometry(Point, 4326)
);

CREATE TABLE biosample (
    id SERIAL PRIMARY KEY,
    sample_guid UUID NOT NULL,
    sample_accession TEXT,
    alias TEXT,
    description TEXT,
    center_name TEXT,
    locked BOOLEAN DEFAULT false,
    specimen_donor_id INTEGER REFERENCES specimen_donor(id),
    original_haplogroups JSONB DEFAULT '[]'::jsonb
);

CREATE TABLE citizen_biosample (
    id SERIAL PRIMARY KEY,
    sample_guid UUID NOT NULL,
    accession TEXT,
    deleted BOOLEAN DEFAULT false,
    original_haplogroups JSONB DEFAULT '[]'::jsonb,
    at_uri TEXT,
    at_cid TEXT
);

CREATE TABLE pgp_biosample (
    pgp_biosample_id SERIAL PRIMARY KEY,
    sample_guid UUID NOT NULL,
    ena_biosample_accession TEXT,
    pgp_participant_id TEXT
);

CREATE TABLE variant_v2 (
    variant_id SERIAL PRIMARY KEY,
    canonical_name TEXT NOT NULL,
    mutation_type TEXT,
    naming_status TEXT,
    aliases JSONB DEFAULT '{}'::jsonb,
    coordinates JSONB DEFAULT '{}'::jsonb,
    annotations JSONB DEFAULT '{}'::jsonb
);

CREATE TABLE tree.haplogroup (
    haplogroup_id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    haplogroup_type TEXT,     -- Y / MT
    lineage TEXT,
    source TEXT,
    confidence_level TEXT,
    formed_ybp INTEGER,
    tmrca_ybp INTEGER,
    provenance JSONB DEFAULT '{}'::jsonb,
    valid_from TIMESTAMPTZ DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE TABLE tree.haplogroup_relationship (
    id SERIAL PRIMARY KEY,
    child_haplogroup_id INTEGER,
    parent_haplogroup_id INTEGER,
    revision_id INTEGER DEFAULT 1,
    source TEXT,
    valid_from TIMESTAMPTZ DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE TABLE tree.haplogroup_variant (
    haplogroup_variant_id SERIAL PRIMARY KEY,
    haplogroup_id INTEGER,
    variant_id INTEGER,
    valid_from TIMESTAMPTZ DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE TABLE genomic_studies (
    id SERIAL PRIMARY KEY,
    accession TEXT NOT NULL,
    title TEXT,
    center_name TEXT,
    study_name TEXT,
    source TEXT,
    bio_project_id TEXT,
    molecule TEXT,
    topology TEXT,
    taxonomy_id INTEGER,
    version INTEGER,
    submission_date DATE,
    details JSONB DEFAULT '{}'::jsonb
);

CREATE TABLE publication (
    id SERIAL PRIMARY KEY,
    pubmed_id TEXT,
    doi TEXT,
    open_alex_id TEXT,
    title TEXT NOT NULL,
    journal TEXT,
    publication_date DATE,
    url TEXT,
    authors TEXT,
    abstract_summary TEXT,
    cited_by_count INTEGER,
    open_access_status TEXT
);

CREATE TABLE publication_biosample (
    publication_id INTEGER,
    biosample_id INTEGER
);
CREATE TABLE publication_citizen_biosample (
    publication_id INTEGER,
    citizen_biosample_id INTEGER
);

-- ── seed ─────────────────────────────────────────────────────────────────────
INSERT INTO specimen_donor (donor_identifier, origin_biobank, sex, donor_type, geocoord) VALUES
 ('D1','Biobank A','male','Standard', ST_SetSRID(ST_MakePoint(-0.12,51.50),4326)),
 ('D2','Biobank B','female','Ancient', ST_SetSRID(ST_MakePoint(35.0,47.0),4326));

INSERT INTO biosample (sample_guid, sample_accession, alias, description, center_name, locked, specimen_donor_id, original_haplogroups) VALUES
 ('11111111-1111-1111-1111-111111111111','SAMN001','std-1','Standard sample','Center X', false, 1, '[{"publication_id":1,"y":"R-M269"}]'::jsonb),
 ('22222222-2222-2222-2222-222222222222','SAMN002','anc-1','Ancient sample','Center Y', false, 2, '[]'::jsonb);

INSERT INTO citizen_biosample (sample_guid, accession, deleted, original_haplogroups, at_uri, at_cid) VALUES
 ('33333333-3333-3333-3333-333333333333','CIT001', false, '[]'::jsonb, 'at://did:plc:abc123/app.decodingus.biosample/xyz', 'bafyreigh2akiscaildc');

INSERT INTO pgp_biosample (sample_guid, ena_biosample_accession, pgp_participant_id) VALUES
 ('44444444-4444-4444-4444-444444444444','ERS999','hu1A2B3C');

INSERT INTO variant_v2 (canonical_name, mutation_type, naming_status, aliases, coordinates) VALUES
 ('M269','SNP','NAMED','{"common_names":["PF6517"],"rs_ids":["rs9786153"]}'::jsonb,'{"GRCh38":{"contig":"chrY","position":22739367}}'::jsonb),
 ('L21','snp','named','{"common_names":["S145"]}'::jsonb,'{"GRCh38":{"contig":"chrY","position":13668077}}'::jsonb);

INSERT INTO tree.haplogroup (name, haplogroup_type, lineage, formed_ybp) VALUES
 ('R','Y','R',28200),
 ('R1b','Y','R>R1b',22800);

INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id) VALUES
 (2, 1);  -- R1b child of R

INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES
 (2, 1);  -- R1b defined by M269

INSERT INTO genomic_studies (accession, title, source, taxonomy_id) VALUES
 ('PRJEB12345','Steppe ancient genomes','ENA',9606);

INSERT INTO publication (pubmed_id, doi, title, journal, publication_date, cited_by_count) VALUES
 ('30001','10.1000/euro1','Peopling of Europe','Nature','2021-03-15',142),
 ('30002','10.1000/steppe2','Steppe Y diversity','Cell','2019-07-01',88);

INSERT INTO publication_biosample (publication_id, biosample_id) VALUES (1, 1);          -- pub1 -> standard biosample 1
INSERT INTO publication_citizen_biosample (publication_id, citizen_biosample_id) VALUES (2, 1);  -- pub2 -> citizen 1
