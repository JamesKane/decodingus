# --- !Ups
--- Add revision tracking table
CREATE TABLE relationship_revision_metadata
(
    haplogroup_relationship_id INT          NOT NULL,
    revision_id                INT          NOT NULL,
    author                     VARCHAR(255) NOT NULL,
    timestamp                  TIMESTAMP    NOT NULL,
    comment                    TEXT         NOT NULL,
    change_type                VARCHAR(50)  NOT NULL,
    previous_revision_id       INT,
    PRIMARY KEY (haplogroup_relationship_id, revision_id),
    FOREIGN KEY (haplogroup_relationship_id)
        REFERENCES haplogroup_relationship (haplogroup_relationship_id)
        ON DELETE CASCADE
);

-- Indexes for common queries
CREATE INDEX idx_revision_metadata_author ON relationship_revision_metadata (author);
CREATE INDEX idx_revision_metadata_timestamp ON relationship_revision_metadata (timestamp);
CREATE INDEX idx_revision_metadata_change_type ON relationship_revision_metadata (change_type);

# --- !Downs

DROP TABLE relationship_revision_metadata;