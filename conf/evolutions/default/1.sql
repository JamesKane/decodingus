# --- !Ups
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE specimen_donor
(
    id               SERIAL PRIMARY KEY,
    donor_identifier VARCHAR(255) NOT NULL,
    origin_biobank   VARCHAR(255) NOT NULL
);

CREATE TABLE biosample
(
    id                SERIAL PRIMARY KEY,
    sample_accession  VARCHAR(255) UNIQUE NOT NULL,
    description       TEXT                NOT NULL,
    alias             VARCHAR(255),
    center_name       VARCHAR(255)        NOT NULL,
    sex               VARCHAR(15) CHECK (sex IN ('male', 'female', 'intersex')),
    geocoord          GEOMETRY(Point, 4326),
    specimen_donor_id INT REFERENCES specimen_donor (id) ON DELETE CASCADE,
    sample_guid       UUID                NOT NULL
);

CREATE TABLE citizen_biosample
(
    id                    SERIAL PRIMARY KEY,
    citizen_biosample_did VARCHAR(255) UNIQUE,
    source_platform       VARCHAR(255),
    collection_date       DATE,
    sex                   VARCHAR(15) CHECK (sex IN ('male', 'female', 'intersex')),
    geocoord              GEOMETRY(Point, 4326),
    description           TEXT,
    sample_guid           UUID NOT NULL
);

CREATE TABLE pgp_biosample
(
    pgp_biosample_id        SERIAL PRIMARY KEY,
    pgp_participant_id      VARCHAR(255) NOT NULL,
    ena_biosample_accession VARCHAR(255) UNIQUE,
    sex                     VARCHAR(15) CHECK (sex IN ('male', 'female', 'intersex')),
    sample_guid             UUID         NOT NULL
);

CREATE TABLE haplogroup
(
    haplogroup_id    SERIAL PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    lineage          VARCHAR(255),
    description      TEXT,
    haplogroup_type  VARCHAR(10)  NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    revision_id      INTEGER      NOT NULL,
    source           VARCHAR(255) NOT NULL,
    confidence_level VARCHAR(255) NOT NULL,
    valid_from       TIMESTAMP    NOT NULL,
    valid_until      TIMESTAMP,
    unique (name)
);

CREATE TABLE haplogroup_relationship
(
    haplogroup_relationship_id SERIAL PRIMARY KEY,
    child_haplogroup_id        INTEGER      NOT NULL,
    parent_haplogroup_id       INTEGER      NOT NULL,
    revision_id                INTEGER      NOT NULL,
    valid_from                 TIMESTAMP    NOT NULL,
    valid_until                TIMESTAMP,
    source                     VARCHAR(255) NOT NULL,
    FOREIGN KEY (child_haplogroup_id) REFERENCES haplogroup (haplogroup_id) ON DELETE CASCADE,
    FOREIGN KEY (parent_haplogroup_id) REFERENCES haplogroup (haplogroup_id) ON DELETE CASCADE,
    UNIQUE (child_haplogroup_id, revision_id)
);

CREATE TABLE genbank_contig
(
    genbank_contig_id SERIAL PRIMARY KEY,
    accession         VARCHAR(255) NOT NULL,
    common_name       VARCHAR(255),
    reference_genome  VARCHAR(255),
    seq_length        INT          NOT NULL,
    UNIQUE (accession)
);

CREATE TABLE variant
(
    variant_id        SERIAL PRIMARY KEY,
    genbank_contig_id INT          NOT NULL,
    position          INTEGER      NOT NULL,
    reference_allele  VARCHAR(255) NOT NULL,
    alternate_allele  VARCHAR(255) NOT NULL,
    variant_type      VARCHAR(5)   NOT NULL CHECK (variant_type IN ('SNP', 'INDEL')),
    rs_id             VARCHAR(255),
    common_name       VARCHAR(255),
    FOREIGN KEY (genbank_contig_id) REFERENCES genbank_contig (genbank_contig_id) ON DELETE CASCADE,
    UNIQUE (genbank_contig_id, position, reference_allele, alternate_allele)
);

