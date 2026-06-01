//! DecodingUs web binary (Axum). HTML + (later) JSON API + firehose.
//!
//! Public vertical slice: home, variant browser, and Y/MT tree navigation,
//! server-rendered with Askama and driven by HTMX fragments (plan §4).

use std::net::SocketAddr;

mod error;
mod htmx;
mod i18n;
mod render;
mod routes;
mod state;

use state::AppState;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,du_web=debug".into()),
        )
        .init();

    // Build the app. With a DATABASE_URL we connect, migrate, and serve the full
    // site; without one we serve only /health (keeps the binary runnable bare).
    let app = match std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) {
        Some(url) => {
            let pool = du_db::connect(&url, 8).await?;
            du_db::run_migrations(&pool).await?;
            tracing::info!("connected to database; migrations applied");
            routes::app(AppState { pool })
        }
        None => {
            tracing::warn!("DATABASE_URL not set — serving /health only");
            routes::health_only()
        }
    };

    let port: u16 = std::env::var("PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(9000);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    tracing::info!(%addr, "decodingus web starting");

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}
