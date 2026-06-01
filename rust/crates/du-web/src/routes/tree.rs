//! Public Y/MT haplogroup tree navigation. `/ytree` and `/mtree` are full pages
//! whose `#tree-container` lazy-loads a fragment; clicking a node swaps the
//! fragment and pushes the URL (HATEOAS navigation, no client routing).

use crate::error::AppError;
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Query, State};
use axum::response::Response;
use axum::routing::get;
use axum::Router;
use du_domain::enums::DnaType;
use serde::Deserialize;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/ytree", get(ytree_page))
        .route("/mtree", get(mtree_page))
        .route("/ytree/fragment", get(ytree_fragment))
        .route("/mtree/fragment", get(mtree_fragment))
}

#[derive(Deserialize)]
struct RootQuery {
    root: Option<String>,
}

#[derive(askama::Template)]
#[template(path = "tree/page.html")]
struct TreePageTemplate {
    title: &'static str,
    base_path: &'static str,
    root: Option<String>,
}

struct NodeView {
    name: String,
    formed_ybp: Option<i32>,
}

#[derive(askama::Template)]
#[template(path = "tree/fragment.html")]
struct FragmentTemplate {
    base_path: &'static str,
    current: Option<NodeView>,
    nodes: Vec<NodeView>,
}

async fn ytree_page(Query(q): Query<RootQuery>) -> Response {
    html(&TreePageTemplate { title: "Y-DNA Tree", base_path: "/ytree", root: q.root })
}

async fn mtree_page(Query(q): Query<RootQuery>) -> Response {
    html(&TreePageTemplate { title: "mtDNA Tree", base_path: "/mtree", root: q.root })
}

async fn ytree_fragment(st: State<AppState>, q: Query<RootQuery>) -> Result<Response, AppError> {
    fragment(st, q, DnaType::YDna, "/ytree").await
}

async fn mtree_fragment(st: State<AppState>, q: Query<RootQuery>) -> Result<Response, AppError> {
    fragment(st, q, DnaType::MtDna, "/mtree").await
}

async fn fragment(
    State(st): State<AppState>,
    Query(q): Query<RootQuery>,
    dna_type: DnaType,
    base_path: &'static str,
) -> Result<Response, AppError> {
    let to_view = |h: du_domain::haplogroup::Haplogroup| NodeView {
        name: h.name,
        formed_ybp: h.formed_ybp,
    };

    let (current, nodes) = match q.root.as_deref().filter(|s| !s.is_empty()) {
        None => (None, du_db::haplogroup::roots(&st.pool, dna_type).await?),
        Some(name) => {
            let cur = du_db::haplogroup::get_by_name(&st.pool, name, dna_type)
                .await?
                .ok_or_else(|| AppError::NotFound(format!("haplogroup {name}")))?;
            let kids = du_db::haplogroup::children(&st.pool, cur.id).await?;
            (Some(cur), kids)
        }
    };

    Ok(html(&FragmentTemplate {
        base_path,
        current: current.map(to_view),
        nodes: nodes.into_iter().map(to_view).collect(),
    }))
}
