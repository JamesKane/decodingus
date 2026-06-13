//! Queries for the unified `core.biosample`.

use crate::{parse_pg_enum, DbError, Page};
use du_domain::biosample::{Biosample, GeoPoint};
use du_domain::enums::{BiosampleSource, DnaType};
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

// ── Public per-sample report (unified read path) ──────────────────────────────
// The canonical `core.biosample` (identity, the `is_public` gate, publications)
// joined to the federated analytics mirror (`fed.*`) via `atproto.uri ↔ *.biosample_ref`.
// Callers never touch `fed.*` directly — this is the seam the eventual full
// core/fed consolidation collapses into (only the query bodies change).

/// Origin of a sample's haplogroup call — provenance shown to the reader.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HaplogroupCallOrigin {
    /// `fed.haplogroup_reconciliation` — the donor's call reconciled across all its
    /// sequencing technologies (the authoritative cross-technology consensus).
    Reconciled,
    /// `fed.biosample.y/mt_haplogroup` (a single Navigator call, not reconciled).
    FedConsensus,
    /// `core.biosample.original_haplogroups` (per-publication original call).
    Original,
}

/// A called haplogroup name plus its lineage. The phylogenetic pathway is
/// resolved separately by [`crate::haplogroup::pathway`] so the SQL layer stays
/// free of tree-walking. The reliability fields are populated only for a
/// `Reconciled` call (the cross-technology consensus).
#[derive(Debug, Clone)]
pub struct HaplogroupCall {
    pub name: String,
    pub dna_type: DnaType,
    pub origin: HaplogroupCallOrigin,
    /// Consensus confidence ∈ [0,1] (reconciled calls only).
    pub confidence: Option<f64>,
    /// Number of sequencing runs reconciled into the consensus.
    pub run_count: Option<i32>,
    /// SNP concordance across the reconciled runs ∈ [0,1].
    pub snp_concordance: Option<f64>,
    /// `COMPATIBLE` / `MINOR_DIVERGENCE` / `INCOMPATIBLE` …
    pub compatibility_level: Option<String>,
}

/// A reconciliation consensus call, before it's lifted to a [`HaplogroupCall`].
struct ReconCall {
    name: String,
    confidence: Option<f64>,
    run_count: Option<i32>,
    snp_concordance: Option<f64>,
    compatibility_level: Option<String>,
}

/// Lift a reconciliation consensus to a `Reconciled`-origin [`HaplogroupCall`].
fn reconciled_call(r: Option<ReconCall>, dna_type: DnaType) -> Option<HaplogroupCall> {
    r.map(|r| HaplogroupCall {
        name: r.name,
        dna_type,
        origin: HaplogroupCallOrigin::Reconciled,
        confidence: r.confidence,
        run_count: r.run_count,
        snp_concordance: r.snp_concordance,
        compatibility_level: r.compatibility_level,
    })
}

/// WGS84 origin point (from the donor's `geocoord`).
#[derive(Debug, Clone, Copy)]
pub struct LatLon {
    pub lat: f64,
    pub lon: f64,
}

#[derive(Debug, Clone)]
pub struct ReportIdentity {
    pub sample_guid: SampleGuid,
    pub source: BiosampleSource,
    pub accession: Option<String>,
    pub alias: Option<String>,
    pub description: Option<String>,
    pub center_name: Option<String>,
    /// The Postgres `biological_sex` label (`MALE`/`FEMALE`/`INTERSEX`), as text.
    pub sex: Option<String>,
    pub origin: Option<LatLon>,
    pub is_public: bool,
    /// atproto link present AND a matching `fed.biosample` row was found.
    pub is_federated: bool,
}

#[derive(Debug, Clone)]
pub struct SequencingRun {
    pub platform_name: Option<String>,
    pub instrument_model: Option<String>,
    pub test_type: Option<String>,
    pub library_layout: Option<String>,
    pub total_reads: Option<i64>,
    pub read_length: Option<i32>,
    pub mean_insert_size: Option<f64>,
    /// at:// uri of the run (join key to its coverage summary).
    pub at_uri: String,
}

