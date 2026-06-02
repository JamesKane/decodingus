//! AT Protocol OAuth client wiring. Serves the client-metadata + JWKS documents
//! (concrete artifacts the Edge team registers/reviews) and drives the login
//! handshake: resolve handle -> DID -> PDS -> authorization server, PAR, redirect,
//! then token exchange on callback and session establishment.
//!
//! The interactive flow needs a live PDS/authorization server, so it is verified
//! jointly with the Edge team. See docs/atproto-oauth-findings.md.

use crate::auth::{Session, SESSION_COOKIE};
use crate::error::AppError;
use crate::state::AppState;
use axum::extract::{Query, State};
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::get;
use axum::{Json, Router};
use du_atproto::did::Did;
use du_atproto::oauth::{
    authorize_url, client_assertion, discover_auth_server, dpop_proof, par_form, token_form,
    AuthServerMetadata, ClientMetadata, EcKey, Pkce,
};
use du_atproto::Resolver;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tower_cookies::{Cookie, Cookies};

const FLOW_COOKIE: &str = "du_oauth_flow";

/// Configured AT Protocol OAuth client (absent when OAuth isn't configured).
pub struct OauthClient {
    pub ec_key: EcKey,
    pub metadata: ClientMetadata,
    pub scope: String,
    pub http: reqwest::Client,
    pub resolver: Resolver,
}

impl OauthClient {
    /// Build from env. Returns None (OAuth disabled) when `OAUTH_BASE_URL` is unset.
    pub fn from_env() -> Option<Arc<OauthClient>> {
        let base_url = std::env::var("OAUTH_BASE_URL").ok().filter(|s| !s.is_empty())?;
        let scope = std::env::var("OAUTH_SCOPE")
            .unwrap_or_else(|_| "atproto transition:generic".to_string());
        let ec_key = match std::env::var("OAUTH_EC_KEY") {
            Ok(b64) => match EcKey::from_base64(&b64) {
                Ok(k) => k,
                Err(e) => {
                    tracing::error!(error = %e, "OAUTH_EC_KEY invalid; OAuth disabled");
                    return None;
                }
            },
            Err(_) => {
                let k = EcKey::generate();
                tracing::warn!(
                    "OAUTH_EC_KEY unset — generated an ephemeral key (set OAUTH_EC_KEY={} to persist)",
                    k.to_base64()
                );
                k
            }
        };
        let metadata = ClientMetadata::confidential_web(&base_url, &scope);
        Some(Arc::new(OauthClient {
            ec_key,
            metadata,
            scope,
            http: reqwest::Client::new(),
            resolver: Resolver::new(),
        }))
    }
}

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/oauth/client-metadata.json", get(client_metadata))
        .route("/oauth/jwks.json", get(jwks))
        .route("/login/atproto", get(login))
        .route("/oauth/callback", get(callback))
}

fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

fn require(st: &AppState) -> Result<&Arc<OauthClient>, AppError> {
    st.oauth.as_ref().ok_or_else(|| AppError::NotFound("OAuth not configured".into()))
}

async fn client_metadata(State(st): State<AppState>) -> Result<Response, AppError> {
    let oc = require(&st)?;
    Ok(Json(&oc.metadata).into_response())
}

async fn jwks(State(st): State<AppState>) -> Result<Response, AppError> {
    let oc = require(&st)?;
    Ok(Json(serde_json::json!({ "keys": [oc.ec_key.public_jwk()] })).into_response())
}

#[derive(Deserialize)]
struct LoginQuery {
    handle: String,
}

#[derive(Serialize, Deserialize)]
struct FlowState {
    state: String,
    verifier: String,
    token_endpoint: String,
    issuer: String,
}

/// POST a form with a DPoP proof, retrying once if the server demands a nonce.
async fn post_with_dpop(
    oc: &OauthClient,
    url: &str,
    form: &[(String, String)],
) -> Result<serde_json::Value, AppError> {
    // First attempt: no nonce yet (the server supplies one via a 400 + DPoP-Nonce).
    let proof = dpop_proof(&oc.ec_key, "POST", url, now(), None, None);
    let resp = oc
        .http
        .post(url)
        .header("DPoP", proof)
        .form(form)
        .send()
        .await
        .map_err(|e| AppError::Upstream(e.to_string()))?;

    if resp.status().is_success() {
        return resp.json().await.map_err(|e| AppError::Upstream(e.to_string()));
    }

    // Retry once with the server-supplied DPoP nonce, if it offered one.
    let Some(server_nonce) = resp.headers().get("DPoP-Nonce").and_then(|v| v.to_str().ok()) else {
        return Err(AppError::Upstream(format!("oauth endpoint {}: {}", url, resp.status())));
    };
    let proof = dpop_proof(&oc.ec_key, "POST", url, now(), Some(server_nonce), None);
    let retry = oc
        .http
        .post(url)
        .header("DPoP", proof)
        .form(form)
        .send()
        .await
        .map_err(|e| AppError::Upstream(e.to_string()))?;
    if retry.status().is_success() {
        return retry.json().await.map_err(|e| AppError::Upstream(e.to_string()));
    }
    Err(AppError::Upstream(format!("oauth endpoint {}: {}", url, retry.status())))
}

