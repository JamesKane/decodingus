//! Public JSON API surface (`/api/v1/*`) — the Tapir replacement. Clean DTOs
//! (decoupled from the internal domain types) are mapped from `du-db` query
//! results and described with `utoipa`; Swagger UI is served at `/api`.
//!
//! Scope: ONLY the read-only public endpoints (tree, coverage, references/
//! biosamples, variants, genome regions) plus the federated population reports
//! (`/api/v1/reports/*`) aggregated from the `fed.*` mirror. Curator/machine
//! management endpoints are deliberately NOT under `/api/v1` — they live under
//! `/manage/*` (change-sets, haplogroup merge, curation intake) and are not part
//! of this public OpenAPI document.

use crate::error::AppError;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use du_domain::enums::DnaType;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use utoipa::{IntoParams, OpenApi, ToSchema};
use utoipa_swagger_ui::SwaggerUi;

// ── DTOs ─────────────────────────────────────────────────────────────────────

/// A paginated envelope. utoipa 5 documents the concrete instantiations
/// (`Page<VariantDto>`, …) referenced from the path/`components` below.
#[derive(Serialize, ToSchema)]
pub struct Page<T> {
    pub items: Vec<T>,
    pub total: i64,
    pub page: i64,
    pub page_size: i64,
    pub total_pages: i64,
}

impl<D, T: From<D>> From<du_db::Page<D>> for Page<T> {
    fn from(p: du_db::Page<D>) -> Self {
        let total_pages = p.total_pages();
        Page {
            items: p.items.into_iter().map(T::from).collect(),
            total: p.total,
            page: p.page,
            page_size: p.page_size,
            total_pages,
        }
    }
}

#[derive(Serialize, ToSchema)]
pub struct VariantDto {
    pub id: i64,
    pub canonical_name: String,
    pub mutation_type: String,
    pub naming_status: String,
    pub common_names: Vec<String>,
    pub rs_ids: Vec<String>,
    /// Coordinates keyed by reference build: `{ "GRCh38": {contig, position, ...} }`.
    pub coordinates: serde_json::Value,
}

impl From<du_domain::variant::Variant> for VariantDto {
    fn from(v: du_domain::variant::Variant) -> Self {
        let coordinates = serde_json::to_value(&v.coordinates).unwrap_or(serde_json::Value::Null);
        VariantDto {
            id: v.id.0,
            canonical_name: v.canonical_name,
            mutation_type: v.mutation_type.label().to_string(),
            naming_status: v.naming_status.label().to_string(),
            common_names: v.aliases.common_names,
            rs_ids: v.aliases.rs_ids,
            coordinates,
        }
    }
}

/// A nested haplogroup tree node.
#[derive(Serialize, ToSchema)]
pub struct HaplogroupNodeDto {
    pub id: i64,
    pub name: String,
    pub haplogroup_type: String,
    pub formed_ybp: Option<i32>,
    pub tmrca_ybp: Option<i32>,
    /// Child nodes. `no_recursion` stops utoipa's schema walk from recursing
    /// infinitely on this self-reference (it emits a `$ref` instead).
    #[schema(no_recursion)]
    pub children: Vec<HaplogroupNodeDto>,
}

#[derive(Serialize, ToSchema)]
pub struct TreeDto {
    /// Top-level node(s): one when a `rootHaplogroup` is given, else every root.
    pub roots: Vec<HaplogroupNodeDto>,
}

#[derive(Serialize, ToSchema)]
pub struct CoverageBenchmarkDto {
    pub lab: Option<String>,
    pub test_type: Option<String>,
    pub library_count: i64,
    pub avg_mean_depth: Option<f64>,
    pub avg_cov_10x: Option<f64>,
    pub expected_min_depth: Option<f64>,
}

impl From<du_domain::coverage::CoverageBenchmark> for CoverageBenchmarkDto {
    fn from(c: du_domain::coverage::CoverageBenchmark) -> Self {
        CoverageBenchmarkDto {
            lab: c.lab,
            test_type: c.test_type,
            library_count: c.library_count,
            avg_mean_depth: c.avg_mean_depth,
            avg_cov_10x: c.avg_cov_10x,
            expected_min_depth: c.expected_min_depth,
        }
    }
}

