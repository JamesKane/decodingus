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
use serde::Deserialize;
use utoipa::{IntoParams, OpenApi};
use utoipa_swagger_ui::SwaggerUi;

mod dto;
mod tree;
pub(crate) use dto::*;
use tree::*;


#[utoipa::path(get, path = "/api/v1/coverage/benchmarks", tag = "coverage",
    responses((status = 200, description = "Coverage benchmarks by lab and test type", body = [CoverageBenchmarkDto])))]
async fn coverage_benchmarks(State(st): State<AppState>) -> Result<Json<Vec<CoverageBenchmarkDto>>, AppError> {
    let rows = du_db::coverage::benchmarks(&st.pool).await?;
    Ok(Json(rows.into_iter().map(CoverageBenchmarkDto::from).collect()))
}

#[utoipa::path(get, path = "/api/v1/sequencer/lab", params(InstrumentParams), tag = "sequencer",
    responses((status = 200, description = "The instrument's sequencing lab", body = SequencerLabDto),
              (status = 404, description = "Unknown instrument or no lab association")))]
async fn sequencer_lab(State(st): State<AppState>, Query(q): Query<InstrumentParams>) -> Result<Json<SequencerLabDto>, AppError> {
    let id = q.instrument_id.trim();
    du_db::sequencer::lookup_lab(&st.pool, id)
        .await?
        .map(|l| Json(SequencerLabDto::from(l)))
        .ok_or_else(|| AppError::NotFound(format!("instrument {id}")))
}

#[utoipa::path(get, path = "/api/v1/sequencer/lab-instruments", tag = "sequencer",
    responses((status = 200, description = "All preseeded instrument→lab associations (bulk cache seed)", body = [SequencerLabDto])))]
async fn sequencer_lab_instruments(State(st): State<AppState>) -> Result<Json<Vec<SequencerLabDto>>, AppError> {
    Ok(Json(du_db::sequencer::lab_instruments(&st.pool).await?.into_iter().map(SequencerLabDto::from).collect()))
}

#[derive(Deserialize, IntoParams)]
struct DiscoveryQuery {
    /// DNA arm: `Y_DNA` or `MT_DNA`.
    #[serde(rename = "type")]
    dna_type: Option<String>,
    /// Proposal status (e.g. `READY_FOR_REVIEW`, `SPLIT_CANDIDATE`).
    status: Option<String>,
    /// Parent (terminal) haplogroup name.
    parent: Option<String>,
    /// Minimum distinct contributing samples.
    min_consensus: Option<i64>,
    page: Option<i64>,
    page_size: Option<i64>,
}

#[utoipa::path(get, path = "/api/v1/discovery/proposals", params(DiscoveryQuery), tag = "discovery",
    responses((status = 200, description = "Proposed haplogroup branches (paginated)", body = Page<DiscoveryProposalDto>)))]
async fn discovery_proposals(State(st): State<AppState>, Query(q): Query<DiscoveryQuery>) -> Result<Json<Page<DiscoveryProposalDto>>, AppError> {
    let filter = du_db::proposal::ProposalFilter {
        status: q.status.as_deref().filter(|s| !s.is_empty()),
        dna_type: q.dna_type.as_deref().filter(|s| !s.is_empty()),
        parent: q.parent.as_deref().filter(|s| !s.is_empty()),
        min_consensus: q.min_consensus,
    };
    let page = du_db::proposal::list(&st.pool, &filter, q.page.unwrap_or(1), q.page_size.unwrap_or(50)).await?;
    Ok(Json(page.into()))
}

#[utoipa::path(get, path = "/api/v1/discovery/proposals/{id}",
    params(("id" = i64, Path, description = "Proposal id")), tag = "discovery",
    responses((status = 200, description = "A proposal with its defining variants", body = DiscoveryProposalDto),
              (status = 404, description = "Not found")))]
async fn discovery_proposal(State(st): State<AppState>, Path(id): Path<i64>) -> Result<Json<DiscoveryProposalDto>, AppError> {
    du_db::proposal::get(&st.pool, id)
        .await?
        .map(|d| Json(DiscoveryProposalDto::from_detail(d)))
        .ok_or_else(|| AppError::NotFound(format!("proposal {id}")))
}

