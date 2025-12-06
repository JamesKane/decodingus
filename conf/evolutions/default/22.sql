# --- !Ups
ALTER TABLE citizen_biosample RENAME COLUMN citizen_biosample_did TO at_uri;
ALTER TABLE citizen_biosample ADD COLUMN deleted BOOLEAN DEFAULT false NOT NULL;
ALTER TABLE citizen_biosample ADD COLUMN at_cid VARCHAR(255);
ALTER TABLE citizen_biosample ADD COLUMN created_at TIMESTAMP DEFAULT now() NOT NULL;
ALTER TABLE citizen_biosample ADD COLUMN updated_at TIMESTAMP DEFAULT now() NOT NULL;
ALTER TABLE citizen_biosample ADD COLUMN accession VARCHAR(255);
ALTER TABLE citizen_biosample ADD COLUMN alias VARCHAR(255);
CREATE UNIQUE INDEX citizen_biosample_accession_uindex ON citizen_biosample (accession);

CREATE TABLE publication_citizen_biosample
(
    publication_id INT REFERENCES publication (id) ON DELETE CASCADE,
    citizen_biosample_id INT REFERENCES citizen_biosample (id) ON DELETE CASCADE,
    PRIMARY KEY (publication_id, citizen_biosample_id)
);

CREATE TABLE citizen_biosample_original_haplogroup
(
    id               SERIAL PRIMARY KEY,
    citizen_biosample_id INT REFERENCES citizen_biosample (id) ON DELETE CASCADE,
    publication_id   INT REFERENCES publication (id) ON DELETE CASCADE,
    original_y_haplogroup VARCHAR(255),
    original_mt_haplogroup VARCHAR(255),
    notes            TEXT,
    UNIQUE (citizen_biosample_id, publication_id)
);

CREATE TABLE project
(
    id               SERIAL PRIMARY KEY,
    project_guid     UUID NOT NULL UNIQUE,
    name             VARCHAR(255) NOT NULL,
    description      TEXT,
    owner_did        VARCHAR(255) NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now(),
    deleted          BOOLEAN DEFAULT false NOT NULL,
    at_cid           VARCHAR(255)
);

# --- !Downs
DROP TABLE project;
DROP TABLE citizen_biosample_original_haplogroup;
DROP TABLE publication_citizen_biosample;
DROP INDEX citizen_biosample_accession_uindex;
ALTER TABLE citizen_biosample DROP COLUMN alias;
ALTER TABLE citizen_biosample DROP COLUMN accession;
ALTER TABLE citizen_biosample DROP COLUMN updated_at;
ALTER TABLE citizen_biosample DROP COLUMN created_at;
ALTER TABLE citizen_biosample DROP COLUMN at_cid;
ALTER TABLE citizen_biosample DROP COLUMN deleted;
ALTER TABLE citizen_biosample RENAME COLUMN at_uri TO citizen_biosample_did;