#[derive(Serialize, ToSchema)]
pub struct PublicationDto {
    pub id: i64,
    pub title: String,
    pub doi: Option<String>,
    pub pubmed_id: Option<String>,
    pub journal: Option<String>,
    pub publication_date: Option<chrono::NaiveDate>,
    pub authors: Option<String>,
    pub abstract_summary: Option<String>,
    pub url: Option<String>,
    pub cited_by_count: Option<i32>,
    pub open_access_status: Option<String>,
}

impl From<du_domain::publication::Publication> for PublicationDto {
    fn from(p: du_domain::publication::Publication) -> Self {
        PublicationDto {
            id: p.id.0,
            title: p.title,
            doi: p.doi,
            pubmed_id: p.pubmed_id,
            journal: p.journal,
            publication_date: p.publication_date,
            authors: p.authors,
            abstract_summary: p.abstract_summary,
            url: p.url,
            cited_by_count: p.cited_by_count,
            open_access_status: p.open_access_status,
        }
    }
}

#[derive(Serialize, ToSchema)]
pub struct BiosampleDto {
    pub sample_guid: String,
    pub source: String,
    pub accession: Option<String>,
    pub alias: Option<String>,
    pub description: Option<String>,
    pub center_name: Option<String>,
    pub locked: bool,
    pub source_attrs: serde_json::Value,
    pub atproto: Option<serde_json::Value>,
}

impl From<du_domain::biosample::Biosample> for BiosampleDto {
    fn from(b: du_domain::biosample::Biosample) -> Self {
        BiosampleDto {
            sample_guid: b.sample_guid.0.to_string(),
            source: b.source.label().to_string(),
            accession: b.accession,
            alias: b.alias,
            description: b.description,
            center_name: b.center_name,
            locked: b.locked,
            source_attrs: b.source_attrs,
            atproto: b.atproto,
        }
    }
}

#[derive(Serialize, ToSchema)]
pub struct GenomeRegionDto {
    pub id: i64,
    pub region_type: String,
    pub name: String,
    pub coordinates: serde_json::Value,
    pub properties: serde_json::Value,
}

impl From<du_domain::genome_region::GenomeRegion> for GenomeRegionDto {
    fn from(r: du_domain::genome_region::GenomeRegion) -> Self {
        GenomeRegionDto {
            id: r.id,
            region_type: r.region_type,
            name: r.name,
            coordinates: r.coordinates,
            properties: r.properties,
        }
    }
}

#[derive(Serialize, ToSchema)]
pub struct StudyDto {
    pub id: i64,
    pub accession: String,
    pub title: Option<String>,
    pub center_name: Option<String>,
    /// Linked samples: `[{sample_guid, accession, source}]`.
    pub samples: serde_json::Value,
}

impl From<du_db::study::StudyWithSamples> for StudyDto {
    fn from(s: du_db::study::StudyWithSamples) -> Self {
        StudyDto { id: s.id, accession: s.accession, title: s.title, center_name: s.center_name, samples: s.samples }
    }
}

#[derive(Serialize, ToSchema)]
pub struct ExportMetadataDto {
    pub variant_count: i64,
    pub format: String,
    pub generated_at: String,
}

// ── federation reporting DTOs ──────────────────────────────────────────────────
// Population-level reports aggregated from the federated mirror (`fed.*`) with
// query-time SQL — the AppView aggregates and reports over Navigator-published
// anonymized summaries (no per-PDS fetch at request time).

/// Coverage aggregated across mirrored alignment summaries, by reference build.
#[derive(Serialize, ToSchema)]
pub struct FedCoverageByBuildDto {
    pub reference_build: Option<String>,
    pub samples: i64,
    pub mean_coverage: Option<f64>,
    pub mean_pct_30x: Option<f64>,
}

impl From<du_db::fed::coverage::BuildCoverage> for FedCoverageByBuildDto {
    fn from(c: du_db::fed::coverage::BuildCoverage) -> Self {
        FedCoverageByBuildDto {
            reference_build: c.reference_build,
            samples: c.samples,
            mean_coverage: c.mean_coverage,
            mean_pct_30x: c.mean_pct_30x,
        }
    }
}

/// Average ancestry share per continental super-population across mirrored breakdowns.
#[derive(Serialize, ToSchema)]
pub struct AncestryShareDto {
    pub super_population: Option<String>,
    pub samples: i64,
    pub avg_percentage: Option<f64>,
}

