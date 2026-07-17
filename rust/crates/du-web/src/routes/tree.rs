//! Public Y/MT haplogroup tree views — two server-rendered SVG cladogram modes
//! (horizontal & vertical, [`crate::tree_layout`]) replacing the old one-level
//! list. One handler per lineage serves both the full page and the HTMX
//! `#tree-container` fragment (history-restore/boosted nav get the full page).
//!
//! A depth-bounded window (default `DEFAULT_DEPTH` levels below the current root,
//! overridable via a client-persisted `?depth=`) keeps any view renderable;
//! every node re-roots on click. Orientation is chosen by the `tree_orient`
//! cookie, flipped by an `?orient=` toggle that also persists the cookie. Search
//! resolves a haplogroup name *or* a defining variant.

use crate::error::AppError;
use crate::htmx::{HxHeaders, HxRequest};
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use crate::tree_layout::{self, InNode, Laid, Orientation, SampleTip};
use crate::auth::Curator;
use axum::extract::{Path, Query, State};
use axum::http::header::{HeaderMap, HeaderValue, COOKIE, SET_COOKIE};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};
use du_db::haplogroup::WindowNode;
use du_domain::enums::DnaType;
use percent_encoding::{utf8_percent_encode, NON_ALPHANUMERIC};
use serde::Deserialize;
use std::collections::HashMap;

const TARGET: &str = "tree-container";
/// Levels rendered below the current display root when none is requested.
const DEFAULT_DEPTH: i32 = 4;
/// Clamp range for a client-supplied `?depth=` (the selector persists the choice
/// in localStorage and injects it into every tree request — see page.html).
const MIN_DEPTH: i32 = 1;
const MAX_DEPTH: i32 = 8;
/// Depths offered in the selector.
const DEPTH_OPTIONS: [i32; 6] = [2, 3, 4, 5, 6, 7];
const ORIENT_COOKIE: &str = "tree_orient";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/ytree", get(ytree))
        .route("/mtree", get(mtree))
        .route("/ytree/snp/:name", get(ysnp))
        .route("/mtree/snp/:name", get(mtsnp))
        // Clade "Geography & Time" infographic: HTML fragment (map + time axis)
        // and its clade-scoped GeoJSON, lazy-loaded from the SNP off-canvas.
        .route("/ytree/node/:name/geo", get(y_clade_geo))
        .route("/mtree/node/:name/geo", get(mt_clade_geo))
        .route("/ytree/node/:name/geo-data", get(y_clade_geo_data))
        .route("/mtree/node/:name/geo-data", get(mt_clade_geo_data))
        // Curator triage for sample leaves whose published call didn't resolve to a node.
        .route("/manage/tree-sample/unplaced", get(unplaced))
        .route("/manage/tree-sample/place", post(place))
}

/// `Y_DNA` (default) / `MT_DNA` from a `type` query param.
fn dna_param(s: Option<&str>) -> DnaType {
    match s {
        Some("MT_DNA") => DnaType::MtDna,
        _ => DnaType::YDna,
    }
}

#[derive(Deserialize)]
struct UnplacedQuery {
    #[serde(rename = "type")]
    dna_type: Option<String>,
}

/// Curator-gated: the queue of UNPLACED published calls (no node matched) for triage.
async fn unplaced(_cur: Curator, State(st): State<AppState>, Query(q): Query<UnplacedQuery>) -> Result<Json<Value>, AppError> {
    let dna = dna_param(q.dna_type.as_deref());
    let items: Vec<Value> = du_db::tree_sample::unplaced(&st.pool, dna, 500)
        .await?
        .into_iter()
        .map(|r| json!({
            "sample_guid": r.sample_guid,
            "call_text": r.call_text,
            "accession": r.accession,
            "alias": r.alias,
        }))
        .collect();
    Ok(Json(json!({ "items": items })))
}

#[derive(Deserialize)]
struct PlaceBody {
    sample_guid: uuid::Uuid,
    #[serde(rename = "type")]
    dna_type: Option<String>,
    /// The haplogroup name (or defining SNP) to pin this sample under.
    haplogroup: String,
}

/// Curator-gated: manually place an UNPLACED sample under a chosen node.
async fn place(_cur: Curator, State(st): State<AppState>, Json(b): Json<PlaceBody>) -> Result<Json<Value>, AppError> {
    let dna = dna_param(b.dna_type.as_deref());
    if du_db::tree_sample::place_sample(&st.pool, b.sample_guid, dna, &b.haplogroup).await? {
        Ok(Json(json!({ "sample_guid": b.sample_guid, "haplogroup": b.haplogroup, "status": "PLACED" })))
    } else {
        Err(AppError::NotFound(format!("could not place {} under {}", b.sample_guid, b.haplogroup)))
    }
}

#[derive(Deserialize)]
struct TreeQuery {
    /// Haplogroup name or defining-variant query to center on.
    root: Option<String>,
    /// Orientation override ("h"/"v"); also persisted to the cookie.
    orient: Option<String>,
    /// Levels to render below the root (clamped); the client persists it.
    depth: Option<i32>,
}

// ── View models ────────────────────────────────────────────────────────────

struct Crumb {
    name: String,
    href: String,
}

