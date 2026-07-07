-- Variant Browser search was a full sequential scan of the ~3.7M core.variant rows.
-- The public search matches a leading-wildcard `canonical_name ILIKE '%q%'` OR a
-- per-row lateral unnest of the alias arrays (`jsonb_array_elements_text(...) ILIKE`),
-- neither of which any existing index can serve — so every keystroke ran the JSONB
-- subplan 3.7M times (~3s/query, measured). Make both the name and the alias text
-- trigram-searchable so the planner can BitmapOr two index scans instead.
--
-- Building these GIN indexes over 3.7M rows takes a while; on a clean cutover load the
-- table is populated first and this indexes it once. IF NOT EXISTS keeps the startup
-- migration a no-op wherever the indexes were already built (e.g. pre-built in dev to
-- dodge the container's idle-flow reaper).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Immutable projection of a variant's searchable alias tokens (established common
-- names + rs ids) as one space-joined string, so the browser's alias substring match
-- is a single trigram-indexable predicate instead of a lateral array unnest. STRICT so
-- a NULL `aliases` yields NULL (indexed as absent) rather than erroring.
CREATE OR REPLACE FUNCTION core.variant_alias_search_text(aliases jsonb)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
RETURNS NULL ON NULL INPUT
AS $$
    SELECT concat_ws(' ',
        (SELECT string_agg(v, ' ' ORDER BY v) FROM jsonb_array_elements_text(aliases->'common_names') AS v),
        (SELECT string_agg(v, ' ' ORDER BY v) FROM jsonb_array_elements_text(aliases->'rs_ids') AS v))
$$;

-- Trigram GIN on the canonical name (leading-wildcard ILIKE) ...
CREATE INDEX IF NOT EXISTS variant_canonical_name_trgm
    ON core.variant USING gin (canonical_name gin_trgm_ops);

-- ... and on the projected alias text (the OR-ed alias substring match).
CREATE INDEX IF NOT EXISTS variant_alias_search_trgm
    ON core.variant USING gin (core.variant_alias_search_text(aliases) gin_trgm_ops);
