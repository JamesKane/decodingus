# --- !Ups
--- Add a sample Publication and associated samples
insert into publication (pubmed_id, doi, title, journal, publication_date, url, authors, abstract_summary)
VALUES (null,
        '10.1016/j.cell.2022.08.004',
        'High-coverage whole-genome sequencing of the expanded 1000 Genomes Project cohort including 602 trios',
        'Cell',
        '2022-09-01',
        null,
        'Byrska-Bishop, MartaEichler, Evan E. et al.',
        'The 1000 Genomes Project (1kGP) is the largest fully open resource of whole-genome sequencing (WGS) data consented for public distribution without access or use restrictions. The final, phase 3 release of the 1kGP included 2,504 unrelated samples from 26 populations and was based primarily on low-coverage WGS. Here, we present a high-coverage 3,202-sample WGS 1kGP resource, which now includes 602 complete trios, sequenced to a depth of 30X using Illumina. We performed single-nucleotide variant (SNV) and short insertion and deletion (INDEL) discovery and generated a comprehensive set of structural variants (SVs) by integrating multiple analytic methods through a machine learning model. We show gains in sensitivity and precision of variant calls compared to phase 3, especially among rare SNVs as well as INDELs and SVs spanning frequency spectrum. We also generated an improved reference imputation panel, making variants discovered here accessible for association studies.');

insert into ena_study (accession, title, center_name, study_name, details)
VALUES ('PRJEB31736',
        '30X whole genome sequencing coverage of the 2504 Phase 3 1000 Genome samples.',
        'NYGC',
        '1000 Genomes Project phase 3: 30X coverage whole genome sequencing ',
        'We sequenced all 2,504 samples from the 1000 Genomes (1KG) Project to a minimum of 30x mean genome coverage. Though a small number of 1KG samples had been sequenced to high coverage previously, we sequenced all samples to depth on the latest technology, providing a unified dataset for the next phase of analyses. We processed these samples using the laboratory processes we have previously used for the CCDG project (with minor modifications). Specifically, we generated PCR-free sequencing libraries using unique dual indices to avoid the index switching phenomenon that occurs and causes low level sequencing data contamination on the Illumina patterned flow cells. We sequenced these samples on the Illumina NovaSeq 6000 sequencing instrument, with 2x150bp reads. We believe this instrument represents the future for WGS with short-read technology, and it was important to sequence the 1KG samples in a format that is consistent with future large scale sequencing projects. Our automated analysis pipeline for whole genome sequencing matches the CCDG and TOPMed recommended best practices. Sequencing reads were aligned to the human reference, hs38DH, using BWA-MEM v0.7.15. Data are further processed using the GATK best-practices (v3.5), which generates VCF files in the 4.2 format. Single nucleotide variants and Indels are called using GATK HaplotypeCaller (v3.5), which generates a single-sample GVCF. Variant Quality Score Recalibration (VQSR) is performed using dbSNP138 so quality metrics for each variant can be used in downstream variant filtering. Additional information and links to data can be found at https: //https://urldefense.com/v3/__http://www.internationalgenome.org/data-portal/data-collection/30x-grch38__;!!C6sPl7C9qQ!BjhVenDl1v0jJYWcAb8zn-KEuaQJDHOLm3JTGxzkEO5rRLeioX_7BoFiaE7woY98KnI$');

insert into publication_ena_study (publication_id, ena_study_id)
VALUES ((select id from publication where doi = '10.1016/j.cell.2022.08.004'),
        (select id from ena_study where accession = 'PRJEB31736'));

# --- !Downs
