//! Queries for the unified `core.biosample`.

use crate::{parse_pg_enum, DbError, Page};
use du_domain::biosample::{Biosample, GeoPoint};
use du_domain::enums::{BiosampleSource, DnaType};
use du_domain::ids::{PublicationId, SampleGuid};
use sqlx::PgPool;
use uuid::Uuid;

/// Ensure a biosample exists for an external accession (ENA `SAMEA…`, NCBI
/// `SAMN…`, …); returns its guid and whether it was newly created. Used by the
/// project crawl to import a paper's cohort. Existing rows are returned untouched
/// (the no-op `DO UPDATE` is only to make the guid returnable on conflict); dedup
/// is on the partial unique index `biosample_accession_key`.
pub async fn upsert_by_accession(
    pool: &PgPool,
    accession: &str,
    source: &str,
    center_name: Option<&str>,
) -> Result<(SampleGuid, bool), DbError> {
    let (guid, created): (Uuid, bool) = sqlx::query_as(
        "INSERT INTO core.biosample (source, accession, center_name) \
         VALUES ($1::core.biosample_source, $2, $3) \
         ON CONFLICT (accession) WHERE accession IS NOT NULL \
         DO UPDATE SET accession = EXCLUDED.accession \
         RETURNING sample_guid, (xmax = 0)",
    )
    .bind(source)
    .bind(accession.trim())
    .bind(center_name)
    .fetch_one(pool)
    .await?;
    Ok((SampleGuid(guid), created))
}

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
         WHERE d.geocoord IS NOT NULL AND b.deleted = false \
           AND NOT (abs(ST_X(d.geocoord)) < 1e-6 AND abs(ST_Y(d.geocoord)) < 1e-6)",
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
    query: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<Biosample>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    // Optional filter on accession / alias (the identifying columns shown).
    let like = query.map(str::trim).filter(|q| !q.is_empty()).map(|q| format!("%{q}%"));
    const FILTER: &str = "AND ($2::text IS NULL OR b.accession ILIKE $2 OR b.alias ILIKE $2)";

    let total: i64 = sqlx::query_scalar(&format!(
        "SELECT count(*) FROM pubs.publication_biosample pb \
         JOIN core.biosample b ON b.sample_guid = pb.sample_guid \
         WHERE pb.publication_id = $1 AND b.deleted = false {FILTER}"
    ))
    .bind(publication_id.0)
    .bind(&like)
    .fetch_one(pool)
    .await?;

    let rows: Vec<BiosampleRow> = sqlx::query_as(&format!(
        "SELECT b.sample_guid, b.source::text AS source, b.accession, b.alias, b.description, \
         b.center_name, b.locked, b.source_attrs, b.atproto \
         FROM pubs.publication_biosample pb \
         JOIN core.biosample b ON b.sample_guid = pb.sample_guid \
         WHERE pb.publication_id = $1 AND b.deleted = false {FILTER} \
         ORDER BY b.accession NULLS LAST, b.sample_guid LIMIT $3 OFFSET $4"
    ))
    .bind(publication_id.0)
    .bind(&like)
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
    /// `tree.haplogroup_sample` — the node this sample sits under in the decoding-us
    /// de-novo tree. The sample was used as a tree building block, so its tip position
    /// is an authoritative assignment (preferred over the raw publication call).
    TreePlacement,
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
    /// Standardized vendor-neutral test label (`du_domain::testprofile`), or `None` for
    /// non-yield tests / records published before the profile fields existed.
    pub test_profile_label: Option<String>,
    /// at:// uri of the run (join key to its coverage summary).
    pub at_uri: String,
}

/// A downloadable data file for an academic sequencing run (`genomics.sequence_file`).
/// These are public academic/ENA objects (CRAM/BAM/masterVar) with a resolvable URL
/// and md5 — the run-manifest substrate the compute grid consumes.
#[derive(Debug, Clone)]
pub struct SequenceFile {
    pub file_name: Option<String>,
    pub file_format: Option<String>,
    pub file_size_bytes: Option<i64>,
    /// First public HTTP/FTP location for the object.
    pub download_url: Option<String>,
    /// md5 checksum, when recorded.
    pub md5: Option<String>,
    pub aligner: Option<String>,
    pub target_reference: Option<String>,
}