impl From<du_db::fed::analytics::SuperPopulationShare> for AncestryShareDto {
    fn from(s: du_db::fed::analytics::SuperPopulationShare) -> Self {
        AncestryShareDto {
            super_population: s.super_population,
            samples: s.samples,
            avg_percentage: s.avg_percentage,
        }
    }
}

/// Count of a consensus Y/MT haplogroup across mirrored biosamples.
#[derive(Serialize, ToSchema)]
pub struct HaplogroupCountDto {
    pub dna_type: String,
    pub haplogroup: String,
    pub samples: i64,
}

impl From<du_db::fed::core::HaplogroupCount> for HaplogroupCountDto {
    fn from(h: du_db::fed::core::HaplogroupCount) -> Self {
        HaplogroupCountDto { dna_type: h.dna_type, haplogroup: h.haplogroup, samples: h.samples }
    }
}

/// One marker of a Y haplogroup's aggregated (modal) STR signature.
#[derive(Serialize, ToSchema)]
pub struct StrSignatureMarkerDto {
    pub marker: String,
    /// Modal repeat count for simple markers (null for multi-copy — see `value_json`).
    pub value: Option<i32>,
    /// Full lexicon `strValue` (carries multi-copy `copies`).
    #[schema(value_type = Object)]
    pub value_json: Option<serde_json::Value>,
    pub confidence: Option<f64>,
    pub supporting_samples: Option<i32>,
    /// MODAL (computed) or MANUAL (curator override).
    pub method: Option<String>,
}

impl From<du_db::ystr::SignatureMarker> for StrSignatureMarkerDto {
    fn from(s: du_db::ystr::SignatureMarker) -> Self {
        StrSignatureMarkerDto {
            marker: s.marker_name,
            value: s.ancestral_value,
            value_json: s.ancestral_json,
            confidence: s.confidence,
            supporting_samples: s.supporting_samples,
            method: s.method,
        }
    }
}

/// A contributing branch-age estimate (method-labeled — STR is one factor in the
/// combined age model; this is NOT the authoritative `tmrca_ybp`).
#[derive(Serialize, ToSchema)]
pub struct AgeEstimateDto {
    pub method: String,
    pub estimate_ybp: Option<i32>,
    pub ci_low_ybp: Option<i32>,
    pub ci_high_ybp: Option<i32>,
    pub sample_count: Option<i32>,
    pub marker_count: Option<i32>,
    pub generation_years: Option<f64>,
}

impl From<du_db::ystr::AgeEstimate> for AgeEstimateDto {
    fn from(e: du_db::ystr::AgeEstimate) -> Self {
        AgeEstimateDto {
            method: e.method,
            estimate_ybp: e.estimate_ybp,
            ci_low_ybp: e.ci_low_ybp,
            ci_high_ybp: e.ci_high_ybp,
            sample_count: e.sample_count,
            marker_count: e.marker_count,
            generation_years: e.generation_years,
        }
    }
}

/// STR→branch prediction request: a query profile in the lexicon's
/// `strMarkerValue[]` shape (the same markers Navigator publishes).
#[derive(Deserialize, ToSchema)]
pub struct StrPredictRequest {
    #[schema(value_type = Object)]
    pub markers: serde_json::Value,
    /// Provenance of the query STRs; drives the WGS-upgrade recommendation.
    pub source: Option<String>,
    pub top_n: Option<i64>,
}

/// One ranked predicted branch.
#[derive(Serialize, ToSchema)]
pub struct StrPredictionDto {
    pub haplogroup: String,
    pub distance: i32,
    pub compared_markers: i64,
    pub signature_markers: i64,
}

#[derive(Serialize, ToSchema)]
pub struct StrPredictResponseDto {
    pub query_markers: i64,
    pub predictions: Vec<StrPredictionDto>,
    /// True unless the query STRs are WGS-derived — the STR-panel→WGS nudge.
    pub wgs_upgrade_recommended: bool,
    pub note: String,
}

// ── query params ─────────────────────────────────────────────────────────────

#[derive(Deserialize, IntoParams)]
pub struct SearchParams {
    /// Free-text filter.
    pub query: Option<String>,
    /// 1-based page number.
    pub page: Option<i64>,
    /// Page size (clamped to ≤200).
    pub page_size: Option<i64>,
}

#[derive(Deserialize, IntoParams)]
pub struct PageParams {
    pub page: Option<i64>,
    pub page_size: Option<i64>,
}

