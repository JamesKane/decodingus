//! Curator genome-region CRUD. The multi-build `coordinates` and `properties`
//! are JSONB documents, edited here as JSON textareas (parse-validated on save,
//! re-rendering the form with an error on invalid JSON).

use crate::auth::Curator;
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use crate::extract::Query;
use axum::extract::{Path, State};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;

const CHANGED: &str = "region-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/regions", get(page))
        .route("/curator/regions/fragment", get(list))
        .route("/curator/regions/new", get(new_form))
        .route("/curator/regions", post(create))
        .route("/curator/regions/:id/panel", get(panel))
        .route("/curator/regions/:id/edit", get(edit_form))
        .route("/curator/regions/:id", post(update))
        .route("/curator/regions/:id", axum::routing::delete(remove))
}

#[derive(Deserialize)]
struct ListQuery {
    query: Option<String>,
    page: Option<i64>,
}

struct Row {
    id: i64,
    region_type: String,
    name: String,
    builds: String,
}
struct ListView {
    query: String,
    rows: Vec<Row>,
    page: i64,
    total: i64,
    total_pages: i64,
}

/// Top-level keys of a JSONB object, comma-joined (the build labels).
fn keys_of(v: &serde_json::Value) -> String {
    v.as_object()
        .map(|o| o.keys().cloned().collect::<Vec<_>>().join(", "))
        .unwrap_or_default()
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    let result =
        du_db::genome_region::list_paginated(&st.pool, q.query.as_deref(), None, q.page.unwrap_or(1), 20).await?;
    Ok(ListView {
        query: q.query.clone().unwrap_or_default(),
        rows: result
            .items
            .iter()
            .map(|r| Row {
                id: r.id,
                region_type: r.region_type.clone(),
                name: r.name.clone(),
                builds: keys_of(&r.coordinates),
            })
            .collect(),
        page: result.page,
        total: result.total,
        total_pages: result.total_pages(),
    })
}

#[derive(askama::Template)]
#[template(path = "curator/regions/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/regions/list.html")]
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

#[derive(askama::Template)]
#[template(path = "curator/regions/detail.html")]
struct DetailTemplate {
    t: T,
    id: i64,
    region_type: String,
    name: String,
    coordinates: String,
    properties: String,
}

fn pretty(v: &serde_json::Value) -> String {
    serde_json::to_string_pretty(v).unwrap_or_else(|_| "{}".into())
}

async fn detail(st: &AppState, t: T, id: i64) -> Result<Response, AppError> {
    let r = du_db::genome_region::get_by_id(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("region {id}")))?;
    Ok(html(&DetailTemplate {
        t,
        id: r.id,
        region_type: r.region_type,
        name: r.name,
        coordinates: pretty(&r.coordinates),
        properties: pretty(&r.properties),
    }))
}

async fn panel(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    detail(&st, locale.t, id).await
}

#[derive(askama::Template)]
#[template(path = "curator/regions/form.html")]
struct FormTemplate {
    t: T,
    action: String,
    is_edit: bool,
    id: i64,
    region_type: String,
    name: String,
    coordinates: String,
    properties: String,
    error: Option<String>,
}

async fn new_form(_c: Curator, locale: Locale) -> Response {
    html(&FormTemplate {
        t: locale.t,
        action: "/curator/regions".into(),
        is_edit: false,
        id: 0,
        region_type: String::new(),
        name: String::new(),
        coordinates: "{\n  \"GRCh38\": { \"contig\": \"chr1\", \"start\": 0, \"end\": 0 }\n}".into(),
        properties: "{}".into(),
        error: None,
    })
}

async fn edit_form(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let r = du_db::genome_region::get_by_id(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("region {id}")))?;
    Ok(html(&FormTemplate {
        t: locale.t,
        action: format!("/curator/regions/{id}"),
        is_edit: true,
        id,
        region_type: r.region_type,
        name: r.name,
        coordinates: pretty(&r.coordinates),
        properties: pretty(&r.properties),
        error: None,
    }))
}

#[derive(Deserialize)]
struct RegionForm {
    region_type: String,
    name: String,
    coordinates: String,
    properties: String,
}

fn changed(body: Response) -> Response {
    (HxHeaders::new().trigger(CHANGED), body).into_response()
}

/// Parse the two JSON fields; on failure re-render the form with an error.
fn parse_json(
    t: T,
    action: String,
    is_edit: bool,
    id: i64,
    f: &RegionForm,
) -> Result<(serde_json::Value, serde_json::Value), Box<Response>> {
    let coords = serde_json::from_str::<serde_json::Value>(&f.coordinates);
    let props = serde_json::from_str::<serde_json::Value>(&f.properties);
    match (coords, props) {
        (Ok(c), Ok(p)) => Ok((c, p)),
        (c, p) => {
            let mut msg = String::new();
            if let Err(e) = &c {
                msg = format!("coordinates: {e}");
            } else if let Err(e) = &p {
                msg = format!("properties: {e}");
            }
            Err(Box::new(html(&FormTemplate {
                t,
                action,
                is_edit,
                id,
                region_type: f.region_type.clone(),
                name: f.name.clone(),
                coordinates: f.coordinates.clone(),
                properties: f.properties.clone(),
                error: Some(msg),
            })))
        }
    }
}

async fn create(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Form(f): Form<RegionForm>,
) -> Result<Response, AppError> {
    if f.name.trim().is_empty() || f.region_type.trim().is_empty() {
        return Err(AppError::BadRequest("region_type and name are required".into()));
    }
    let (coords, props) = match parse_json(locale.t, "/curator/regions".into(), false, 0, &f) {
        Ok(v) => v,
        Err(resp) => return Ok(*resp),
    };
    let id = du_db::genome_region::create(&st.pool, f.region_type.trim(), f.name.trim(), &coords, &props).await?;
    Ok(changed(detail(&st, locale.t, id).await?))
}

async fn update(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<RegionForm>,
) -> Result<Response, AppError> {
    if f.name.trim().is_empty() || f.region_type.trim().is_empty() {
        return Err(AppError::BadRequest("region_type and name are required".into()));
    }
    let (coords, props) = match parse_json(locale.t, format!("/curator/regions/{id}"), true, id, &f) {
        Ok(v) => v,
        Err(resp) => return Ok(*resp),
    };
    du_db::genome_region::update(&st.pool, id, f.region_type.trim(), f.name.trim(), &coords, &props).await?;
    Ok(changed(detail(&st, locale.t, id).await?))
}

async fn remove(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    du_db::genome_region::delete(&st.pool, id).await?;
    #[derive(askama::Template)]
    #[template(path = "curator/regions/empty.html")]
    struct Empty {
        t: T,
    }
    Ok(changed(html(&Empty { t: locale.t })))
}
