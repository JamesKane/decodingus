//! Wire DTOs for the public API (`/api/v1/*`) — response/request shapes and
//! query-param structs, decoupled from the internal `du-db`/`du-domain` types and
//! described with `utoipa`. Mapped from query results via `From`. Consumed by the
//! handlers in [`super`] and [`super::tree`].

use serde::{Deserialize, Serialize};
use utoipa::{IntoParams, ToSchema};

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

#[derive(Serialize, ToSchema, Clone)]
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
    /// Placed non-D2C sample leaves **at or below** this node (the YFull-style cumulative
    /// count). Open the node's `/y-tree/node/{name}/samples` to list them.
    pub sample_count: i64,
    /// Defining variants for this node (with multi-build coordinates). Populated only by the
    /// `/full` tree endpoints; omitted (empty) on the plain tree so existing clients are
    /// unaffected.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub variants: Vec<VariantDto>,
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

/// A non-D2C biosample placed as a leaf at or below a haplogroup node (YFull-style).
#[derive(Serialize, ToSchema)]
pub struct LeafSampleDto {
    pub sample_guid: String,
    pub accession: Option<String>,
    pub alias: Option<String>,
    /// Origin (`EXTERNAL`, `ANCIENT`, `STANDARD`, `PGP`) — never `CITIZEN` (D2C is excluded).
    pub source: String,
    /// The most recent linked publication, when the sample is paper-referenced.
    pub publication: Option<PublicationRefDto>,
}

#[derive(Serialize, ToSchema)]
pub struct PublicationRefDto {
    pub title: String,
    pub doi: Option<String>,
    pub url: Option<String>,
}

#[derive(Serialize, ToSchema)]
pub struct LeafSamplesDto {
    pub items: Vec<LeafSampleDto>,
}

impl From<du_db::tree_sample::LeafSample> for LeafSampleDto {
    fn from(s: du_db::tree_sample::LeafSample) -> Self {
        let publication = s.pub_title.map(|title| PublicationRefDto { title, doi: s.pub_doi, url: s.pub_url });
        LeafSampleDto {
            sample_guid: s.sample_guid.to_string(),
            accession: s.accession,
            alias: s.alias,
            source: s.source,
            publication,
        }
    }
}

/// Cheap cache-revalidation probe: the current tree revision + the full-tree ETag,
/// so a client can detect a newer tree without downloading it.
#[derive(Serialize, ToSchema)]
pub struct TreeVersionDto {
    /// Monotonic revision, bumped by every tree-mutating operation.
    pub revision: i64,
    /// The `ETag` of `GET /…-tree/full` at this revision (use as `If-None-Match`).
    pub etag: String,
    /// When the tree last changed (RFC 3339).
    pub updated_at: String,
}

/// A sequencing instrument resolved to its laboratory (Edge `@RG` lookup).
#[derive(Serialize, ToSchema)]
pub struct SequencerLabDto {
    /// The `@RG` instrument id (e.g. `A00123`).
    pub instrument_id: String,
    pub lab_name: String,
    /// Direct-to-consumer lab (vs. clinical/academic).
    pub is_d2c: bool,
    pub manufacturer: Option<String>,
    pub model_name: Option<String>,
    pub website_url: Option<String>,
}

impl From<du_db::sequencer::LabLookup> for SequencerLabDto {
    fn from(l: du_db::sequencer::LabLookup) -> Self {
        Self {
            instrument_id: l.instrument_id,
            lab_name: l.lab_name,
            is_d2c: l.is_d2c,
            manufacturer: l.manufacturer,
            model_name: l.model_name,
            website_url: l.website_url,
        }
    }
}

/// A defining variant of a discovery proposal (with cross-submitter support).
#[derive(Serialize, ToSchema)]
pub struct DiscoveryVariantDto {
    pub name: Option<String>,
    pub supporting_sample_count: i32,
}

/// A proposed haplogroup branch from the discovery consensus engine.
#[derive(Serialize, ToSchema)]
pub struct DiscoveryProposalDto {
    pub id: i64,
    pub proposed_name: Option<String>,
    pub parent_haplogroup: Option<String>,
    pub dna_type: Option<String>,
    pub status: String,
    /// Supporting private-variant observations.
    pub evidence_count: i32,
    /// Distinct contributing samples.
    pub submitter_count: i32,
    pub confidence: Option<f64>,
    /// Defining variants (detail only; empty in list responses).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub variants: Vec<DiscoveryVariantDto>,
}

