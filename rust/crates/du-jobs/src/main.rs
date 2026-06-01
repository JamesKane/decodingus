//! Background job runner (replaces the Pekko Quartz scheduler). Registers the
//! scheduled workers and runs them until shutdown (Ctrl-C).
//!
//! The five legacy jobs (publication update + discovery via OpenAlex, YBrowse
//! variant ingest via du-bio, variant export, match discovery) are wired here as
//! their external clients land (du-external) and ingestion is built (du-bio). A
//! DB heartbeat is registered now to exercise the harness end-to-end.

use std::time::Duration;

mod scheduler;
mod ybrowse;
use scheduler::{Job, Scheduler};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,du_jobs=debug".into()),
        )
        .init();

    let url = std::env::var("DATABASE_URL")
        .map_err(|_| anyhow::anyhow!("DATABASE_URL is required for the job runner"))?;
    let pool = du_db::connect(&url, 4).await?;

    let mut sched = Scheduler::new();

    // DB heartbeat — proves the harness; also a cheap liveness signal.
    {
        let pool = pool.clone();
        sched.register(Job::new("db-heartbeat", Duration::from_secs(300), move || {
            let pool = pool.clone();
            async move {
                let variants = du_db::variant::search(&pool, None, 1, 1).await?.total;
                let publications = du_db::publication::search(&pool, None, 1, 1).await?.total;
                tracing::info!(variants, publications, "heartbeat");
                Ok(())
            }
        }));
    }

    // YBrowse variant ingest (GRCh38 VCF -> lift to GRCh37/hs1 -> core.variant).
    // Registered only when configured (YBROWSE_VCF + chain paths).
    if let Some(cfg) = ybrowse::Config::from_env() {
        let pool = pool.clone();
        sched.register(Job::new("ybrowse-variant-ingest", Duration::from_secs(86_400), move || {
            let pool = pool.clone();
            let cfg = cfg.clone();
            async move { ybrowse::run(&pool, &cfg).await }
        }));
        tracing::info!("ybrowse-variant-ingest registered");
    } else {
        tracing::info!("ybrowse-variant-ingest not configured (set YBROWSE_VCF + chain paths)");
    }

    // TODO(jobs): as du-external lands, register publication-update/discovery
    // (OpenAlex), variant-export, and match-discovery.

    let shutdown = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    sched.run_until(shutdown).await;
    Ok(())
}