async fn resolve_pds_and_authserver(
    oc: &OauthClient,
    handle_or_did: &str,
) -> Result<(Did, AuthServerMetadata), AppError> {
    let did = if handle_or_did.starts_with("did:") {
        Did::parse(handle_or_did)?
    } else {
        oc.resolver.resolve_handle(handle_or_did).await?
    };
    let pds = oc.resolver.resolve_pds(&did).await?;
    let meta = discover_auth_server(&oc.http, &pds).await?;
    Ok((did, meta))
}

/// Start the OAuth flow: PAR, then redirect the user to the authorization server.
async fn login(
    State(st): State<AppState>,
    cookies: Cookies,
    Query(q): Query<LoginQuery>,
) -> Result<Response, AppError> {
    let oc = require(&st)?;
    let handle = q.handle.trim();
    let (_did, meta) = resolve_pds_and_authserver(oc, handle).await?;

    let par_endpoint = meta
        .pushed_authorization_request_endpoint
        .clone()
        .ok_or_else(|| AppError::Upstream("authorization server has no PAR endpoint".into()))?;

    let pkce = Pkce::generate();
    let state = du_atproto::oauth::random_token();
    let redirect_uri = oc.metadata.redirect_uris[0].clone();
    let assertion = client_assertion(&oc.ec_key, &oc.metadata.client_id, &meta.issuer, now());
    let form = par_form(
        &oc.metadata.client_id,
        &redirect_uri,
        &oc.scope,
        &state,
        &pkce.challenge,
        Some(handle),
        &assertion,
    );

    let par: serde_json::Value = post_with_dpop(oc, &par_endpoint, &form).await?;
    let request_uri = par
        .get("request_uri")
        .and_then(|v| v.as_str())
        .ok_or_else(|| AppError::Upstream("PAR response missing request_uri".into()))?;

    // Stash the flow state (signed, short-lived) for the callback.
    let flow = FlowState {
        state,
        verifier: pkce.verifier,
        token_endpoint: meta.token_endpoint.clone(),
        issuer: meta.issuer.clone(),
    };
    let mut cookie = Cookie::new(FLOW_COOKIE, serde_json::to_string(&flow).unwrap());
    cookie.set_path("/");
    cookie.set_http_only(true);
    cookie.set_max_age(tower_cookies::cookie::time::Duration::minutes(10));
    cookie.set_same_site(tower_cookies::cookie::SameSite::Lax);
    cookies.signed(&st.key).add(cookie);

    Ok(Redirect::to(&authorize_url(&meta.authorization_endpoint, &oc.metadata.client_id, request_uri)).into_response())
}

#[derive(Deserialize)]
struct CallbackQuery {
    code: Option<String>,
    state: Option<String>,
    error: Option<String>,
}

/// Handle the authorization redirect: exchange the code for tokens, resolve the
/// user, and establish a session.
async fn callback(
    State(st): State<AppState>,
    cookies: Cookies,
    Query(q): Query<CallbackQuery>,
) -> Result<Response, AppError> {
    let oc = require(&st)?;
    if let Some(err) = q.error {
        return Err(AppError::BadRequest(format!("authorization denied: {err}")));
    }
    let code = q.code.ok_or_else(|| AppError::BadRequest("missing code".into()))?;
    let returned_state = q.state.unwrap_or_default();

    let signed = cookies.signed(&st.key);
    let flow: FlowState = signed
        .get(FLOW_COOKIE)
        .and_then(|c| serde_json::from_str(c.value()).ok())
        .ok_or_else(|| AppError::BadRequest("no/expired oauth flow".into()))?;
    if flow.state != returned_state {
        return Err(AppError::BadRequest("state mismatch".into()));
    }
    // One-shot: clear the flow cookie.
    let mut clear = Cookie::new(FLOW_COOKIE, "");
    clear.set_path("/");
    signed.remove(clear);

    let redirect_uri = oc.metadata.redirect_uris[0].clone();
    let assertion = client_assertion(&oc.ec_key, &oc.metadata.client_id, &flow.issuer, now());
    let form = token_form(&oc.metadata.client_id, &redirect_uri, &code, &flow.verifier, &assertion);
    let tokens: serde_json::Value = post_with_dpop(oc, &flow.token_endpoint, &form).await?;

    let did = tokens
        .get("sub")
        .and_then(|v| v.as_str())
        .ok_or_else(|| AppError::Upstream("token response missing sub (DID)".into()))?
        .to_string();

    // Resolve the handle/display from the DID document (best-effort).
    let handle = match Did::parse(&did) {
        Ok(d) => oc.resolver.resolve_did(&d).await.ok().and_then(|doc| doc.handle()),
        Err(_) => None,
    };
    let display = handle.clone().unwrap_or_else(|| did.clone());

    let user_id =
        du_db::auth::upsert_user_by_did(&st.pool, &did, handle.as_deref(), Some(&display)).await?;
    let (display_name, roles) = du_db::auth::session_info(&st.pool, user_id).await?;

    let session = Session {
        user_id: user_id.0,
        display_name: display_name.unwrap_or(display),
        roles,
    };
    let mut sc = Cookie::new(SESSION_COOKIE, serde_json::to_string(&session).unwrap());
    sc.set_path("/");
    sc.set_http_only(true);
    sc.set_same_site(tower_cookies::cookie::SameSite::Lax);
    cookies.signed(&st.key).add(sc);

    Ok(Redirect::to("/").into_response())
}
