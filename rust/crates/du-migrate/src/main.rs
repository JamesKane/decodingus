//! Legacy -> new schema ETL binary (plan §8). Scaffold only.
//!
//! Usage (planned):
//!   decodingus-migrate --legacy <DSN> --target <DSN>            run the ETL
//!   decodingus-migrate --legacy <DSN> --target <DSN> --verify   reconcile counts

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();
    tracing::info!("decodingus-migrate scaffold — ETL transformers not yet implemented");
    Ok(())
}