CREATE TABLE haplogroup_variant
(
    haplogroup_variant_id SERIAL PRIMARY KEY,
    haplogroup_id         INT NOT NULL,
    variant_id            INT NOT NULL,
    FOREIGN KEY (haplogroup_id) REFERENCES haplogroup (haplogroup_id) ON DELETE CASCADE,
    FOREIGN KEY (variant_id) REFERENCES variant (variant_id) ON DELETE CASCADE,
    UNIQUE (haplogroup_id, variant_id)
);

CREATE TABLE biosample_haplogroup
(
    sample_guid      UUID NOT NULL,
    y_haplogroup_id  INT,
    mt_haplogroup_id INT,
    FOREIGN KEY (y_haplogroup_id) REFERENCES haplogroup (haplogroup_id) ON DELETE CASCADE,
    FOREIGN KEY (mt_haplogroup_id) REFERENCES haplogroup (haplogroup_id) ON DELETE CASCADE,
    PRIMARY KEY (sample_guid)
);

CREATE TABLE analysis_method
(
    analysis_method_id SERIAL PRIMARY KEY,
    method_name        VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE population
(
    population_id   SERIAL PRIMARY KEY,
    population_name VARCHAR(255) NOT NULL UNIQUE
    -- parent_population_id BIGINT REFERENCES population(population_id) -- Uncomment if needed
);

CREATE TABLE ancestry_analysis
(
    ancestry_analysis_id SERIAL PRIMARY KEY,
    sample_guid          UUID NOT NULL,
    analysis_method_id   INT  NOT NULL,
    population_id        INT  NOT NULL,
    probability          DECIMAL(5, 4),
    FOREIGN KEY (analysis_method_id) REFERENCES analysis_method (analysis_method_id) ON DELETE CASCADE,
    FOREIGN KEY (population_id) REFERENCES population (population_id) ON DELETE CASCADE,
    UNIQUE (sample_guid, analysis_method_id, population_id)
);

CREATE TABLE sequence_library
(
    id          SERIAL PRIMARY KEY,
    sample_guid UUID         NOT NULL,
    lab         VARCHAR(255) NOT NULL,
    test_type   VARCHAR(255) NOT NULL,
    run_date    TIMESTAMP    NOT NULL,
    instrument  VARCHAR(255) NOT NULL,
    reads       BIGINT       NOT NULL,
    read_length INTEGER      NOT NULL,
    paired_end  BOOLEAN      NOT NULL,
    insert_size INTEGER,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP
);

CREATE TABLE sequence_file
(
    id               SERIAL PRIMARY KEY,
    library_id       INT          NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    file_size_bytes  BIGINT       NOT NULL,
    file_md5         VARCHAR(255) NOT NULL,
    file_format      VARCHAR(255) NOT NULL,
    aligner          VARCHAR(255) NOT NULL,
    target_reference VARCHAR(255) NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP,
    FOREIGN KEY (library_id) REFERENCES sequence_library (id) ON DELETE CASCADE
);

CREATE TABLE sequence_http_location
(
    id               SERIAL PRIMARY KEY,
    sequence_file_id INT  NOT NULL,
    file_url         TEXT NOT NULL,
    file_index_url   TEXT,
    FOREIGN KEY (sequence_file_id) REFERENCES sequence_file (id) ON DELETE CASCADE
);

CREATE TABLE sequence_atp_location
(
    id               SERIAL PRIMARY KEY,
    sequence_file_id INT          NOT NULL,
    repo_did         VARCHAR(255) NOT NULL,
    record_cid       VARCHAR(255) NOT NULL,
    record_path      TEXT         NOT NULL,
    index_did        VARCHAR(255),
    index_cid        VARCHAR(255),
    FOREIGN KEY (sequence_file_id) REFERENCES sequence_file (id) ON DELETE CASCADE
);

CREATE TABLE quality_metrics
(
    id               SERIAL PRIMARY KEY,
    contig_id        INT              NOT NULL,
    start_pos        INT              NOT NULL,
    end_pos          INT              NOT NULL,
    num_reads        INT              NOT NULL,
    ref_n            INT              NOT NULL,
    no_cov           INT              NOT NULL,
    low_cov          INT              NOT NULL,
    excessive_cov    INT              NOT NULL,
    poor_mq          INT              NOT NULL,
    callable         INT              NOT NULL,
    cov_percent      DOUBLE PRECISION NOT NULL,
    mean_depth       DOUBLE PRECISION NOT NULL,
    mean_mq          DOUBLE PRECISION NOT NULL,
    sequence_file_id BIGINT           NOT NULL,
    FOREIGN KEY (sequence_file_id) REFERENCES sequence_file (id) ON DELETE CASCADE,
    FOREIGN KEY (contig_id) REFERENCES genbank_contig (genbank_contig_id) ON DELETE CASCADE
);

CREATE TABLE reported_variant
(
    id               BIGSERIAL PRIMARY KEY,
    sample_guid      UUID             NOT NULL,
    contig_id        INT              NOT NULL,
    position         INT              NOT NULL,
    reference_allele VARCHAR(255)     NOT NULL,
    alternate_allele VARCHAR(255)     NOT NULL,
    variant_type     VARCHAR(5)       NOT NULL CHECK (variant_type IN ('SNP', 'INDEL')),
    reported_date    TIMESTAMP        NOT NULL,
    provenance       VARCHAR(255)     NOT NULL,
    confidence_score DOUBLE PRECISION NOT NULL,
    notes            TEXT,
    status           VARCHAR(255)     NOT NULL,
    FOREIGN KEY (contig_id) REFERENCES genbank_contig (genbank_contig_id) ON DELETE CASCADE
);

CREATE TABLE reported_negative_variant
(
    id            BIGSERIAL PRIMARY KEY,
    sample_guid   UUID         NOT NULL,
    variant_id    INT          NOT NULL,
    reported_date TIMESTAMP,
    notes         TEXT,
    status        VARCHAR(255) NOT NULL,
    FOREIGN KEY (variant_id) REFERENCES variant (variant_id) ON DELETE CASCADE
);

CREATE TABLE publication
(
    id               SERIAL PRIMARY KEY,
    pubmed_id        VARCHAR(20) UNIQUE,
    doi              VARCHAR(255) UNIQUE,
    title            TEXT NOT NULL,
    journal          VARCHAR(255),
    publication_date DATE,
    url              VARCHAR(2048)
);

CREATE TABLE ena_study
(
    id          SERIAL PRIMARY KEY,
    accession   VARCHAR(50) UNIQUE NOT NULL,
    title       VARCHAR(255)       NOT NULL,
    center_name VARCHAR(255)       NOT NULL,
    study_name  VARCHAR(255)       NOT NULL,
    details     TEXT
);

CREATE TABLE publication_ena_study
(
    publication_id INT,
    ena_study_id   INT,
    FOREIGN KEY (publication_id) REFERENCES publication (id),
    FOREIGN KEY (ena_study_id) REFERENCES ena_study (id),
    PRIMARY KEY (publication_id, ena_study_id)
);

CREATE TABLE publication_biosample
(
    publication_id INT REFERENCES publication (id) ON DELETE CASCADE,
    biosample_id   INT REFERENCES biosample (id) ON DELETE CASCADE,
    PRIMARY KEY (publication_id, biosample_id)
);

# --- !Downs
DROP TABLE publication_biosample;
DROP TABLE publication_ena_study;
DROP TABLE ena_study;
DROP TABLE publication;
DROP TABLE reported_negative_variant;
DROP TABLE reported_variant;
DROP TABLE quality_metrics;
DROP TABLE sequence_atp_location;
DROP TABLE sequence_http_location;
DROP TABLE sequence_file;
DROP TABLE sequence_library;
DROP TABLE ancestry_analysis;
DROP TABLE population;
DROP TABLE analysis_method;
DROP TABLE biosample_haplogroup;
DROP TABLE haplogroup_variant;
DROP TABLE variant;
DROP TABLE genbank_contig;
DROP TABLE haplogroup_relationship;
DROP TABLE haplogroup;
DROP TABLE pgp_biosample;
DROP TABLE citizen_biosample;
DROP TABLE biosample;
DROP TABLE specimen_donor;