#[derive(Deserialize, IntoParams)]
pub struct RootParams {
    /// Subtree root; omit for the full forest.
    #[serde(rename = "rootHaplogroup")]
    #[param(rename = "rootHaplogroup")]
    pub root_haplogroup: Option<String>,
}

// ── tree assembly ────────────────────────────────────────────────────────────

fn assemble_forest(nodes: Vec<du_db::haplogroup::SubtreeNode>) -> Vec<HaplogroupNodeDto> {
    let mut by_parent: HashMap<Option<i64>, Vec<du_db::haplogroup::SubtreeNode>> = HashMap::new();
    for n in nodes {
        by_parent.entry(n.parent_id).or_default().push(n);
    }
    build_level(None, &by_parent, 0)
}

fn build_level(
    parent: Option<i64>,
    by_parent: &HashMap<Option<i64>, Vec<du_db::haplogroup::SubtreeNode>>,
    depth: u16,
) -> Vec<HaplogroupNodeDto> {
    // Depth guard: tree-merge data can contain cycles; cap recursion defensively.
    if depth > 256 {
        return Vec::new();
    }
    let mut kids = match by_parent.get(&parent) {
        Some(k) => k.iter().collect::<Vec<_>>(),
        None => return Vec::new(),
    };
    kids.sort_by(|a, b| a.name.cmp(&b.name));
    kids.into_iter()
        .map(|n| HaplogroupNodeDto {
            id: n.id,
            name: n.name.clone(),
            haplogroup_type: n.haplogroup_type.clone(),
            formed_ybp: n.formed_ybp,
            tmrca_ybp: n.tmrca_ybp,
            children: build_level(Some(n.id), by_parent, depth + 1),
        })
        .collect()
}

async fn tree_response(st: &AppState, dna: DnaType, root: Option<&str>) -> Result<Json<TreeDto>, AppError> {
    let nodes = du_db::haplogroup::subtree(&st.pool, dna, root).await?;
    Ok(Json(TreeDto { roots: assemble_forest(nodes) }))
}

// ── handlers ─────────────────────────────────────────────────────────────────

#[utoipa::path(get, path = "/api/v1/y-tree", params(RootParams), tag = "tree",
    responses((status = 200, description = "Y-chromosome haplogroup tree", body = TreeDto)))]
async fn y_tree(State(st): State<AppState>, Query(q): Query<RootParams>) -> Result<Json<TreeDto>, AppError> {
    tree_response(&st, DnaType::YDna, q.root_haplogroup.as_deref().filter(|s| !s.is_empty())).await
}

#[utoipa::path(get, path = "/api/v1/mt-tree", params(RootParams), tag = "tree",
    responses((status = 200, description = "Mitochondrial haplogroup tree", body = TreeDto)))]
async fn mt_tree(State(st): State<AppState>, Query(q): Query<RootParams>) -> Result<Json<TreeDto>, AppError> {
    tree_response(&st, DnaType::MtDna, q.root_haplogroup.as_deref().filter(|s| !s.is_empty())).await
}

#[utoipa::path(get, path = "/api/v1/coverage/benchmarks", tag = "coverage",
    responses((status = 200, description = "Coverage benchmarks by lab and test type", body = [CoverageBenchmarkDto])))]
async fn coverage_benchmarks(State(st): State<AppState>) -> Result<Json<Vec<CoverageBenchmarkDto>>, AppError> {
    let rows = du_db::coverage::benchmarks(&st.pool).await?;
    Ok(Json(rows.into_iter().map(CoverageBenchmarkDto::from).collect()))
}

#[utoipa::path(get, path = "/api/v1/haplogroups/{haplogroupName}/str-signature", tag = "tree",
    responses((status = 200, description = "Aggregated modal Y-STR signature for a haplogroup", body = [StrSignatureMarkerDto])))]
async fn haplogroup_str_signature(
    State(st): State<AppState>,
    Path(name): Path<String>,
) -> Result<Json<Vec<StrSignatureMarkerDto>>, AppError> {
    let rows = du_db::ystr::branch_signature(&st.pool, &name).await?;
    Ok(Json(rows.into_iter().map(StrSignatureMarkerDto::from).collect()))
}

#[utoipa::path(get, path = "/api/v1/haplogroups/{haplogroupName}/age", tag = "tree",
    responses((status = 200, description = "Contributing branch-age estimates (e.g. STR_VARIANCE)", body = [AgeEstimateDto])))]
