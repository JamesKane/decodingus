//! Mirrored device keys (`com.decodingus.atmosphere.deviceKey`). Each client publishes its
//! Ed25519 device PUBLIC key (as a `did:key`) to the user's own repo; this is the registry
//! `crate::sig::verify_signed` checks signed Edge calls against. A DID may hold several
//! (one per device); revocation is a record delete (`super::delete`). See [`super`] for the
//! shared cursor/delete. PII-free — a DID + a public key + pointers only.

use super::Common;
use crate::DbError;
use sqlx::PgPool;

/// A mirrored device key. `public_key` is a `did:key:z…` string (verified directly).
pub struct DeviceKey {
    pub common: Common,
    pub public_key: String,
}

pub async fn upsert(pool: &PgPool, d: &DeviceKey) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.device_key \
           (did, rkey, at_uri, cid, public_key, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, public_key = EXCLUDED.public_key, \
           record_created_at = EXCLUDED.record_created_at, time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.device_key.time_us",
    )
    .bind(&d.common.did)
    .bind(&d.common.rkey)
    .bind(&d.common.at_uri)
    .bind(&d.common.cid)
    .bind(&d.public_key)
    .bind(d.common.record_created_at)
    .bind(d.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}

/// The `did:key` strings registered for a DID — the verifier's lookup (any may match).
pub async fn keys_for(pool: &PgPool, did: &str) -> Result<Vec<String>, DbError> {
    Ok(sqlx::query_scalar("SELECT public_key FROM fed.device_key WHERE did = $1")
        .bind(did)
        .fetch_all(pool)
        .await?)
}
