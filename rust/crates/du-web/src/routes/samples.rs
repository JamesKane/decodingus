//! Public per-sample report (`/sample/:slug`) — an ExploreYourDNA-style page for
//! biosamples a curator has opted public. Identity + Y/mt haplogroup pathways +
//! origin map + sequencing/coverage + ancestry, assembled from the unified
//! biosample read path (`du_db::biosample::report`). A curator-only toggle flips
//! the `is_public` gate; curators may also preview private samples here.

use crate::auth::{Curator, MaybeUser, NavUser};
use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, patch, post};
use axum::{Form, Json, Router};
use du_db::biosample::{BiosamplePatch, HaplogroupCall, SampleReport};
use du_db::haplogroup::Pathway;
use du_domain::enums::BiosampleSource;
use du_domain::ids::SampleGuid;
use serde::Deserialize;
use uuid::Uuid;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/sample/:slug", get(report))
        .route("/curator/samples/:slug/public", post(toggle_public))
        .route("/manage/biosamples/merge", post(merge_biosamples))
        .route("/manage/biosamples/:guid", patch(patch_biosample))
}

// ── view models (all display logic lives here; templates stay logic-free) ──────

/// Distinct colors for ancestry components, cycled by index. Last is the
/// synthetic "unassigned" remainder (grey).
const ANCESTRY_PALETTE: [&str; 9] =
    ["#4e79a7", "#f28e2b", "#e15759", "#76b7b2", "#59a14f", "#edc948", "#b07aa1", "#ff9da7", "#9c755f"];
const UNASSIGNED_COLOR: &str = "#bab0ac";

struct OriginView {
    lat: f64,
    lon: f64,
}

struct PubView {
    title: String,
    href: Option<String>,
    year: String,
}

struct StepView {
    name: String,
    formed: String,
    tmrca: String,
    snps: Vec<String>,
    href: String,
}

struct PathwayView {
    /// The raw called name (None ⇒ no haplogroup call at all for this lineage).
    call: Option<String>,
    /// True when the call resolved to tree nodes (we have steps to show).
    placed: bool,
    /// True when the call is the cross-technology reconciliation consensus.
    reconciled: bool,
    /// True when the call comes from the sample's de-novo tree placement (it was a
    /// building block) rather than a published/federated call.
    from_tree: bool,
    /// Consensus reliability for a reconciled call (formatted; "" when absent).
    confidence: String,
    run_count: String,
    concordance: String,
    steps: Vec<StepView>,
}

struct SeqView {
    platform: String,
    instrument: String,
    test_type: String,
    layout: String,
    reads: String,
    read_length: String,
}

/// One downloadable academic data file (CRAM/BAM/masterVar).
struct FileView {
    name: String,
    format: String,
    size: String,
    /// Public download URL (empty string ⇒ not shown as a link).
    url: String,
    md5: String,
    aligner: String,
    reference: String,
}

/// An academic sequencing run and its data files (`genomics.sequence_*`).
struct SeqDataView {
    instrument: String,
    layout: String,
    reads: String,
    read_length: String,
    run_date: String,
    files: Vec<FileView>,
}

struct CovView {
    build: String,
    aligner: String,
    test_type: String,
    mean: String,
    pct_10x: String,
    pct_20x: String,
    pct_30x: String,
    /// Advertised spec / cohort norm shown alongside the actual depth.
    expected: String,
    norm: String,
    /// BELOW / AT / ABOVE (empty when nothing to compare against).
    conformance: String,
}

struct AncestryComp {
    label: String,
    /// Bar width as a bare number string (percent), e.g. "12.3".
    width: String,
    /// Display percentage, e.g. "12.3%".
    pct_label: String,
    color: String,
}

struct SampleView {
    display_name: String,
    accession: Option<String>,
    alias: Option<String>,
    description: Option<String>,
    source: String,
    center_name: Option<String>,
    sex: Option<String>,
    federated: bool,
    origin: Option<OriginView>,
    publications: Vec<PubView>,
    y: PathwayView,
    mt: PathwayView,
    sequencing: Vec<SeqView>,
    coverage: Vec<CovView>,
    sequence_data: Vec<SeqDataView>,
    ancestry: Vec<AncestryComp>,
    ancestry_method: Option<String>,
}