/// An academic sequencing run migrated from the source study (`genomics.sequence_library`),
/// keyed by `sample_guid` (NOT federated / not at:// based). Carries its data files.
/// Coverage/calls are not present for these yet — the compute grid will backfill them.
#[derive(Debug, Clone)]
pub struct AcademicRun {
    pub instrument: Option<String>,
    pub reads: Option<i64>,
    pub read_length: Option<i32>,
    pub paired_end: Option<bool>,
    pub run_date: Option<String>,
    pub files: Vec<SequenceFile>,
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
    /// The run's raw test-type code, when resolvable (drives the conformance check /
    /// norm joins).
    pub test_type: Option<String>,
    /// Standardized vendor-neutral test label for display (`du_domain::testprofile`).
    pub test_profile_label: Option<String>,
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
    /// Academic sequencing runs + downloadable data files (`genomics.sequence_*`),
    /// keyed by sample_guid. Distinct from federated `sequencing` (fed.sequencerun).
    pub sequence_data: Vec<AcademicRun>,
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

    // ── Q2c: de-novo tree placement, resolved at the DONOR level. When any biosample
    // of this individual was used as a tree building block, the loader recorded the
    // terminal node in tree.haplogroup_sample (PLACED for a topology tip, CURATED for
    // a curator pin). Because the placement lives on the tip biosample — a *sibling*
    // of this catalog accession under the same specimen_donor — we resolve it across
    // all of the donor's biosamples, preferring this sample's own placement, then a
    // curator pin. This is what surfaces the assignment on every accession of the donor.
    let mut placed_y: Option<String> = None;
    let mut placed_mt: Option<String> = None;
    {
        #[derive(sqlx::FromRow)]
        struct PlaceRow {
            dna_type: Option<String>,
            name: Option<String>,
        }
        let rows: Vec<PlaceRow> = sqlx::query_as(
            "SELECT DISTINCT ON (hs.dna_type) hs.dna_type::text AS dna_type, h.name \
             FROM tree.haplogroup_sample hs \
             JOIN core.biosample b2 ON b2.sample_guid = hs.sample_guid \
             JOIN tree.haplogroup h ON h.id = hs.haplogroup_id AND h.valid_until IS NULL \
             WHERE hs.status IN ('PLACED', 'CURATED') \
               AND (b2.sample_guid = $1 \
                    OR b2.donor_id = (SELECT donor_id FROM core.biosample WHERE sample_guid = $1 AND donor_id IS NOT NULL)) \
             ORDER BY hs.dna_type, (b2.sample_guid = $1) DESC, (hs.status = 'CURATED') DESC, b2.sample_guid",
        )
        .bind(guid.0)
        .fetch_all(pool)
        .await?;
        for r in rows {
            match r.dna_type.as_deref() {
                Some("Y_DNA") => placed_y = r.name,
                Some("MT_DNA") => placed_mt = r.name,
                _ => {}
            }
        }
    }

    // A plain (no-reliability) call from a name + origin — for the non-reconciled sources.
    let plain = |name: String, dna_type: DnaType, origin: HaplogroupCallOrigin| HaplogroupCall {
        name,
        dna_type,
        origin,
        confidence: None,
        run_count: None,
        snp_concordance: None,
        compatibility_level: None,
    };

