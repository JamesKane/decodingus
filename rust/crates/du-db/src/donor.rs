//! Specimen-donor consolidation — the entity *above* biosamples that links the
//! multiple biosample rows belonging to one individual.
//!
//! One person is routinely represented by several `core.biosample` rows: the same
//! 1000-Genomes donor appears under an NCBI BioSample accession (`SAMN…`) **and** an
//! EBI accession (`SAMEA…`) from different publications, and — when the sample was
//! used as a de-novo tree building block — again as a tree tip keyed by the raw
//! panel id (`HG00126`). The ETL gave each accession its own `specimen_donor` and
//! left the tip donor-less, so the donor entity never actually linked them, and the
//! sample's haplogroup (recorded on the tip's tree placement) couldn't surface on
//! the catalog accessions.
//!
//! [`consolidate_denovo_donors`] repairs this: it groups biosamples by their shared
//! panel id (a tip's `accession` == a reference's `alias`), points every member at a
//! single surviving donor (the richest one — most complete metadata), fills that
//! donor's gaps + canonical identifier, and prunes the emptied donor rows. The
//! sample report then resolves the haplogroup at the donor level, so every accession
//! of the individual shows the de-novo tree placement.

use crate::DbError;
use sqlx::PgPool;

/// Outcome of [`consolidate_denovo_donors`].
#[derive(Debug, Clone, Default)]
pub struct DonorConsolidationReport {
    /// Distinct same-individual groups (shared panel id with a de-novo tip + ≥1 ref).
    pub groups: i64,
    /// Biosamples repointed to a surviving donor.
    pub biosamples_repointed: u64,
    /// Redundant donor rows deleted (emptied by the repoint).
    pub donors_pruned: u64,
}

/// The member set: every biosample whose identity key (a de-novo tip's `accession`,
/// else a reference's `alias`) is a panel id shared between a de-novo tip and at
/// least one non-de-novo reference. `key` is lower-cased for grouping; `orig_id`
/// keeps the original-case id for the donor identifier.
const MEMBERS_CTE: &str = "\
    members AS ( \
        SELECT b.sample_guid, b.donor_id, \
               (CASE WHEN b.source_attrs->>'denovo' = 'true' THEN lower(b.accession) ELSE lower(b.alias) END) AS key, \
               (CASE WHEN b.source_attrs->>'denovo' = 'true' THEN b.accession ELSE b.alias END) AS orig_id \
        FROM core.biosample b \
        WHERE b.deleted = false \
          AND (CASE WHEN b.source_attrs->>'denovo' = 'true' THEN lower(b.accession) ELSE lower(b.alias) END) IN ( \
              SELECT lower(d.accession) FROM core.biosample d \
              WHERE d.source_attrs->>'denovo' = 'true' AND d.deleted = false AND d.accession IS NOT NULL \
                AND EXISTS (SELECT 1 FROM core.biosample r \
                            WHERE r.deleted = false AND COALESCE(r.source_attrs->>'denovo','') <> 'true' \
                              AND lower(r.alias) = lower(d.accession)) \
          ) \
    )";

/// Consolidate the donor rows of same-individual biosamples (see module docs).
/// Idempotent — once a group shares one donor there is nothing left to repoint or
/// prune. `apply = false` counts the work without mutating.
pub async fn consolidate_denovo_donors(pool: &PgPool, apply: bool) -> Result<DonorConsolidationReport, DbError> {
    let groups: i64 = sqlx::query_scalar(&format!("WITH {MEMBERS_CTE} SELECT count(DISTINCT key) FROM members"))
        .fetch_one(pool)
        .await?;
    let mut rep = DonorConsolidationReport { groups, ..Default::default() };
    if !apply {
        return Ok(rep);
    }

    let mut tx = pool.begin().await?;

    // 1) Materialize each member with its group's surviving donor: the donor with the
    //    most complete metadata (geocoord + biobank + sex), ties broken by lowest id.
    sqlx::query(&format!(
        "CREATE TEMP TABLE _donor_grp ON COMMIT DROP AS \
         WITH {MEMBERS_CTE}, \
         survivor AS ( \
             SELECT DISTINCT ON (m.key) m.key, sd.id AS survivor_donor \
             FROM (SELECT DISTINCT key, donor_id FROM members WHERE donor_id IS NOT NULL) m \
             JOIN core.specimen_donor sd ON sd.id = m.donor_id \
             ORDER BY m.key, \
                 ((sd.geocoord IS NOT NULL)::int + (sd.origin_biobank IS NOT NULL)::int + (sd.sex IS NOT NULL)::int) DESC, \
                 sd.id \
         ) \
         SELECT m.sample_guid, m.donor_id, m.key, m.orig_id, s.survivor_donor \
         FROM members m JOIN survivor s ON s.key = m.key"
    ))
    .execute(&mut *tx)
    .await?;

    // 2) Fill the survivor donor's gaps from its group siblings + set the canonical
    //    identifier (original-case panel id) — do this BEFORE the repoint, while the
    //    other donors still hold their metadata.
    sqlx::query(
        "UPDATE core.specimen_donor s SET \
             sex = COALESCE(s.sex, agg.sex), \
             geocoord = COALESCE(s.geocoord, agg.geocoord), \
             origin_biobank = COALESCE(s.origin_biobank, agg.biobank), \
             donor_identifier = agg.ident, \
             updated_at = now() \
         FROM ( \
             SELECT g.survivor_donor, \
                    (array_agg(sd.sex) FILTER (WHERE sd.sex IS NOT NULL))[1] AS sex, \
                    (array_agg(sd.geocoord) FILTER (WHERE sd.geocoord IS NOT NULL))[1] AS geocoord, \
                    (array_agg(sd.origin_biobank) FILTER (WHERE sd.origin_biobank IS NOT NULL))[1] AS biobank, \
                    max(g.orig_id) AS ident \
             FROM _donor_grp g LEFT JOIN core.specimen_donor sd ON sd.id = g.donor_id \
             GROUP BY g.survivor_donor \
         ) agg \
         WHERE s.id = agg.survivor_donor",
    )
    .execute(&mut *tx)
    .await?;

    // 3) Repoint every group member to its surviving donor.
    let repointed = sqlx::query(
        "UPDATE core.biosample b SET donor_id = g.survivor_donor, updated_at = now() \
         FROM _donor_grp g WHERE b.sample_guid = g.sample_guid AND b.donor_id IS DISTINCT FROM g.survivor_donor",
    )
    .execute(&mut *tx)
    .await?
    .rows_affected();

    // 4) Prune donors that the repoint emptied (were in a group, now unreferenced).
    let pruned = sqlx::query(
        "DELETE FROM core.specimen_donor sd \
         WHERE sd.id IN (SELECT DISTINCT donor_id FROM _donor_grp WHERE donor_id IS NOT NULL) \
           AND NOT EXISTS (SELECT 1 FROM core.biosample b WHERE b.donor_id = sd.id)",
    )
    .execute(&mut *tx)
    .await?
    .rows_affected();

    tx.commit().await?;
    rep.biosamples_repointed = repointed;
    rep.donors_pruned = pruned;
    Ok(rep)
}