async fn haplogroup_age(
    State(st): State<AppState>,
    Path(name): Path<String>,
) -> Result<Json<Vec<AgeEstimateDto>>, AppError> {
    let rows = du_db::ystr::branch_age_estimates(&st.pool, &name).await?;
    Ok(Json(rows.into_iter().map(AgeEstimateDto::from).collect()))
}

#[utoipa::path(post, path = "/api/v1/str/predict", tag = "tree", request_body = StrPredictRequest,
    responses((status = 200, description = "STR→branch predictions (ranked by genetic distance)", body = StrPredictResponseDto)))]
async fn str_predict(
    State(st): State<AppState>,
    Json(req): Json<StrPredictRequest>,
) -> Result<Json<StrPredictResponseDto>, AppError> {
    let query = du_db::ystr::parse_markers(&req.markers);
    if query.is_empty() {
        return Err(AppError::BadRequest("no parseable STR markers in request".into()));
    }
    let top_n = req.top_n.unwrap_or(10).clamp(1, 50);
    // Require meaningful marker overlap (up to 8) so a branch can't rank off one marker.
    let min_compared = query.len().clamp(1, 8);
    let preds = du_db::ystr::predict(&st.pool, &query, top_n, min_compared).await?;

    let wgs_derived = matches!(req.source.as_deref(), Some("WGS_DERIVED") | Some("BIG_Y_DERIVED"));
    let note = if wgs_derived {
        "Predicted from WGS-derived STRs; SNP calls supersede STR prediction.".to_string()
    } else {
        "STR-based predictions are probabilistic. Upgrade to WGS / Big Y for SNP-confirmed branch placement.".to_string()
    };
    Ok(Json(StrPredictResponseDto {
        query_markers: query.len() as i64,
        predictions: preds
            .into_iter()
            .map(|p| StrPredictionDto {
                haplogroup: p.haplogroup,
                distance: p.distance,
                compared_markers: p.compared_markers as i64,
                signature_markers: p.signature_markers as i64,
            })
            .collect(),
        wgs_upgrade_recommended: !wgs_derived,
        note,
    }))
}

#[utoipa::path(get, path = "/api/v1/reports/coverage", tag = "reports",
    responses((status = 200, description = "Federated coverage aggregated by reference build", body = [FedCoverageByBuildDto])))]
async fn reports_coverage(State(st): State<AppState>) -> Result<Json<Vec<FedCoverageByBuildDto>>, AppError> {
    let rows = du_db::fed::coverage::aggregate_by_build(&st.pool).await?;
    Ok(Json(rows.into_iter().map(FedCoverageByBuildDto::from).collect()))
}

#[utoipa::path(get, path = "/api/v1/reports/ancestry", tag = "reports",
    responses((status = 200, description = "Average ancestry share by continental super-population", body = [AncestryShareDto])))]
async fn reports_ancestry(State(st): State<AppState>) -> Result<Json<Vec<AncestryShareDto>>, AppError> {
    let rows = du_db::fed::analytics::super_population_distribution(&st.pool).await?;
    Ok(Json(rows.into_iter().map(AncestryShareDto::from).collect()))
}

#[utoipa::path(get, path = "/api/v1/reports/haplogroups", tag = "reports",
    responses((status = 200, description = "Y/MT haplogroup distribution across mirrored biosamples", body = [HaplogroupCountDto])))]
async fn reports_haplogroups(State(st): State<AppState>) -> Result<Json<Vec<HaplogroupCountDto>>, AppError> {
    let rows = du_db::fed::core::haplogroup_distribution(&st.pool).await?;
    Ok(Json(rows.into_iter().map(HaplogroupCountDto::from).collect()))
}

#[utoipa::path(get, path = "/api/v1/references/details", params(SearchParams), tag = "references",
    responses((status = 200, description = "Publications (paginated)", body = Page<PublicationDto>)))]
async fn references_details(
    State(st): State<AppState>,
    Query(q): Query<SearchParams>,
) -> Result<Json<Page<PublicationDto>>, AppError> {
    let page = du_db::publication::search(&st.pool, q.query.as_deref(), q.page.unwrap_or(1), q.page_size.unwrap_or(25)).await?;
    Ok(Json(page.into()))
}

#[utoipa::path(get, path = "/api/v1/references/details/{publicationId}/biosamples",
    params(("publicationId" = i64, Path, description = "Publication id"), PageParams), tag = "references",
    responses((status = 200, description = "Biosamples linked to a publication", body = Page<BiosampleDto>)))]