#[derive(Debug, Clone)]
pub struct CoverageSummary {
    pub reference_build: Option<String>,
    pub aligner: Option<String>,
    pub mean_coverage: Option<f64>,
    pub median_coverage: Option<f64>,
    pub pct_10x: Option<f64>,
    pub pct_20x: Option<f64>,
    pub pct_30x: Option<f64>,
    /// at:// uri of the sequencing run this coverage belongs to (may be NULL).
    pub sequence_run_ref: Option<String>,
    /// The run's test type, when resolvable (drives the conformance check).
    pub test_type: Option<String>,
    /// Advertised minimum depth for the test type (`test_type_definition`), if set.
    pub expected_min_depth: Option<f64>,
    /// The empirical cohort median depth for the test type (`test_type_coverage_norm`).
    pub norm_median_depth: Option<f64>,
    /// Conformance vs. the advertised spec (or cohort norm when no spec): `BELOW` /
    /// `AT` / `ABOVE`, or `None` when there's nothing to compare against.
    pub conformance: Option<String>,
}

/// Classify a sample's aligned mean depth against the **empirical cohort norm**
/// for its test type (preferred), falling back to the advertised spec only when no
/// cohort norm exists yet. The cohort norm is the fair baseline: an advertised
/// "30× WGS" is really a raw-yield spec (~90 Gb of reads), which aligns to less than
/// 30× after QC/dedup, and D2C lab products don't target 30× aligned at all — so
/// comparing aligned depth to a literal advertised number would mislabel them. The
/// cohort norm is measured in the same aligned-depth units and reflects what each
/// test type actually delivers. ±5% of the baseline counts as `AT`.
fn conformance(mean: Option<f64>, expected: Option<f64>, norm: Option<f64>) -> Option<String> {
    let mean = mean?;
    let baseline = norm.or(expected)?;
    if baseline <= 0.0 {
        return None;
    }
    Some(if mean < baseline * 0.95 {
        "BELOW"
    } else if mean > baseline * 1.05 {
        "ABOVE"
    } else {
        "AT"
    }
    .to_string())
}

#[derive(Debug, Clone)]
pub struct AncestryBreakdown {
    pub analysis_method: Option<String>,
    pub panel_type: Option<String>,
    pub confidence_level: Option<f64>,
    /// Continental rollup: `[{superPopulation, percentage}]`.
    pub super_populations: serde_json::Value,
    /// Sub-continental percentages (payload shape not asserted — render defensively).
    pub components: serde_json::Value,
}

#[derive(Debug, Clone)]
pub struct ReportPublication {
    pub id: PublicationId,
    pub title: String,
    pub doi: Option<String>,
    pub url: Option<String>,
    pub publication_date: Option<chrono::NaiveDate>,
}

/// Everything the public per-sample report needs, assembled from the canonical
/// biosample plus its federated analytics. `is_public` is carried (not filtered)
/// so the web layer can let curators preview private samples; every public
/// surface MUST check `identity.is_public` itself.
#[derive(Debug, Clone)]
pub struct SampleReport {
    pub identity: ReportIdentity,
    pub y: Option<HaplogroupCall>,
    pub mt: Option<HaplogroupCall>,
    pub sequencing: Vec<SequencingRun>,
    pub coverage: Vec<CoverageSummary>,
    pub ancestry: Option<AncestryBreakdown>,
    pub publications: Vec<ReportPublication>,
}

/// Pick the first non-null call from an `original_haplogroups` JSONB array,
/// tolerating both shapes (standard `{y, mt, y_result, mt_result}` and citizen
/// `{y_result, mt_result}`, all keys null-stripped): prefer `primary`, else `fallback`.
pub(crate) fn pick_original_call(arr: &serde_json::Value, primary: &str, fallback: &str) -> Option<String> {
    let entries = arr.as_array()?;
    entries.iter().find_map(|e| {
        let take = |k: &str| {
            e.get(k)
                .and_then(serde_json::Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string)
        };
        take(primary).or_else(|| take(fallback))
    })
}

/// Resolve a slug/accession/alias/sample_guid string to a single `sample_guid`.
/// Prefers public, then non-deleted rows deterministically (earliest guid).
pub async fn resolve_guid(pool: &PgPool, identifier: &str) -> Result<Option<SampleGuid>, DbError> {
    let id = identifier.trim();
    if let Ok(uuid) = Uuid::parse_str(id) {
        let exists: Option<Uuid> =
            sqlx::query_scalar("SELECT sample_guid FROM core.biosample WHERE sample_guid = $1 AND deleted = false")
                .bind(uuid)
                .fetch_optional(pool)
                .await?;
        return Ok(exists.map(SampleGuid));
    }
    let guid: Option<Uuid> = sqlx::query_scalar(
        "SELECT sample_guid FROM core.biosample \
         WHERE deleted = false AND (lower(accession) = lower($1) OR lower(alias) = lower($1)) \
         ORDER BY is_public DESC, sample_guid LIMIT 1",
    )
    .bind(id)
    .fetch_optional(pool)
    .await?;
    Ok(guid.map(SampleGuid))
}

