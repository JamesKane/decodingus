//! GitHub Releases client — resolves the current Navigator installer downloads.
//!
//! The obvious approach (link `…/releases/latest/download/<asset>`) does not work
//! for this project on two counts:
//!
//! 1. GitHub's "latest" excludes pre-releases, and every Navigator installer
//!    release is an alpha pre-release. The repo's newest *stable* release is a
//!    reference-data drop (`assets-chm13v2.0`), so "latest" points at the wrong
//!    thing entirely.
//! 2. Tauri bundles the app version into every filename
//!    (`navigator_0.1.0_x64-setup.exe`), so there is no stable asset name to
//!    hard-code.
//!
//! So the site resolves downloads at runtime: list releases (newest first, drafts
//! skipped, pre-releases kept) and take the first one that actually carries
//! installer assets. Parsing and asset classification are pure and unit-tested;
//! only [`GithubClient::releases`] touches the network.

use crate::error::ExternalError;
use chrono::{DateTime, Utc};
use serde::Deserialize;

const DEFAULT_BASE: &str = "https://api.github.com";
/// GitHub rejects API requests without a User-Agent.
const USER_AGENT: &str = "decoding-us.com (+https://decoding-us.com)";
/// How many releases to scan for installers. Generous enough to see past a run of
/// asset-only or notes-only releases without paging.
const SCAN_DEPTH: u8 = 30;

/// Which platform an installer targets.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Platform {
    Windows,
    MacOs,
    Linux,
}

impl Platform {
    /// Stable slug used in `/download/{slug}` URLs.
    pub fn slug(self) -> &'static str {
        match self {
            Self::Windows => "windows",
            Self::MacOs => "macos",
            Self::Linux => "linux",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "windows" | "win" => Some(Self::Windows),
            "macos" | "mac" | "osx" => Some(Self::MacOs),
            "linux" => Some(Self::Linux),
            _ => None,
        }
    }
}

/// A downloadable installer: one release asset, classified.
#[derive(Debug, Clone, PartialEq)]
pub struct Installer {
    pub platform: Platform,
    /// `x86_64`, `arm64`, or `universal`.
    pub arch: &'static str,
    /// Package format shown to the user: `exe`, `msi`, `dmg`, `AppImage`, `deb`, `rpm`.
    pub kind: &'static str,
    pub name: String,
    pub url: String,
    pub size: u64,
    /// Preferred pick for this platform's one-click `/download/{platform}` link.
    /// The mainstream desktop build: x64 Windows installer, universal macOS
    /// disk image, x86_64 Linux AppImage.
    pub primary: bool,
}

/// A resolved release with its installer downloads.
#[derive(Debug, Clone, PartialEq)]
pub struct ReleaseDownloads {
    /// Git tag (`v0.1.0-alpha.15`).
    pub tag: String,
    /// Release title, when it differs from the tag.
    pub name: Option<String>,
    pub html_url: String,
    pub published_at: Option<DateTime<Utc>>,
    pub prerelease: bool,
    pub installers: Vec<Installer>,
    /// The `SHA256SUMS` asset, when the release publishes one.
    pub checksums_url: Option<String>,
}

impl ReleaseDownloads {
    /// The one-click download for a platform: its primary installer, else its
    /// first installer of any kind.
    pub fn primary_for(&self, p: Platform) -> Option<&Installer> {
        let mut for_platform = self.installers.iter().filter(|i| i.platform == p);
        let first = for_platform.clone().find(|i| i.primary);
        first.or_else(|| for_platform.next())
    }

    /// Installers for a platform, primary first — what the download page lists.
    pub fn for_platform(&self, p: Platform) -> Vec<&Installer> {
        let mut v: Vec<&Installer> = self.installers.iter().filter(|i| i.platform == p).collect();
        v.sort_by_key(|i| !i.primary);
        v
    }
}