#[derive(askama::Template)]
#[template(path = "tree/page.html")]
struct TreePageTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    title: String,
    base_path: &'static str,
    root_name: String,
    query: String,
    orientation: Orientation,
    /// (depth value, is-current) for the selector options.
    depth_options: Vec<(i32, bool)>,
    crumbs: Vec<Crumb>,
    laid: Option<Laid>,
}

#[derive(askama::Template)]
#[template(path = "tree/svg.html")]
struct SvgFragment {
    t: T,
    base_path: &'static str,
    crumbs: Vec<Crumb>,
    laid: Option<Laid>,
}

#[derive(askama::Template)]
#[template(path = "tree/snp_sidebar.html")]
struct SnpSidebar {
    t: T,
    name: String,
    /// URL of this node's sample-map panel fragment (lazy-loaded on click).
    geo_href: String,
    provenance: Option<Provenance>,
    /// The consolidated age block: the branch TMRCA/formed on a time axis (with any
    /// ancient-DNA anchors). Replaces the old textual formed/TMRCA provenance rows.
    age: Option<AgeBlock>,
    variants: Vec<VariantRow>,
    /// Reconstructed ancestral Y-STR motif (phylogenetic ASR): the markers that
    /// mutated on the branch leading into this node (parent→node), headlined.
    str_changes: Vec<StrChangeRow>,
    /// Total markers in the node's reconstructed ancestral haplotype (context for
    /// the change count; 0 ⇒ no STR evidence in the subtree).
    str_motif_markers: usize,
    /// Count of placed non-D2C sample leaves at or below this node (shown instead of the list).
    sample_count: i64,
}

/// One Y-STR mutation along the branch leading into this node, e.g. DYS393 12→13.
struct StrChangeRow {
    marker: String,
    from: i32,
    to: i32,
}

struct VariantRow {
    name: String,
    mutation_type: String,
    aliases: Vec<String>,
    coordinates: Vec<String>,
    /// This branch's ancestral>derived transition (ASR), e.g. "T>G".
    transition: Option<String>,
    /// SNP occurs on other branches too (homoplasy).
    recurrent: bool,
    /// This branch reverted to the ancestral state (derived == variant ancestral).
    back_mutation: bool,
}

/// Where a branch came from — increasingly important as multiple source trees
/// (ISOGG, decoding-us, ytree.net, …) fold into one node.
struct Provenance {
    /// Originating source (the `tree.haplogroup.source` column).
    source: String,
    /// Cross-source alternate names (`provenance.aliases`) — e.g. the ISOGG path
    /// name vs. a decoding-us `R1b-…` name for the same branch.
    aliases: Vec<String>,
    /// When the source last updated this node (date only), if recorded.
    updated: Option<String>,
    /// Curator-adopted backbone (a `provenance.backbone_source` marker).
    backbone: bool,
}

/// The node's age, consolidated into one block: the numeric formed/TMRCA/CI plus the
/// time-axis visualization (which also carries any dated ancient-DNA anchors). This is
/// the single home for the age — the SNP sidebar no longer repeats it as text rows and
/// the sample-map panel no longer draws its own copy of the axis.
struct AgeBlock {
    formed_ybp: Option<i32>,
    tmrca_ybp: Option<i32>,
    /// 95% CI (low, high ybp) of the combined TMRCA, when an estimate exists.
    tmrca_ci: Option<(i32, i32)>,
    /// Number of tester samples whose private SNPs measured this node's age (the
    /// SNP-Poisson "population size"). `None`/0 for nodes dated only by propagation.
    sample_count: Option<i32>,
    /// ybp→calendar for the TMRCA (e.g. "2550 BC"), for a glanceable caption.
    tmrca_cal: Option<String>,
    axis: TimeAxis,
}

// ── Handlers ────────────────────────────────────────────────────────────────

async fn ytree(
    st: State<AppState>,
    hx: HxRequest,
    locale: Locale,
    user: crate::auth::MaybeUser,
    headers: HeaderMap,
    q: Query<TreeQuery>,
) -> Result<Response, AppError> {
    render_tree(st, hx, locale, user, headers, q, DnaType::YDna, "/ytree", "Y", "tree.title.y").await
}

async fn mtree(
    st: State<AppState>,
    hx: HxRequest,
    locale: Locale,
    user: crate::auth::MaybeUser,
    headers: HeaderMap,
    q: Query<TreeQuery>,
) -> Result<Response, AppError> {
    render_tree(st, hx, locale, user, headers, q, DnaType::MtDna, "/mtree", "L", "tree.title.mt").await
}

async fn ysnp(st: State<AppState>, locale: Locale, name: Path<String>) -> Result<Response, AppError> {
    snp_sidebar(st, locale, name, DnaType::YDna).await
}

async fn mtsnp(st: State<AppState>, locale: Locale, name: Path<String>) -> Result<Response, AppError> {
    snp_sidebar(st, locale, name, DnaType::MtDna).await
}

