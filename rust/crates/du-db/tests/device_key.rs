//! Live-DB test for the device-key registry (`du_db::fed::device_key`): ingest (idempotent
//! ordered upsert), multi-key-per-DID lookup, and revocation via `fed::delete`. Skips when
//! DATABASE_URL is unset.

use du_db::fed::{self, device_key, Common};

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

fn dk(did: &str, rkey: &str, key: &str, time_us: i64) -> device_key::DeviceKey {
    device_key::DeviceKey {
        common: Common {
            did: did.to_string(),
            rkey: rkey.to_string(),
            at_uri: format!("at://{did}/com.decodingus.atmosphere.deviceKey/{rkey}"),
            cid: None,
            record_created_at: None,
            time_us,
        },
        public_key: key.to_string(),
    }
}

#[tokio::test]
async fn device_keys_register_lookup_and_revoke() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping device-key test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let alice = "did:plc:alice";

    // Two devices for one DID.
    device_key::upsert(&pool, &dk(alice, "dk1", "did:key:zAAA", 10)).await.unwrap();
    device_key::upsert(&pool, &dk(alice, "dk2", "did:key:zBBB", 11)).await.unwrap();
    let mut keys = device_key::keys_for(&pool, alice).await.unwrap();
    keys.sort();
    assert_eq!(keys, vec!["did:key:zAAA".to_string(), "did:key:zBBB".to_string()]);
    assert!(device_key::keys_for(&pool, "did:plc:nobody").await.unwrap().is_empty());

    // An older event does not clobber a newer-keyed row (time_us ordering).
    device_key::upsert(&pool, &dk(alice, "dk1", "did:key:zSTALE", 5)).await.unwrap();
    assert!(device_key::keys_for(&pool, alice).await.unwrap().contains(&"did:key:zAAA".to_string()));
    // A newer event for the same rkey rotates the stored key.
    device_key::upsert(&pool, &dk(alice, "dk1", "did:key:zROTATED", 20)).await.unwrap();
    assert!(device_key::keys_for(&pool, alice).await.unwrap().contains(&"did:key:zROTATED".to_string()));

    // Revocation: deleting the record (via the shared fed::delete) drops the key.
    assert!(fed::delete(&pool, fed::NS_DEVICE_KEY, alice, "dk1").await.unwrap());
    let remaining = device_key::keys_for(&pool, alice).await.unwrap();
    assert_eq!(remaining, vec!["did:key:zBBB".to_string()], "revoked key gone, the other remains");
}
