//! Live-DB test for the D1 exchange broker's consent substrate
//! (`du_db::exchange`): dual-consent gating, session creation, exchange-ready
//! lookup, decline, and published-key round-trip. (Signature verification lives in
//! the du-web handler; here the broker's state machine is exercised directly.)
//! Skips when DATABASE_URL is unset.

use du_db::exchange::{self, ConsentOutcome, NewRequest};
use sqlx::PgPool;

fn database_url() -> Option<String> {
    std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty())
}

async fn request(pool: &PgPool, uri: &str, initiator: &str, partner: &str) {
    exchange::create_request(
        pool,
        &NewRequest {
            request_uri: uri,
            initiator_did: initiator,
            partner_did: partner,
            purpose: "GENEALOGY_PII",
            scope: Some("project:1"),
            details: serde_json::json!({}),
        },
    )
    .await
    .expect("create request");
}

#[tokio::test]
async fn dual_consent_gates_a_session() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping exchange test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let (ann, ben) = ("did:key:zAnn", "did:key:zBen");

    // Published key round-trip.
    exchange::publish_key(&pool, ben, &[7u8; 32], Some("at://did:key:zBen/key/1")).await.expect("publish");
    let k = exchange::key_for(&pool, ben).await.expect("key_for").expect("found");
    assert_eq!(k.x25519_pub.len(), 32);
    assert_eq!(k.key_uri.as_deref(), Some("at://did:key:zBen/key/1"));

    // One consent is not enough.
    let uri = "at://did:key:zAnn/exchange/1";
    request(&pool, uri, ann, ben).await;
    assert_eq!(exchange::record_consent(&pool, uri, ann, true, None, "sigA").await.unwrap(), ConsentOutcome::Recorded);

    // The second affirmative consent opens a session.
    let outcome = exchange::record_consent(&pool, uri, ben, true, None, "sigB").await.unwrap();
    let session_id = match outcome {
        ConsentOutcome::Consented(sid) => sid,
        other => panic!("expected Consented, got {other:?}"),
    };
    let status: String =
        sqlx::query_scalar("SELECT status FROM exchange.exchange_request WHERE request_uri = $1").bind(uri).fetch_one(&pool).await.unwrap();
    assert_eq!(status, "CONSENTED");

    // Both parties see the session as exchange-ready, with the right partner + key.
    let anns = exchange::pending_for(&pool, ann).await.unwrap();
    assert_eq!(anns.len(), 1);
    assert_eq!(anns[0].session_id, session_id);
    assert_eq!(anns[0].partner_did, ben);
    assert_eq!(anns[0].partner_key_uri.as_deref(), Some("at://did:key:zBen/key/1"));
    let bens = exchange::pending_for(&pool, ben).await.unwrap();
    assert_eq!(bens[0].partner_did, ann);

    // Re-recording a consent is idempotent (no second session).
    assert_eq!(exchange::record_consent(&pool, uri, ann, true, None, "sigA").await.unwrap(), ConsentOutcome::Recorded);
    let n: i64 = sqlx::query_scalar("SELECT count(*) FROM exchange.exchange_session WHERE request_uri = $1").bind(uri).fetch_one(&pool).await.unwrap();
    assert_eq!(n, 1, "still one session");

    // Decline path: a 'false' consent kills the request, no session.
    let uri2 = "at://did:key:zAnn/exchange/2";
    request(&pool, uri2, ann, ben).await;
    assert_eq!(exchange::record_consent(&pool, uri2, ben, false, None, "sigNo").await.unwrap(), ConsentOutcome::Declined);
    let status2: String =
        sqlx::query_scalar("SELECT status FROM exchange.exchange_request WHERE request_uri = $1").bind(uri2).fetch_one(&pool).await.unwrap();
    assert_eq!(status2, "DECLINED");
    assert!(exchange::pending_for(&pool, ann).await.unwrap().iter().all(|r| r.request_uri != uri2));

    // Consent on an unknown request.
    assert_eq!(exchange::record_consent(&pool, "at://nope", ann, true, None, "x").await.unwrap(), ConsentOutcome::Unknown);
}

