//! DecodingUs web binary (Axum). HTML + (later) JSON API + firehose.
//!
//! Public surface (trees, variants, references, map, coverage) plus session
//! auth and the curator tools. Server-rendered with Askama, driven by HTMX.
//!
//! Dev helper: `decodingus hash-password <password>` prints an Argon2 hash for
//! seeding `ident.user_login_info.password_hash`.

use std::net::SocketAddr;
use tower_cookies::Key;

mod api;
mod auth;
mod error;
mod extract;
mod htmx;
mod i18n;
mod render;
mod oauth;
mod routes;
mod security;
mod sig;
mod state;
mod tree_layout;

use state::AppState;

/// Derive a 64-byte cookie-signing key from APP_SECRET (extending short secrets
/// so dev defaults work; set a long random APP_SECRET in production).
fn cookie_key() -> Key {
    let secret = std::env::var("APP_SECRET")
        .unwrap_or_else(|_| "dev-insecure-app-secret-change-me-in-production".to_string());
    let mut seed = secret.into_bytes();
    let base = seed.clone();
    while seed.len() < 64 {
        seed.extend_from_slice(&base);
    }
    Key::from(&seed[..64])
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Dev helper: hash a password and exit (no DB needed).
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("hash-password") {
        let pw = args.get(2).cloned().unwrap_or_default();
        println!("{}", auth::hash_password(&pw).map_err(anyhow::Error::msg)?);
        return Ok(());
    }

    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,du_web=debug".into()),
        )
        .init();

    let app = match std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) {
        Some(url) => {
            let pool = du_db::connect(&url, 8).await?;
            du_db::run_migrations(&pool).await?;
            let oauth = oauth::OauthClient::from_env();
            tracing::info!(oauth = oauth.is_some(), "connected to database; migrations applied");
            routes::app(AppState { pool, key: cookie_key(), oauth })
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
