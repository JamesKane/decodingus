//! Curator variant CRUD. Same HTMX two-panel write-flow as haplogroups; edits
//! the scalar fields + alias lists (common_names / rs_ids). Coordinate editing
//! is out of scope for this panel and is preserved across updates.

use crate::auth::Curator;
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use du_domain::enums::{MutationType, NamingStatus};
use du_domain::ids::VariantId;
use du_domain::variant::Aliases;
use serde::Deserialize;

const CHANGED: &str = "variant-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/variants", get(page))
        .route("/curator/variants/fragment", get(list))
        .route("/curator/variants/new", get(new_form))
        .route("/curator/variants", post(create))
        .route("/curator/variants/:id/panel", get(panel))
        .route("/curator/variants/:id/edit", get(edit_form))
        .route("/curator/variants/:id", post(update))
        .route("/curator/variants/:id", axum::routing::delete(remove))
}

fn parse_mutation(s: &str) -> MutationType {
    match s {
        "INDEL" => MutationType::Indel,
        "STR" => MutationType::Str,
        "DEL" => MutationType::Del,
        "INS" => MutationType::Ins,
        "MNP" => MutationType::Mnp,
        _ => MutationType::Snp,
    }
}
fn parse_naming(s: &str) -> NamingStatus {
    match s {
        "NAMED" => NamingStatus::Named,
        "PENDING_REVIEW" => NamingStatus::PendingReview,
        _ => NamingStatus::Unnamed,
    }
}
/// "a, b ,c" -> ["a","b","c"]
fn csv(s: Option<String>) -> Vec<String> {
    s.unwrap_or_default()
        .split(',')
        .map(|t| t.trim().to_string())
        .filter(|t| !t.is_empty())
        .collect()
}

#[derive(Deserialize)]
struct ListQuery {
    query: Option<String>,
    page: Option<i64>,
}

struct Row {
    id: i64,
    name: String,
    mutation_type: String,
    naming_status: String,
}
struct ListView {
    query: String,
    rows: Vec<Row>,
    page: i64,
    total: i64,
    total_pages: i64,
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    let result = du_db::variant::search(&st.pool, q.query.as_deref(), q.page.unwrap_or(1), 20).await?;
    Ok(ListView {
        query: q.query.clone().unwrap_or_default(),
        rows: result
            .items
            .iter()
            .map(|v| Row {
                id: v.id.0,
                name: v.canonical_name.clone(),
                mutation_type: v.mutation_type.label().to_string(),
                naming_status: v.naming_status.label().to_string(),
            })
            .collect(),
        page: result.page,
        total: result.total,
        total_pages: result.total_pages(),
    })
}

#[derive(askama::Template)]
#[template(path = "curator/variants/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    list: ListView,
}

#[derive(askama::Template)]
#[template(path = "curator/variants/list.html")]
struct ListTemplate {
    t: T,
    list: ListView,
}

async fn page(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&PageTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(crate::auth::NavUser { display_name: s.display_name, is_curator: true }),
        list,
    }))
}

async fn list(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&ListTemplate { t: locale.t, list }))
}

struct DetailView {
    id: i64,
    name: String,
    mutation_type: String,
    naming_status: String,
    common_names: String,
    rs_ids: String,
    builds: String,
}

#[derive(askama::Template)]
#[template(path = "curator/variants/detail.html")]
struct DetailTemplate {
    t: T,
    v: DetailView,
    can_delete: bool,
    error: Option<String>,
}

async fn detail_view(st: &AppState, id: VariantId) -> Result<DetailView, AppError> {
    let v = du_db::variant::get_by_id(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("variant {}", id.0)))?;
    let mut builds: Vec<&str> = v.coordinates.0.keys().map(String::as_str).collect();
    builds.sort_unstable();
    Ok(DetailView {
        id: v.id.0,
        name: v.canonical_name,
        mutation_type: v.mutation_type.label().to_string(),
        naming_status: v.naming_status.label().to_string(),
        common_names: v.aliases.common_names.join(", "),
        rs_ids: v.aliases.rs_ids.join(", "),
        builds: builds.join(", "),
    })
}

