-- # --- !Ups

-- Phase 3: Consolidate biosample_original_haplogroup into biosample as JSONB array
ALTER TABLE public.biosample ADD COLUMN original_haplogroups JSONB DEFAULT '[]'::jsonb;

-- Migrate existing data from the separate table into the JSONB array column
UPDATE public.biosample b SET original_haplogroups = (
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'publicationId', boh.publication_id,
        'yHaplogroupResult', boh.y_haplogroup_result,
        'mtHaplogroupResult', boh.mt_haplogroup_result,
        'notes', boh.notes
    ) ORDER BY boh.publication_id), '[]'::jsonb)
    FROM biosample_original_haplogroup boh
    WHERE boh.biosample_id = b.id
)
WHERE b.id IN (SELECT DISTINCT biosample_id FROM biosample_original_haplogroup);

-- GIN index for containment queries (e.g., finding biosamples by publication_id in array)
CREATE INDEX idx_biosample_orig_hg ON public.biosample
    USING GIN (original_haplogroups jsonb_path_ops)
    WHERE original_haplogroups != '[]'::jsonb;

-- Same for citizen_biosample
ALTER TABLE public.citizen_biosample ADD COLUMN original_haplogroups JSONB DEFAULT '[]'::jsonb;

UPDATE public.citizen_biosample cb SET original_haplogroups = (
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'publicationId', cboh.publication_id,
        'yHaplogroupResult', cboh.y_haplogroup_result,
        'mtHaplogroupResult', cboh.mt_haplogroup_result,
        'notes', cboh.notes
    ) ORDER BY cboh.publication_id), '[]'::jsonb)
    FROM citizen_biosample_original_haplogroup cboh
    WHERE cboh.citizen_biosample_id = cb.id
)
WHERE cb.id IN (SELECT DISTINCT citizen_biosample_id FROM citizen_biosample_original_haplogroup);

CREATE INDEX idx_citizen_biosample_orig_hg ON public.citizen_biosample
    USING GIN (original_haplogroups jsonb_path_ops)
    WHERE original_haplogroups != '[]'::jsonb;

-- Drop old tables
DROP TABLE public.biosample_original_haplogroup;
DROP TABLE public.citizen_biosample_original_haplogroup;


-- # --- !Downs

-- Recreate biosample_original_haplogroup table
CREATE TABLE public.biosample_original_haplogroup (
    id               SERIAL PRIMARY KEY,
    biosample_id     INT REFERENCES biosample (id) ON DELETE CASCADE,
    publication_id   INT REFERENCES publication (id) ON DELETE CASCADE,
    original_y_haplogroup VARCHAR(255),
    original_mt_haplogroup VARCHAR(255),
    notes            TEXT,
    y_haplogroup_result JSONB,
    mt_haplogroup_result JSONB,
    UNIQUE (biosample_id, publication_id)
);

-- Migrate data back from JSONB array
INSERT INTO public.biosample_original_haplogroup (
    biosample_id, publication_id, y_haplogroup_result, mt_haplogroup_result, notes
)
SELECT b.id,
    (entry->>'publicationId')::int,
    entry->'yHaplogroupResult',
    entry->'mtHaplogroupResult',
    entry->>'notes'
FROM public.biosample b,
     jsonb_array_elements(b.original_haplogroups) AS entry
WHERE b.original_haplogroups != '[]'::jsonb;

-- Recreate citizen_biosample_original_haplogroup table
CREATE TABLE public.citizen_biosample_original_haplogroup (
    id                    SERIAL PRIMARY KEY,
    citizen_biosample_id  INT REFERENCES citizen_biosample (id) ON DELETE CASCADE,
    publication_id        INT REFERENCES publication (id) ON DELETE CASCADE,
    original_y_haplogroup VARCHAR(255),
    original_mt_haplogroup VARCHAR(255),
    notes                 TEXT,
    y_haplogroup_result   JSONB,
    mt_haplogroup_result  JSONB,
    UNIQUE (citizen_biosample_id, publication_id)
);

INSERT INTO public.citizen_biosample_original_haplogroup (
    citizen_biosample_id, publication_id, y_haplogroup_result, mt_haplogroup_result, notes
)
SELECT cb.id,
    (entry->>'publicationId')::int,
    entry->'yHaplogroupResult',
    entry->'mtHaplogroupResult',
    entry->>'notes'
FROM public.citizen_biosample cb,
     jsonb_array_elements(cb.original_haplogroups) AS entry
WHERE cb.original_haplogroups != '[]'::jsonb;

-- Drop JSONB columns and indexes
DROP INDEX IF EXISTS idx_biosample_orig_hg;
DROP INDEX IF EXISTS idx_citizen_biosample_orig_hg;
ALTER TABLE public.biosample DROP COLUMN original_haplogroups;
ALTER TABLE public.citizen_biosample DROP COLUMN original_haplogroups;