/// Assemble the report for one sample by guid, or `None` if it doesn't exist /
/// is deleted. Does NOT filter on `is_public` — the caller gates visibility.
pub async fn report_by_guid(pool: &PgPool, guid: SampleGuid) -> Result<Option<SampleReport>, DbError> {
    // ── Q1: identity + gate flags (joins the donor for sex/origin) ──
    #[derive(sqlx::FromRow)]
    struct IdRow {
        sample_guid: Uuid,
        source: String,
        accession: Option<String>,
        alias: Option<String>,
        description: Option<String>,
        center_name: Option<String>,
        is_public: bool,
        at_uri: Option<String>,
        repo_did: Option<String>,
        original_haplogroups: serde_json::Value,
        sex: Option<String>,
        lat: Option<f64>,
        lon: Option<f64>,
    }
    let id_row: Option<IdRow> = sqlx::query_as(
        "SELECT b.sample_guid, b.source::text AS source, b.accession, b.alias, b.description, \
                b.center_name, b.is_public, b.atproto->>'uri' AS at_uri, b.atproto->>'repo_did' AS repo_did, \
                b.original_haplogroups, \
                d.sex::text AS sex, ST_Y(d.geocoord) AS lat, ST_X(d.geocoord) AS lon \
         FROM core.biosample b \
         LEFT JOIN core.specimen_donor d ON d.id = b.donor_id \
         WHERE b.sample_guid = $1 AND b.deleted = false",
    )
    .bind(guid.0)
    .fetch_optional(pool)
    .await?;
    let Some(idr) = id_row else { return Ok(None) };

    // ── Q2: federated consensus haplogroups (only when atproto-linked) ──
    let mut fed_y: Option<String> = None;
    let mut fed_mt: Option<String> = None;
    let mut is_federated = false;
    if let Some(at_uri) = idr.at_uri.as_deref() {
        let fed: Option<(Option<String>, Option<String>)> =
            sqlx::query_as("SELECT y_haplogroup, mt_haplogroup FROM fed.biosample WHERE at_uri = $1")
                .bind(at_uri)
                .fetch_optional(pool)
                .await?;
        if let Some((y, mt)) = fed {
            is_federated = true;
            fed_y = y;
            fed_mt = mt;
        }
    }

    // ── Q2b: the cross-technology consensus (the authoritative call). Keyed by the
    // citizen's repo DID = the reconciliation publisher's DID; pick the best per arm.
    let mut recon_y: Option<ReconCall> = None;
    let mut recon_mt: Option<ReconCall> = None;
    if let Some(repo_did) = idr.repo_did.as_deref() {
        #[derive(sqlx::FromRow)]
        struct ReconRow {
            dna_type: Option<String>,
            consensus_haplogroup: Option<String>,
            confidence: Option<f64>,
            run_count: Option<i32>,
            snp_concordance: Option<f64>,
            compatibility_level: Option<String>,
        }
        let rows: Vec<ReconRow> = sqlx::query_as(
            "SELECT DISTINCT ON (dna_type) dna_type, consensus_haplogroup, confidence, run_count, \
                    snp_concordance, compatibility_level \
             FROM fed.haplogroup_reconciliation \
             WHERE did = $1 AND consensus_haplogroup IS NOT NULL \
             ORDER BY dna_type, run_count DESC NULLS LAST, time_us DESC",
        )
        .bind(repo_did)
        .fetch_all(pool)
        .await?;
        for r in rows {
            let call = r.consensus_haplogroup.map(|name| ReconCall {
                name,
                confidence: r.confidence,
                run_count: r.run_count,
                snp_concordance: r.snp_concordance,
                compatibility_level: r.compatibility_level,
            });
            match r.dna_type.as_deref() {
                Some("Y_DNA") => recon_y = call,
                Some("MT_DNA") => recon_mt = call,
                _ => {}
            }
        }
        if recon_y.is_some() || recon_mt.is_some() {
            is_federated = true;
        }
    }

    // Call precedence: cross-technology consensus, else the single federated call,
    // else the newest original publication call.
    let y = reconciled_call(recon_y, DnaType::YDna)
        .or_else(|| {
            fed_y.map(|name| HaplogroupCall {
                name,
                dna_type: DnaType::YDna,
                origin: HaplogroupCallOrigin::FedConsensus,
                confidence: None,
                run_count: None,
                snp_concordance: None,
                compatibility_level: None,
            })
        })
        .or_else(|| {
            pick_original_call(&idr.original_haplogroups, "y", "y_result").map(|name| HaplogroupCall {
                name,
                dna_type: DnaType::YDna,
                origin: HaplogroupCallOrigin::Original,
                confidence: None,
                run_count: None,
                snp_concordance: None,
                compatibility_level: None,
            })
        });
    let mt = reconciled_call(recon_mt, DnaType::MtDna)
        .or_else(|| {
            fed_mt.map(|name| HaplogroupCall {
                name,
                dna_type: DnaType::MtDna,
                origin: HaplogroupCallOrigin::FedConsensus,
                confidence: None,
                run_count: None,
                snp_concordance: None,
                compatibility_level: None,
            })
        })
        .or_else(|| {
            pick_original_call(&idr.original_haplogroups, "mt", "mt_result").map(|name| HaplogroupCall {
                name,
                dna_type: DnaType::MtDna,
                origin: HaplogroupCallOrigin::Original,
                confidence: None,
                run_count: None,
                snp_concordance: None,
                compatibility_level: None,
            })
        });

    // ── Q3/Q4/Q5: federated sequencing, coverage, ancestry (only when linked) ──
    let mut sequencing = Vec::new();
    let mut coverage = Vec::new();
    let mut ancestry = None;
    if let Some(at_uri) = idr.at_uri.as_deref() {
        #[derive(sqlx::FromRow)]
        struct SeqRow {
            at_uri: String,
            platform_name: Option<String>,
            instrument_model: Option<String>,
            test_type: Option<String>,
            library_layout: Option<String>,
            total_reads: Option<i64>,
            read_length: Option<i32>,
            mean_insert_size: Option<f64>,
        }
        let seq: Vec<SeqRow> = sqlx::query_as(
            "SELECT at_uri, platform_name, instrument_model, test_type, library_layout, \
                    total_reads, read_length, mean_insert_size \
             FROM fed.sequencerun WHERE biosample_ref = $1 ORDER BY record_created_at DESC NULLS LAST",
        )
        .bind(at_uri)
        .fetch_all(pool)
        .await?;
        sequencing = seq
            .into_iter()
            .map(|r| SequencingRun {
                platform_name: r.platform_name,
                instrument_model: r.instrument_model,
                test_type: r.test_type,
                library_layout: r.library_layout,
                total_reads: r.total_reads,
                read_length: r.read_length,
                mean_insert_size: r.mean_insert_size,
                at_uri: r.at_uri,
            })
            .collect();

        #[derive(sqlx::FromRow)]
        struct CovRow {
            reference_build: Option<String>,
            aligner: Option<String>,
            mean_coverage: Option<f64>,
            median_coverage: Option<f64>,
            pct_10x: Option<f64>,
            pct_20x: Option<f64>,
            pct_30x: Option<f64>,
            sequence_run_ref: Option<String>,
            test_type: Option<String>,
            expected_min_depth: Option<f64>,
            norm_median_depth: Option<f64>,
        }
        // Resolve each coverage row's test type (via its run), the advertised spec
        // (test_type_definition, opportunistic), and the empirical cohort norm.
        let cov: Vec<CovRow> = sqlx::query_as(
            "SELECT cs.reference_build, cs.aligner, cs.mean_coverage, cs.median_coverage, \
                    cs.pct_10x, cs.pct_20x, cs.pct_30x, cs.sequence_run_ref, \
                    sr.test_type AS test_type, ttd.expected_min_depth AS expected_min_depth, \
                    n.median_mean_depth AS norm_median_depth \
             FROM fed.coverage_summary cs \
             LEFT JOIN fed.sequencerun sr ON sr.at_uri = cs.sequence_run_ref \
             LEFT JOIN genomics.test_type_definition ttd ON upper(ttd.code) = upper(sr.test_type) \
             LEFT JOIN genomics.test_type_coverage_norm n ON n.test_type = sr.test_type \
             WHERE cs.biosample_ref = $1 ORDER BY cs.mean_coverage DESC NULLS LAST",
        )
        .bind(at_uri)
        .fetch_all(pool)
        .await?;
        coverage = cov
            .into_iter()
            .map(|r| CoverageSummary {
                conformance: conformance(r.mean_coverage, r.expected_min_depth, r.norm_median_depth),
                reference_build: r.reference_build,
                aligner: r.aligner,
                mean_coverage: r.mean_coverage,
                median_coverage: r.median_coverage,
                pct_10x: r.pct_10x,
                pct_20x: r.pct_20x,
                pct_30x: r.pct_30x,
                sequence_run_ref: r.sequence_run_ref,
                test_type: r.test_type,
                expected_min_depth: r.expected_min_depth,
                norm_median_depth: r.norm_median_depth,
            })
            .collect();

        #[derive(sqlx::FromRow)]
        struct AncRow {
            analysis_method: Option<String>,
            panel_type: Option<String>,
            confidence_level: Option<f64>,
            super_population_summary: serde_json::Value,
            components: serde_json::Value,
        }
        let anc: Option<AncRow> = sqlx::query_as(
            "SELECT analysis_method, panel_type, confidence_level, super_population_summary, components \
             FROM fed.population_breakdown WHERE biosample_ref = $1 \
             ORDER BY record_created_at DESC NULLS LAST LIMIT 1",
        )
        .bind(at_uri)
        .fetch_optional(pool)
        .await?;
        ancestry = anc.map(|r| AncestryBreakdown {
            analysis_method: r.analysis_method,
            panel_type: r.panel_type,
            confidence_level: r.confidence_level,
            super_populations: r.super_population_summary,
            components: r.components,
        });
    }

    // ── Q6: source publications ──
    #[derive(sqlx::FromRow)]
    struct PubRow {
        id: i64,
        title: String,
        doi: Option<String>,
        url: Option<String>,
        publication_date: Option<chrono::NaiveDate>,
    }
    let pubs: Vec<PubRow> = sqlx::query_as(
        "SELECT p.id, p.title, p.doi, p.url, p.publication_date \
         FROM pubs.publication_biosample pb JOIN pubs.publication p ON p.id = pb.publication_id \
         WHERE pb.sample_guid = $1 ORDER BY p.publication_date DESC NULLS LAST",
    )
    .bind(guid.0)
    .fetch_all(pool)
    .await?;
    let publications = pubs
        .into_iter()
        .map(|p| ReportPublication {
            id: PublicationId(p.id),
            title: p.title,
            doi: p.doi,
            url: p.url,
            publication_date: p.publication_date,
        })
        .collect();

    let origin = match (idr.lat, idr.lon) {
        (Some(lat), Some(lon)) => Some(LatLon { lat, lon }),
        _ => None,
    };
    let identity = ReportIdentity {
        sample_guid: SampleGuid(idr.sample_guid),
        source: parse_pg_enum(&idr.source, "source")?,
        accession: idr.accession,
        alias: idr.alias,
        description: idr.description,
        center_name: idr.center_name,
        sex: idr.sex,
        origin,
        is_public: idr.is_public,
        is_federated,
    };
    Ok(Some(SampleReport { identity, y, mt, sequencing, coverage, ancestry, publications }))
}

