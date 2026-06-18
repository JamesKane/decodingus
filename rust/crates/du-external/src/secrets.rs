//! Secret retrieval with a TTL cache (mirrors the legacy 1-hour cache).
//! `SecretSource::Env` (default) reads `SECRET_<NAME>` from the environment;
//! `SecretSource::Aws` (feature `aws`) reads AWS Secrets Manager.

use crate::error::ExternalError;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub enum SecretSource {
    /// Reads `SECRET_<UPPER_NAME>` from the process environment (dev/test).
    Env,
    #[cfg(feature = "aws")]
    Aws(aws_sdk_secretsmanager::Client),
}

/// Env var name for a secret (`db/password` -> `SECRET_DB_PASSWORD`).
fn env_key(name: &str) -> String {
    let mut k = String::from("SECRET_");
    for c in name.chars() {
        k.push(if c.is_ascii_alphanumeric() { c.to_ascii_uppercase() } else { '_' });
    }
    k
}

impl SecretSource {
    pub fn env() -> Self {
        SecretSource::Env
    }

    #[cfg(feature = "aws")]
    pub async fn aws() -> Self {
        let conf = aws_config::load_defaults(aws_config::BehaviorVersion::latest()).await;
        SecretSource::Aws(aws_sdk_secretsmanager::Client::new(&conf))
    }

    async fn fetch(&self, name: &str) -> Result<Option<String>, ExternalError> {
        match self {
            SecretSource::Env => Ok(std::env::var(env_key(name)).ok()),
            #[cfg(feature = "aws")]
            SecretSource::Aws(client) => {
                let resp = client
                    .get_secret_value()
                    .secret_id(name)
                    .send()
                    .await
                    .map_err(|e| ExternalError::Aws(e.to_string()))?;
                Ok(resp.secret_string().map(str::to_string))
            }
        }
    }
}

/// A TTL cache over a `SecretSource`. Secrets are fetched on miss and cached
/// until `ttl` elapses.
pub struct CachedSecrets {
    source: SecretSource,
    ttl: Duration,
    cache: Mutex<HashMap<String, (Instant, String)>>,
}

impl CachedSecrets {
    /// Default TTL: one hour (as in the legacy CachedSecretsManagerService).
    pub fn new(source: SecretSource) -> Self {
        Self::with_ttl(source, Duration::from_secs(3600))
    }

    pub fn with_ttl(source: SecretSource, ttl: Duration) -> Self {
        CachedSecrets { source, ttl, cache: Mutex::new(HashMap::new()) }
    }

    /// Fresh cached value, if present and within TTL.
    fn get_cached(&self, name: &str) -> Option<String> {
        let cache = self.cache.lock().unwrap();
        cache.get(name).filter(|(at, _)| at.elapsed() < self.ttl).map(|(_, v)| v.clone())
    }

    fn put(&self, name: &str, value: &str) {
        self.cache.lock().unwrap().insert(name.to_string(), (Instant::now(), value.to_string()));
    }

    /// Get a secret, using the cache and refreshing on miss/expiry.
    pub async fn get(&self, name: &str) -> Result<Option<String>, ExternalError> {
        if let Some(v) = self.get_cached(name) {
            return Ok(Some(v));
        }
        let fetched = self.source.fetch(name).await?;
        if let Some(v) = &fetched {
            self.put(name, v);
        }
        Ok(fetched)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_key_normalizes() {
        assert_eq!(env_key("db/password"), "SECRET_DB_PASSWORD");
        assert_eq!(env_key("recaptcha.secret"), "SECRET_RECAPTCHA_SECRET");
    }

    #[test]
    fn cache_serves_fresh_and_expires_at_zero_ttl() {
        // Fresh within a long TTL.
        let c = CachedSecrets::with_ttl(SecretSource::Env, Duration::from_secs(3600));
        c.put("k", "v");
        assert_eq!(c.get_cached("k").as_deref(), Some("v"));

        // Zero TTL is always stale.
        let c0 = CachedSecrets::with_ttl(SecretSource::Env, Duration::ZERO);
        c0.put("k", "v");
        assert_eq!(c0.get_cached("k"), None);
    }

    #[tokio::test]
    async fn env_source_reads_and_caches() {
        std::env::set_var("SECRET_API_TOKEN", "sek");
        let c = CachedSecrets::new(SecretSource::env());
        assert_eq!(c.get("api/token").await.unwrap().as_deref(), Some("sek"));
        // Now cached: still returns even if the env changes.
        std::env::remove_var("SECRET_API_TOKEN");
        assert_eq!(c.get("api/token").await.unwrap().as_deref(), Some("sek"));
        // An unknown secret is None.
        assert_eq!(c.get("missing/one").await.unwrap(), None);
    }
}