#[allow(clippy::too_many_arguments)]
async fn render_tree(
    State(st): State<AppState>,
    hx: HxRequest,
    locale: Locale,
    user: crate::auth::MaybeUser,
    headers: HeaderMap,
    Query(q): Query<TreeQuery>,
    dna_type: DnaType,
    base_path: &'static str,
    default_root: &str,
    title_key: &str,
) -> Result<Response, AppError> {
    // Orientation: explicit ?orient= wins (and will be persisted), else cookie.
    let orientation = match q.orient.as_deref() {
        Some(o) => Orientation::parse(o),
        None => cookie(&headers, ORIENT_COOKIE)
            .map(|s| Orientation::parse(&s))
            .unwrap_or(Orientation::Horizontal),
    };

    // Resolve the display root: search query → name-or-variant, else the lineage
    // default, else the first actual root (covers empty/renamed roots).
    let query = q.root.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let root_name = match query {
        Some(qy) => du_db::haplogroup::resolve_name_or_variant(&st.pool, qy, dna_type)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("haplogroup or variant {qy}")))?,
        None => default_root_name(&st.pool, dna_type, default_root).await?,
    };

    // Depth: client-supplied ?depth= (persisted in localStorage), clamped.
    let depth = q.depth.unwrap_or(DEFAULT_DEPTH).clamp(MIN_DEPTH, MAX_DEPTH);

    // Window + nesting + layout. Each visible node's directly-placed samples hang off it as
    // YFull-style leaf tips (capped per node).
    let window = du_db::haplogroup::subtree_window(&st.pool, dna_type, &root_name, depth).await?;
    let node_ids: Vec<i64> = window.iter().map(|n| n.id).collect();
    let mut samples_by_node: HashMap<i64, Vec<tree_layout::SampleTip>> = HashMap::new();
    for (id, label, slug) in du_db::tree_sample::direct_labels(&st.pool, dna_type, &node_ids).await? {
        if is_uuid_label(&label) {
            continue; // hide private (UUID-accession) biosample leaves
        }
        samples_by_node.entry(id).or_default().push(tree_layout::SampleTip { label, slug });
    }
    // Collapse private auto-named intermediate nodes (`…:n<number>`) so they vanish from the
    // public tree, re-parenting their children/samples onto the nearest named ancestor.
    let window = hide_private_nodes(window, &mut samples_by_node);
    let laid = build_root(&window, &samples_by_node).and_then(|root| tree_layout::layout(Some(&root), orientation));

    // Breadcrumbs: ancestors (root→parent) + the current node (no link).
    let crumbs = build_crumbs(&st.pool, dna_type, base_path, &root_name).await?;

    // Persist orientation when it was toggled via the query param.
    let set_cookie = q.orient.is_some().then(|| orient_cookie(orientation));

    let mut resp = if hx.wants_fragment_for(TARGET) {
        let push = format!("{base_path}?root={root_name}");
        let frag = SvgFragment { t: locale.t, base_path, crumbs, laid };
        (HxHeaders::new().push_url(push), html(&frag)).into_response()
    } else {
        let page = TreePageTemplate {
            t: locale.t,
            next: locale.next,
            user: user.nav(),
            title: locale.t.get(title_key).to_string(),
            base_path,
            root_name,
            query: query.unwrap_or_default().to_string(),
            orientation,
            depth_options: DEPTH_OPTIONS.iter().map(|&d| (d, d == depth)).collect(),
            crumbs,
            laid,
        };
        html(&page)
    };
    if let Some(c) = set_cookie {
        if let Ok(hv) = HeaderValue::from_str(&c) {
            resp.headers_mut().insert(SET_COOKIE, hv);
        }
    }
    Ok(resp)
}

