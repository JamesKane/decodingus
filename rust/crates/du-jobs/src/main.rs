//! Background job runner (replaces the Pekko Quartz scheduler). Registers the
//! scheduled workers and runs them until shutdown (Ctrl-C).
//!
//! The five legacy jobs (publication update + discovery via OpenAlex, YBrowse
//! variant ingest via du-bio, variant export, match discovery) are wired here as
//! their external clients land (du-external) and ingestion is built (du-bio). A
//! DB heartbeat is registered now to exercise the harness end-to-end.

use std::time::Duration;

mod scheduler;
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

    // TODO(jobs): as du-external / du-bio land, register:
    //   - publication-update     (OpenAlex, ~6.7 req/s)
    //   - publication-discovery   (OpenAlex search configs)
    //   - ybrowse-variant-ingest  (du-bio VCF/GFF + liftover -> core.variant)
    //   - variant-export
    //   - match-discovery         (O(n^2) population overlap)

    let shutdown = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    sched.run_until(shutdown).await;
    Ok(())
}
