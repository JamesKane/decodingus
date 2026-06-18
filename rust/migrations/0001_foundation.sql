-- DecodingUs redesigned schema — foundation.
-- Namespaces, extensions, and native enums. See plan §2.
--
-- The legacy app spread tables across public/auth/tree/social/support/genomics/
-- billing + a second "metadata" database. We keep logical grouping via schemas
-- but in ONE database (the metadata DB collapses into `fed`).

CREATE EXTENSION IF NOT EXISTS postgis;       -- biosample/donor geometry(Point,4326)
CREATE EXTENSION IF NOT EXISTS citext;        -- case-insensitive user email
CREATE EXTENSION IF NOT EXISTS pgcrypto;      -- gen_random_uuid()

CREATE SCHEMA IF NOT EXISTS core;     -- samples, donors, variants, regions
CREATE SCHEMA IF NOT EXISTS tree;     -- haplogroups + versioning
CREATE SCHEMA IF NOT EXISTS genomics; -- sequencing, coverage, callable loci
CREATE SCHEMA IF NOT EXISTS pubs;     -- publications/studies (`pub` is reserved-ish; use pubs)
CREATE SCHEMA IF NOT EXISTS ident;    -- users/auth/roles/atproto
CREATE SCHEMA IF NOT EXISTS ibd;      -- match discovery
CREATE SCHEMA IF NOT EXISTS fed;      -- PDS fleet/firehose (was the metadata DB)
CREATE SCHEMA IF NOT EXISTS social;   -- reputation/messaging
CREATE SCHEMA IF NOT EXISTS support;  -- contact messages
CREATE SCHEMA IF NOT EXISTS billing;  -- subscriptions

-- Native enums. Labels match du-domain serde forms (SCREAMING_SNAKE_CASE) so
-- Rust maps them directly via #[derive(sqlx::Type)] and JSONB round-trips align.
CREATE TYPE core.dna_type AS ENUM ('Y_DNA', 'MT_DNA');
CREATE TYPE core.biological_sex AS ENUM ('MALE', 'FEMALE', 'INTERSEX');
CREATE TYPE core.biosample_source AS ENUM ('STANDARD', 'CITIZEN', 'PGP', 'EXTERNAL', 'ANCIENT');
CREATE TYPE core.data_generation_method AS ENUM ('SEQUENCING', 'GENOTYPING');
CREATE TYPE core.target_type AS ENUM ('WHOLE_GENOME', 'Y_CHROMOSOME', 'MT_DNA', 'AUTOSOMAL', 'X_CHROMOSOME', 'MIXED');
CREATE TYPE core.mutation_type AS ENUM ('SNP', 'INDEL', 'STR', 'DEL', 'INS', 'MNP');
CREATE TYPE core.naming_status AS ENUM ('UNNAMED', 'PENDING_REVIEW', 'NAMED');
CREATE TYPE tree.change_set_status AS ENUM ('DRAFT', 'READY_FOR_REVIEW', 'UNDER_REVIEW', 'APPLIED', 'DISCARDED');
CREATE TYPE tree.tree_change_type AS ENUM ('CREATE', 'UPDATE', 'DELETE', 'REPARENT', 'VARIANT_EDIT');
