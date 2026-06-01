//! Queries for the unified `core.biosample`.

use crate::{parse_pg_enum, DbError, Page};
use du_domain::biosample::{Biosample, GeoPoint};
use du_domain::ids::{PublicationId, SampleGuid};
use sqlx::PgPool;
use uuid::Uuid;

#[derive(sqlx::FromRow)]
struct BiosampleRow {
    sample_guid: Uuid,
    source: String,
    accession: Option<String>,
    alias: Option<String>,
    description: Option<String>,
    center_name: Option<String>,
    locked: bool,
    source_attrs: serde_json::Value,
    atproto: Option<serde_json::Value>,
}

impl BiosampleRow {
    fn into_domain(self) -> Result<Biosample, DbError> {
        Ok(Biosample {
            sample_guid: SampleGuid(self.sample_guid),
            source: parse_pg_enum(&self.source, "source")?,
            accession: self.accession,
            alias: self.alias,
            description: self.description,
            center_name: self.center_name,
            locked: self.locked,
            source_attrs: self.source_attrs,
            atproto: self.atproto,
        })
    }
}

const SELECT: &str = "SELECT sample_guid, source::text AS source, accession, alias, description, \
    center_name, locked, source_attrs, atproto FROM core.biosample WHERE deleted = false";

pub async fn get_by_guid(pool: &PgPool, guid: SampleGuid) -> Result<Option<Biosample>, DbError> {
    let row: Option<BiosampleRow> = sqlx::query_as(&format!("{SELECT} AND sample_guid = $1"))
        .bind(guid.0)
        .fetch_optional(pool)
        .await?;
    row.map(BiosampleRow::into_domain).transpose()
}

/// All mappable biosample locations. PostGIS `ST_X`/`ST_Y` extract lon/lat from
/// the donor's `geocoord` (geometry Point, 4326). Backs the biosample map.
pub async fn geo_points(pool: &PgPool) -> Result<Vec<GeoPoint>, DbError> {
    #[derive(sqlx::FromRow)]
    struct GeoRow {
        lat: f64,
        lon: f64,
        accession: Option<String>,
        source: String,
    }
    let rows: Vec<GeoRow> = sqlx::query_as(
        "SELECT ST_Y(d.geocoord) AS lat, ST_X(d.geocoord) AS lon, b.accession, \
         b.source::text AS source \
         FROM core.biosample b JOIN core.specimen_donor d ON d.id = b.donor_id \
         WHERE d.geocoord IS NOT NULL AND b.deleted = false",
    )
    .fetch_all(pool)
    .await?;
    rows.into_iter()
        .map(|r| {
            Ok(GeoPoint {
                lat: r.lat,
                lon: r.lon,
                accession: r.accession,
                source: parse_pg_enum(&r.source, "source")?,
            })
        })
        .collect()
}

/// Paginated biosamples linked to a publication (the biosample report).
pub async fn for_publication(
    pool: &PgPool,
    publication_id: PublicationId,
    page: i64,
    page_size: i64,
) -> Result<Page<Biosample>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);

    let total: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM pubs.publication_biosample pb \
         JOIN core.biosample b ON b.sample_guid = pb.sample_guid \
         WHERE pb.publication_id = $1 AND b.deleted = false",
    )
    .bind(publication_id.0)
    .fetch_one(pool)
    .await?;

    let rows: Vec<BiosampleRow> = sqlx::query_as(
        "SELECT b.sample_guid, b.source::text AS source, b.accession, b.alias, b.description, \
         b.center_name, b.locked, b.source_attrs, b.atproto \
         FROM pubs.publication_biosample pb \
         JOIN core.biosample b ON b.sample_guid = pb.sample_guid \
         WHERE pb.publication_id = $1 AND b.deleted = false \
         ORDER BY b.accession NULLS LAST, b.sample_guid LIMIT $2 OFFSET $3",
    )
    .bind(publication_id.0)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;

    let items = rows
        .into_iter()
        .map(BiosampleRow::into_domain)
        .collect::<Result<Vec<_>, _>>()?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

/// Lookup by accession or alias (the private biosample search).
pub async fn find_by_alias_or_accession(
    pool: &PgPool,
    query: &str,
) -> Result<Vec<Biosample>, DbError> {
    let like = format!("%{}%", query.trim());
    let rows: Vec<BiosampleRow> =
        sqlx::query_as(&format!("{SELECT} AND (accession ILIKE $1 OR alias ILIKE $1) ORDER BY accession LIMIT 50"))
            .bind(&like)
            .fetch_all(pool)
            .await?;
    rows.into_iter().map(BiosampleRow::into_domain).collect()
}