#[utoipa::path(get, path = "/api/v1/test-types", tag = "test-types",
    responses((status = 200, description = "Test-type taxonomy + empirical coverage norms", body = [TestTypeDto])))]
async fn test_types(State(st): State<AppState>) -> Result<Json<Vec<TestTypeDto>>, AppError> {
    Ok(Json(du_db::test_type::list(&st.pool).await?.into_iter().map(TestTypeDto::from).collect()))
}

#[utoipa::path(get, path = "/api/v1/test-types/{code}",
    params(("code" = String, Path, description = "Test-type code (e.g. WGS, BIG_Y_700)")), tag = "test-types",
    responses((status = 200, description = "A test type + its coverage norm", body = TestTypeDto),
              (status = 404, description = "Unknown test type")))]
async fn test_type_by_code(State(st): State<AppState>, Path(code): Path<String>) -> Result<Json<TestTypeDto>, AppError> {
    du_db::test_type::get(&st.pool, &code)
        .await?
        .map(|t| Json(TestTypeDto::from(t)))
        .ok_or_else(|| AppError::NotFound(format!("test type {code}")))
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

    let wgs_derived = matches!(req.source.as_deref(), Some("WGS_DERIVED" | "BIG_Y_DERIVED"));
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

/// Best display name for a defining variant: canonical name, else first alias.
fn snp_name(v: &du_db::haplogroup::VariantInfo) -> Option<String> {
    if let Some(n) = v.canonical_name.as_deref().filter(|s| !s.is_empty()) {
        return Some(n.to_string());
    }
    v.aliases
        .get("common_names")
        .and_then(serde_json::Value::as_array)
        .and_then(|a| a.first())
        .and_then(serde_json::Value::as_str)
        .map(str::to_string)
}

fn pathway_dto(call: &du_db::biosample::HaplogroupCall, p: du_db::haplogroup::Pathway) -> HaplogroupPathwayDto {
    use du_db::biosample::HaplogroupCallOrigin;
    HaplogroupPathwayDto {
        called_name: call.name.clone(),
        resolved_name: p.resolved_name,
        dna_type: call.dna_type.label().to_string(),
        origin: match call.origin {
            HaplogroupCallOrigin::Reconciled => "RECONCILED",
            HaplogroupCallOrigin::FedConsensus => "FED_CONSENSUS",
            HaplogroupCallOrigin::Original => "ORIGINAL",
        }
        .to_string(),
        confidence: call.confidence,
        run_count: call.run_count,
        snp_concordance: call.snp_concordance,
        compatibility_level: call.compatibility_level.clone(),
        steps: p
            .steps
            .into_iter()
            .map(|s| PathwayStepDto {
                name: s.name,
                formed_ybp: s.formed_ybp,
                tmrca_ybp: s.tmrca_ybp,
                defining_snps: s.defining_snps.iter().filter_map(snp_name).collect(),
            })
            .collect(),
    }
}

#[utoipa::path(get, path = "/api/v1/samples/{slug}",
    params(("slug" = String, Path, description = "Sample slug, accession, alias, or guid")), tag = "references",
    responses((status = 200, description = "Public per-sample report", body = SampleReportDto),
               (status = 404, description = "Not found or not public")))]