async fn render_detail(st: &AppState, t: T, id: VariantId, error: Option<String>) -> Result<Response, AppError> {
    let v = detail_view(st, id).await?;
    let can_delete = !du_db::variant::is_referenced(&st.pool, id).await?;
    Ok(html(&DetailTemplate { t, v, can_delete, error }))
}

async fn panel(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    render_detail(&st, locale.t, VariantId(id), None).await
}

#[derive(askama::Template)]
#[template(path = "curator/variants/form.html")]
struct FormTemplate {
    t: T,
    action: String,
    is_edit: bool,
    id: i64,
    name: String,
    mutation_type: String,
    naming_status: String,
    common_names: String,
    rs_ids: String,
}

async fn new_form(_c: Curator, locale: Locale) -> Response {
    html(&FormTemplate {
        t: locale.t,
        action: "/curator/variants".into(),
        is_edit: false,
        id: 0,
        name: String::new(),
        mutation_type: "SNP".into(),
        naming_status: "UNNAMED".into(),
        common_names: String::new(),
        rs_ids: String::new(),
    })
}

async fn edit_form(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let d = detail_view(&st, VariantId(id)).await?;
    Ok(html(&FormTemplate {
        t: locale.t,
        action: format!("/curator/variants/{id}"),
        is_edit: true,
        id,
        name: d.name,
        mutation_type: d.mutation_type,
        naming_status: d.naming_status,
        common_names: d.common_names,
        rs_ids: d.rs_ids,
    }))
}

#[derive(Deserialize)]
struct VariantForm {
    name: String,
    mutation_type: Option<String>,
    naming_status: Option<String>,
    common_names: Option<String>,
    rs_ids: Option<String>,
}

fn aliases_from(form: &VariantForm) -> Aliases {
    Aliases {
        common_names: csv(form.common_names.clone()),
        rs_ids: csv(form.rs_ids.clone()),
        ..Default::default()
    }
}

fn changed(body: Response) -> Response {
    (HxHeaders::new().trigger(CHANGED), body).into_response()
}

async fn create(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Form(f): Form<VariantForm>,
) -> Result<Response, AppError> {
    if f.name.trim().is_empty() {
        return Err(AppError::BadRequest("name is required".into()));
    }
    let id = du_db::variant::create(
        &st.pool,
        f.name.trim(),
        parse_mutation(f.mutation_type.as_deref().unwrap_or("SNP")),
        parse_naming(f.naming_status.as_deref().unwrap_or("UNNAMED")),
        &aliases_from(&f),
    )
    .await?;
    Ok(changed(render_detail(&st, locale.t, id, None).await?))
}

async fn update(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<VariantForm>,
) -> Result<Response, AppError> {
    if f.name.trim().is_empty() {
        return Err(AppError::BadRequest("name is required".into()));
    }
    du_db::variant::update(
        &st.pool,
        VariantId(id),
        f.name.trim(),
        parse_mutation(f.mutation_type.as_deref().unwrap_or("SNP")),
        parse_naming(f.naming_status.as_deref().unwrap_or("UNNAMED")),
        &aliases_from(&f),
    )
    .await?;
    Ok(changed(render_detail(&st, locale.t, VariantId(id), None).await?))
}

async fn remove(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let vid = VariantId(id);
    if du_db::variant::is_referenced(&st.pool, vid).await? {
        let msg = locale.t.get("var.deleteBlocked").to_string();
        return render_detail(&st, locale.t, vid, Some(msg)).await;
    }
    du_db::variant::delete(&st.pool, vid).await?;

    #[derive(askama::Template)]
    #[template(path = "curator/variants/empty.html")]
    struct Empty {
        t: T,
    }
    Ok(changed(html(&Empty { t: locale.t })))
}
