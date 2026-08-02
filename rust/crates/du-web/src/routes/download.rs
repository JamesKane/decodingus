//! Navigator download page (`/download`) + stable per-platform links.
//!
//! The Navigator edge app ships via GitHub Releases. Installer URLs can't be
//! hard-coded — Tauri stamps the app version into every filename, and GitHub's
//! `/releases/latest` skips pre-releases (which is all the Navigator publishes,
//! so "latest" resolves to an unrelated reference-data release). So the page
//! resolves the newest release carrying installers at request time, through a
//! process-wide cache, and `/download/{windows,macos,linux}` 302s straight to
//! that release's asset — permanent URLs that always land on the current build.
//!
//! GitHub being unreachable is not an error state for the site: the page (and the
//! redirects) fall back to the repo's releases listing.

use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::Path;
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::get;
use axum::Router;
use du_external::github::{GithubClient, Installer, Platform, ReleaseDownloads};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::auth::{MaybeUser, NavUser};

/// `owner/repo` publishing the Navigator installers. Overridable so a fork or a
/// staging deploy can point elsewhere.
const DEFAULT_REPO: &str = "JamesKane/decodingus-navigator";
/// Re-resolve at most this often. Well inside GitHub's unauthenticated 60/hour.
const OK_TTL: Duration = Duration::from_secs(30 * 60);
/// Back off briefly after a failure instead of hammering the API per request.
const ERR_TTL: Duration = Duration::from_secs(5 * 60);

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/download", get(page))
        // Not hx-boosted: these are cross-origin redirects to GitHub.
        .route("/download/:platform", get(redirect_platform))
}

fn repo() -> (String, String) {
    let spec = std::env::var("DU_NAVIGATOR_REPO").unwrap_or_else(|_| DEFAULT_REPO.to_string());
    match spec.split_once('/') {
        Some((o, r)) if !o.is_empty() && !r.is_empty() => (o.to_string(), r.to_string()),
        _ => {
            tracing::warn!(spec, "DU_NAVIGATOR_REPO is not owner/repo — using the default");
            DEFAULT_REPO.split_once('/').map(|(o, r)| (o.to_string(), r.to_string())).unwrap()
        }
    }
}

fn releases_url() -> String {
    let (owner, repo) = repo();
    format!("https://github.com/{owner}/{repo}/releases")
}

// ── resolution cache ─────────────────────────────────────────────────────────

/// Last resolution + when it happened. `release: None` records "GitHub said
/// nothing useful (or didn't answer)", which is cached briefly too.
struct Entry {
    at: Instant,
    release: Option<Arc<ReleaseDownloads>>,
}

static CACHE: OnceLock<Mutex<Option<Entry>>> = OnceLock::new();

fn cache() -> &'static Mutex<Option<Entry>> {
    CACHE.get_or_init(|| Mutex::new(None))
}

/// The current release, from cache when fresh. Concurrent misses may each fetch;
/// that's a bounded, harmless duplicate rather than a lock held across an await.
async fn current_release() -> Option<Arc<ReleaseDownloads>> {
    {
        let guard = cache().lock().expect("download cache mutex");
        if let Some(e) = guard.as_ref() {
            let ttl = if e.release.is_some() { OK_TTL } else { ERR_TTL };
            if e.at.elapsed() < ttl {
                return e.release.clone();
            }
        }
    }

    let (owner, name) = repo();
    let fetched = match GithubClient::new().latest_installer_release(&owner, &name).await {
        Ok(Some(r)) => Some(Arc::new(r)),
        Ok(None) => {
            tracing::warn!(repo = %format!("{owner}/{name}"), "no release publishes installer assets");
            None
        }
        Err(e) => {
            // Serve the fallback rather than a 500: a download page that links the
            // releases listing still gets people the app.
            tracing::warn!(error = %e, "GitHub release lookup failed");
            None
        }
    };
    *cache().lock().expect("download cache mutex") =
        Some(Entry { at: Instant::now(), release: fetched.clone() });
    fetched
}

// ── view model ───────────────────────────────────────────────────────────────

/// One downloadable file. The list is ordered primary-first, which is what the
/// template keys the big button off.
struct FileView {
    name: String,
    url: String,
    size: String,
    kind: String,
    arch: String,
}

struct PlatformView {
    slug: &'static str,
    label: String,
    icon: &'static str,
    /// Requirements/format note, e.g. "Windows 10 or later".
    note: String,
    files: Vec<FileView>,
    /// Matches the visitor's own OS (per User-Agent) — rendered first, highlighted.
    detected: bool,
}