/// Human-readable byte size (e.g. "1.4 GB"), or "—". A 0 size means "unrecorded"
/// for these migrated files (ENA CRAM rows carry no byte count), not literally 0 B.
fn human_bytes(n: Option<i64>) -> String {
    let Some(n) = n.filter(|&n| n > 0) else { return "—".to_string() };
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut size = n as f64;
    let mut u = 0;
    while size >= 1024.0 && u < UNITS.len() - 1 {
        size /= 1024.0;
        u += 1;
    }
    if u == 0 {
        format!("{n} B")
    } else {
        format!("{size:.1} {}", UNITS[u])
    }
}

fn dash(v: Option<String>) -> String {
    v.filter(|s| !s.trim().is_empty()).unwrap_or_else(|| "—".to_string())
}

fn num_i64(v: Option<i64>) -> String {
    v.map(|n| n.to_string()).unwrap_or_else(|| "—".to_string())
}

fn num_i32(v: Option<i32>) -> String {
    v.map(|n| n.to_string()).unwrap_or_else(|| "—".to_string())
}

fn num_f64(v: Option<f64>, decimals: usize) -> String {
    v.map(|n| format!("{n:.decimals$}")).unwrap_or_else(|| "—".to_string())
}

fn titlecase(s: &str) -> String {
    let mut chars = s.chars();
    match chars.next() {
        Some(c) => c.to_uppercase().collect::<String>() + &chars.as_str().to_lowercase(),
        None => String::new(),
    }
}

/// Pull `(label, value)` pairs out of an ancestry JSONB array, tolerating the
/// several key spellings the federated payload may use.
fn extract_components(v: &serde_json::Value) -> Vec<(String, f64)> {
    let Some(arr) = v.as_array() else { return Vec::new() };
    let mut out = Vec::new();
    for e in arr {
        let label = ["superPopulation", "population", "name", "label", "ancestry", "group"]
            .iter()
            .find_map(|k| e.get(*k).and_then(serde_json::Value::as_str))
            .map(str::to_string);
        let value = ["percentage", "fraction", "value", "proportion", "percent"]
            .iter()
            .find_map(|k| e.get(*k).and_then(serde_json::Value::as_f64));
        if let (Some(l), Some(va)) = (label, value) {
            if va > 0.0 {
                out.push((l, va));
            }
        }
    }
    out
}

fn build_ancestry(rep: &SampleReport) -> (Vec<AncestryComp>, Option<String>) {
    let Some(anc) = &rep.ancestry else { return (Vec::new(), None) };
    // Prefer the continental rollup; fall back to sub-continental components.
    let mut pairs = extract_components(&anc.super_populations);
    if pairs.is_empty() {
        pairs = extract_components(&anc.components);
    }
    if pairs.is_empty() {
        return (Vec::new(), anc.analysis_method.clone());
    }
    // Normalize to percentages: if everything looks like a 0..1 fraction, scale.
    let max = pairs.iter().map(|(_, v)| *v).fold(0.0_f64, f64::max);
    let scale = if max <= 1.0 { 100.0 } else { 1.0 };
    pairs.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));

    let mut comps: Vec<AncestryComp> = pairs
        .iter()
        .enumerate()
        .map(|(i, (label, v))| {
            let pct = v * scale;
            AncestryComp {
                label: label.clone(),
                width: format!("{pct:.1}"),
                pct_label: format!("{pct:.1}%"),
                color: ANCESTRY_PALETTE[i % ANCESTRY_PALETTE.len()].to_string(),
            }
        })
        .collect();

    // Synthetic remainder so the stacked bar is honest/full-width.
    let total: f64 = pairs.iter().map(|(_, v)| v * scale).sum();
    if total < 99.0 {
        let rem = 100.0 - total;
        comps.push(AncestryComp {
            label: "Unassigned".to_string(),
            width: format!("{rem:.1}"),
            pct_label: format!("{rem:.1}%"),
            color: UNASSIGNED_COLOR.to_string(),
        });
    }
    (comps, anc.analysis_method.clone())
}

/// Tree-view base path for re-rooting links, by lineage.
fn tree_base(call: &HaplogroupCall) -> &'static str {
    match call.dna_type {
        du_domain::enums::DnaType::YDna => "/ytree",
        du_domain::enums::DnaType::MtDna => "/mtree",
    }
}

