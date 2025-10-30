# --- !Ups
create table public.sequencing_lab
(
    id                   serial
        primary key,
    name                 varchar(255) not null
        unique,
    is_d2c               boolean               default false not null,
    website_url          varchar(255), -- URL to the lab's official website
    description_markdown text,         -- Rich text description (e.g., accreditation, methods)
    created_at           timestamp    not null default now(),
    updated_at           timestamp
);

create table public.sequencer_instrument
(
    id            serial
        primary key,
    instrument_id varchar(255) not null
        unique,                                -- The ID found in the BAM/CRAM read header (e.g., 'A00123')
    lab_id        integer      not null
        references public.sequencing_lab (id), -- Foreign key to the lab
    manufacturer  varchar(255),                -- Optional: e.g., 'Illumina', 'PacBio'
    model         varchar(255),                -- Optional: e.g., 'NovaSeq 6000', 'MiSeq'
    created_at    timestamp    not null default now(),
    updated_at    timestamp
);

-- An index to optimize lookups by the instrument ID for the API
create unique index sequencer_instrument_instrument_id_uindex
    on public.sequencer_instrument (instrument_id);

# --- !Downs

drop table public.sequencer_instrument;
drop table public.sequencing_lab;