// ── raw API shapes ───────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct ApiRelease {
    tag_name: String,
    name: Option<String>,
    html_url: String,
    published_at: Option<String>,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    assets: Vec<ApiAsset>,
}

#[derive(Deserialize)]
struct ApiAsset {
    name: String,
    browser_download_url: String,
    #[serde(default)]
    size: u64,
}

/// Classify a release asset by filename. Returns `None` for anything that isn't a
/// user-installable build (checksums, signatures, update manifests, source
/// tarballs, the `.app.tar.gz` updater bundle).
fn classify(name: &str, url: &str, size: u64) -> Option<Installer> {
    let lower = name.to_ascii_lowercase();
    // Tauri's updater artifacts sit beside the installers; they are not downloads.
    if lower.ends_with(".sig") || lower.ends_with(".app.tar.gz") || lower.ends_with(".tar.gz.sig") {
        return None;
    }
    let (platform, kind) = if lower.ends_with("-setup.exe") || lower.ends_with(".exe") {
        (Platform::Windows, "exe")
    } else if lower.ends_with(".msi") {
        (Platform::Windows, "msi")
    } else if lower.ends_with(".dmg") {
        (Platform::MacOs, "dmg")
    } else if lower.ends_with(".appimage") {
        (Platform::Linux, "AppImage")
    } else if lower.ends_with(".deb") {
        (Platform::Linux, "deb")
    } else if lower.ends_with(".rpm") {
        (Platform::Linux, "rpm")
    } else {
        return None;
    };

    // Architecture from the filename's arch token. Tauri spells the same
    // architecture differently per bundle (x64 / x86_64 / amd64).
    let arch = if lower.contains("universal") {
        "universal"
    } else if lower.contains("aarch64") || lower.contains("arm64") {
        "arm64"
    } else {
        // Windows/macOS bundles occasionally omit the token; x86_64 is the default target.
        "x86_64"
    };

    let primary = match platform {
        Platform::Windows => kind == "exe" && arch == "x86_64",
        Platform::MacOs => kind == "dmg" && (arch == "universal" || arch == "x86_64"),
        // AppImage runs on any distro; .deb is Debian/Ubuntu-only.
        Platform::Linux => kind == "AppImage" && arch == "x86_64",
    };

    Some(Installer {
        platform,
        arch,
        kind,
        name: name.to_string(),
        url: url.to_string(),
        size,
        primary,
    })
}

fn to_downloads(r: ApiRelease) -> ReleaseDownloads {
    let checksums_url = r
        .assets
        .iter()
        .find(|a| {
            let l = a.name.to_ascii_lowercase();
            l.starts_with("sha256") || l.ends_with(".sha256")
        })
        .map(|a| a.browser_download_url.clone());
    let installers =
        r.assets.iter().filter_map(|a| classify(&a.name, &a.browser_download_url, a.size)).collect();
    ReleaseDownloads {
        name: r.name.filter(|n| !n.trim().is_empty() && *n != r.tag_name),
        tag: r.tag_name,
        html_url: r.html_url,
        published_at: r.published_at.as_deref().and_then(|s| DateTime::parse_from_rfc3339(s).ok()).map(Into::into),
        prerelease: r.prerelease,
        installers,
        checksums_url,
    }
}

/// Pick the newest release that actually ships installers.
///
/// The API returns releases newest-first. Drafts are skipped (not public);
/// pre-releases are kept, because that is all the Navigator publishes today.
/// Releases carrying only data assets or release notes are skipped, which is what
/// keeps the reference-data drop (`assets-chm13v2.0`) from being served as the app.
fn pick_installer_release(json: &str) -> Result<Option<ReleaseDownloads>, ExternalError> {
    let releases: Vec<ApiRelease> =
        serde_json::from_str(json).map_err(|e| ExternalError::Parse(e.to_string()))?;
    Ok(releases
        .into_iter()
        .filter(|r| !r.draft)
        .map(to_downloads)
        .find(|r| !r.installers.is_empty()))
}

