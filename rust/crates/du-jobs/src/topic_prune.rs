//! One-shot backfill: retroactively apply the discovery **topic whitelist** (mig
//! 0065) to the existing pending candidate queue.
//!
//! Date-sorted discovery (mig 0063) briefly ran without the `primary_topic.id`
//! whitelist, flooding `pubs.publication_candidate` with off-topic work (health,
//! non-human aDNA, microbiome). The whitelist only constrains *future* queries — it
//! never re-scores candidates already collected. This job closes that gap: for each
//! still-`pending` candidate it fetches the work's OpenAlex primary topic and rejects
//! the ones outside the enabled configs' whitelist(s), leaving on-topic candidates
//! (and any already accepted/deferred/rejected rows) untouched.
//!
//! Usage:
//!   decodingus-jobs run-once publication-topic-prune          # preview (no writes)
//!   decodingus-jobs run-once publication-topic-prune --apply  # reject off-topic
//!
//! Requires `OPENALEX_MAILTO` (polite pool). One HTTP request per pending candidate,
//! rate-limited under OpenAlex's 10 req/s.

use du_db::PgPool;
use du_external::openalex::{OpenAlexClient, TopicLookup};
use std::collections::HashSet;
use std::time::Duration;

/// ~6.7 req/s, comfortably under OpenAlex's 10/s (mirrors the discovery job).
const REQUEST_GAP: Duration = Duration::from_millis(150);

pub struct Config {
    pub apply: bool,
}

impl Config {
    pub fn from_env(args: &[String]) -> Self {
        Config { apply: args.iter().any(|a| a == "--apply") }
    }
}

/// Parse the OpenAlex `topic_filter` fragments into the set of allowed short topic
/// ids (`T10751`, …). Only `primary_topic.id:` fragments are understood — any other
/// fragment kind can't be evaluated locally and is ignored (logged by the caller).
fn allowed_topics(filters: &[String]) -> HashSet<String> {
    let mut set = HashSet::new();
    for filter in filters {
        for fragment in filter.split(',') {
            let fragment = fragment.trim();
            if let Some(list) = fragment.strip_prefix("primary_topic.id:") {
                for id in list.split('|') {
                    let id = id.trim();
                    if !id.is_empty() {
                        set.insert(id.to_string());
                    }
                }
            }
        }
    }
    set
}

#[derive(Default)]
struct Tally {
    on_topic: usize,
    off_topic: usize,
    no_topic: usize,
    missing: usize,
    failed: usize,
}

/// Retroactively prune off-topic pending candidates against the discovery whitelist.
pub async fn run(pool: &PgPool, client: &OpenAlexClient, cfg: &Config) -> anyhow::Result<()> {
    let filters = du_db::publication::enabled_topic_filters(pool).await?;
    let allowed = allowed_topics(&filters);
    if allowed.is_empty() {
        anyhow::bail!(
            "no `primary_topic.id` whitelist configured on any enabled search config — \
             refusing to prune (would reject the whole queue). Set publication_search_config.topic_filter first."
        );
    }

    let pending = du_db::publication::pending_candidate_ids(pool).await?;
    tracing::info!(
        pending = pending.len(), whitelist = allowed.len(), apply = cfg.apply,
        "publication-topic-prune starting"
    );

    let mut tally = Tally::default();
    // Candidates whose primary topic is outside the whitelist (or absent/deleted).
    let mut to_reject: Vec<i64> = Vec::new();
    for (id, openalex_id) in &pending {
        match client.work_topic(openalex_id).await {
            Ok(TopicLookup::Topic(Some(topic))) if allowed.contains(&topic) => tally.on_topic += 1,
            Ok(TopicLookup::Topic(Some(_))) => {
                tally.off_topic += 1;
                to_reject.push(*id);
            }
            Ok(TopicLookup::Topic(None)) => {
                // Exists but untagged — the whitelist filter would have excluded it.
                tally.no_topic += 1;
                to_reject.push(*id);
            }
            Ok(TopicLookup::Missing) => {
                // Deleted/merged at OpenAlex — not a real current work; drop it too.
                tally.missing += 1;
                to_reject.push(*id);
            }
            Err(e) => {
                // Transient/unknown error — leave the candidate pending for a re-run.
                tracing::warn!(%openalex_id, error = %e, "topic lookup failed; skipping");
                tally.failed += 1;
            }
        }
        tokio::time::sleep(REQUEST_GAP).await;
    }

    let rejected = if cfg.apply {
        du_db::publication::reject_candidates_system(pool, &to_reject).await?
    } else {
        0
    };

    tracing::info!(
        apply = cfg.apply, pending = pending.len(), on_topic = tally.on_topic,
        off_topic = tally.off_topic, no_topic = tally.no_topic, missing = tally.missing,
        failed = tally.failed, would_reject = to_reject.len(), rejected,
        "publication-topic-prune complete{}",
        if cfg.apply { "" } else { " (preview — pass --apply to reject)" }
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_default_whitelist() {
        let filters = vec!["primary_topic.id:T10751|T10421|T10087|T10992|T10015|T12232".to_string()];
        let set = allowed_topics(&filters);
        assert_eq!(set.len(), 6);
        assert!(set.contains("T10751"));
        assert!(set.contains("T12232"));
        assert!(!set.contains("T10012"));
    }

    #[test]
    fn unions_multiple_configs_and_ignores_unknown_fragments() {
        let filters = vec![
            "primary_topic.id:T10751".to_string(),
            "from_publication_date:2020-01-01,primary_topic.id:T10421| T10087 ".to_string(),
        ];
        let set = allowed_topics(&filters);
        assert_eq!(set.len(), 3);
        assert!(set.contains("T10087")); // trimmed
    }

    #[test]
    fn empty_when_no_topic_fragment() {
        let filters = vec!["from_publication_date:2020-01-01".to_string()];
        assert!(allowed_topics(&filters).is_empty());
    }
}