async fn sample_report(State(st): State<AppState>, Path(slug): Path<String>) -> Result<Json<SampleReportDto>, AppError> {
    // The API never exposes private samples (no curator preview here).
    let rep = du_db::biosample::report(&st.pool, &slug)
        .await?
        .filter(|r| r.identity.is_public)
        .ok_or_else(|| AppError::NotFound(format!("sample {slug}")))?;

    let y_haplogroup = match &rep.y {
        Some(c) => Some(pathway_dto(c, du_db::haplogroup::pathway(&st.pool, &c.name, c.dna_type).await?)),
        None => None,
    };
    let mt_haplogroup = match &rep.mt {
        Some(c) => Some(pathway_dto(c, du_db::haplogroup::pathway(&st.pool, &c.name, c.dna_type).await?)),
        None => None,
    };

    let id = &rep.identity;
    let dto = SampleReportDto {
        sample_guid: id.sample_guid.0.to_string(),
        source: id.source.label().to_string(),
        accession: id.accession.clone(),
        alias: id.alias.clone(),
        description: id.description.clone(),
        center_name: id.center_name.clone(),
        sex: id.sex.clone(),
        latitude: id.origin.map(|o| o.lat),
        longitude: id.origin.map(|o| o.lon),
        is_federated: id.is_federated,
        y_haplogroup,
        mt_haplogroup,
        sequencing: rep
            .sequencing
            .iter()
            .map(|r| SequencingRunDto {
                platform_name: r.platform_name.clone(),
                instrument_model: r.instrument_model.clone(),
                test_type: r.test_type.clone(),
                library_layout: r.library_layout.clone(),
                total_reads: r.total_reads,
                read_length: r.read_length,
                mean_insert_size: r.mean_insert_size,
            })
            .collect(),
        coverage: rep
            .coverage
            .iter()
            .map(|c| CoverageSummaryDto {
                reference_build: c.reference_build.clone(),
                aligner: c.aligner.clone(),
                mean_coverage: c.mean_coverage,
                median_coverage: c.median_coverage,
                pct_10x: c.pct_10x,
                pct_20x: c.pct_20x,
                pct_30x: c.pct_30x,
                test_type: c.test_type.clone(),
                expected_min_depth: c.expected_min_depth,
                norm_median_depth: c.norm_median_depth,
                conformance: c.conformance.clone(),
            })
            .collect(),
        ancestry: rep.ancestry.as_ref().map(|a| AncestryDto {
            analysis_method: a.analysis_method.clone(),
            panel_type: a.panel_type.clone(),
            confidence_level: a.confidence_level,
            super_populations: a.super_populations.clone(),
            components: a.components.clone(),
        }),
        publications: rep
            .publications
            .iter()
            .map(|p| SamplePublicationDto {
                id: p.id.0,
                title: p.title.clone(),
                doi: p.doi.clone(),
                url: p.url.clone(),
                publication_date: p.publication_date,
            })
            .collect(),
    };
    Ok(Json(dto))
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

#[utoipa::path(get, path = "/api/v1/variants/export.gff", tag = "variants",
    responses((status = 200, description = "DU-named variants as GFF3 (GRCh38) for propagation", content_type = "text/plain")))]