async fn biosample_report(
    State(st): State<AppState>,
    Path(publication_id): Path<i64>,
    Query(q): Query<PageParams>,
) -> Result<Json<Page<BiosampleDto>>, AppError> {
    let page = du_db::biosample::for_publication(
        &st.pool,
        du_domain::ids::PublicationId(publication_id),
        q.page.unwrap_or(1),
        q.page_size.unwrap_or(50),
    )
    .await?;
    Ok(Json(page.into()))
}

#[utoipa::path(get, path = "/api/v1/biosample/studies", tag = "references",
    responses((status = 200, description = "Genomic studies with their linked samples", body = [StudyDto])))]
async fn biosample_studies(State(st): State<AppState>) -> Result<Json<Vec<StudyDto>>, AppError> {
    let rows = du_db::study::with_samples(&st.pool).await?;
    Ok(Json(rows.into_iter().map(StudyDto::from).collect()))
}

#[utoipa::path(get, path = "/api/v1/variants", params(SearchParams), tag = "variants",
    responses((status = 200, description = "Variants (paginated)", body = Page<VariantDto>)))]
async fn list_variants(
    State(st): State<AppState>,
    Query(q): Query<SearchParams>,
) -> Result<Json<Page<VariantDto>>, AppError> {
    let page = du_db::variant::search(&st.pool, q.query.as_deref(), q.page.unwrap_or(1), q.page_size.unwrap_or(25)).await?;
    Ok(Json(page.into()))
}

#[utoipa::path(get, path = "/api/v1/variants/{variantId}",
    params(("variantId" = i64, Path, description = "Variant id")), tag = "variants",
    responses((status = 200, description = "A single variant", body = VariantDto), (status = 404, description = "Not found")))]
async fn get_variant(State(st): State<AppState>, Path(id): Path<i64>) -> Result<Json<VariantDto>, AppError> {
    let v = du_db::variant::get_by_id(&st.pool, du_domain::ids::VariantId(id))
        .await?
        .ok_or_else(|| AppError::NotFound(format!("variant {id}")))?;
    Ok(Json(v.into()))
}

#[utoipa::path(get, path = "/api/v1/haplogroups/{haplogroupName}/variants",
    params(("haplogroupName" = String, Path, description = "Haplogroup name")), tag = "variants",
    responses((status = 200, description = "Variants defining a haplogroup", body = [VariantDto])))]
async fn variants_by_haplogroup(
    State(st): State<AppState>,
    Path(name): Path<String>,
) -> Result<Json<Vec<VariantDto>>, AppError> {
    let vs = du_db::variant::for_haplogroup_name(&st.pool, &name).await?;
    Ok(Json(vs.into_iter().map(VariantDto::from).collect()))
}

#[utoipa::path(get, path = "/api/v1/variants/export/metadata", tag = "variants",
    responses((status = 200, description = "Export size + freshness", body = ExportMetadataDto)))]
async fn export_metadata(State(st): State<AppState>) -> Result<Json<ExportMetadataDto>, AppError> {
    let variant_count = du_db::variant::count(&st.pool).await?;
    Ok(Json(ExportMetadataDto {
        variant_count,
        format: "csv".into(),
        generated_at: chrono::Utc::now().to_rfc3339(),
    }))
}

#[utoipa::path(get, path = "/api/v1/variants/export", tag = "variants",
    responses((status = 200, description = "Variant catalog as CSV", content_type = "text/csv")))]
async fn export_variants(State(st): State<AppState>) -> Result<Response, AppError> {
    let variants = du_db::variant::export_all(&st.pool).await?;
    let mut csv = String::from("id,canonical_name,mutation_type,naming_status,builds,common_names,rs_ids\n");
    for v in &variants {
        let mut builds: Vec<&str> = v.coordinates.0.keys().map(String::as_str).collect();
        builds.sort_unstable();
        csv.push_str(&format!(
            "{},{},{},{},{},{},{}\n",
            v.id.0,
            csv_field(&v.canonical_name),
            v.mutation_type.label(),
            v.naming_status.label(),
            csv_field(&builds.join(";")),
            csv_field(&v.aliases.common_names.join(";")),
            csv_field(&v.aliases.rs_ids.join(";")),
        ));
    }
    Ok((
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, "text/csv; charset=utf-8"),
            (header::CONTENT_DISPOSITION, "attachment; filename=\"variants.csv\""),
        ],
        csv,
    )
        .into_response())
}

