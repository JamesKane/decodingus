//! DecodingUs web binary (Axum). HTML + JSON API + static assets + firehose.
//!
//! Scaffold: boots tracing, builds the router, serves `/health` (carried over
//! from the Play app's load-balancer check). Subsystem routers are mounted as
//! they are ported (plan §4, §10).

use axum::{routing::get, Router};
use std::net::SocketAddr;

mod routes;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,du_web=debug".into()),
        )
        .init();

    let app = build_router();

    let port: u16 = std::env::var("PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(9000);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    tracing::info!(%addr, "decodingus web starting");

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}

/// Builds the application router. Split out so handler tests can exercise it
/// without binding a socket.
pub fn build_router() -> Router {
    Router::new().route("/health", get(routes::health))
}
