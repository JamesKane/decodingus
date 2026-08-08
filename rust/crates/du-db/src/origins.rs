//! Ancestral origins of the placed samples under a clade — the read side of
//! `fed.ancestral_origin`, and the input to the genealogical-era origins icicle.
//! Design: `proposals/ancestral-origin-icicle.md`.
//!
//! **Two resolution paths, because the tree predates federation.** A published origin names its
//! sample either by the parent biosample's at-uri (genuinely federated samples) or by a vendor
//! identifier. The second is the one that fires: **no** placed sample currently carries an at-uri
//! — the tips were bulk-loaded — while 7,548 of them carry an `FTDNA` row in
//! `core.biosample_identifier`. Both are unioned so the at-uri path works as federation grows.
//!
//! **The identifier never leaves this module.** It is a vendor kit id, `is_public = false` by
//! namespace policy ([`crate::identifier::is_public_namespace`]); it is a join key here and must
//! not reach any public projection. [`SampleOrigin`] deliberately carries no identifier field.

use crate::place::{self, PlacePath};
use crate::{pg_enum_label, DbError};
use du_domain::enums::DnaType;
use sqlx::PgPool;
use uuid::Uuid;

/// One placed sample's ancestral origin, normalized and ready to group by.
#[derive(Debug, Clone)]
pub struct SampleOrigin {
    pub sample_guid: Uuid,
    /// The node this sample is placed on — the composition rolls up from here to every ancestor.
    pub haplogroup_id: i64,
    /// Family name of the most distant known ancestor. Single-token by ingest gate.
    pub surname: Option<String>,
    /// Normalized locality ladder. May be empty when the record carried no place at all.
    pub place: PlacePath,
    /// The ancestor's birth year — also the reason place-level detail was allowed to publish.
    pub birth_year: Option<i32>,
}

/// Published origins for every placed sample at or below `root_name`.
///
/// The subtree is walked **unbounded**, not to the render window's depth: a block's composition is
/// what lies beneath it, and cutting the walk at the visible depth would silently understate every
/// block at the boundary.
///
/// When two contributors publish an origin for the same sample the most recently indexed record
/// wins (`time_us`), so the result holds at most one row per sample and the counts cannot
/// double-count a man.
pub async fn origins_under(
    pool: &PgPool,
    dna_type: DnaType,
    root_name: &str,
) -> Result<Vec<SampleOrigin>, DbError> {
    #[derive(sqlx::FromRow)]
    struct Row {
        sample_guid: Uuid,
        haplogroup_id: i64,
        surname: Option<String>,
        origin_place: Option<String>,
        origin_country: Option<String>,
        birth_year: Option<i32>,
    }
    let rows: Vec<Row> = sqlx::query_as(
        "WITH RECURSIVE sub AS ( \
            SELECT id FROM tree.haplogroup \
            WHERE name = $1 AND haplogroup_type::text = $2 AND valid_until IS NULL \
            UNION ALL \
            SELECT r.child_haplogroup_id FROM tree.haplogroup_relationship r \
            JOIN sub ON r.parent_haplogroup_id = sub.id \
            WHERE r.valid_until IS NULL \
         ), \
         placed AS ( \
            SELECT hs.sample_guid, hs.haplogroup_id, b.atproto->>'uri' AS at_uri \
            FROM tree.haplogroup_sample hs \
            JOIN sub ON sub.id = hs.haplogroup_id \
            JOIN core.biosample b ON b.sample_guid = hs.sample_guid AND b.deleted = false \
            WHERE hs.dna_type::text = $2 AND hs.status IN ('PLACED','CURATED') \
         ), \
         ids AS ( \
            SELECT ao.did, ao.rkey, upper(e->>'namespace') AS ns, upper(e->>'value') AS val \
            FROM fed.ancestral_origin ao, LATERAL jsonb_array_elements(ao.external_ids) e \
            WHERE ao.lineage = $2 \
         ), \
         matched AS ( \
            SELECT p.sample_guid, p.haplogroup_id, ao.surname, ao.origin_place, \
                   ao.origin_country, ao.birth_year, ao.time_us \
            FROM placed p \
            JOIN core.biosample_identifier i ON i.sample_guid = p.sample_guid \
            JOIN ids ON ids.ns = i.namespace AND ids.val = i.value \
            JOIN fed.ancestral_origin ao ON ao.did = ids.did AND ao.rkey = ids.rkey \
            UNION ALL \
            SELECT p.sample_guid, p.haplogroup_id, ao.surname, ao.origin_place, \
                   ao.origin_country, ao.birth_year, ao.time_us \
            FROM placed p \
            JOIN fed.ancestral_origin ao ON ao.biosample_ref = p.at_uri \
            WHERE p.at_uri IS NOT NULL AND ao.lineage = $2 \
         ) \
         SELECT DISTINCT ON (sample_guid) \
                sample_guid, haplogroup_id, surname, origin_place, origin_country, birth_year \
         FROM matched \
         ORDER BY sample_guid, time_us DESC",
    )
    .bind(root_name)
    .bind(pg_enum_label(&dna_type)?)
    .fetch_all(pool)
    .await?;

    Ok(rows
        .into_iter()
        .map(|r| SampleOrigin {
            sample_guid: r.sample_guid,
            haplogroup_id: r.haplogroup_id,
            surname: r.surname,
            place: place::normalize(r.origin_place.as_deref(), r.origin_country.as_deref()),
            birth_year: r.birth_year,
        })
        .collect())
}

/// How many placed samples sit at or below `root_name` — the denominator the view reports
/// alongside the composition, so "12 origins" is never mistaken for "12 men".
///
/// [`crate::tree_sample::count_under`] answers the same question and is reused rather than
/// duplicated; this alias exists only to keep the origins call site reading in one place.
pub use crate::tree_sample::count_under as placed_under;