/// Best display name for a defining variant: canonical name, else first alias.
fn snp_label(v: &du_db::haplogroup::VariantInfo) -> Option<String> {
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

fn build_pathway(call: Option<&HaplogroupCall>, pathway: Option<Pathway>) -> PathwayView {
    let Some(call) = call else {
        return PathwayView {
            call: None,
            placed: false,
            reconciled: false,
            from_tree: false,
            confidence: String::new(),
            run_count: String::new(),
            concordance: String::new(),
            steps: Vec::new(),
        };
    };
    let base = tree_base(call);
    let steps = pathway
        .map(|p| {
            p.steps
                .into_iter()
                .map(|s| {
                    let encoded = utf8_percent_encode(&s.name);
                    StepView {
                        href: format!("{base}?root={encoded}"),
                        name: s.name,
                        formed: num_i32(s.formed_ybp),
                        tmrca: num_i32(s.tmrca_ybp),
                        snps: s.defining_snps.iter().filter_map(snp_label).collect(),
                    }
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let pct = |v: Option<f64>| v.map(|x| format!("{:.0}%", x * 100.0)).unwrap_or_default();
    PathwayView {
        call: Some(call.name.clone()),
        placed: !steps.is_empty(),
        reconciled: call.origin == du_db::biosample::HaplogroupCallOrigin::Reconciled,
        from_tree: call.origin == du_db::biosample::HaplogroupCallOrigin::TreePlacement,
        confidence: pct(call.confidence),
        run_count: call.run_count.map(|n| n.to_string()).unwrap_or_default(),
        concordance: pct(call.snp_concordance),
        steps,
    }
}

/// Minimal percent-encoding for a clade name used in a `?root=` query value.
fn utf8_percent_encode(s: &str) -> String {
    percent_encoding::utf8_percent_encode(s, percent_encoding::NON_ALPHANUMERIC).to_string()
}

impl SampleView {
    fn build(rep: SampleReport, y_path: Option<Pathway>, mt_path: Option<Pathway>) -> Self {
        let id = &rep.identity;
        let display_name = id
            .accession
            .clone()
            .or_else(|| id.alias.clone())
            .unwrap_or_else(|| id.sample_guid.0.to_string());

        let publications = rep
            .publications
            .iter()
            .map(|p| {
                let href = p
                    .url
                    .clone()
                    .or_else(|| p.doi.as_ref().map(|d| format!("https://doi.org/{d}")));
                PubView {
                    title: p.title.clone(),
                    href,
                    year: p.publication_date.map(|d| d.format("%Y").to_string()).unwrap_or_default(),
                }
            })
            .collect();

        let sequencing = rep
            .sequencing
            .iter()
            .map(|r| SeqView {
                platform: dash(r.platform_name.clone()),
                instrument: dash(r.instrument_model.clone()),
                // Prefer the standardized profile label (e.g. "WGS150 45Gbases");
                // fall back to the raw catalog code for non-yield / pre-profile records.
                test_type: dash(r.test_profile_label.clone().or_else(|| r.test_type.clone())),
                layout: dash(r.library_layout.clone()),
                reads: num_i64(r.total_reads),
                read_length: num_i32(r.read_length),
            })
            .collect();

        let coverage = rep
            .coverage
            .iter()
            .map(|c| CovView {
                build: dash(c.reference_build.clone()),
                aligner: dash(c.aligner.clone()),
                test_type: dash(c.test_profile_label.clone().or_else(|| c.test_type.clone())),
                mean: num_f64(c.mean_coverage, 1),
                pct_10x: num_f64(c.pct_10x, 1),
                pct_20x: num_f64(c.pct_20x, 1),
                pct_30x: num_f64(c.pct_30x, 1),
                expected: num_f64(c.expected_min_depth, 0),
                norm: num_f64(c.norm_median_depth, 1),
                conformance: c.conformance.clone().unwrap_or_default(),
            })
            .collect();

        let sequence_data = rep
            .sequence_data
            .iter()
            .map(|r| SeqDataView {
                instrument: dash(r.instrument.clone()),
                layout: match r.paired_end {
                    Some(true) => "PAIRED".to_string(),
                    Some(false) => "SINGLE".to_string(),
                    None => "—".to_string(),
                },
                reads: num_i64(r.reads),
                read_length: num_i32(r.read_length),
                run_date: dash(r.run_date.clone()),
                files: r
                    .files
                    .iter()
                    .map(|f| FileView {
                        name: dash(f.file_name.clone()),
                        format: dash(f.file_format.clone()),
                        size: human_bytes(f.file_size_bytes),
                        // Normalize to an absolute URL: some locations carry a scheme
                        // (https://evolbio…), others are scheme-less ENA paths
                        // (ftp.sra.ebi.ac.uk/…) that resolve fine over https.
                        url: f
                            .download_url
                            .as_deref()
                            .map(|u| if u.contains("://") { u.to_string() } else { format!("https://{u}") })
                            .unwrap_or_default(),
                        md5: dash(f.md5.clone()),
                        aligner: dash(f.aligner.clone()),
                        reference: dash(f.target_reference.clone()),
                    })
                    .collect(),
            })
            .collect();

        let (ancestry, ancestry_method) = build_ancestry(&rep);

        SampleView {
            display_name,
            accession: id.accession.clone(),
            alias: id.alias.clone(),
            description: id.description.clone(),
            source: rep.identity.source.label().to_string(),
            center_name: id.center_name.clone(),
            sex: id.sex.as_deref().map(titlecase),
            federated: id.is_federated,
            origin: id.origin.map(|o| OriginView { lat: o.lat, lon: o.lon }),
            publications,
            y: build_pathway(rep.y.as_ref(), y_path),
            mt: build_pathway(rep.mt.as_ref(), mt_path),
            sequencing,
            coverage,
            sequence_data,
            ancestry,
            ancestry_method,
        }
    }
}

// ── templates ─────────────────────────────────────────────────────────────────

#[derive(askama::Template)]
#[template(path = "samples/report.html")]
struct SampleReportTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    /// Curator preview affordances (the visibility toggle).
    is_curator: bool,
    /// Identifier used in the URL (for the toggle's form action).
    slug: String,
    /// Current visibility (drives the toggle's checked state).
    is_public: bool,
    s: SampleView,
}

#[derive(askama::Template)]
#[template(path = "samples/_public_toggle.html")]
struct PublicToggleFragment {
    t: T,
    slug: String,
    is_public: bool,
}

// ── handlers ──────────────────────────────────────────────────────────────────

async fn report(
    State(st): State<AppState>,
    locale: Locale,
    user: MaybeUser,
    Path(slug): Path<String>,
) -> Result<Response, AppError> {
    let rep = du_db::biosample::report(&st.pool, &slug)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("sample {slug}")))?;

    // Gate: private samples 404 for the public (indistinguishable from missing);
    // a signed-in curator may preview them.
    let is_curator = user.0.as_ref().map(crate::auth::Session::is_curator).unwrap_or(false);
    if !rep.identity.is_public && !is_curator {
        return Err(AppError::NotFound(format!("sample {slug}")));
    }
    let is_public = rep.identity.is_public;

    // Resolve each called haplogroup to its tree pathway (best-effort).
    let y_path = match &rep.y {
        Some(c) => Some(du_db::haplogroup::pathway(&st.pool, &c.name, c.dna_type).await?),
        None => None,
    };
    let mt_path = match &rep.mt {
        Some(c) => Some(du_db::haplogroup::pathway(&st.pool, &c.name, c.dna_type).await?),
        None => None,
    };

    let view = SampleView::build(rep, y_path, mt_path);
    Ok(html(&SampleReportTemplate {
        t: locale.t,
        next: locale.next,
        user: user.nav(),
        is_curator,
        slug,
        is_public,
        s: view,
    }))
}

#[derive(Deserialize)]
struct ToggleForm {
    /// A checkbox: present (`Some`) when checked, absent when not.
    is_public: Option<String>,
}

/// Curator-only: flip the sample's public-visibility flag, return the swapped
/// toggle fragment. RBAC enforced by the `Curator` extractor.
async fn toggle_public(
    Curator(_s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(slug): Path<String>,
    Form(f): Form<ToggleForm>,
) -> Result<Response, AppError> {
    let guid = du_db::biosample::resolve_guid(&st.pool, &slug)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("sample {slug}")))?;
    let make_public = f.is_public.is_some();
    du_db::biosample::set_public(&st.pool, guid, make_public).await?;
    Ok(html(&PublicToggleFragment { t: locale.t, slug, is_public: make_public }))
}

// ── machine-authenticated biosample field correction ─────────────────────────
// A curator/ops PATCH endpoint (the un-ported Scala curator biosample edit).
// Gated by X-API-Key == DU_CURATION_API_KEY (injected in prod from the AWS secret
// `prod/decodingus/api`), the same machine-auth as the curation-proposal intake.
// Becomes an OAuth bearer once the Edge handshake is live.

/// Require the curation/ops API key (X-API-Key == DU_CURATION_API_KEY).
fn check_api_key(headers: &HeaderMap) -> Result<(), AppError> {
    match std::env::var("DU_CURATION_API_KEY").ok().filter(|s| !s.is_empty()) {
        None => Err(AppError::Upstream("curation API not configured".into())),
        Some(expected) => {
            let provided = headers.get("x-api-key").and_then(|v| v.to_str().ok()).unwrap_or("");
            if provided == expected {
                Ok(())
            } else {
                Err(AppError::Forbidden)
            }
        }
    }
}

/// Partial biosample update. Only the fields present in the JSON body are written;
/// omitted fields are left untouched. `source` accepts the wire enum
/// (`STANDARD`/`CITIZEN`/`PGP`/`EXTERNAL`/`ANCIENT`).
#[derive(Deserialize)]
struct BiosamplePatchIn {
    source: Option<BiosampleSource>,
    is_public: Option<bool>,
    accession: Option<String>,
    alias: Option<String>,
    center_name: Option<String>,
    description: Option<String>,
}

/// `PATCH /manage/biosamples/:guid` — apply a partial field correction to one
/// biosample and return the updated row. 403 without the key, 404 for an unknown
/// or deleted guid, 422 on an accession collision.
async fn patch_biosample(
    State(st): State<AppState>,
    headers: HeaderMap,
    Path(guid): Path<Uuid>,
    Json(body): Json<BiosamplePatchIn>,
) -> Result<Response, AppError> {
    check_api_key(&headers)?;
    let p = BiosamplePatch {
        source: body.source,
        is_public: body.is_public,
        accession: body.accession,
        alias: body.alias,
        center_name: body.center_name,
        description: body.description,
    };
    if p.is_empty() {
        return Err(AppError::BadRequest("no fields to update".into()));
    }
    let guid = SampleGuid(guid);
    if !du_db::biosample::patch(&st.pool, guid, &p).await? {
        return Err(AppError::NotFound(format!("biosample {}", guid.0)));
    }
    let row = du_db::biosample::get_by_guid(&st.pool, guid)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("biosample {}", guid.0)))?;
    Ok((StatusCode::OK, Json(row)).into_response())
}

