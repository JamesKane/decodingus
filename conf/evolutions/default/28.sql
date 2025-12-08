-- !Ups

-- 1. Create the 'tree' schema if it doesn't already exist
CREATE SCHEMA IF NOT EXISTS tree;

-- 2. Migrate existing haplogroup tables from 'public' schema to 'tree' schema
ALTER TABLE public.haplogroup SET SCHEMA tree;
ALTER TABLE public.haplogroup_relationship SET SCHEMA tree;
ALTER TABLE public.haplogroup_variant SET SCHEMA tree;
ALTER TABLE public.haplogroup_variant_metadata SET SCHEMA tree;
ALTER TABLE public.relationship_revision_metadata SET SCHEMA tree;

-- !Downs

-- 1. Revert haplogroup tables from 'tree' schema back to 'public' schema
ALTER TABLE tree.haplogroup SET SCHEMA public;
ALTER TABLE tree.haplogroup_relationship SET SCHEMA public;
ALTER TABLE tree.haplogroup_variant SET SCHEMA public;
ALTER TABLE tree.haplogroup_variant_metadata SET SCHEMA public;
ALTER TABLE tree.relationship_revision_metadata SET SCHEMA public;

-- 2. Drop the 'tree' schema if it exists (CASCADE will remove tables within it)
DROP SCHEMA IF EXISTS tree CASCADE;
