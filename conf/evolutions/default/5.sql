# --- !Ups
--- Add variant revision tracking table
CREATE TABLE haplogroup_variant_metadata (
                                             haplogroup_variant_id INT NOT NULL,
                                             revision_id INT NOT NULL,
                                             author VARCHAR(255) NOT NULL,
                                             timestamp TIMESTAMP NOT NULL,
                                             comment TEXT NOT NULL,
                                             change_type VARCHAR(50) NOT NULL,
                                             previous_revision_id INT,
                                             PRIMARY KEY (haplogroup_variant_id, revision_id),
                                             FOREIGN KEY (haplogroup_variant_id)
                                                 REFERENCES haplogroup_variant (haplogroup_variant_id)
                                                 ON DELETE CASCADE
);

# --- !Downs
DROP TABLE haplogroup_variant_metadata;