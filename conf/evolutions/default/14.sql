# --- !Ups

CREATE TABLE biosample_original_haplogroup
(
    id               SERIAL PRIMARY KEY,
    biosample_id     INT REFERENCES biosample (id) ON DELETE CASCADE,
    publication_id   INT REFERENCES publication (id) ON DELETE CASCADE,
    original_y_haplogroup VARCHAR(255),
    original_mt_haplogroup VARCHAR(255),
    notes            TEXT,
    UNIQUE (biosample_id, publication_id)
);

-- !Downs

DROP TABLE biosample_original_haplogroup;