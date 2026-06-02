//! Public Y/MT haplogroup tree views — two server-rendered SVG cladogram modes
//! (horizontal & vertical, [`crate::tree_layout`]) replacing the old one-level
//! list. One handler per lineage serves both the full page and the HTMX
//! `#tree-container` fragment (history-restore/boosted nav get the full page).
//!
//! A depth-bounded window (`WINDOW_DEPTH` levels below the current root) keeps
//! any view renderable; every node re-roots on click. Orientation is chosen by
//! the `tree_orient` cookie, flipped by an `?orient=` toggle that also persists
//! the cookie. Search resolves a haplogroup name *or* a defining variant.

use crate::error::AppError;
use crate::htmx::{HxHeaders, HxRequest};
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use crate::tree_layout::{self, InNode, Laid, Orientation};
use axum::extract::{Path, Query, State};
use axum::http::header::{HeaderMap, HeaderValue, COOKIE, SET_COOKIE};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use du_db::haplogroup::WindowNode;
use du_domain::enums::DnaType;
use serde::Deserialize;
use std::collections::HashMap;

const TARGET: &str = "tree-container";
/// Levels rendered below the current display root.
const WINDOW_DEPTH: i32 = 4;
const ORIENT_COOKIE: &str = "tree_orient";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/ytree", get(ytree))
        .route("/mtree", get(mtree))
        .route("/ytree/snp/:name", get(ysnp))
        .route("/mtree/snp/:name", get(mtsnp))
}

#[derive(Deserialize)]
struct TreeQuery {
    /// Haplogroup name or defining-variant query to center on.
    root: Option<String>,
    /// Orientation override ("h"/"v"); also persisted to the cookie.
    orient: Option<String>,
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
    variants: Vec<VariantRow>,
}

struct VariantRow {
    name: String,
    mutation_type: String,
    aliases: Vec<String>,
    coordinates: Vec<String>,
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

    // Window + nesting + layout.
    let window = du_db::haplogroup::subtree_window(&st.pool, dna_type, &root_name, WINDOW_DEPTH).await?;
    let laid = build_root(&window).and_then(|root| tree_layout::layout(Some(&root), orientation));

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
        .map(|v| VariantRow {
            name: v.canonical_name,
            mutation_type: v.mutation_type,
            aliases: json_str_list(&v.aliases),
            coordinates: coord_list(&v.coordinates),
        })
        .collect();
    Ok(html(&SnpSidebar { t: locale.t, name, variants }))
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

/// Nest the flat window into an `InNode` tree rooted at the depth-0 node.
fn build_root(window: &[WindowNode]) -> Option<InNode> {
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
    Some(to_innode(root, &children_of))
}

fn to_innode(n: &WindowNode, children_of: &HashMap<i64, Vec<&WindowNode>>) -> InNode {
    let children = children_of
        .get(&n.id)
        .map(|kids| kids.iter().map(|c| to_innode(c, children_of)).collect())
        .unwrap_or_default();
    InNode {
        name: n.name.clone(),
        variant_count: n.variant_count,
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

/// Render coordinates JSONB (`{refGenome: {contig,position,ref,alt}}`) as
/// `"contig:pos ref>alt [b38]"` strings.
fn coord_list(v: &serde_json::Value) -> Vec<String> {
    let Some(obj) = v.as_object() else { return vec![] };
    obj.iter()
        .filter_map(|(genome, c)| {
            let contig = c.get("contig").and_then(|x| x.as_str()).unwrap_or("?");
            let pos = c.get("position").and_then(serde_json::Value::as_i64)?;
            let r = c.get("ref").and_then(|x| x.as_str()).unwrap_or("");
            let a = c.get("alt").and_then(|x| x.as_str()).unwrap_or("");
            Some(format!("{contig}:{pos} {r}>{a} [{}]", short_genome(genome)))
        })
        .collect()
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
