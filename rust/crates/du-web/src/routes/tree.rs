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
use crate::tree_layout::{self, InNode, Laid, Orientation};
use crate::auth::Curator;
use axum::extract::{Path, Query, State};
use axum::http::header::{HeaderMap, HeaderValue, COOKIE, SET_COOKIE};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};
use du_db::haplogroup::WindowNode;
use du_domain::enums::DnaType;
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
    provenance: Option<Provenance>,
    variants: Vec<VariantRow>,
    /// Placed non-D2C sample leaves at or below this node (capped for the sidebar).
    samples: Vec<LeafRow>,
    /// How many more placed samples exist beyond the shown `samples` (0 ⇒ all shown).
    samples_more: i64,
}

/// One placed sample row in the sidebar (label + optional paper citation).
struct LeafRow {
    label: String,
    source: String,
    citation: Option<String>,
}

/// Max leaf rows rendered in the sidebar before collapsing to an "+N more" note.
const SIDEBAR_SAMPLE_CAP: usize = 50;

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
    formed_ybp: Option<i32>,
    tmrca_ybp: Option<i32>,
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

    // Window + nesting + layout. Cumulative leaf counts roll up over the whole tree, so a
    // window-boundary node still reflects samples placed below the visible depth.
    let window = du_db::haplogroup::subtree_window(&st.pool, dna_type, &root_name, depth).await?;
    let counts = du_db::tree_sample::cumulative_counts(&st.pool, dna_type).await?;
    let laid = build_root(&window, &counts).and_then(|root| tree_layout::layout(Some(&root), orientation));

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
            // Back-mutation: this branch's derived state is the SNP's ancestral allele.
            let back_mutation =
                matches!((&v.link_derived, coord_ancestral(&v.coordinates)), (Some(d), Some(a)) if *d == a);
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

    // Branch provenance: source, cross-source aliases, last-updated, backbone, age.
    let provenance = du_db::haplogroup::get_by_name(&st.pool, &name, dna_type).await?.map(|h| {
        let p = &h.provenance;
        Provenance {
            source: h.source.unwrap_or_else(|| "—".into()),
            aliases: p
                .get("aliases")
                .and_then(serde_json::Value::as_array)
                .map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_string)).collect())
                .unwrap_or_default(),
            // Stored like "2025-05-24T13:58:48…[Etc/UTC]" — keep the date.
            updated: p.get("source_updated").and_then(|v| v.as_str()).and_then(|s| s.split('T').next()).map(str::to_string),
            backbone: p.get("backbone_source").is_some(),
            formed_ybp: h.formed_ybp,
            tmrca_ybp: h.tmrca_ybp,
        }
    });

    // Placed non-D2C sample leaves at or below this node (capped for the sidebar).
    let mut leaves = du_db::tree_sample::samples_under(&st.pool, &name, dna_type).await?;
    let samples_more = (leaves.len() as i64 - SIDEBAR_SAMPLE_CAP as i64).max(0);
    leaves.truncate(SIDEBAR_SAMPLE_CAP);
    let samples: Vec<LeafRow> = leaves
        .into_iter()
        .map(|s| {
            let label = s.accession.or(s.alias).unwrap_or_else(|| s.sample_guid.to_string());
            let citation = s.pub_title.map(|t| match s.pub_doi {
                Some(doi) => format!("{t} ({doi})"),
                None => t,
            });
            LeafRow { label, source: s.source, citation }
        })
        .collect();

    Ok(html(&SnpSidebar { t: locale.t, name, provenance, variants, samples, samples_more }))
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

/// Nest the flat window into an `InNode` tree rooted at the depth-0 node. `counts` is the
/// cumulative placed-sample count per node id.
fn build_root(window: &[WindowNode], counts: &HashMap<i64, i64>) -> Option<InNode> {
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
    Some(to_innode(root, &children_of, counts))
}

fn to_innode(n: &WindowNode, children_of: &HashMap<i64, Vec<&WindowNode>>, counts: &HashMap<i64, i64>) -> InNode {
    let children = children_of
        .get(&n.id)
        .map(|kids| kids.iter().map(|c| to_innode(c, children_of, counts)).collect())
        .unwrap_or_default();
    InNode {
        name: n.name.clone(),
        variant_count: n.variant_count,
        sample_count: counts.get(&n.id).copied().unwrap_or(0),
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
    use axum::body::{to_bytes, Body};
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

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

        // The cladogram node carries the cumulative count "1 samples".
        let svg = body(state.clone(), "/ytree?root=R-M269").await;
        assert!(svg.contains("1 samples"), "node shows the placed-sample count");

        // The sidebar lists the paper sample (with source) and not the D2C one.
        let side = body(state.clone(), "/ytree/snp/R-M269").await;
        assert!(side.contains("EX-1") && side.contains("EXTERNAL"), "sidebar lists the placed sample");
        assert!(!side.contains("CIT-1"), "D2C sample never surfaces");

        // The curator triage queue is Curator-gated (unauth → redirect to login).
        let guarded = crate::routes::app(state)
            .oneshot(Request::builder().uri("/manage/tree-sample/unplaced").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(guarded.status(), StatusCode::SEE_OTHER, "unplaced triage requires Curator");
    }
}