#[utoipa::path(get, path = "/api/v1/genome-regions", tag = "genome-regions",
    responses((status = 200, description = "Reference builds with region coordinates", body = [String])))]
async fn list_region_builds(State(st): State<AppState>) -> Result<Json<Vec<String>>, AppError> {
    Ok(Json(du_db::genome_region::distinct_builds(&st.pool).await?))
}

#[utoipa::path(get, path = "/api/v1/genome-regions/{build}",
    params(("build" = String, Path, description = "Reference build, e.g. GRCh38")), tag = "genome-regions",
    responses((status = 200, description = "Regions for a build", body = [GenomeRegionDto])))]
async fn regions_by_build(
    State(st): State<AppState>,
    Path(build): Path<String>,
) -> Result<Json<Vec<GenomeRegionDto>>, AppError> {
    let rows = du_db::genome_region::for_build(&st.pool, &build).await?;
    Ok(Json(rows.into_iter().map(GenomeRegionDto::from).collect()))
}

/// Minimal CSV field escaping (quote when the value contains a comma/quote/newline).
fn csv_field(s: &str) -> String {
    if s.contains([',', '"', '\n']) {
        format!("\"{}\"", s.replace('"', "\"\""))
    } else {
        s.to_string()
    }
}

// ── OpenAPI document + router ─────────────────────────────────────────────────

#[derive(OpenApi)]
#[openapi(
    info(title = "DecodingUs API", version = "1.0.0", description = "Public read API for the DecodingUs AppView."),
    paths(
        y_tree, mt_tree, coverage_benchmarks, references_details, biosample_report, biosample_studies,
        list_variants, get_variant, variants_by_haplogroup, export_metadata, export_variants,
        list_region_builds, regions_by_build,
        reports_coverage, reports_ancestry, reports_haplogroups,
        haplogroup_str_signature, haplogroup_age, str_predict,
    ),
    components(schemas(
        VariantDto, HaplogroupNodeDto, TreeDto, CoverageBenchmarkDto, PublicationDto, BiosampleDto,
        GenomeRegionDto, StudyDto, ExportMetadataDto, Page<VariantDto>, Page<PublicationDto>, Page<BiosampleDto>,
        FedCoverageByBuildDto, AncestryShareDto, HaplogroupCountDto, StrSignatureMarkerDto,
        StrPredictRequest, StrPredictionDto, StrPredictResponseDto, AgeEstimateDto,
    )),
    tags(
        (name = "tree", description = "Y/MT haplogroup trees"),
        (name = "variants", description = "Variant catalog"),
        (name = "coverage", description = "Sequencing coverage benchmarks"),
        (name = "references", description = "Publications, biosamples, studies"),
        (name = "genome-regions", description = "Multi-build genome regions"),
        (name = "reports", description = "Population reports aggregated from the federated mirror"),
    )
)]
pub struct ApiDoc;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/v1/y-tree", get(y_tree))
        .route("/api/v1/mt-tree", get(mt_tree))
        .route("/api/v1/coverage/benchmarks", get(coverage_benchmarks))
        .route("/api/v1/reports/coverage", get(reports_coverage))
        .route("/api/v1/reports/ancestry", get(reports_ancestry))
        .route("/api/v1/reports/haplogroups", get(reports_haplogroups))
        .route("/api/v1/references/details", get(references_details))
        .route("/api/v1/references/details/:publication_id/biosamples", get(biosample_report))
        .route("/api/v1/biosample/studies", get(biosample_studies))
        .route("/api/v1/variants", get(list_variants))
        .route("/api/v1/variants/export", get(export_variants))
        .route("/api/v1/variants/export/metadata", get(export_metadata))
        .route("/api/v1/variants/:variant_id", get(get_variant))
        .route("/api/v1/haplogroups/:haplogroup_name/variants", get(variants_by_haplogroup))
        .route("/api/v1/haplogroups/:haplogroup_name/str-signature", get(haplogroup_str_signature))
        .route("/api/v1/haplogroups/:haplogroup_name/age", get(haplogroup_age))
        .route("/api/v1/str/predict", post(str_predict))
        .route("/api/v1/genome-regions", get(list_region_builds))
        .route("/api/v1/genome-regions/:build", get(regions_by_build))
        .merge(SwaggerUi::new("/api").url("/api/openapi.json", ApiDoc::openapi()))
}
