//! Minimal tokio job scheduler (replaces the Pekko Quartz scheduler). Each job
//! is a name + period + async closure; jobs run on their own interval with error
//! isolation (a failing run is logged, not fatal) and an optional run-on-start.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

pub type JobFuture = Pin<Box<dyn Future<Output = anyhow::Result<()>> + Send>>;
type RunFn = Arc<dyn Fn() -> JobFuture + Send + Sync>;

pub struct Job {
    pub name: String,
    pub period: Duration,
    pub run_on_start: bool,
    run: RunFn,
}

impl Job {
    pub fn new<F, Fut>(name: &str, period: Duration, f: F) -> Self
    where
        F: Fn() -> Fut + Send + Sync + 'static,
        Fut: Future<Output = anyhow::Result<()>> + Send + 'static,
    {
        Job {
            name: name.to_string(),
            period,
            run_on_start: true,
            run: Arc::new(move || Box::pin(f())),
        }
    }

    /// Run once, logging (not propagating) any error.
    pub async fn run_once(&self) {
        let started = std::time::Instant::now();
        match (self.run)().await {
            Ok(()) => tracing::info!(job = %self.name, ms = started.elapsed().as_millis(), "job ok"),
            Err(e) => tracing::error!(job = %self.name, error = %e, "job failed"),
        }
    }
}

#[derive(Default)]
pub struct Scheduler {
    jobs: Vec<Job>,
}

impl Scheduler {
    pub fn new() -> Self {
        Scheduler { jobs: Vec::new() }
    }

    pub fn register(&mut self, job: Job) {
        self.jobs.push(job);
    }

    /// Spawn each job on its interval and run until `shutdown` resolves, then
    /// abort the job loops.
    pub async fn run_until<S: Future>(self, shutdown: S) {
        let mut handles = Vec::new();
        for job in self.jobs {
            handles.push(tokio::spawn(async move {
                if job.run_on_start {
                    job.run_once().await;
                }
                let mut tick = tokio::time::interval(job.period);
                tick.tick().await; // consume the immediate first tick
                loop {
                    tick.tick().await;
                    job.run_once().await;
                }
            }));
        }
        tracing::info!(jobs = handles.len(), "scheduler running");
        shutdown.await;
        tracing::info!("scheduler shutting down");
        for h in handles {
            h.abort();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};

    #[tokio::test]
    async fn run_once_invokes_closure_and_isolates_errors() {
        let counter = Arc::new(AtomicUsize::new(0));
        let c = counter.clone();
        let ok = Job::new("ok", Duration::from_secs(60), move || {
            let c = c.clone();
            async move {
                c.fetch_add(1, SeqCst);
                Ok(())
            }
        });
        ok.run_once().await;
        assert_eq!(counter.load(SeqCst), 1);

        // A failing job must not panic/propagate.
        let bad = Job::new("bad", Duration::from_secs(60), || async { anyhow::bail!("boom") });
        bad.run_once().await;
    }

    #[tokio::test(start_paused = true)]
    async fn scheduler_runs_jobs_on_their_period() {
        let counter = Arc::new(AtomicUsize::new(0));
        let c = counter.clone();
        let mut sched = Scheduler::new();
        sched.register(Job::new("tick", Duration::from_secs(10), move || {
            let c = c.clone();
            async move {
                c.fetch_add(1, SeqCst);
                Ok(())
            }
        }));

        // With paused time, advance manually: run-on-start + two periods = 3 runs.
        let handle = tokio::spawn(sched.run_until(async {
            tokio::time::sleep(Duration::from_secs(25)).await;
        }));
        tokio::time::sleep(Duration::from_secs(26)).await;
        let _ = handle.await;
        assert_eq!(counter.load(SeqCst), 3);
    }
}
