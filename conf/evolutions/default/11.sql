-- !Ups
-- Add a lock column to prevent batch updates from the source from removing manual corrections
ALTER TABLE biosample ADD COLUMN locked boolean;

-- Then update existing records
-- Lock samples that have either sex or geocoord manually set
UPDATE biosample
SET locked = false;

-- Finally make the column non-null with default
ALTER TABLE biosample ALTER COLUMN locked SET NOT NULL;
ALTER TABLE biosample ALTER COLUMN locked SET DEFAULT false;

-- !Downs
ALTER TABLE biosample DROP COLUMN locked;