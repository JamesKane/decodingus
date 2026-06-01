//! Public Y/MT haplogroup tree navigation, unified into ONE handler per lineage
//! (plan §4): a normal request returns the full page with the current level
//! embedded inline; an HTMX swap of `#tree-container` returns just the fragment
//! and sets `HX-Push-Url` server-side. History-restore and boosted navigations
//! get the full page.

use crate::error::AppError;
use crate::htmx::{HxHeaders, HxRequest};
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Query, State};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use du_domain::enums::DnaType;
use serde::Deserialize;

const TARGET: &str = "tree-container";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/ytree", get(ytree))
        .route("/mtree", get(mtree))
}

#[derive(Deserialize)]
struct RootQuery {
    root: Option<String>,
}

struct NodeView {
    name: String,
    formed_ybp: Option<i32>,
}

#[derive(askama::Template)]
#[template(path = "tree/page.html")]
struct TreePageTemplate {
    t: T,
    next: String,
    title: String,
    base_path: &'static str,
    current: Option<NodeView>,
    nodes: Vec<NodeView>,
}

#[derive(askama::Template)]
#[template(path = "tree/fragment.html")]
struct FragmentTemplate {
    t: T,
    base_path: &'static str,
    current: Option<NodeView>,
    nodes: Vec<NodeView>,
}

async fn ytree(
    st: State<AppState>,
    hx: HxRequest,
    locale: Locale,
    q: Query<RootQuery>,
) -> Result<Response, AppError> {
    render_tree(st, hx, locale, q, DnaType::YDna, "/ytree", "tree.title.y").await
}

async fn mtree(
    st: State<AppState>,
    hx: HxRequest,
    locale: Locale,
    q: Query<RootQuery>,
) -> Result<Response, AppError> {
    render_tree(st, hx, locale, q, DnaType::MtDna, "/mtree", "tree.title.mt").await
}

async fn render_tree(
    State(st): State<AppState>,
    hx: HxRequest,
    locale: Locale,
    Query(q): Query<RootQuery>,
    dna_type: DnaType,
    base_path: &'static str,
    title_key: &str,
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
    let current = current.map(to_view);
    let nodes: Vec<NodeView> = nodes.into_iter().map(to_view).collect();

    if hx.wants_fragment_for(TARGET) {
        // Server drives the URL bar for the swap.
        let push = match &current {
            Some(n) => format!("{base_path}?root={}", n.name),
            None => base_path.to_string(),
        };
        let frag = FragmentTemplate { t: locale.t, base_path, current, nodes };
        Ok((HxHeaders::new().push_url(push), html(&frag)).into_response())
    } else {
        let page = TreePageTemplate {
            t: locale.t,
            next: locale.next,
            title: locale.t.get(title_key).to_string(),
            base_path,
            current,
            nodes,
        };
        Ok(html(&page))
    }
}