async fn export_variants_gff(State(st): State<AppState>) -> Result<Response, AppError> {
    // Propagation feed for the DU naming authority: minted DU names + GRCh38
    // coordinates as GFF3, for YBrowse/external tools to pick up.
    let variants = du_db::variant::export_du_named(&st.pool).await?;
    let mut gff = String::from("##gff-version 3\n");
    for v in &variants {
        let Some(c) = v.coordinates.0.get("GRCh38") else { continue };
        let name = &v.canonical_name;
        let mut attrs = format!("ID={name};Name={name}");
        if let Some(anc) = &c.ancestral {
            attrs.push_str(&format!(";allele_anc={anc}"));
        }
        if let Some(der) = &c.derived {
            attrs.push_str(&format!(";allele_der={der}"));
        }
        // GFF3 is 1-based, inclusive; a SNV spans a single position.
        gff.push_str(&format!(
            "{}\tDecodingUs\tSNV\t{}\t{}\t.\t.\t.\t{}\n",
            c.contig, c.position, c.position, attrs
        ));
    }
    Ok((
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, "text/plain; charset=utf-8"),
            (header::CONTENT_DISPOSITION, "attachment; filename=\"decodingus-variants.gff3\""),
        ],
        gff,
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
        y_tree, mt_tree, y_tree_full, mt_tree_full, y_tree_version, mt_tree_version, y_node_samples, mt_node_samples, coverage_benchmarks, sequencer_lab, sequencer_lab_instruments, discovery_proposals, discovery_proposal, test_types, test_type_by_code, references_details, biosample_report, sample_report, biosample_studies,
        list_variants, get_variant, variants_by_haplogroup, export_metadata, export_variants,
        export_variants_gff, list_region_builds, regions_by_build,
        reports_coverage, reports_ancestry, reports_haplogroups,
        haplogroup_str_signature, haplogroup_age, str_predict,
    ),
    components(schemas(
        VariantDto, HaplogroupNodeDto, TreeDto, TreeVersionDto, LeafSampleDto, PublicationRefDto, LeafSamplesDto, CoverageBenchmarkDto, SequencerLabDto, DiscoveryProposalDto, DiscoveryVariantDto, Page<DiscoveryProposalDto>, TestTypeDto, PublicationDto, BiosampleDto,
        SampleReportDto, HaplogroupPathwayDto, PathwayStepDto, SequencingRunDto, CoverageSummaryDto,
        AncestryDto, SamplePublicationDto,
        GenomeRegionDto, StudyDto, ExportMetadataDto, Page<VariantDto>, Page<PublicationDto>, Page<BiosampleDto>,
        FedCoverageByBuildDto, AncestryShareDto, HaplogroupCountDto, StrSignatureMarkerDto,
        StrPredictRequest, StrPredictionDto, StrPredictResponseDto, AgeEstimateDto,
    )),
    tags(
        (name = "tree", description = "Y/MT haplogroup trees"),
        (name = "variants", description = "Variant catalog"),
        (name = "coverage", description = "Sequencing coverage benchmarks"),
        (name = "sequencer", description = "Sequencer instrument → lab lookup"),
        (name = "discovery", description = "Proposed haplogroup branches (discovery consensus)"),
        (name = "test-types", description = "Test-type taxonomy + empirical coverage norms"),
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
        .route("/api/v1/y-tree/full", get(y_tree_full))
        .route("/api/v1/mt-tree/full", get(mt_tree_full))
        .route("/api/v1/y-tree/version", get(y_tree_version))
        .route("/api/v1/mt-tree/version", get(mt_tree_version))
        .route("/api/v1/y-tree/node/:name/samples", get(y_node_samples))
        .route("/api/v1/mt-tree/node/:name/samples", get(mt_node_samples))
        .route("/api/v1/coverage/benchmarks", get(coverage_benchmarks))
        .route("/api/v1/sequencer/lab", get(sequencer_lab))
        .route("/api/v1/sequencer/lab-instruments", get(sequencer_lab_instruments))
        .route("/api/v1/discovery/proposals", get(discovery_proposals))
        .route("/api/v1/discovery/proposals/:id", get(discovery_proposal))
        .route("/api/v1/test-types", get(test_types))
        .route("/api/v1/test-types/:code", get(test_type_by_code))
        .route("/api/v1/reports/coverage", get(reports_coverage))
        .route("/api/v1/reports/ancestry", get(reports_ancestry))
        .route("/api/v1/reports/haplogroups", get(reports_haplogroups))
        .route("/api/v1/references/details", get(references_details))
        .route("/api/v1/references/details/:publication_id/biosamples", get(biosample_report))
        .route("/api/v1/samples/:slug", get(sample_report))
        .route("/api/v1/biosample/studies", get(biosample_studies))
        .route("/api/v1/variants", get(list_variants))
        .route("/api/v1/variants/export", get(export_variants))
        .route("/api/v1/variants/export.gff", get(export_variants_gff))
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

#[cfg(test)]
mod tests {
    use super::{HaplogroupNodeDto, TreeDto, VariantDto};

    /// Pins the `/y-tree/full` JSON contract the Navigator's `parse_decodingus_json` consumes:
    /// snake_case node fields, a nested `children` array, and per-node `variants[].coordinates`
    /// keyed by build label (`hs1`/`GRCh38`). The plain tree omits `variants` entirely.
    #[test]
    fn full_tree_node_serializes_with_variants_and_coordinates() {
        let variant = VariantDto {
            id: 5,
            canonical_name: "M207".into(),
            mutation_type: "SNP".into(),
            naming_status: "named".into(),
            common_names: vec![],
            rs_ids: vec![],
            coordinates: serde_json::json!({
                "hs1": {"contig": "chrY", "position": 2_800_000, "ancestral": "A", "derived": "G"}
            }),
        };
        let node = HaplogroupNodeDto {
            id: 10,
            name: "R-M207".into(),
            haplogroup_type: "Y_DNA".into(),
            formed_ybp: None,
            tmrca_ybp: None,
            sample_count: 0,
            variants: vec![variant],
            children: vec![],
        };
        let v = serde_json::to_value(TreeDto { roots: vec![node] }).unwrap();
        let root = &v["roots"][0];
        assert_eq!(root["id"], 10);
        assert_eq!(root["haplogroup_type"], "Y_DNA"); // snake_case, no rename_all
        let var = &root["variants"][0];
        assert_eq!(var["canonical_name"], "M207");
        assert_eq!(var["coordinates"]["hs1"]["position"], 2_800_000);
        assert_eq!(var["coordinates"]["hs1"]["derived"], "G");
    }

    #[test]
    fn plain_tree_omits_empty_variants() {
        let node = HaplogroupNodeDto {
            id: 1,
            name: "A".into(),
            haplogroup_type: "Y_DNA".into(),
            formed_ybp: None,
            tmrca_ybp: None,
            sample_count: 0,
            variants: vec![],
            children: vec![],
        };
        let v = serde_json::to_value(TreeDto { roots: vec![node] }).unwrap();
        assert!(v["roots"][0].get("variants").is_none(), "empty variants must be omitted");
    }

    /// End-to-end: a placed non-D2C sample shows as a cumulative `sample_count` on the tree
    /// node and in the node's leaf list; a D2C (CITIZEN) sample never appears.
    #[tokio::test]
    async fn tree_carries_sample_count_and_leaf_list() {
        use axum::body::{to_bytes, Body};
        use axum::http::{Request, StatusCode};
        use tower::ServiceExt;

        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping tree-samples endpoint test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();
        sqlx::query("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ('R-M269', 'Y_DNA'::core.dna_type)")
            .execute(&pool)
            .await
            .unwrap();
        // One EXTERNAL (paper) sample + one CITIZEN (D2C) sample, both calling R-M269.
        for (src, acc) in [("EXTERNAL", "EX-1"), ("CITIZEN", "CIT-1")] {
            sqlx::query(
                "INSERT INTO core.biosample (source, accession, original_haplogroups) \
                 VALUES ($1::core.biosample_source, $2, '[{\"y\":\"R-M269\"}]'::jsonb)",
            )
            .bind(src)
            .bind(acc)
            .execute(&pool)
            .await
            .unwrap();
        }
        du_db::tree_sample::recompute_placements(&pool, du_domain::enums::DnaType::YDna).await.unwrap();
        let state = crate::state::AppState { pool, key: tower_cookies::Key::generate(), oauth: None };

        let get = |state: crate::state::AppState, uri: &'static str| async move {
            crate::routes::app(state)
                .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
                .await
                .unwrap()
        };

        // The node carries sample_count = 1 (only the non-D2C sample).
        let t = get(state.clone(), "/api/v1/y-tree?rootHaplogroup=R-M269").await;
        assert_eq!(t.status(), StatusCode::OK);
        let tv: serde_json::Value = serde_json::from_slice(&to_bytes(t.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(tv["roots"][0]["name"], "R-M269");
        assert_eq!(tv["roots"][0]["sample_count"], 1);

        // The leaf list has the paper sample and not the D2C one.
        let s = get(state, "/api/v1/y-tree/node/R-M269/samples").await;
        assert_eq!(s.status(), StatusCode::OK);
        let sv: serde_json::Value = serde_json::from_slice(&to_bytes(s.into_body(), usize::MAX).await.unwrap()).unwrap();
        let items = sv["items"].as_array().unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0]["accession"], "EX-1");
        assert_eq!(items[0]["source"], "EXTERNAL");
    }

    #[test]
    fn etag_varies_by_shape_dna_root_revision() {
        use super::tree_etag;
        use du_domain::enums::DnaType;
        let base = tree_etag(true, DnaType::YDna, None, 5);
        assert_eq!(base, "\"full-y-*-r5\"");
        assert_ne!(base, tree_etag(false, DnaType::YDna, None, 5)); // shape
        assert_ne!(base, tree_etag(true, DnaType::MtDna, None, 5)); // dna
        assert_ne!(base, tree_etag(true, DnaType::YDna, Some("R-M269"), 5)); // root
        assert_ne!(base, tree_etag(true, DnaType::YDna, None, 6)); // revision
    }

    #[test]
    fn if_none_match_handles_list_and_wildcard() {
        use super::if_none_match;
        use axum::http::{header, HeaderMap, HeaderValue};
        let etag = "\"full-y-*-r5\"";
        let mut h = HeaderMap::new();
        assert!(!if_none_match(&h, etag)); // absent
        h.insert(header::IF_NONE_MATCH, HeaderValue::from_static("\"full-y-*-r5\""));
        assert!(if_none_match(&h, etag));
        h.insert(header::IF_NONE_MATCH, HeaderValue::from_static("\"other\", \"full-y-*-r5\""));
        assert!(if_none_match(&h, etag), "matches one of a list");
        h.insert(header::IF_NONE_MATCH, HeaderValue::from_static("*"));
        assert!(if_none_match(&h, etag), "wildcard matches");
        h.insert(header::IF_NONE_MATCH, HeaderValue::from_static("\"stale-r1\""));
        assert!(!if_none_match(&h, etag), "non-matching validator");
    }

    /// Full conditional-GET cycle against an ephemeral DB: 200 + ETag → 304 on
    /// `If-None-Match` → 200 again once the revision marker bumps.
    #[tokio::test]
    async fn conditional_get_304_until_revision_bumps() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping tree-cache test");
            return;
        };
        use axum::body::{to_bytes, Body};
        use axum::http::{header, Request, StatusCode};
        use tower::ServiceExt;

        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();
        let state = super::AppState { pool: pool.clone(), key: tower_cookies::Key::generate(), oauth: None };
        let app = super::router().with_state(state);

        let plain = || Request::builder().uri("/api/v1/y-tree/full").body(Body::empty()).unwrap();
        let with_inm = |etag: &str| {
            Request::builder().uri("/api/v1/y-tree/full").header(header::IF_NONE_MATCH, etag).body(Body::empty()).unwrap()
        };

        // 1) First fetch: 200 + ETag + Last-Modified.
        let r1 = app.clone().oneshot(plain()).await.unwrap();
        assert_eq!(r1.status(), StatusCode::OK);
        let etag = r1.headers().get(header::ETAG).unwrap().to_str().unwrap().to_string();
        assert!(r1.headers().contains_key(header::LAST_MODIFIED));

        // 2) Revalidate with the ETag: 304, empty body.
        let r2 = app.clone().oneshot(with_inm(&etag)).await.unwrap();
        assert_eq!(r2.status(), StatusCode::NOT_MODIFIED);
        assert_eq!(r2.headers().get(header::ETAG).unwrap().to_str().unwrap(), etag);
        assert!(to_bytes(r2.into_body(), usize::MAX).await.unwrap().is_empty(), "304 carries no body");

        // 3) Bump the revision → ETag changes → the old validator no longer matches.
        du_db::tree_revision::bump(&pool).await.expect("bump");
        let r3 = app.clone().oneshot(with_inm(&etag)).await.unwrap();
        assert_eq!(r3.status(), StatusCode::OK, "stale validator → full payload");
        let etag3 = r3.headers().get(header::ETAG).unwrap().to_str().unwrap().to_string();
        assert_ne!(etag3, etag, "ETag advanced with the revision");

        // 4) /version reports the current revision/ETag without a body fetch.
        let rv = app.clone().oneshot(Request::builder().uri("/api/v1/y-tree/version").body(Body::empty()).unwrap()).await.unwrap();
        assert_eq!(rv.status(), StatusCode::OK);
        let vbody = to_bytes(rv.into_body(), usize::MAX).await.unwrap();
        let v: serde_json::Value = serde_json::from_slice(&vbody).unwrap();
        assert_eq!(v["etag"].as_str().unwrap(), etag3);
        assert!(v["revision"].as_i64().unwrap() >= 2);
    }

    #[test]
    fn sequencer_lab_dto_serializes_snake_case() {
        use super::SequencerLabDto;
        let dto = SequencerLabDto {
            instrument_id: "A00123".into(),
            lab_name: "Nebula Genomics".into(),
            is_d2c: true,
            manufacturer: Some("Illumina".into()),
            model_name: Some("NovaSeq 6000".into()),
            website_url: Some("https://nebula.org".into()),
        };
        let v = serde_json::to_value(dto).unwrap();
        assert_eq!(v["instrument_id"], "A00123");
        assert_eq!(v["lab_name"], "Nebula Genomics");
        assert_eq!(v["is_d2c"], true);
        assert_eq!(v["model_name"], "NovaSeq 6000");
        assert_eq!(v["website_url"], "https://nebula.org");
    }

    /// Sequencer endpoints over HTTP (routing + error mapping). Against an empty
    /// catalog: an unknown instrument → 404, the bulk list → 200 with `[]`.
    /// (The 200-with-data resolution is covered by `du-db/tests/sequencer.rs`.)
    #[tokio::test]
    async fn sequencer_endpoints_route_and_404() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping sequencer endpoint test");
            return;
        };
        use axum::body::{to_bytes, Body};
        use axum::http::{Request, StatusCode};
        use tower::ServiceExt;

        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let state = super::AppState { pool: db.pool().clone(), key: tower_cookies::Key::generate(), oauth: None };
        let app = super::router().with_state(state);

        let r404 = app.clone().oneshot(Request::builder().uri("/api/v1/sequencer/lab?instrument_id=NOPE").body(Body::empty()).unwrap()).await.unwrap();
        assert_eq!(r404.status(), StatusCode::NOT_FOUND);

        let rl = app.clone().oneshot(Request::builder().uri("/api/v1/sequencer/lab-instruments").body(Body::empty()).unwrap()).await.unwrap();
        assert_eq!(rl.status(), StatusCode::OK);
        let list: serde_json::Value = serde_json::from_slice(&to_bytes(rl.into_body(), usize::MAX).await.unwrap()).unwrap();
        // The bulk list carries the 0038-seeded YDNA-Warehouse ties (≥ 36).
        let items = list.as_array().unwrap();
        assert!(items.len() >= 36);
        assert!(items.iter().any(|i| i["instrument_id"] == "A00186" && i["lab_name"] == "Family Tree DNA"));
    }

    /// Discovery proposal endpoints over HTTP: an unknown id → 404, the list → 200
    /// with an empty paginated body against an empty catalog.
    #[tokio::test]
    async fn discovery_endpoints_route_and_404() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping discovery endpoint test");
            return;
        };
        use axum::body::{to_bytes, Body};
        use axum::http::{Request, StatusCode};
        use tower::ServiceExt;

        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let state = super::AppState { pool: db.pool().clone(), key: tower_cookies::Key::generate(), oauth: None };
        let app = super::router().with_state(state);

        let r404 = app.clone().oneshot(Request::builder().uri("/api/v1/discovery/proposals/999999").body(Body::empty()).unwrap()).await.unwrap();
        assert_eq!(r404.status(), StatusCode::NOT_FOUND);

        let rl = app.clone().oneshot(Request::builder().uri("/api/v1/discovery/proposals?type=Y_DNA").body(Body::empty()).unwrap()).await.unwrap();
        assert_eq!(rl.status(), StatusCode::OK);
        let page: serde_json::Value = serde_json::from_slice(&to_bytes(rl.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(page["total"], 0);
        assert!(page["items"].as_array().unwrap().is_empty());
    }

    /// Test-type endpoints over HTTP: an unknown code → 404, the list → 200 `[]`
    /// against an unseeded catalog.
    #[tokio::test]
    async fn test_type_endpoints_route_and_404() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping test-type endpoint test");
            return;
        };
        use axum::body::{to_bytes, Body};
        use axum::http::{Request, StatusCode};
        use tower::ServiceExt;

        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let state = super::AppState { pool: db.pool().clone(), key: tower_cookies::Key::generate(), oauth: None };
        let app = super::router().with_state(state);

        let r404 = app.clone().oneshot(Request::builder().uri("/api/v1/test-types/NOPE").body(Body::empty()).unwrap()).await.unwrap();
        assert_eq!(r404.status(), StatusCode::NOT_FOUND);

        let rl = app.clone().oneshot(Request::builder().uri("/api/v1/test-types").body(Body::empty()).unwrap()).await.unwrap();
        assert_eq!(rl.status(), StatusCode::OK);
        let list: serde_json::Value = serde_json::from_slice(&to_bytes(rl.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert!(list.as_array().unwrap().is_empty());
    }
}
