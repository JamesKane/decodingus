-- =============================================================================
-- DecodingUs Database Initialization
-- =============================================================================
-- This script runs once when the PostgreSQL container is first created.
-- It sets up the required databases and extensions.
-- =============================================================================

-- Create the metadata database (main database is created by POSTGRES_DB env var)
CREATE DATABASE decodingus_metadata;

-- Connect to main database and enable extensions
\c decodingus_db

-- PostGIS for geospatial data
CREATE EXTENSION IF NOT EXISTS postgis;

-- LTree for hierarchical data (haplogroup trees)
CREATE EXTENSION IF NOT EXISTS ltree;

-- pg_trgm for fuzzy text search
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Connect to metadata database and enable extensions
\c decodingus_metadata

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