struct DownloadView {
    /// `None` when GitHub couldn't be reached or publishes no installers; the
    /// template then falls back to the releases listing.
    version: Option<String>,
    published: Option<String>,
    prerelease: bool,
    release_url: String,
    releases_url: String,
    checksums_url: Option<String>,
    platforms: Vec<PlatformView>,
}

#[derive(askama::Template)]
#[template(path = "static/download.html")]
struct DownloadTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    d: DownloadView,
}

/// Human-readable size. GitHub reports exact bytes; installers are ~100–140 MB,
/// so MB with one decimal is the useful granularity.
fn fmt_size(bytes: u64) -> String {
    const MB: f64 = 1024.0 * 1024.0;
    if bytes == 0 {
        return String::new();
    }
    format!("{:.1} MB", bytes as f64 / MB)
}

/// Guess the visitor's platform from the User-Agent so their build is offered
/// first. Only ever a convenience — every platform stays visible and downloadable.
fn detect_platform(user_agent: &str) -> Option<Platform> {
    let ua = user_agent.to_ascii_lowercase();
    // Order matters: Android UAs contain "linux", and iOS UAs contain "mac os x".
    if ua.contains("android") || ua.contains("iphone") || ua.contains("ipad") {
        return None;
    }
    if ua.contains("windows") {
        Some(Platform::Windows)
    } else if ua.contains("mac os") || ua.contains("macintosh") {
        Some(Platform::MacOs)
    } else if ua.contains("linux") || ua.contains("x11") {
        Some(Platform::Linux)
    } else {
        None
    }
}

fn to_file(i: &Installer) -> FileView {
    FileView {
        name: i.name.clone(),
        url: i.url.clone(),
        size: fmt_size(i.size),
        kind: i.kind.to_string(),
        arch: i.arch.to_string(),
    }
}

fn build_view(release: Option<&ReleaseDownloads>, t: &T, detected: Option<Platform>) -> DownloadView {
    let specs = [
        (Platform::Windows, "windows", "bi-windows", "dl.note.windows"),
        (Platform::MacOs, "macos", "bi-apple", "dl.note.macos"),
        (Platform::Linux, "linux", "bi-ubuntu", "dl.note.linux"),
    ];
    let mut platforms: Vec<PlatformView> = specs
        .iter()
        .map(|(p, slug, icon, note)| PlatformView {
            slug,
            label: t.get(&format!("dl.platform.{slug}")).to_string(),
            icon,
            note: t.get(note).to_string(),
            files: release.map(|r| r.for_platform(*p).into_iter().map(to_file).collect()).unwrap_or_default(),
            detected: detected == Some(*p),
        })
        .collect();
    // The visitor's own platform leads; the rest keep Windows/macOS/Linux order.
    platforms.sort_by_key(|p| !p.detected);

    DownloadView {
        version: release.map(|r| r.tag.clone()),
        published: release.and_then(|r| r.published_at).map(|d| d.format("%Y-%m-%d").to_string()),
        prerelease: release.map(|r| r.prerelease).unwrap_or(false),
        release_url: release.map(|r| r.html_url.clone()).unwrap_or_else(releases_url),
        releases_url: releases_url(),
        checksums_url: release.and_then(|r| r.checksums_url.clone()),
        platforms,
    }
}

async fn page(locale: Locale, user: MaybeUser, headers: axum::http::HeaderMap) -> Response {
    let ua = headers.get(axum::http::header::USER_AGENT).and_then(|v| v.to_str().ok()).unwrap_or("");
    let release = current_release().await;
    let d = build_view(release.as_deref(), &locale.t, detect_platform(ua));
    html(&DownloadTemplate { t: locale.t, next: locale.next, user: user.nav(), d })
}