async fn snp_sidebar(
    State(st): State<AppState>,
    locale: Locale,
    Path(name): Path<String>,
    dna_type: DnaType,
) -> Result<Response, AppError> {
    let variants = du_db::haplogroup::variants_of(&st.pool, &name, dna_type)
        .await?
        .into_iter()
        .map(|v| {
            let transition = match (&v.link_ancestral, &v.link_derived) {
                (Some(a), Some(d)) => Some(format!("{a}>{d}")),
                _ => None,
            };
            // Back-mutation: this branch's derived state is the SNP's ancestral allele —
            // but only meaningful for a RECURRENT SNP (one that occurs on another branch,
            // so there is something to revert). A single-origin SNP has nothing to back-
            // mutate from, so a derived==ancestral coincidence there is a catalog strand
            // artifact (e.g. Y18975, stored reverse-complemented by the inverted-block
            // liftover: link T>A vs catalog A>T), not biology. Gate on `recurrent`.
            let back_mutation = v.recurrent
                && matches!((&v.link_derived, coord_ancestral(&v.coordinates)), (Some(d), Some(a)) if *d == a);
            let aliases = json_str_list(&v.aliases);
            // UNNAMED variants (homoplasy collisions) fall back to an alias.
            let name = v
                .canonical_name
                .or_else(|| aliases.first().cloned())
                .unwrap_or_else(|| "(unnamed)".into());
            VariantRow {
                name,
                mutation_type: v.mutation_type,
                aliases: aliases.clone(),
                coordinates: coord_list(&v.coordinates),
                transition,
                recurrent: v.recurrent,
                back_mutation,
            }
        })
        .collect();

    // Branch provenance (source, aliases, last-updated, backbone) and the single age
    // block: the numeric formed/TMRCA/CI consolidated onto the time axis, which also
    // carries any dated ancient-DNA anchors in the subtree.
    let hg = du_db::haplogroup::get_by_name(&st.pool, &name, dna_type).await?;
    let (provenance, age) = match hg {
        Some(h) => {
            // Age-estimate inputs behind the displayed TMRCA: the combined 95% CI and
            // the SNP-Poisson tester count ("population size").
            let estimates = du_db::age::estimates_for(&st.pool, h.id.0).await?;
            let tmrca_ci = estimates
                .iter()
                .find(|e| e.method == "COMBINED")
                .and_then(|e| e.ci_low_ybp.zip(e.ci_high_ybp));
            // Population behind the age = tester samples in the whole subtree (direct
            // + propagated), not just this node's direct testers. Only meaningful when
            // an age was actually computed from SNP data.
            let sample_count = if estimates.iter().any(|e| e.method == "SNP_POISSON") {
                let n = du_db::age::subtree_tester_count(&st.pool, h.id.0).await?;
                (n > 0).then_some(n as i32)
            } else {
                None
            };
            let p = &h.provenance;
            let provenance = Provenance {
                source: h.source.clone().unwrap_or_else(|| "—".into()),
                aliases: p
                    .get("aliases")
                    .and_then(serde_json::Value::as_array)
                    .map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_string)).collect())
                    .unwrap_or_default(),
                // Stored like "2025-05-24T13:58:48…[Etc/UTC]" — keep the date.
                updated: p.get("source_updated").and_then(|v| v.as_str()).and_then(|s| s.split('T').next()).map(str::to_string),
                backbone: p.get("backbone_source").is_some(),
            };
            // Consolidated age: the time axis is the single representation. Build it from
            // this node's formed/TMRCA and the subtree's ancient anchors; present only
            // when the node actually has an age to plot.
            let anchors = du_db::age::ancient_anchors_under(&st.pool, &name, dna_type).await?;
            let age = build_time_axis(h.formed_ybp, h.tmrca_ybp, tmrca_ci, &anchors).map(|axis| AgeBlock {
                formed_ybp: h.formed_ybp,
                tmrca_ybp: h.tmrca_ybp,
                tmrca_ci,
                sample_count,
                tmrca_cal: h.tmrca_ybp.map(tree_layout::format_ybp),
                axis,
            });
            (Some(provenance), age)
        }
        None => (None, None),
    };

    // Reconstructed ancestral Y-STR motif (Y only) — the per-marker mutations on
    // the branch leading into this node (parent→node), plus the motif size.
    let (str_changes, str_motif_markers) = if matches!(dna_type, DnaType::YDna) {
        let asr = du_db::ystr::branch_str_asr(&st.pool, &name).await?;
        let changes = asr
            .iter()
            .filter(|m| m.changed())
            .map(|m| StrChangeRow {
                marker: m.marker_name.clone(),
                from: m.parent_value.unwrap_or(m.ancestral_value),
                to: m.ancestral_value,
            })
            .collect();
        (changes, asr.len())
    } else {
        (Vec::new(), 0)
    };

    // Count of placed non-D2C sample leaves at or below this node (shown instead of the list).
    let sample_count = du_db::tree_sample::count_under(&st.pool, &name, dna_type).await?;

    let geo_href = format!(
        "{}/node/{}/geo",
        base_path_for(dna_type),
        utf8_percent_encode(&name, NON_ALPHANUMERIC)
    );
    Ok(html(&SnpSidebar {
        t: locale.t,
        name,
        geo_href,
        provenance,
        age,
        variants,
        str_changes,
        str_motif_markers,
        sample_count,
    }))
}

/// The public tree base path for a lineage.
fn base_path_for(dna_type: DnaType) -> &'static str {
    match dna_type {
        DnaType::YDna => "/ytree",
        DnaType::MtDna => "/mtree",
    }
}

// ── Clade "Geography & Time" infographic ─────────────────────────────────────

async fn y_clade_geo(st: State<AppState>, locale: Locale, name: Path<String>) -> Result<Response, AppError> {
    clade_geo(st, locale, name, DnaType::YDna).await
}

async fn mt_clade_geo(st: State<AppState>, locale: Locale, name: Path<String>) -> Result<Response, AppError> {
    clade_geo(st, locale, name, DnaType::MtDna).await
}

async fn y_clade_geo_data(st: State<AppState>, name: Path<String>) -> Result<Json<Value>, AppError> {
    clade_geo_data(st, name, DnaType::YDna).await
}

async fn mt_clade_geo_data(st: State<AppState>, name: Path<String>) -> Result<Json<Value>, AppError> {
    clade_geo_data(st, name, DnaType::MtDna).await
}

/// Clade-scoped GeoJSON of placed samples' donor coordinates, styled client-side by
/// `source` (ancient vs modern). Mirrors [`crate::routes::maps`]'s FeatureCollection.
async fn clade_geo_data(
    State(st): State<AppState>,
    Path(name): Path<String>,
    dna_type: DnaType,
) -> Result<Json<Value>, AppError> {
    let points = du_db::tree_sample::geo_points_under(&st.pool, &name, dna_type).await?;
    let features: Vec<Value> = points
        .into_iter()
        .map(|p| {
            json!({
                "type": "Feature",
                "geometry": { "type": "Point", "coordinates": [p.lon, p.lat] },
                "properties": {
                    "accession": p.accession,
                    "source": p.source.label(),
                    "ancient": matches!(p.source, du_domain::enums::BiosampleSource::Ancient),
                },
            })
        })
        .collect();
    Ok(Json(json!({ "type": "FeatureCollection", "features": features })))
}

