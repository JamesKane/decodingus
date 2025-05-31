# --- !Ups

-- Create the new checksums table
CREATE TABLE sequence_file_checksum (
                                        id               SERIAL PRIMARY KEY,
                                        sequence_file_id INT NOT NULL,
                                        checksum         VARCHAR(255) NOT NULL,
                                        algorithm        VARCHAR(50) NOT NULL,
                                        verified_at      TIMESTAMP NOT NULL,
                                        FOREIGN KEY (sequence_file_id) REFERENCES sequence_file (id) ON DELETE CASCADE,
                                        UNIQUE (sequence_file_id, algorithm)
);

-- Migrate existing MD5 checksums
INSERT INTO sequence_file_checksum (sequence_file_id, checksum, algorithm, verified_at)
SELECT id, file_md5, 'MD5', created_at
FROM sequence_file;

ALTER TABLE sequence_file DROP COLUMN file_md5;

# --- !Downs

-- If we dropped file_md5, restore it first
ALTER TABLE sequence_file ADD COLUMN file_md5 VARCHAR(255);

-- Restore MD5 checksums if we dropped the column
UPDATE sequence_file sf
SET file_md5 = sfc.checksum
FROM sequence_file_checksum sfc
WHERE sf.id = sfc.sequence_file_id AND sfc.algorithm = 'MD5';

DROP TABLE sequence_file_checksum;