/// `GET /download/{windows|macos|linux}` — the permanent link. 302s to the
/// current release's installer for that platform, or to the releases listing when
/// it can't be resolved (unknown platform slug included: better a real page than
/// a 404).
async fn redirect_platform(Path(platform): Path<String>) -> Response {
    let target = match Platform::parse(&platform.to_ascii_lowercase()) {
        Some(p) => current_release()
            .await
            .and_then(|r| r.primary_for(p).map(|i| i.url.clone()))
            .unwrap_or_else(releases_url),
        None => releases_url(),
    };
    // Temporary: the target changes with every release, so it must not be cached.
    Redirect::temporary(&target).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::i18n::Lang;
    use askama::Template;
    use du_external::github::Installer;

    fn t() -> T {
        T::new(Lang::En)
    }

    fn installer(platform: Platform, kind: &'static str, arch: &'static str, primary: bool) -> Installer {
        Installer {
            platform,
            arch,
            kind,
            name: format!("navigator_0.1.0_{arch}.{kind}"),
            url: format!("https://x/{arch}.{kind}"),
            size: 110_000_000,
            primary,
        }
    }

    fn release() -> ReleaseDownloads {
        ReleaseDownloads {
            tag: "v0.1.0-alpha.15".into(),
            name: None,
            html_url: "https://github.com/o/r/releases/tag/v0.1.0-alpha.15".into(),
            published_at: Some("2026-08-01T12:38:34Z".parse().unwrap()),
            prerelease: true,
            installers: vec![
                installer(Platform::Windows, "exe", "x86_64", true),
                installer(Platform::MacOs, "dmg", "universal", true),
                installer(Platform::Linux, "AppImage", "x86_64", true),
                installer(Platform::Linux, "deb", "arm64", false),
            ],
            checksums_url: Some("https://x/sums".into()),
        }
    }

    #[test]
    fn page_lists_every_platform_with_version_and_checksums() {
        let html = DownloadTemplate {
            t: t(),
            next: "/".into(),
            user: None,
            d: build_view(Some(&release()), &t(), None),
        }
        .render()
        .unwrap();
        assert!(html.contains("v0.1.0-alpha.15"), "names the resolved version");
        assert!(html.contains("2026-08-01"), "and when it shipped");
        assert!(html.contains("https://x/x86_64.exe"), "windows installer");
        assert!(html.contains("https://x/universal.dmg"), "macOS installer");
        assert!(html.contains("https://x/x86_64.AppImage"), "linux installer");
        assert!(html.contains("https://x/arm64.deb"), "and the secondary linux build");
        assert!(html.contains("https://x/sums"), "checksums for verification");
        assert!(html.contains("104.9 MB"), "sizes are human-readable");
        assert!(html.contains(t().get("dl.prerelease")), "alpha builds are labelled as such");
    }

    #[test]
    fn unresolved_release_still_links_the_releases_page() {
        let d = build_view(None, &t(), None);
        assert_eq!(d.version, None);
        assert!(d.platforms.iter().all(|p| p.files.is_empty()));
        let html =
            DownloadTemplate { t: t(), next: "/".into(), user: None, d }.render().unwrap();
        assert!(html.contains(&releases_url()), "falls back to the releases listing");
        // A substring, not the whole string: Askama escapes the apostrophe in "couldn't".
        assert!(html.contains("the direct links are unavailable"), "and says why there are no buttons");
        assert!(html.contains("alert-warning"), "as a visible warning");
    }

    #[test]
    fn visitors_own_platform_is_offered_first() {
        let d = build_view(Some(&release()), &t(), Some(Platform::Linux));
        assert_eq!(d.platforms[0].slug, "linux");
        assert!(d.platforms[0].detected);
        assert_eq!(d.platforms.len(), 3, "the others are still listed");
        // With no detection the canonical order stands.
        let d = build_view(Some(&release()), &t(), None);
        assert_eq!(d.platforms.iter().map(|p| p.slug).collect::<Vec<_>>(), ["windows", "macos", "linux"]);
    }

    #[test]
    fn user_agent_detection_covers_the_desktop_three() {
        let win = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
        let mac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
        let linux = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36";
        assert_eq!(detect_platform(win), Some(Platform::Windows));
        assert_eq!(detect_platform(mac), Some(Platform::MacOs));
        assert_eq!(detect_platform(linux), Some(Platform::Linux));
        // Phones run neither installer: don't pretend one of them is "theirs".
        assert_eq!(detect_platform("Mozilla/5.0 (Linux; Android 14; Pixel 8)"), None);
        assert_eq!(detect_platform("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)"), None);
        assert_eq!(detect_platform(""), None);
    }

    #[test]
    fn sizes_render_in_megabytes() {
        assert_eq!(fmt_size(109_567_821), "104.5 MB");
        assert_eq!(fmt_size(0), "", "an unknown size shows nothing rather than 0 MB");
    }

    #[test]
    fn repo_override_is_validated() {
        // Default when unset (the common case; env is process-wide so only the
        // parse rule is asserted here).
        assert_eq!(DEFAULT_REPO.split_once('/'), Some(("JamesKane", "decodingus-navigator")));
        assert!(releases_url().ends_with("/releases"));
    }
}