pub struct GithubClient {
    http: reqwest::Client,
    base: String,
    /// Optional PAT. Unauthenticated is 60 requests/hour per IP, which the
    /// caller's cache keeps us far inside; a token raises it if ever needed.
    token: Option<String>,
}

impl Default for GithubClient {
    fn default() -> Self {
        Self::new()
    }
}

impl GithubClient {
    pub fn new() -> Self {
        GithubClient {
            http: reqwest::Client::new(),
            base: DEFAULT_BASE.to_string(),
            token: std::env::var("GITHUB_TOKEN").ok().filter(|t| !t.is_empty()),
        }
    }

    /// Point the client at a different API origin (tests).
    pub fn with_base(mut self, base: impl Into<String>) -> Self {
        self.base = base.into();
        self
    }

    /// The newest release of `owner/repo` that ships installers, or `None` when
    /// the repo has published none.
    pub async fn latest_installer_release(
        &self,
        owner: &str,
        repo: &str,
    ) -> Result<Option<ReleaseDownloads>, ExternalError> {
        let url = format!("{}/repos/{owner}/{repo}/releases?per_page={SCAN_DEPTH}", self.base);
        let mut req = self
            .http
            .get(url)
            .header(reqwest::header::USER_AGENT, USER_AGENT)
            .header(reqwest::header::ACCEPT, "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28");
        if let Some(t) = &self.token {
            req = req.bearer_auth(t);
        }
        let body = req.send().await?.error_for_status()?.text().await?;
        pick_installer_release(&body)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Trimmed shape of the real `JamesKane/decodingus-navigator` response: an
    /// installer pre-release, then the stable reference-data release that
    /// GitHub's own "latest" would hand back.
    const FIXTURE: &str = r#"[
      {
        "tag_name": "v0.1.0-alpha.15",
        "name": "v0.1.0-alpha.15",
        "html_url": "https://github.com/o/r/releases/tag/v0.1.0-alpha.15",
        "published_at": "2026-08-01T12:38:34Z",
        "draft": false,
        "prerelease": true,
        "assets": [
          {"name": "DUNavigator_0.1.0_universal.dmg", "browser_download_url": "https://x/dmg", "size": 142161474},
          {"name": "navigator_0.1.0_aarch64.AppImage", "browser_download_url": "https://x/aarch64.AppImage", "size": 129448456},
          {"name": "navigator_0.1.0_amd64.deb", "browser_download_url": "https://x/amd64.deb", "size": 137012312},
          {"name": "navigator_0.1.0_arm64.deb", "browser_download_url": "https://x/arm64.deb", "size": 136128772},
          {"name": "navigator_0.1.0_x64-setup.exe", "browser_download_url": "https://x/exe", "size": 109567821},
          {"name": "navigator_0.1.0_x86_64.AppImage", "browser_download_url": "https://x/x86_64.AppImage", "size": 130357752},
          {"name": "SHA256SUMS", "browser_download_url": "https://x/sums", "size": 575}
        ]
      },
      {
        "tag_name": "assets-chm13v2.0",
        "name": "Ancestry/IBD + STR assets (chm13v2.0)",
        "html_url": "https://github.com/o/r/releases/tag/assets-chm13v2.0",
        "published_at": "2026-07-11T16:28:52Z",
        "draft": false,
        "prerelease": false,
        "assets": [{"name": "ancestry-panel.tar.zst", "browser_download_url": "https://x/panel", "size": 12}]
      }
    ]"#;

    fn fixture() -> ReleaseDownloads {
        pick_installer_release(FIXTURE).unwrap().expect("a release with installers")
    }

    #[test]
    fn picks_the_installer_release_over_githubs_latest() {
        let r = fixture();
        // The data-only release is newer in GitHub's "latest" sense (it is the only
        // non-prerelease) but ships no installers, so it must not win.
        assert_eq!(r.tag, "v0.1.0-alpha.15");
        assert!(r.prerelease, "the app only publishes alphas today");
        assert_eq!(r.name, None, "a title equal to the tag isn't worth repeating");
        assert_eq!(r.published_at.unwrap().to_rfc3339(), "2026-08-01T12:38:34+00:00");
        assert_eq!(r.checksums_url.as_deref(), Some("https://x/sums"));
    }

