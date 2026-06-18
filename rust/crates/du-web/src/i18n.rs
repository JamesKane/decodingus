//! Lightweight i18n: Play-style `key=value` catalogs embedded at compile time,
//! a `Lang` + `T` translator, and a `Locale` extractor that resolves the active
//! language from the `lang` cookie then `Accept-Language` (default English).
//!
//! Replaces Play's `messages`/`Messages`. Keeping it dependency-free (no fluent)
//! matches the catalog format the project already used.

use axum::extract::FromRequestParts;
use axum::http::header::{ACCEPT_LANGUAGE, COOKIE};
use axum::http::request::Parts;
use std::collections::HashMap;
use std::convert::Infallible;
use std::sync::OnceLock;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Lang {
    En,
    Es,
    Fr,
}

impl Lang {
    pub fn code(self) -> &'static str {
        match self {
            Lang::En => "en",
            Lang::Es => "es",
            Lang::Fr => "fr",
        }
    }

    pub fn parse(s: &str) -> Option<Lang> {
        match s.get(0..2).map(str::to_ascii_lowercase).as_deref() {
            Some("en") => Some(Lang::En),
            Some("es") => Some(Lang::Es),
            Some("fr") => Some(Lang::Fr),
            _ => None,
        }
    }

    /// All languages, for rendering the switcher.
    pub fn all() -> [Lang; 3] {
        [Lang::En, Lang::Es, Lang::Fr]
    }
}

const EN_SRC: &str = include_str!("../../../locales/en.txt");
const ES_SRC: &str = include_str!("../../../locales/es.txt");
const FR_SRC: &str = include_str!("../../../locales/fr.txt");

fn parse_catalog(src: &'static str) -> HashMap<&'static str, &'static str> {
    src.lines()
        .filter_map(|line| {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                return None;
            }
            line.split_once('=').map(|(k, v)| (k.trim(), v.trim()))
        })
        .collect()
}

fn catalog(lang: Lang) -> &'static HashMap<&'static str, &'static str> {
    static EN: OnceLock<HashMap<&str, &str>> = OnceLock::new();
    static ES: OnceLock<HashMap<&str, &str>> = OnceLock::new();
    static FR: OnceLock<HashMap<&str, &str>> = OnceLock::new();
    match lang {
        Lang::En => EN.get_or_init(|| parse_catalog(EN_SRC)),
        Lang::Es => ES.get_or_init(|| parse_catalog(ES_SRC)),
        Lang::Fr => FR.get_or_init(|| parse_catalog(FR_SRC)),
    }
}

/// Translator for one language. Cheap to copy.
#[derive(Clone, Copy)]
pub struct T {
    pub lang: Lang,
}

impl T {
    pub fn new(lang: Lang) -> Self {
        T { lang }
    }

    /// Look up a key in the active language, falling back to English, then to
    /// the key itself. Returned slices are `'static` (from the embedded catalogs)
    /// or the borrowed key, so no allocation.
    pub fn get<'a>(&self, key: &'a str) -> &'a str {
        catalog(self.lang)
            .get(key)
            .or_else(|| catalog(Lang::En).get(key))
            .copied()
            .unwrap_or(key)
    }

    /// True when `lang` is the active language (for highlighting the switcher).
    pub fn is(&self, lang: Lang) -> bool {
        self.lang == lang
    }

    /// Options for the language switcher: (code, localized label, active).
    pub fn languages(&self) -> Vec<LangOption> {
        Lang::all()
            .into_iter()
            .map(|l| LangOption {
                code: l.code(),
                label: self.get(match l {
                    Lang::En => "lang.en",
                    Lang::Es => "lang.es",
                    Lang::Fr => "lang.fr",
                }),
                active: self.is(l),
            })
            .collect()
    }
}

pub struct LangOption {
    pub code: &'static str,
    pub label: &'static str,
    pub active: bool,
}

/// Per-request locale: the translator plus the current path (percent-encoded)
/// so the language switcher can return the user to the same page.
pub struct Locale {
    pub t: T,
    /// Current path+query, percent-encoded for use as a `?next=` value.
    pub next: String,
}

fn lang_from_cookie(parts: &Parts) -> Option<Lang> {
    let raw = parts.headers.get(COOKIE)?.to_str().ok()?;
    raw.split(';')
        .filter_map(|kv| kv.trim().split_once('='))
        .find(|(k, _)| *k == "lang")
        .and_then(|(_, v)| Lang::parse(v))
}

fn lang_from_accept(parts: &Parts) -> Option<Lang> {
    let raw = parts.headers.get(ACCEPT_LANGUAGE)?.to_str().ok()?;
    // First tag wins (ignore q-weights for our small set).
    raw.split(',').next().and_then(|tag| Lang::parse(tag.trim()))
}

#[axum::async_trait]
impl<S: Send + Sync> FromRequestParts<S> for Locale {
    type Rejection = Infallible;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, Self::Rejection> {
        let lang = lang_from_cookie(parts)
            .or_else(|| lang_from_accept(parts))
            .unwrap_or(Lang::En);
        let path_q = parts
            .uri
            .path_and_query()
            .map_or("/", |pq| pq.as_str());
        let next = percent_encoding::utf8_percent_encode(
            path_q,
            percent_encoding::NON_ALPHANUMERIC,
        )
        .to_string();
        Ok(Locale { t: T::new(lang), next })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fallback_chain_en_then_key() {
        let es = T::new(Lang::Es);
        assert_eq!(es.get("nav.home"), "Inicio");
        // a key only present implicitly falls back to English, then the key.
        assert_eq!(es.get("does.not.exist"), "does.not.exist");
    }

    #[test]
    fn catalogs_share_keys_with_english() {
        let en = catalog(Lang::En);
        for lang in [Lang::Es, Lang::Fr] {
            for k in en.keys() {
                assert!(catalog(lang).contains_key(k), "{} missing key {k}", lang.code());
            }
        }
    }
}
