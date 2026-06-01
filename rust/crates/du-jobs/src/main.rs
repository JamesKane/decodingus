//! Background job runner (replaces Pekko Quartz scheduler). Scaffold only —
//! the five scheduled jobs are wired in during the genomics/external milestone.

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();
    tracing::info!("decodingus-jobs scaffold — no jobs scheduled yet");
    Ok(())
}
