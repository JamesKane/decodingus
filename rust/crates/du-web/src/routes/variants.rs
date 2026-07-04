//! Public variant browser. The browser page embeds the first results page inline
//! (no load round-trip); search/pagination and the detail panel are HTMX
//! fragments targeting `#variants-table` / `#detail-panel`.

use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::response::Response;
use axum::routing::get;
use axum::Router;
use du_db::Page;
use du_domain::ids::VariantId;
use du_domain::variant::Variant;
use serde::Deserialize;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/variants", get(browser))
        .route("/variants/list", get(list))
        .route("/variants/detail/:id", get(detail))
}

#[derive(Deserialize)]
struct ListQuery {
    query: Option<String>,
    page: Option<i64>,
    page_size: Option<i64>,
}

/// A flattened variant for the list table (templates stay logic-free).
struct RowView {
    id: i64,
    name: String,
    mutation_type: String,
    naming_status: String,
    builds: String,
}

impl RowView {
    fn from(v: &Variant) -> Self {
        let mut builds: Vec<&str> = v.coordinates.0.keys().map(String::as_str).collect();
        builds.sort_unstable();
        RowView {
            id: v.id.0,
            name: v.canonical_name.clone(),
            mutation_type: v.mutation_type.label().to_string(),
            naming_status: v.naming_status.label().to_string(),
            builds: builds.join(", "),
        }
    }
}

/// Shared list-fragment view data (also embedded by the browser page).
struct ListView {
    query: String,
    rows: Vec<RowView>,
    page: i64,
    page_size: i64,
    total: i64,
    total_pages: i64,
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    let page_num = q.page.unwrap_or(1);
    let page_size = q.page_size.unwrap_or(25);
    let result: Page<Variant> =
        du_db::variant::search(&st.pool, q.query.as_deref(), page_num, page_size).await?;
    Ok(ListView {
        query: q.query.clone().unwrap_or_default(),
        rows: result.items.iter().map(RowView::from).collect(),
        page: result.page,
        page_size: result.page_size,
        total: result.total,
        total_pages: result.total_pages(),
    })
}

#[derive(askama::Template)]
#[template(path = "variants/browser.html")]
struct BrowserTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    list: ListView,
}

#[derive(askama::Template)]
#[template(path = "variants/list.html")]
struct ListTemplate {
    t: T,
    list: ListView,
}

struct CoordView {
    build: String,
    contig: String,
    position: i64,
    change: Option<String>,
}

/// A tree branch this variant defines, flattened for the template.
struct BranchView {
    name: String,
    /// Link to the branch on the relevant tree page.
    tree_url: String,
    low_confidence: bool,
    /// The defining variant's name, shown only when it differs from the viewed variant
    /// (the placement is carried by a same-site sibling row).
    via: Option<String>,
}

#[derive(askama::Template)]
#[template(path = "variants/detail.html")]
struct DetailTemplate {
    t: T,
    name: String,
    mutation_type: String,
    naming_status: String,
    common_names: Vec<String>,
    rs_ids: Vec<String>,
    coords: Vec<CoordView>,
    branches: Vec<BranchView>,
}

async fn browser(
    State(st): State<AppState>,
    locale: Locale,
    user: crate::auth::MaybeUser,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&BrowserTemplate { t: locale.t, next: locale.next, user: user.nav(), list }))
}

async fn list(
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&ListTemplate { t: locale.t, list }))
}

async fn detail(
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let v = du_db::variant::get_by_id(&st.pool, VariantId(id))
        .await?
        .ok_or_else(|| AppError::NotFound(format!("variant {id}")))?;

    let mut coords: Vec<CoordView> = v
        .coordinates
        .0
        .iter()
        .map(|(build, c)| CoordView {
            build: build.clone(),
            contig: c.contig.clone(),
            position: c.position,
            change: match (&c.ancestral, &c.derived) {
                (Some(anc), Some(der)) => Some(format!("{anc}>{der}")),
                _ => None,
            },
        })
        .collect();
    coords.sort_by(|a, b| a.build.cmp(&b.build));

    // Which tree branch(es) this SNP is assigned to. Match by hs1 site (the tree is
    // hs1-native), so a catalog-named SNP still surfaces the placement its de-novo
    // coordinate-named sibling carries.
    let site = v
        .coordinates
        .get(du_domain::enums::ReferenceBuild::Hs1)
        .map(|c| serde_json::json!({ "hs1": { "contig": c.contig, "position": c.position } }));
    let variant_name = v.canonical_name.clone();
    let branches = du_db::variant::tree_branches(&st.pool, VariantId(id), site)
        .await?
        .into_iter()
        .map(|b| {
            let base = if b.dna_type == "MT_DNA" { "/mtree" } else { "/ytree" };
            BranchView {
                tree_url: format!("{base}?root={}", b.haplogroup_name),
                via: (b.via_name != variant_name).then_some(b.via_name),
                name: b.haplogroup_name,
                low_confidence: b.low_confidence,
            }
        })
        .collect();

    Ok(html(&DetailTemplate {
        t: locale.t,
        name: v.canonical_name,
        mutation_type: v.mutation_type.label().to_string(),
        naming_status: v.naming_status.label().to_string(),
        common_names: v.aliases.common_names,
        rs_ids: v.aliases.rs_ids,
        coords,
        branches,
    }))
}