/// Merge one confirmed-duplicate biosample into another. `survivor` is the record
/// to KEEP (e.g. the publication-linked accessioned row); `merged` is tombstoned
/// after every FK to it is repointed to the survivor.
#[derive(Deserialize)]
struct MergeIn {
    survivor: Uuid,
    merged: Uuid,
    merged_by: Option<String>,
    evidence: Option<serde_json::Value>,
}

/// `POST /manage/biosamples/merge` — repoint every child FK to `survivor`, fold
/// metadata, tombstone `merged`, write a `core.biosample_merge` audit row (one
/// transaction). X-API-Key gated. 422 on a self/duplicate/missing/stale-plan
/// merge (never a silent orphan — the FK-coverage check aborts instead).
async fn merge_biosamples(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<MergeIn>,
) -> Result<Response, AppError> {
    check_api_key(&headers)?;
    let merged_by = body.merged_by.unwrap_or_else(|| "ops-merge-api".to_string());
    let evidence = body.evidence.unwrap_or_else(|| serde_json::json!({ "via": "ops merge api" }));
    let rep = du_db::dedup::merge_biosamples(&st.pool, body.survivor, body.merged, &merged_by, None, evidence)
        .await
        .map_err(|e| match e {
            du_db::DbError::Decode(msg) => AppError::BadRequest(msg),
            other => other.into(),
        })?;
    Ok((
        StatusCode::OK,
        Json(serde_json::json!({
            "survivor": rep.survivor,
            "merged": rep.merged,
            "rows_repointed": rep.rows_repointed,
            "rows_dropped": rep.rows_dropped,
            "candidates_dismissed": rep.candidates_dismissed,
        })),
    )
        .into_response())
}