impl From<du_db::proposal::ProposalSummary> for DiscoveryProposalDto {
    fn from(s: du_db::proposal::ProposalSummary) -> Self {
        Self {
            id: s.id,
            proposed_name: s.proposed_name,
            parent_haplogroup: s.parent_name,
            dna_type: s.dna_type,
            status: s.status,
            evidence_count: s.evidence_count,
            submitter_count: s.submitter_count,
            confidence: s.confidence,
            variants: Vec::new(),
        }
    }
}

impl DiscoveryProposalDto {
    pub(crate) fn from_detail(d: du_db::proposal::ProposalDetail) -> Self {
        let variants = d
            .variants
            .into_iter()
            .map(|v| DiscoveryVariantDto { name: v.name, supporting_sample_count: v.supporting_sample_count })
            .collect();
        Self { variants, ..Self::from(d.summary) }
    }
}

/// Query for the single-instrument lab lookup.
#[derive(Deserialize, IntoParams)]
pub(crate) struct InstrumentParams {
    /// The `@RG` instrument id to resolve, e.g. `A00123`.
    pub(crate) instrument_id: String,
}

#[derive(Serialize, ToSchema)]
pub struct CoverageBenchmarkDto {
    pub lab: Option<String>,
    pub test_type: Option<String>,
    pub library_count: i64,
    pub avg_mean_depth: Option<f64>,
    pub avg_cov_10x: Option<f64>,
    pub expected_min_depth: Option<f64>,
    /// Whether the lab's average depth meets the advertised spec (when both known).
    pub meets_spec: Option<bool>,
    /// Average depth minus the advertised spec (positive = over, negative = under).
    pub depth_delta: Option<f64>,
}

impl From<du_domain::coverage::CoverageBenchmark> for CoverageBenchmarkDto {
    fn from(c: du_domain::coverage::CoverageBenchmark) -> Self {
        // Vendor conformance: compare the lab's average against the advertised spec.
        let (meets_spec, depth_delta) = match (c.avg_mean_depth, c.expected_min_depth) {
            (Some(avg), Some(exp)) => (Some(avg >= exp), Some(avg - exp)),
            _ => (None, None),
        };
        CoverageBenchmarkDto {
            lab: c.lab,
            test_type: c.test_type,
            library_count: c.library_count,
            avg_mean_depth: c.avg_mean_depth,
            avg_cov_10x: c.avg_cov_10x,
            expected_min_depth: c.expected_min_depth,
            meets_spec,
            depth_delta,
        }
    }
}

/// A test type's definition + its empirical coverage norm.
#[derive(Serialize, ToSchema)]
pub struct TestTypeDto {
    pub code: String,
    pub display_name: String,
    pub category: String,
    pub vendor: Option<String>,
    pub target_type: Option<String>,
    pub expected_min_depth: Option<f64>,
    pub supports_haplogroup_y: bool,
    pub supports_haplogroup_mt: bool,
    pub supports_autosomal_ibd: bool,
    pub supports_ancestry: bool,
    pub typical_file_formats: Vec<String>,
    /// Federated-cohort norm: samples observed + typical depth / 30× coverage.
    pub norm_sample_count: Option<i32>,
    pub norm_median_depth: Option<f64>,
    pub norm_median_pct_30x: Option<f64>,
}