#[tokio::test]
async fn incoming_surfaces_pending_to_recipient_only() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping incoming test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let (ann, ben) = ("did:key:zAnn", "did:key:zBen");

    let uri = "at://did:key:zAnn/exchange/in";
    request(&pool, uri, ann, ben).await;

    // The recipient (partner) discovers it; the initiator does not (it's not awaiting ann).
    let bens = exchange::incoming_for(&pool, ben).await.unwrap();
    assert_eq!(bens.len(), 1);
    assert_eq!(bens[0].request_uri, uri);
    assert_eq!(bens[0].purpose, "GENEALOGY_PII");
    assert!(exchange::incoming_for(&pool, ann).await.unwrap().is_empty(), "initiator's own request is not 'incoming' to it");

    // Once the recipient acts (consents), it drops out of their incoming list.
    exchange::record_consent(&pool, uri, ben, true, None, "sigB").await.unwrap();
    assert!(exchange::incoming_for(&pool, ben).await.unwrap().is_empty(), "acted-on request clears");
}

#[tokio::test]
async fn relay_round_trip_and_participant_gate() {
    let Some(url) = database_url() else {
        eprintln!("DATABASE_URL unset — skipping relay test");
        return;
    };
    let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
    let pool = db.pool().clone();
    let (ann, ben, eve) = ("did:key:zAnn", "did:key:zBen", "did:key:zEve");

    let uri = "at://did:key:zAnn/exchange/rt";
    request(&pool, uri, ann, ben).await;
    exchange::record_consent(&pool, uri, ann, true, None, "a").await.unwrap();
    let session_id = match exchange::record_consent(&pool, uri, ben, true, None, "b").await.unwrap() {
        ConsentOutcome::Consented(sid) => sid,
        other => panic!("expected session, got {other:?}"),
    };

    // ann → ben: post an opaque envelope; the session goes ACTIVE.
    let id = exchange::post_envelope(&pool, session_id, ann, ben, 1, b"\x01\x02ciphertext").await.expect("post");
    let st: String = sqlx::query_scalar("SELECT status FROM exchange.exchange_session WHERE session_id = $1").bind(session_id).fetch_one(&pool).await.unwrap();
    assert_eq!(st, "ACTIVE");

    // ben pulls it; ann (the sender) sees nothing addressed to it.
    let benv = exchange::pull_envelopes(&pool, session_id, ben).await.unwrap();
    assert_eq!(benv.len(), 1);
    assert_eq!(benv[0].id, id);
    assert_eq!(benv[0].from_did, ann);
    assert_eq!(benv[0].blob, b"\x01\x02ciphertext");
    assert!(exchange::pull_envelopes(&pool, session_id, ann).await.unwrap().is_empty());

    // Only the recipient may ack; ack deletes it.
    assert!(!exchange::ack_envelope(&pool, id, ann).await.unwrap(), "non-recipient cannot ack");
    assert!(exchange::ack_envelope(&pool, id, ben).await.unwrap());
    assert!(exchange::pull_envelopes(&pool, session_id, ben).await.unwrap().is_empty(), "acked envelope is gone");

    // A non-participant cannot post into the session.
    let err = exchange::post_envelope(&pool, session_id, eve, ben, 2, b"x").await;
    assert!(err.is_err(), "non-participant post is rejected");

    // TTL: an expired session is swept (cascading its envelopes).
    exchange::post_envelope(&pool, session_id, ben, ann, 3, b"later").await.unwrap();
    sqlx::query("UPDATE exchange.exchange_session SET expires_at = now() - interval '1 day' WHERE session_id = $1").bind(session_id).execute(&pool).await.unwrap();
    let (_envs, sessions) = exchange::expire(&pool).await.unwrap();
    assert!(sessions >= 1, "expired session swept");
    let remain: i64 = sqlx::query_scalar("SELECT count(*) FROM exchange.relay_envelope WHERE session_id = $1").bind(session_id).fetch_one(&pool).await.unwrap();
    assert_eq!(remain, 0, "envelopes cascade-deleted with the session");
}