/// The "Geography & Time" panel fragment: a Leaflet map target (client fetches the
/// GeoJSON above) plus a server-rendered SVG time axis of the clade's TMRCA/CI with any
/// dated ancient-DNA anchors in the subtree.
async fn clade_geo(
    State(st): State<AppState>,
    locale: Locale,
    Path(name): Path<String>,
    dna_type: DnaType,
) -> Result<Response, AppError> {
    let base_path = base_path_for(dna_type);
    // Validate the node exists (404 otherwise); the age/time axis now lives in the SNP
    // sidebar, so this panel only needs the map.
    if du_db::haplogroup::get_by_name(&st.pool, &name, dna_type).await?.is_none() {
        return Err(AppError::NotFound(format!("no haplogroup {name}")));
    }

    // Total placed samples vs. those with a mappable donor coordinate.
    let total = du_db::tree_sample::count_under(&st.pool, &name, dna_type).await?;
    let mapped = du_db::tree_sample::geo_points_under(&st.pool, &name, dna_type)
        .await?
        .len() as i64;

    let enc = utf8_percent_encode(&name, NON_ALPHANUMERIC).to_string();
    Ok(html(&GeoPanel {
        t: locale.t,
        name,
        geo_data_url: format!("{base_path}/node/{enc}/geo-data"),
        mapped,
        total,
    }))
}

#[derive(askama::Template)]
#[template(path = "tree/geo_panel.html")]
struct GeoPanel {
    t: T,
    name: String,
    /// Clade-scoped GeoJSON endpoint the Leaflet island fetches.
    geo_data_url: String,
    /// Placed samples with a mappable donor coordinate, and the total placed count —
    /// shown as "N of M mapped" so sparse geocoord coverage is explicit.
    mapped: i64,
    total: i64,
}

// ── Time-axis geometry ───────────────────────────────────────────────────────
// A horizontal ybp axis (present at right → past at left) with the clade's branch,
// its TMRCA + 95% CI band, and any dated ancient-DNA anchors in the subtree. Computed
// in Rust (like `tree_layout`) so the template just emits an inline <svg>.

const AXIS_W: f64 = 560.0;
const AXIS_H: f64 = 150.0;
const AXIS_PAD_L: f64 = 40.0;
const AXIS_PAD_R: f64 = 16.0;
const AXIS_BASELINE_Y: f64 = 96.0;
const AXIS_ANCHOR_Y: f64 = 52.0;

// All coordinates are precomputed here so the template does no arithmetic.
struct TimeAxis {
    width: f64,
    height: f64,
    baseline_y: f64,
    /// Top of the tick gridlines.
    grid_y1: f64,
    /// Branch segment: older end → present.
    branch_x1: f64,
    branch_x2: f64,
    axis_label_x: f64,
    axis_label_y: f64,
    ci: Option<CiBand>,
    tmrca: Option<AxisMark>,
    formed: Option<FormedMark>,
    ticks: Vec<AxisTick>,
    anchors: Vec<AxisAnchor>,
}

struct CiBand {
    x: f64,
    width: f64,
    y: f64,
    height: f64,
    label: String,
}

struct AxisMark {
    x: f64,
    label: String,
}

/// The "formed" tick: a short vertical mark at `x` from `y1` to `y2`.
struct FormedMark {
    x: f64,
    y1: f64,
    y2: f64,
    label: String,
}

struct AxisTick {
    x: f64,
    label: String,
    label_y: f64,
}

struct AxisAnchor {
    /// Uncertainty whisker endpoints (older, younger) and its y; equal x when unknown.
    wx1: f64,
    wx2: f64,
    wy: f64,
    /// Precomputed diamond marker path centered on the anchor's date.
    diamond_d: String,
    name: String,
    label: String,
}

fn r1(v: f64) -> f64 {
    (v * 10.0).round() / 10.0
}

/// A "nice" round step (1/2/2.5/5 × 10ⁿ) at least `rough`.
fn nice_step(rough: f64) -> f64 {
    if rough <= 0.0 {
        return 1.0;
    }
    let mag = 10f64.powf(rough.log10().floor());
    for m in [1.0, 2.0, 2.5, 5.0, 10.0] {
        if m * mag >= rough {
            return m * mag;
        }
    }
    10.0 * mag
}

/// ybp → "k"-abbreviated years-before-present tick label.
fn tick_label(ybp: i64) -> String {
    if ybp == 0 {
        "0".into()
    } else if ybp % 1000 == 0 {
        format!("{}k", ybp / 1000)
    } else {
        ybp.to_string()
    }
}