    #[test]
    fn classifies_every_installer_and_drops_the_rest() {
        let r = fixture();
        assert_eq!(r.installers.len(), 6, "6 installers; SHA256SUMS is not one");
        let win = r.for_platform(Platform::Windows);
        assert_eq!(win.len(), 1);
        assert_eq!((win[0].kind, win[0].arch), ("exe", "x86_64"));

        let mac = r.for_platform(Platform::MacOs);
        assert_eq!(mac.len(), 1);
        assert_eq!((mac[0].kind, mac[0].arch), ("dmg", "universal"));

        // Linux ships four: AppImage + deb, each x86_64 + arm64.
        let linux = r.for_platform(Platform::Linux);
        assert_eq!(linux.len(), 4);
        assert!(linux[0].primary, "primary sorts first");
        assert_eq!((linux[0].kind, linux[0].arch), ("AppImage", "x86_64"));
    }

    #[test]
    fn one_click_link_resolves_per_platform() {
        let r = fixture();
        assert_eq!(r.primary_for(Platform::Windows).unwrap().url, "https://x/exe");
        assert_eq!(r.primary_for(Platform::MacOs).unwrap().url, "https://x/dmg");
        // Not the arm64 AppImage and not the .deb.
        assert_eq!(r.primary_for(Platform::Linux).unwrap().url, "https://x/x86_64.AppImage");
    }

    #[test]
    fn falls_back_to_any_installer_when_none_is_primary() {
        let json = r#"[{"tag_name":"v1","html_url":"h","assets":[
            {"name":"navigator_1.0.0_arm64.deb","browser_download_url":"https://x/arm64.deb","size":1}]}]"#;
        let r = pick_installer_release(json).unwrap().unwrap();
        assert!(!r.installers[0].primary, "an arm64 .deb is nobody's default");
        assert_eq!(r.primary_for(Platform::Linux).unwrap().url, "https://x/arm64.deb");
        assert_eq!(r.primary_for(Platform::Windows), None, "no Windows build in this release");
    }

    #[test]
    fn skips_drafts_and_updater_artifacts() {
        let json = r#"[
          {"tag_name":"draft","html_url":"h","draft":true,"assets":[
            {"name":"navigator_9.9.9_x64-setup.exe","browser_download_url":"https://x/draft","size":1}]},
          {"tag_name":"v1","html_url":"h","assets":[
            {"name":"DUNavigator.app.tar.gz","browser_download_url":"https://x/updater","size":1},
            {"name":"navigator_1.0.0_x64-setup.exe.sig","browser_download_url":"https://x/sig","size":1},
            {"name":"navigator_1.0.0_x64-setup.exe","browser_download_url":"https://x/exe","size":1}]}
        ]"#;
        let r = pick_installer_release(json).unwrap().unwrap();
        assert_eq!(r.tag, "v1", "an unpublished draft is not a download");
        assert_eq!(r.installers.len(), 1, "updater bundle and signature are not installers");
    }

    #[test]
    fn no_installers_anywhere_is_not_an_error() {
        let json = r#"[{"tag_name":"assets-only","html_url":"h","assets":[
            {"name":"panel.tar.zst","browser_download_url":"https://x/p","size":1}]}]"#;
        assert_eq!(pick_installer_release(json).unwrap(), None);
        assert_eq!(pick_installer_release("[]").unwrap(), None);
    }

    #[test]
    fn platform_slugs_round_trip() {
        for p in [Platform::Windows, Platform::MacOs, Platform::Linux] {
            assert_eq!(Platform::parse(p.slug()), Some(p));
        }
        assert_eq!(Platform::parse("solaris"), None);
    }
}