    // Call precedence: cross-technology consensus, else the single federated call,
    // else the de-novo tree placement, else the newest original publication call.
    let y = reconciled_call(recon_y, DnaType::YDna)
        .or_else(|| fed_y.map(|n| plain(n, DnaType::YDna, HaplogroupCallOrigin::FedConsensus)))
        .or_else(|| placed_y.map(|n| plain(n, DnaType::YDna, HaplogroupCallOrigin::TreePlacement)))
        .or_else(|| {
            pick_original_call(&idr.original_haplogroups, "y", "y_result")
                .map(|n| plain(n, DnaType::YDna, HaplogroupCallOrigin::Original))
        });
    let mt = reconciled_call(recon_mt, DnaType::MtDna)
        .or_else(|| fed_mt.map(|n| plain(n, DnaType::MtDna, HaplogroupCallOrigin::FedConsensus)))
        .or_else(|| placed_mt.map(|n| plain(n, DnaType::MtDna, HaplogroupCallOrigin::TreePlacement)))
        .or_else(|| {
            pick_original_call(&idr.original_haplogroups, "mt", "mt_result")
                .map(|n| plain(n, DnaType::MtDna, HaplogroupCallOrigin::Original))
        });

    // ── Academic sequencing runs + data files (genomics.sequence_*, by guid) ──
    // Keyed on sample_guid (not at://), so it surfaces for migrated academic samples
    // that were never federated. md5 + first public URL extracted from the JSONB.
    #[derive(sqlx::FromRow)]
    struct SeqDataRow {
        library_id: i64,
        instrument: Option<String>,
        reads: Option<i64>,
        read_length: Option<i32>,
        paired_end: Option<bool>,
        run_date: Option<String>,
        file_name: Option<String>,
        file_format: Option<String>,
        file_size_bytes: Option<i64>,
        download_url: Option<String>,
        md5: Option<String>,
        aligner: Option<String>,
        target_reference: Option<String>,
    }
    let seq_data_rows: Vec<SeqDataRow> = sqlx::query_as(
        "SELECT sl.id AS library_id, sl.instrument, sl.reads, sl.read_length, sl.paired_end, \
                sl.run_date::text AS run_date, \
                sf.file_name, sf.file_format, sf.file_size_bytes, \
                sf.http_locations->0->>'file_url' AS download_url, \
                (SELECT c->>'checksum' FROM jsonb_array_elements(COALESCE(sf.checksums,'[]'::jsonb)) c \
                 WHERE lower(c->>'algorithm') = 'md5' LIMIT 1) AS md5, \
                sf.aligner, sf.target_reference \
         FROM genomics.sequence_library sl \
         LEFT JOIN genomics.sequence_file sf ON sf.library_id = sl.id \
         WHERE sl.sample_guid = $1 \
         ORDER BY sl.id, sf.id",
    )
    .bind(guid.0)
    .fetch_all(pool)
    .await?;
    // Rows arrive ordered by library_id; fold the LEFT-JOINed file rows into one
    // AcademicRun per library, skipping the synthetic null-file row of a fileless run.
    let mut sequence_data: Vec<AcademicRun> = Vec::new();
    let mut current_id: Option<i64> = None;
    for r in seq_data_rows {
        if current_id != Some(r.library_id) {
            current_id = Some(r.library_id);
            sequence_data.push(AcademicRun {
                instrument: r.instrument,
                reads: r.reads,
                read_length: r.read_length,
                paired_end: r.paired_end,
                run_date: r.run_date,
                files: Vec::new(),
            });
        }
        if r.file_name.is_some() || r.download_url.is_some() {
            if let Some(run) = sequence_data.last_mut() {
                run.files.push(SequenceFile {
                    file_name: r.file_name,
                    file_format: r.file_format,
                    file_size_bytes: r.file_size_bytes,
                    download_url: r.download_url,
                    md5: r.md5,
                    aligner: r.aligner,
                    target_reference: r.target_reference,
                });
            }
        }
    }

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
            test_profile_label: Option<String>,
        }
        let seq: Vec<SeqRow> = sqlx::query_as(
            "SELECT at_uri, platform_name, instrument_model, test_type, library_layout, \
                    total_reads, read_length, mean_insert_size, test_profile_label \
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
                test_profile_label: r.test_profile_label,
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
            test_profile_label: Option<String>,
            expected_min_depth: Option<f64>,
            norm_median_depth: Option<f64>,
        }
        // Resolve each coverage row's test type (via its run), the advertised spec
        // (test_type_definition, opportunistic), and the empirical cohort norm.
        let cov: Vec<CovRow> = sqlx::query_as(
            "SELECT cs.reference_build, cs.aligner, cs.mean_coverage, cs.median_coverage, \
                    cs.pct_10x, cs.pct_20x, cs.pct_30x, cs.sequence_run_ref, \
                    sr.test_type AS test_type, sr.test_profile_label AS test_profile_label, \
                    ttd.expected_min_depth AS expected_min_depth, \
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
                test_profile_label: r.test_profile_label,
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
        // Treat "null island" (0,0) as unknown — it's open ocean in the Gulf of
        // Guinea, never a real sampling origin (a zeroed/missing geocoord).
        (Some(lat), Some(lon)) if !(lat.abs() < 1e-6 && lon.abs() < 1e-6) => Some(LatLon { lat, lon }),
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
    Ok(Some(SampleReport { identity, y, mt, sequencing, coverage, sequence_data, ancestry, publications }))
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

/// A partial update to a `core.biosample` row. Every `Some` field is written;
/// `None` leaves that column untouched. Null-setting is intentionally not
/// supported — this backs a curator field-correction API where every edit sets a
/// concrete value (source reclassification, alias/accession/center remaps).
#[derive(Debug, Default, Clone)]
pub struct BiosamplePatch {
    pub source: Option<BiosampleSource>,
    pub is_public: Option<bool>,
    pub accession: Option<String>,
    pub alias: Option<String>,
    pub center_name: Option<String>,
    pub description: Option<String>,
}

impl BiosamplePatch {
    pub fn is_empty(&self) -> bool {
        self.source.is_none()
            && self.is_public.is_none()
            && self.accession.is_none()
            && self.alias.is_none()
            && self.center_name.is_none()
            && self.description.is_none()
    }
}

/// Apply a partial update to a live (non-deleted) biosample. Returns `Ok(true)`
/// when a row was updated, `Ok(false)` when the patch was empty or no live row
/// matched. A duplicate `accession` surfaces as `DbError::Conflict` (a 422 at the
/// API), not a 500.
pub async fn patch(pool: &PgPool, guid: SampleGuid, p: &BiosamplePatch) -> Result<bool, DbError> {
    if p.is_empty() {
        return Ok(false);
    }
    // Placeholders are numbered in the same order the binds are chained below.
    // $1 is always the guid.
    let mut sets: Vec<String> = Vec::new();
    let mut n = 2u32;
    if p.source.is_some() {
        sets.push(format!("source = ${n}::core.biosample_source"));
        n += 1;
    }
    if p.is_public.is_some() {
        sets.push(format!("is_public = ${n}"));
        n += 1;
    }
    if p.accession.is_some() {
        sets.push(format!("accession = ${n}"));
        n += 1;
    }
    if p.alias.is_some() {
        sets.push(format!("alias = ${n}"));
        n += 1;
    }
    if p.center_name.is_some() {
        sets.push(format!("center_name = ${n}"));
        n += 1;
    }
    if p.description.is_some() {
        sets.push(format!("description = ${n}"));
    }
    let sql = format!(
        "UPDATE core.biosample SET {}, updated_at = now() \
         WHERE sample_guid = $1 AND deleted = false",
        sets.join(", ")
    );
    let mut q = sqlx::query(&sql).bind(guid.0);
    if let Some(s) = &p.source {
        q = q.bind(s.label());
    }
    if let Some(b) = p.is_public {
        q = q.bind(b);
    }
    if let Some(a) = &p.accession {
        q = q.bind(a);
    }
    if let Some(a) = &p.alias {
        q = q.bind(a);
    }
    if let Some(c) = &p.center_name {
        q = q.bind(c);
    }
    if let Some(d) = &p.description {
        q = q.bind(d);
    }
    match q.execute(pool).await {
        Ok(r) => Ok(r.rows_affected() > 0),
        Err(e) if e.as_database_error().and_then(|d| d.code()).as_deref() == Some("23505") => {
            Err(DbError::Conflict(format!(
                "accession already in use: {}",
                p.accession.as_deref().unwrap_or("")
            )))
        }
        Err(e) => Err(e.into()),
    }
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