fn build_time_axis(
    formed_ybp: Option<i32>,
    tmrca_ybp: Option<i32>,
    tmrca_ci: Option<(i32, i32)>,
    anchors: &[du_db::age::AncientAnchor],
) -> Option<TimeAxis> {
    // Need at least a formed or TMRCA age to anchor the branch.
    let older = formed_ybp.or(tmrca_ybp)?;

    let anchor_pts: Vec<(i32, i32, &du_db::age::AncientAnchor)> = anchors
        .iter()
        .filter_map(|a| {
            let ybp = a.ybp()?;
            let unc = a.uncertainty_years.unwrap_or(0.0).max(0.0) as i32;
            Some((ybp, unc, a))
        })
        .collect();

    // Domain = 0 (present) .. oldest thing shown, rounded to a nice ceiling.
    let raw_max = std::iter::once(older as f64)
        .chain(tmrca_ci.map(|(_, hi)| hi as f64))
        .chain(anchor_pts.iter().map(|(ybp, unc, _)| (ybp + unc) as f64))
        .fold(0.0_f64, f64::max)
        .max(1.0);
    let step = nice_step(raw_max * 1.08 / 4.0);
    let domain_max = ((raw_max * 1.08 / step).ceil() * step).max(step);

    let x0 = AXIS_PAD_L;
    let x1 = AXIS_W - AXIS_PAD_R;
    let x = |ybp: f64| -> f64 { r1(x1 - (ybp / domain_max) * (x1 - x0)) };

    let ci = tmrca_ci.map(|(lo, hi)| {
        let bx = x(hi as f64);
        CiBand {
            x: bx,
            width: r1(x(lo as f64) - bx),
            y: AXIS_BASELINE_Y - 7.0,
            height: 14.0,
            label: format!("{lo}–{hi} ybp"),
        }
    });

    let mut ticks = Vec::new();
    let step_i = step as i64;
    let mut t = 0i64;
    while (t as f64) <= domain_max + 0.5 {
        ticks.push(AxisTick {
            x: x(t as f64),
            label: tick_label(t),
            label_y: AXIS_BASELINE_Y + 22.0,
        });
        t += step_i.max(1);
    }

    let anchors_out = anchor_pts
        .iter()
        .map(|(ybp, unc, a)| {
            let ax = x(*ybp as f64);
            let ay = AXIS_ANCHOR_Y;
            AxisAnchor {
                wx1: x((ybp + unc) as f64),
                wx2: x((ybp - unc).max(0) as f64),
                wy: ay,
                diamond_d: format!(
                    "M {ax} {} L {} {ay} L {ax} {} L {} {ay} Z",
                    r1(ay - 5.0),
                    r1(ax + 5.0),
                    r1(ay + 5.0),
                    r1(ax - 5.0),
                ),
                name: a.haplogroup_name.clone(),
                label: tree_layout::format_ybp(*ybp),
            }
        })
        .collect();

    Some(TimeAxis {
        width: AXIS_W,
        height: AXIS_H,
        baseline_y: AXIS_BASELINE_Y,
        grid_y1: 16.0,
        branch_x1: x(older as f64),
        branch_x2: x(0.0),
        axis_label_x: AXIS_W / 2.0,
        axis_label_y: AXIS_H - 4.0,
        ci,
        tmrca: tmrca_ybp.map(|v| AxisMark { x: x(v as f64), label: tree_layout::format_ybp(v) }),
        formed: formed_ybp.map(|v| FormedMark {
            x: x(v as f64),
            y1: AXIS_BASELINE_Y - 9.0,
            y2: AXIS_BASELINE_Y + 9.0,
            label: tree_layout::format_ybp(v),
        }),
        ticks,
        anchors: anchors_out,
    })
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/// Read a cookie value from the request `Cookie` header.
fn cookie(headers: &HeaderMap, key: &str) -> Option<String> {
    headers
        .get(COOKIE)?
        .to_str()
        .ok()?
        .split(';')
        .filter_map(|kv| kv.trim().split_once('='))
        .find(|(k, _)| *k == key)
        .map(|(_, v)| v.to_string())
}

fn orient_cookie(o: Orientation) -> String {
    format!("{ORIENT_COOKIE}={}; Path=/; Max-Age=31536000; SameSite=Lax", o.code())
}

/// The lineage's display root: the configured default if present, else the
/// first real root (so the page still renders for renamed/empty lineages).
async fn default_root_name(pool: &du_db::PgPool, dna_type: DnaType, default_root: &str) -> Result<String, AppError> {
    if du_db::haplogroup::get_by_name(pool, default_root, dna_type).await?.is_some() {
        return Ok(default_root.to_string());
    }
    let roots = du_db::haplogroup::roots(pool, dna_type).await?;
    roots
        .into_iter()
        .next()
        .map(|h| h.name)
        .ok_or_else(|| AppError::NotFound(format!("no {default_root} tree loaded")))
}

/// Max sample tips drawn directly under one node before collapsing to a "+N" overflow tip.
const NODE_TIP_CAP: usize = 8;

/// A private auto-named node: `<name>:n<digits>` — the de-novo loader's placeholder for a branch
/// with no public SNP name. Meant to be invisible in the public tree.
fn is_private_node(name: &str) -> bool {
    name.rsplit_once(":n")
        .is_some_and(|(_, tail)| !tail.is_empty() && tail.bytes().all(|b| b.is_ascii_digit()))
}

/// A biosample leaf label that is a bare UUID (a private D2C subject id) rather than a public
/// accession like `HG01890` — hidden from the tree.
fn is_uuid_label(s: &str) -> bool {
    s.len() == 36
        && s.bytes()
            .enumerate()
            .all(|(i, b)| if matches!(i, 8 | 13 | 18 | 23) { b == b'-' } else { b.is_ascii_hexdigit() })
}

/// Drop private ([`is_private_node`]) nodes from the window, re-parenting their descendants and
/// merging their placed samples onto the nearest non-private ancestor — so the private node
/// disappears while public samples beneath it still render under its named parent.
fn hide_private_nodes(window: Vec<WindowNode>, samples: &mut HashMap<i64, Vec<SampleTip>>) -> Vec<WindowNode> {
    use std::collections::HashSet;
    let private: HashSet<i64> = window.iter().filter(|n| is_private_node(&n.name)).map(|n| n.id).collect();
    if private.is_empty() {
        return window;
    }
    let parent_of: HashMap<i64, Option<i64>> = window.iter().map(|n| (n.id, n.parent_id)).collect();
    // Climb from `start` past any private ids to the nearest non-private ancestor.
    let named_ancestor = |start: Option<i64>| -> Option<i64> {
        let mut cur = start;
        while let Some(id) = cur {
            if private.contains(&id) {
                cur = parent_of.get(&id).copied().flatten();
            } else {
                return Some(id);
            }
        }
        None
    };
    // Move each private node's samples up to its nearest named ancestor.
    for &pid in &private {
        if let Some(labels) = samples.remove(&pid) {
            if let Some(anc) = named_ancestor(parent_of.get(&pid).copied().flatten()) {
                samples.entry(anc).or_default().extend(labels);
            }
        }
    }
    window
        .into_iter()
        .filter(|n| !private.contains(&n.id))
        .map(|mut n| {
            if n.parent_id.is_some_and(|p| private.contains(&p)) {
                n.parent_id = named_ancestor(n.parent_id);
            }
            n
        })
        .collect()
}

/// Nest the flat window into an `InNode` tree rooted at the depth-0 node. `samples` maps a node
/// id to its directly-placed sample labels (rendered as leaf tips).
fn build_root(window: &[WindowNode], samples: &HashMap<i64, Vec<SampleTip>>) -> Option<InNode> {
    let mut children_of: HashMap<i64, Vec<&WindowNode>> = HashMap::new();
    let mut root: Option<&WindowNode> = None;
    for n in window {
        match n.parent_id {
            Some(p) => children_of.entry(p).or_default().push(n),
            None => root = Some(n),
        }
    }
    // Window root is the depth-0 node; fall back to min-depth if shapes differ.
    let root = root.or_else(|| window.iter().min_by_key(|n| n.depth))?;
    Some(to_innode(root, &children_of, samples))
}

fn to_innode(n: &WindowNode, children_of: &HashMap<i64, Vec<&WindowNode>>, samples: &HashMap<i64, Vec<SampleTip>>) -> InNode {
    let children = children_of
        .get(&n.id)
        .map(|kids| kids.iter().map(|c| to_innode(c, children_of, samples)).collect())
        .unwrap_or_default();
    let all = samples.get(&n.id).map(Vec::as_slice).unwrap_or(&[]);
    let tips: Vec<SampleTip> = all.iter().take(NODE_TIP_CAP).cloned().collect();
    let sample_overflow = (all.len() as i64 - tips.len() as i64).max(0);
    InNode {
        name: n.name.clone(),
        variant_count: n.variant_count,
        samples: tips,
        sample_overflow,
        is_backbone: n.is_backbone,
        is_recent: n.is_recent,
        formed_ybp: n.formed_ybp,
        tmrca_ybp: n.tmrca_ybp,
        has_hidden: n.has_hidden,
        children,
    }
}

async fn build_crumbs(
    pool: &du_db::PgPool,
    dna_type: DnaType,
    base_path: &str,
    root_name: &str,
) -> Result<Vec<Crumb>, AppError> {
    let Some(cur) = du_db::haplogroup::get_by_name(pool, root_name, dna_type).await? else {
        return Ok(vec![]);
    };
    let mut crumbs: Vec<Crumb> = du_db::haplogroup::ancestors(pool, cur.id)
        .await?
        .into_iter()
        .map(|(_, name)| Crumb { href: format!("{base_path}?root={name}"), name })
        .collect();
    // Current node closes the trail (no link).
    crumbs.push(Crumb { name: root_name.to_string(), href: String::new() });
    Ok(crumbs)
}

/// Flatten a `{key: [..strings..]}` aliases JSONB into a display list.
fn json_str_list(v: &serde_json::Value) -> Vec<String> {
    let Some(obj) = v.as_object() else { return vec![] };
    obj.values()
        .filter_map(serde_json::Value::as_array)
        .flatten()
        .filter_map(|s| s.as_str().map(str::to_string))
        .collect()
}

/// Render coordinates JSONB as `"contig:pos anc>der [b38]"` strings — the locus
/// plus the SNP's ancestral→derived states (the reference genome is not the
/// phylogenetic root, so these are `ancestral`/`derived`, not ref/alt). When no
/// alleles are recorded, shows just the locus.
fn coord_list(v: &serde_json::Value) -> Vec<String> {
    let allele = |c: &serde_json::Value, k: &str| {
        c.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string()
    };
    let Some(obj) = v.as_object() else { return vec![] };
    obj.iter()
        .filter_map(|(genome, c)| {
            let contig = c.get("contig").and_then(|x| x.as_str()).unwrap_or("?");
            let pos = c.get("position").and_then(serde_json::Value::as_i64)?;
            let anc = allele(c, "ancestral");
            let der = allele(c, "derived");
            let alleles = if anc.is_empty() && der.is_empty() { String::new() } else { format!(" {anc}>{der}") };
            Some(format!("{contig}:{pos}{alleles} [{}]", short_genome(genome)))
        })
        .collect()
}

/// The SNP's representative ancestral allele (prefer GRCh38, else any build), used
/// to flag back-mutations against a branch's derived state.
fn coord_ancestral(v: &serde_json::Value) -> Option<String> {
    let obj = v.as_object()?;
    let pick = obj.get("GRCh38").or_else(|| obj.values().next())?;
    pick.get("ancestral").and_then(|x| x.as_str()).map(str::to_string)
}

fn short_genome(g: &str) -> &str {
    if g.contains("GRCh37") || g.contains("hg19") {
        "b37"
    } else if g.contains("GRCh38") || g.contains("hg38") {
        "b38"
    } else if g.contains("T2T") || g.contains("CHM13") || g == "hs1" {
        "hs1"
    } else {
        g
    }
}

#[cfg(test)]
mod tests {
    use super::{hide_private_nodes, is_private_node, is_uuid_label, SampleTip};
    use axum::body::{to_bytes, Body};
    use axum::http::{Request, StatusCode};
    use du_db::haplogroup::WindowNode;
    use std::collections::HashMap;
    use tower::ServiceExt;

    fn win(id: i64, name: &str, parent: Option<i64>, depth: i32) -> WindowNode {
        WindowNode {
            id,
            name: name.into(),
            parent_id: parent,
            depth,
            formed_ybp: None,
            tmrca_ybp: None,
            is_backbone: false,
            is_recent: false,
            variant_count: 0,
            has_hidden: false,
        }
    }

    #[test]
    fn private_node_and_uuid_predicates() {
        assert!(is_private_node("A-L987:n0"));
        assert!(is_private_node("R-M269:n12"));
        assert!(!is_private_node("A-L987")); // real name
        assert!(!is_private_node("foo:notes")); // ':n' but not digits
        assert!(!is_private_node("BT")); // no ':n'

        assert!(is_uuid_label("00192078-fecd-4194-8a0d-09ce47c9c138"));
        assert!(!is_uuid_label("HG01890")); // public accession
        assert!(!is_uuid_label("A-L987")); // node name
    }

    #[test]
    fn hide_private_collapses_children_and_samples() {
        // A-L987 (10) → A-L987:n0 (11, private) → { A-child (12), samples on 11 }
        let window = vec![
            win(10, "A-L987", None, 0),
            win(11, "A-L987:n0", Some(10), 1),
            win(12, "A-M1", Some(11), 2),
        ];
        let mut samples: HashMap<i64, Vec<SampleTip>> = HashMap::new();
        let tip = |s: &str| SampleTip { label: s.into(), slug: s.into() };
        samples.insert(11, vec![tip("HG02982"), tip("HG02984")]);

        let out = hide_private_nodes(window, &mut samples);
        // Private node dropped.
        assert!(out.iter().all(|n| n.name != "A-L987:n0"));
        // Its child re-parented onto the named ancestor A-L987 (10).
        let child = out.iter().find(|n| n.id == 12).unwrap();
        assert_eq!(child.parent_id, Some(10));
        // Its samples merged onto A-L987 (10).
        assert_eq!(samples.get(&10).map(Vec::len), Some(2));
        assert!(!samples.contains_key(&11));
    }

    /// The cladogram shows a node's cumulative placed-sample count, and the SNP sidebar lists
    /// the placed (non-D2C) samples with their citation.
    #[tokio::test]
    async fn cladogram_shows_sample_count_and_sidebar_leaves() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping cladogram test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();
        sqlx::query("INSERT INTO tree.haplogroup (name, haplogroup_type) VALUES ('R-M269', 'Y_DNA'::core.dna_type)")
            .execute(&pool)
            .await
            .unwrap();
        // One paper sample (placed) + one D2C sample (excluded), both calling R-M269.
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

        let body = |state: crate::state::AppState, uri: &'static str| async move {
            let resp = crate::routes::app(state)
                .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
                .await
                .unwrap();
            assert_eq!(resp.status(), StatusCode::OK);
            String::from_utf8(to_bytes(resp.into_body(), usize::MAX).await.unwrap().to_vec()).unwrap()
        };

        // The placed sample hangs off the node as a leaf tip (label rendered in the SVG).
        let svg = body(state.clone(), "/ytree?root=R-M269").await;
        assert!(svg.contains("tree-tip"), "the cladogram renders sample leaf tips");
        assert!(svg.contains("EX-1"), "the placed sample's label appears as a tip");
        assert!(!svg.contains("CIT-1"), "the D2C sample is not a tip");

        // The sidebar shows the placed-sample count (YFull-style: a count under the node,
        // not a per-sample list). The D2C sample is excluded at placement, so the count is
        // just the one paper sample and the D2C accession never surfaces.
        let side = body(state.clone(), "/ytree/snp/R-M269").await;
        assert!(side.contains("Placed samples"), "sidebar shows the placed-sample count section");
        assert!(!side.contains("CIT-1"), "D2C sample never surfaces");

        // The curator triage queue is Curator-gated (unauth → redirect to login).
        let guarded = crate::routes::app(state)
            .oneshot(Request::builder().uri("/manage/tree-sample/unplaced").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(guarded.status(), StatusCode::SEE_OTHER, "unplaced triage requires Curator");
    }
}
