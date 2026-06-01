//! Biosample geographic map. The page loads Leaflet (client-side) and fetches
//! GeoJSON from `/biosamples/geo-data`, which is produced from the donor
//! `geocoord` via PostGIS. This is the one public area that needs client JS.

use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::State;
use axum::response::Response;
use axum::routing::get;
use axum::{Json, Router};
use serde_json::{json, Value};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/biosamples/map", get(map_page))
        .route("/biosamples/geo-data", get(geo_data))
}

#[derive(askama::Template)]
#[template(path = "biosamples/map.html")]
struct MapTemplate {
    t: T,
    next: String,
}

async fn map_page(locale: Locale) -> Response {
    html(&MapTemplate { t: locale.t, next: locale.next })
}

/// GeoJSON FeatureCollection of biosample locations for Leaflet.
async fn geo_data(State(st): State<AppState>) -> Result<Json<Value>, AppError> {
    let points = du_db::biosample::geo_points(&st.pool).await?;
    let features: Vec<Value> = points
        .into_iter()
        .map(|p| {
            json!({
                "type": "Feature",
                "geometry": { "type": "Point", "coordinates": [p.lon, p.lat] },
                "properties": { "accession": p.accession, "source": p.source.label() },
            })
        })
        .collect();
    Ok(Json(json!({ "type": "FeatureCollection", "features": features })))
}