impl From<du_db::test_type::TestTypeInfo> for TestTypeDto {
    fn from(t: du_db::test_type::TestTypeInfo) -> Self {
        Self {
            code: t.code,
            display_name: t.display_name,
            category: t.category,
            vendor: t.vendor,
            target_type: t.target_type,
            expected_min_depth: t.expected_min_depth,
            supports_haplogroup_y: t.supports_haplogroup_y,
            supports_haplogroup_mt: t.supports_haplogroup_mt,
            supports_autosomal_ibd: t.supports_autosomal_ibd,
            supports_ancestry: t.supports_ancestry,
            typical_file_formats: t.typical_file_formats,
            norm_sample_count: t.norm_sample_count,
            norm_median_depth: t.norm_median_depth,
            norm_median_pct_30x: t.norm_median_pct_30x,
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

// ── per-sample report DTOs ─────────────────────────────────────────────────────

#[derive(Serialize, ToSchema)]
pub struct PathwayStepDto {
    pub name: String,
    pub formed_ybp: Option<i32>,
    pub tmrca_ybp: Option<i32>,
    pub defining_snps: Vec<String>,
}

#[derive(Serialize, ToSchema)]
pub struct HaplogroupPathwayDto {
    /// Name as called on the sample.
    pub called_name: String,
    /// Matched tree node, or null when the call isn't placed in the tree.
    pub resolved_name: Option<String>,
    pub dna_type: String,
    /// `RECONCILED` (cross-technology consensus) / `FED_CONSENSUS` / `ORIGINAL`.
    pub origin: String,
    /// Consensus confidence ∈ [0,1] (reconciled calls only).
    pub confidence: Option<f64>,
    /// Sequencing runs reconciled into the consensus.
    pub run_count: Option<i32>,
    /// SNP concordance across the reconciled runs ∈ [0,1].
    pub snp_concordance: Option<f64>,
    /// `COMPATIBLE` / `MINOR_DIVERGENCE` / `INCOMPATIBLE` …
    pub compatibility_level: Option<String>,
    /// Root → tip clades (empty when unplaced).
    pub steps: Vec<PathwayStepDto>,
}

#[derive(Serialize, ToSchema)]
pub struct SequencingRunDto {
    pub platform_name: Option<String>,
    pub instrument_model: Option<String>,
    pub test_type: Option<String>,
    pub library_layout: Option<String>,
    pub total_reads: Option<i64>,
    pub read_length: Option<i32>,
    pub mean_insert_size: Option<f64>,
}

#[derive(Serialize, ToSchema)]
pub struct CoverageSummaryDto {
    pub reference_build: Option<String>,
    pub aligner: Option<String>,
    pub mean_coverage: Option<f64>,
    pub median_coverage: Option<f64>,
    pub pct_10x: Option<f64>,
    pub pct_20x: Option<f64>,
    pub pct_30x: Option<f64>,
    pub test_type: Option<String>,
    /// Advertised minimum depth for the test type, when known.
    pub expected_min_depth: Option<f64>,
    /// Empirical cohort median depth for the test type.
    pub norm_median_depth: Option<f64>,
    /// `BELOW` / `AT` / `ABOVE` the advertised spec (or cohort norm when no spec).
    pub conformance: Option<String>,
}

#[derive(Serialize, ToSchema)]
pub struct AncestryDto {
    pub analysis_method: Option<String>,
    pub panel_type: Option<String>,
    pub confidence_level: Option<f64>,
    /// Continental rollup: `[{superPopulation, percentage}]`.
    #[schema(value_type = Object)]
    pub super_populations: serde_json::Value,
    /// Sub-continental percentages (payload shape passed through verbatim).
    #[schema(value_type = Object)]
    pub components: serde_json::Value,
}

#[derive(Serialize, ToSchema)]
pub struct SamplePublicationDto {
    pub id: i64,
    pub title: String,
    pub doi: Option<String>,
    pub url: Option<String>,
    pub publication_date: Option<chrono::NaiveDate>,
}

/// The public per-sample report (mirrors the `/sample/:slug` page).
#[derive(Serialize, ToSchema)]
pub struct SampleReportDto {
    pub sample_guid: String,
    pub source: String,
    pub accession: Option<String>,
    pub alias: Option<String>,
    pub description: Option<String>,
    pub center_name: Option<String>,
    pub sex: Option<String>,
    pub latitude: Option<f64>,
    pub longitude: Option<f64>,
    pub is_federated: bool,
    pub y_haplogroup: Option<HaplogroupPathwayDto>,
    pub mt_haplogroup: Option<HaplogroupPathwayDto>,
    pub sequencing: Vec<SequencingRunDto>,
    pub coverage: Vec<CoverageSummaryDto>,
    pub ancestry: Option<AncestryDto>,
    pub publications: Vec<SamplePublicationDto>,
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

impl RootParams {
    /// The non-empty subtree root, if any.
    pub(crate) fn root(&self) -> Option<&str> {
        self.root_haplogroup.as_deref().filter(|s| !s.is_empty())
    }
}
