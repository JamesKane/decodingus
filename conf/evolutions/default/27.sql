-- !Ups

ALTER TABLE sequence_file ADD COLUMN checksums JSONB DEFAULT '[]'::jsonb;
ALTER TABLE sequence_file ADD COLUMN http_locations JSONB DEFAULT '[]'::jsonb;
ALTER TABLE sequence_file ADD COLUMN atp_location JSONB;

UPDATE sequence_file sf SET checksums = (
    SELECT COALESCE(jsonb_agg(to_jsonb(sfc) - 'sequence_file_id'), '[]'::jsonb)
    FROM sequence_file_checksum sfc WHERE sfc.sequence_file_id = sf.id
);

UPDATE sequence_file sf SET http_locations = (
    SELECT COALESCE(jsonb_agg(to_jsonb(shl) - 'sequence_file_id'), '[]'::jsonb)
    FROM sequence_http_location shl WHERE shl.sequence_file_id = sf.id
);

UPDATE sequence_file sf SET atp_location = (
    SELECT to_jsonb(sal) - 'sequence_file_id'
    FROM sequence_atp_location sal WHERE sal.sequence_file_id = sf.id
);

CREATE INDEX idx_sf_checksums ON sequence_file USING GIN (checksums jsonb_path_ops);
CREATE INDEX idx_sf_http_locations ON sequence_file USING GIN (http_locations jsonb_path_ops);
CREATE INDEX idx_sf_atp_location ON sequence_file USING GIN (atp_location jsonb_path_ops);

DROP TABLE sequence_file_checksum;
DROP TABLE sequence_http_location;
DROP TABLE sequence_atp_location;

-- !Downs

CREATE TABLE sequence_file_checksum (
    id BIGSERIAL PRIMARY KEY,
    sequence_file_id INT NOT NULL,
    checksum VARCHAR(255) NOT NULL,
    algorithm VARCHAR(50) NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    FOREIGN KEY (sequence_file_id) REFERENCES sequence_file(id) ON DELETE CASCADE,
    UNIQUE (sequence_file_id, algorithm)
);

CREATE TABLE sequence_http_location (
    id BIGSERIAL PRIMARY KEY,
    sequence_file_id INT NOT NULL,
    url VARCHAR(2048) NOT NULL,
    url_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    FOREIGN KEY (sequence_file_id) REFERENCES sequence_file(id) ON DELETE CASCADE,
    UNIQUE (sequence_file_id, url_hash)
);

CREATE TABLE sequence_atp_location (
    id BIGSERIAL PRIMARY KEY,
    sequence_file_id INT NOT NULL,
    repo_did VARCHAR(255) NOT NULL,
    record_uri VARCHAR(255) NOT NULL,
    cid VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    FOREIGN KEY (sequence_file_id) REFERENCES sequence_file(id) ON DELETE CASCADE,
    UNIQUE (sequence_file_id, record_uri)
);

-- Re-populate sequence_file_checksum from sequence_file.checksums (simplified, assumes single checksum for now)
INSERT INTO sequence_file_checksum (sequence_file_id, checksum, algorithm, verified_at, created_at, updated_at)
SELECT
    sf.id,
    (jsonb_array_elements(sf.checksums)->>'checksum')::VARCHAR,
    (jsonb_array_elements(sf.checksums)->>'algorithm')::VARCHAR,
    (jsonb_array_elements(sf.checksums)->>'verified_at')::TIMESTAMP WITH TIME ZONE,
    (jsonb_array_elements(sf.checksums)->>'created_at')::TIMESTAMP WITH TIME ZONE,
    (jsonb_array_elements(sf.checksums)->>'updated_at')::TIMESTAMP WITH TIME ZONE
FROM sequence_file sf
WHERE jsonb_array_length(sf.checksums) > 0;


-- Re-populate sequence_http_location from sequence_file.http_locations (simplified)
INSERT INTO sequence_http_location (sequence_file_id, url, url_hash, created_at, updated_at)
SELECT
    sf.id,
    (jsonb_array_elements(sf.http_locations)->>'url')::VARCHAR,
    (jsonb_array_elements(sf.http_locations)->>'url_hash')::VARCHAR,
    (jsonb_array_elements(sf.http_locations)->>'created_at')::TIMESTAMP WITH TIME ZONE,
    (jsonb_array_elements(sf.http_locations)->>'updated_at')::TIMESTAMP WITH TIME ZONE
FROM sequence_file sf
WHERE jsonb_array_length(sf.http_locations) > 0;

-- Re-populate sequence_atp_location from sequence_file.atp_location (simplified)
INSERT INTO sequence_atp_location (sequence_file_id, repo_did, record_uri, cid, created_at, updated_at)
SELECT
    sf.id,
    (sf.atp_location->>'repo_did')::VARCHAR,
    (sf.atp_location->>'record_uri')::VARCHAR,
    (sf.atp_location->>'cid')::VARCHAR,
    (sf.atp_location->>'created_at')::TIMESTAMP WITH TIME ZONE,
    (sf.atp_location->>'updated_at')::TIMESTAMP WITH TIME ZONE
FROM sequence_file sf
WHERE sf.atp_location IS NOT NULL;


ALTER TABLE sequence_file DROP COLUMN checksums;
ALTER TABLE sequence_file DROP COLUMN http_locations;
ALTER TABLE sequence_file DROP COLUMN atp_location;
