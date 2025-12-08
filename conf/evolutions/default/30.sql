-- !Ups

-- 1. Add new test_type_id column
ALTER TABLE sequence_library ADD COLUMN test_type_id INTEGER;

-- 2. Migrate data from old test_type string to new test_type_id using test_type_definition
UPDATE sequence_library sl
SET test_type_id = ttd.id
FROM test_type_definition ttd
WHERE UPPER(sl.test_type) = ttd.code; -- Corrected to ttd.code

-- 3. Add foreign key constraint
ALTER TABLE sequence_library
ADD CONSTRAINT fk_sequence_library_test_type
FOREIGN KEY (test_type_id) REFERENCES test_type_definition(id) ON DELETE RESTRICT;

-- 4. Make the test_type_id column NOT NULL (if all existing data could be migrated)
--    Note: If there are unmappable values in sequence_library.test_type, this step will fail.
--    Assuming all existing values map to an entry in test_type_definition.
ALTER TABLE sequence_library ALTER COLUMN test_type_id SET NOT NULL;

-- 5. Drop the old test_type column (optional, can be done later after verification)
ALTER TABLE sequence_library DROP COLUMN test_type;

-- !Downs

-- 1. Re-add the old test_type column
ALTER TABLE sequence_library ADD COLUMN test_type VARCHAR(255);

-- 2. Migrate data back from test_type_id to test_type string
UPDATE sequence_library sl
SET test_type = ttd.code
FROM test_type_definition ttd
WHERE sl.test_type_id = ttd.id;

-- 3. Drop foreign key constraint
ALTER TABLE sequence_library DROP CONSTRAINT IF EXISTS fk_sequence_library_test_type;

-- 4. Drop the new test_type_id column
ALTER TABLE sequence_library DROP COLUMN test_type_id;