/// Resolve an identifier (slug/accession/alias/guid) and assemble its report.
pub async fn report(pool: &PgPool, identifier: &str) -> Result<Option<SampleReport>, DbError> {
    match resolve_guid(pool, identifier).await? {
        Some(guid) => report_by_guid(pool, guid).await,
        None => Ok(None),
    }
}

/// Set the public-visibility flag on a sample. Returns whether a row changed.
pub async fn set_public(pool: &PgPool, guid: SampleGuid, value: bool) -> Result<bool, DbError> {
    let affected = sqlx::query(
        "UPDATE core.biosample SET is_public = $2, updated_at = now() \
         WHERE sample_guid = $1 AND deleted = false",
    )
    .bind(guid.0)
    .bind(value)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(affected > 0)
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

#[cfg(test)]
mod tests {
    use super::conformance;

    #[test]
    fn conformance_prefers_cohort_norm_then_spec() {
        // The cohort norm wins over the advertised spec: a sample at 28× whose test
        // type's cohort delivers ~29× is AT — NOT flagged BELOW against an advertised
        // 30× aligned bar (the D2C case the user called out).
        assert_eq!(conformance(Some(28.0), Some(30.0), Some(29.0)).as_deref(), Some("AT"));
        // Genuinely under its cohort.
        assert_eq!(conformance(Some(20.0), Some(30.0), Some(29.0)).as_deref(), Some("BELOW"));
        // Above its cohort.
        assert_eq!(conformance(Some(33.0), None, Some(29.0)).as_deref(), Some("ABOVE"));
        // No cohort norm yet → fall back to the advertised spec.
        assert_eq!(conformance(Some(20.0), Some(30.0), None).as_deref(), Some("BELOW"));
        // Nothing to compare against.
        assert_eq!(conformance(Some(30.0), None, None), None);
        assert_eq!(conformance(None, Some(30.0), Some(29.0)), None);
    }
}
