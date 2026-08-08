//! Locality normalization for published ancestral origins — a pure function turning the
//! free-text place a client recorded into a `country / admin / locality` ladder the icicle can
//! group by. See `proposals/ancestral-origin-icicle.md` §3.
//!
//! **Why this lives server-side.** The wire record carries the place *as recorded*, because one
//! normalizer in the AppView is fixable without a client release and can be re-run over records
//! already ingested. A normalizer at the edge would freeze whatever each client shipped with.
//!
//! **Why it is heuristic, and stays heuristic.** The input is geocoder output — comma-separated,
//! country last — not a gazetteer key. Two synonym tables cover what the corpus actually needs
//! (Irish counties, US states); everything else passes through as recorded rather than being
//! guessed at. A wrong fold is worse than an unfolded label: it silently merges two branches'
//! origins into one slice of a chart.
//!
//! ```text
//! "Raheen, Clashmore, Co. Waterford, Ireland" → Ireland / Co. Waterford / Raheen
//! "Pickens County, SC, USA"                   → United States / South Carolina / Pickens County
//! "Moulin, Pitlochry PH16 5EP, UK"            → United Kingdom / Pitlochry / Moulin
//! "Ireland"                                   → Ireland / — / —
//! ```

/// A normalized place, coarsest first. Every field is independently optional: a record may carry
/// a country and nothing else, which is exactly what the §2.3 precision ladder produces for an
/// origin with no ancestor birth year.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PlacePath {
    /// Canonical country. The four UK constituent countries stay distinct from `United Kingdom`
    /// — `Scotland` vs `England` is the distinction a Y project reads by, and folding them to
    /// `United Kingdom` would destroy it.
    pub country: Option<String>,
    /// Canonical first-level division: an Irish county (`Co. Cork`), a US state (`Virginia`), or
    /// whatever was recorded where no synonym table applies.
    pub admin: Option<String>,
    /// The finest named place recorded below `admin`.
    pub locality: Option<String>,
}

/// Which rung of the ladder a view groups by.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Level {
    Country,
    Admin,
    Locality,
}

impl PlacePath {
    /// The label at `level`, falling back **coarser** when the requested rung is absent — a
    /// sample known only to `Ireland` groups under `Ireland` at every level rather than
    /// disappearing into "unknown", which would understate what the branch does tell us.
    ///
    /// `None` means no locality at all was recorded, and the view must draw that as its own
    /// visible slice.
    pub fn label_at(&self, level: Level) -> Option<&str> {
        let ladder: [&Option<String>; 3] = match level {
            Level::Country => [&self.country, &None, &None],
            Level::Admin => [&self.admin, &self.country, &None],
            Level::Locality => [&self.locality, &self.admin, &self.country],
        };
        ladder.into_iter().flatten().next().map(String::as_str)
    }

    pub fn is_empty(&self) -> bool {
        self.country.is_none() && self.admin.is_none() && self.locality.is_none()
    }
}

/// Normalize a recorded place string and/or a separately recorded country into a [`PlacePath`].
///
/// `place` is geocoder-shaped (`"Cork, Co. Cork, Ireland"`); `country` is whatever the client
/// recorded in its own country field, used when `place` is absent or carries no recognizable
/// country. Both may be absent, which yields an empty path.
pub fn normalize(place: Option<&str>, country: Option<&str>) -> PlacePath {
    let parts: Vec<String> = place
        .unwrap_or_default()
        .split(',')
        .map(strip_postcode)
        .filter(|s| !s.is_empty())
        .collect();

    // Consume the trailing country component(s). `"Chelmsford, England, UK"` spends two: a bare
    // `UK` behind a constituent country is a geocoder artefact, and the constituent country is
    // the more informative of the two.
    let mut rest: &[String] = &parts;
    let mut resolved = None;
    if let Some((last, head)) = rest.split_last() {
        if let Some(c) = canonical_country(last) {
            resolved = Some(c);
            rest = head;
            if resolved.as_deref() == Some(UNITED_KINGDOM) {
                if let Some((prev, prev_head)) = rest.split_last() {
                    if let Some(inner) = canonical_country(prev) {
                        if inner != UNITED_KINGDOM {
                            resolved = Some(inner);
                            rest = prev_head;
                        }
                    }
                }
            }
        }
    }
    // Still no country, but the string ends in a US state (`"Blount Co., AL"`). Infer it: a bare
    // state token in the trailing position is a US address. This runs *after* country matching,
    // so the codes that collide with countries are already claimed — `CA` is Canada, `DE` is
    // Germany, `IN` is India — and only genuinely unclaimed state tokens reach here.
    // The state itself stays in `rest`: it is this string's admin component, and whatever sits
    // above it is still the locality (`"Blount Co., AL"` → Alabama / Blount Co.).
    if resolved.is_none() && rest.last().is_some_and(|l| us_state(l).is_some()) {
        resolved = Some("United States".to_string());
    }
    // No country anywhere in the place string — fall back to the recorded country field, which is
    // *declared* to be a country, so an unrecognized value is taken at its word rather than
    // dropped. The strict table is only needed for the place string, where recognition is how we
    // tell a country component from a place component; here there is nothing to disambiguate.
    // Without this, every origin outside the synonym table (Israel, Cuba, Isle of Man, Guernsey…)
    // vanished into "no locality recorded" despite having one.
    let country = resolved.or_else(|| {
        country
            .map(|c| canonical_country(c).unwrap_or_else(|| titled(strip_qualifier(c))))
            .filter(|c| !c.is_empty())
    });

    let admin = rest
        .last()
        .and_then(|raw| canonical_admin(country.as_deref(), raw));
    // Only when something sits *above* the admin component is there a finer locality to name.
    let locality = (rest.len() >= 2).then(|| titled(&rest[0]));

    PlacePath {
        country,
        admin,
        locality,
    }
}

const UNITED_KINGDOM: &str = "United Kingdom";

/// Fold a country token to its canonical name, or `None` when it is not a country at all — which
/// is how [`normalize`] tells a country component from a place component.
///
/// The table covers what the corpus needs; it is deliberately not exhaustive, because a *declared*
/// country field is trusted as-is by [`normalize`] rather than being checked against this list.
pub fn canonical_country(raw: &str) -> Option<String> {
    let key = squash(strip_qualifier(raw));
    let name = match key.as_str() {
        "ireland" | "republic of ireland" | "eire" | "ie" | "irl" => "Ireland",
        "northern ireland" | "n ireland" | "n. ireland" | "ulster" => "Northern Ireland",
        "scotland" | "alba" => "Scotland",
        "england" => "England",
        "wales" | "cymru" => "Wales",
        "uk" | "u.k." | "united kingdom" | "great britain" | "britain" | "gb" => UNITED_KINGDOM,
        "usa" | "u.s.a." | "us" | "u.s." | "united states" | "united states of america" => "United States",
        "canada" | "ca" => "Canada",
        "australia" | "au" => "Australia",
        "new zealand" | "nz" => "New Zealand",
        "germany" | "deutschland" | "de" => "Germany",
        "france" | "fr" => "France",
        "spain" | "espana" | "es" => "Spain",
        "italy" | "italia" | "it" => "Italy",
        "norway" | "norge" | "no" => "Norway",
        "sweden" | "sverige" | "se" => "Sweden",
        "denmark" | "danmark" | "dk" => "Denmark",
        "netherlands" | "the netherlands" | "holland" | "nl" => "Netherlands",
        "belgium" | "be" => "Belgium",
        "switzerland" | "ch" => "Switzerland",
        "austria" | "at" => "Austria",
        "poland" | "polska" | "pl" => "Poland",
        "portugal" | "pt" => "Portugal",
        "finland" | "suomi" | "fi" => "Finland",
        "russia" | "russian federation" | "ru" => "Russia",
        "iceland" | "is" => "Iceland",
        "luxembourg" | "lu" => "Luxembourg",
        "czech republic" | "czechia" | "cz" => "Czech Republic",
        "hungary" | "hu" => "Hungary",
        "greece" | "gr" => "Greece",
        "turkey" | "turkiye" | "tr" => "Turkey",
        "ukraine" | "ua" => "Ukraine",
        "romania" | "ro" => "Romania",
        "india" | "in" => "India",
        "china" | "cn" => "China",
        "japan" | "jp" => "Japan",
        "mexico" | "mx" => "Mexico",
        "brazil" | "br" => "Brazil",
        "argentina" | "ar" => "Argentina",
        "south africa" | "za" => "South Africa",
        // Caribbean and Atlantic origins recur in the corpus alongside the Irish diaspora.
        "barbados" | "bb" => "Barbados",
        "jamaica" | "jm" => "Jamaica",
        "aruba" | "aw" => "Aruba",
        "martinique" | "mq" => "Martinique",
        "cayman islands" | "ky" => "Cayman Islands",
        "saint kitts and nevis" | "st kitts and nevis" | "kn" => "Saint Kitts and Nevis",
        "bermuda" | "bm" => "Bermuda",
        _ => return None,
    };
    Some(name.to_string())
}

/// Fold a first-level division against its country's synonym table. Unknown values pass through
/// title-cased rather than being dropped — an unrecognized county is still a real distinction.
fn canonical_admin(country: Option<&str>, raw: &str) -> Option<String> {
    let key = squash(raw);
    if key.is_empty() {
        return None;
    }
    match country {
        // The 32 counties, however the geocoder spelled them. `Cork` and `Co. Cork` are the same
        // county; `Cork` the city normalizes here too, and is recovered as the locality when the
        // string carried one.
        Some("Ireland") | Some("Northern Ireland") => {
            let bare = key
                .trim_start_matches("county ")
                .trim_start_matches("co. ")
                .trim_start_matches("co ")
                .trim();
            IRISH_COUNTIES
                .iter()
                .find(|c| squash(c) == bare)
                .map(|c| format!("Co. {c}"))
                .or_else(|| Some(titled(raw)))
        }
        Some("United States") => us_state(raw).or_else(|| Some(titled(raw))),
        _ => Some(titled(raw)),
    }
}

/// A US state by postal abbreviation or full name, canonicalized to the full name. `None` for
/// anything else — which is also how [`normalize`] recognizes a bare trailing state token.
fn us_state(raw: &str) -> Option<String> {
    let key = squash(raw);
    US_STATES
        .iter()
        .find(|(abbr, name)| key == squash(abbr) || key == squash(name))
        .map(|(_, name)| (*name).to_string())
}

/// Drop a parenthetical qualifier: the corpus writes `"United States (Native American)"`, which is
/// an ancestry note attached to a country field, and keeping it would put that lineage in a
/// country of its own.
fn strip_qualifier(raw: &str) -> &str {
    match raw.split_once('(') {
        Some((head, _)) if !head.trim().is_empty() => head,
        _ => raw,
    }
}

/// Lowercase, collapse internal whitespace, drop surrounding punctuation — the comparison key.
fn squash(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut space = false;
    for ch in s.trim().chars() {
        if ch.is_whitespace() {
            space = !out.is_empty();
        } else {
            if space {
                out.push(' ');
                space = false;
            }
            out.extend(ch.to_lowercase());
        }
    }
    out
}

/// Trim a component and remove an embedded postal code. Geocoded strings carry them mid-string —
/// `"Pitlochry PH16 5EP"`, `"VA 24521"` — where they would otherwise make every town and every
/// ZIP its own distinct admin. Measured on the reference corpus, leaving US ZIPs in produced
/// dozens of singleton "admins" (`Va 24521`, `Wv 26801`) that are all one state.
fn strip_postcode(part: &str) -> String {
    let kept: Vec<&str> = part
        .split_whitespace()
        .filter(|t| !is_uk_postcode_token(t) && !is_us_zip_token(t))
        .collect();
    kept.join(" ").trim().to_string()
}

/// A UK postcode half: an outward code (`PH16`, `SW1A`) or an inward code (`5EP`) — a token mixing
/// letters and digits, no longer than four characters. Deliberately narrow: `"1st"` and ordinary
/// words must survive, and a real place name never looks like this.
fn is_uk_postcode_token(t: &str) -> bool {
    let t = t.trim_matches(|c: char| !c.is_alphanumeric());
    (2..=4).contains(&t.len())
        && t.chars().all(|c| c.is_ascii_alphanumeric())
        && t.chars().any(|c| c.is_ascii_digit())
        && t.chars().any(|c| c.is_ascii_alphabetic())
}

/// A US ZIP (`24521`) or ZIP+4 (`22554-7232`). Five digits exactly, so four-digit years — which
/// the corpus does carry in free-text notes — survive.
fn is_us_zip_token(t: &str) -> bool {
    let t = t.trim_matches(|c: char| !c.is_alphanumeric());
    let (head, tail) = match t.split_once('-') {
        Some((h, t4)) => (h, Some(t4)),
        None => (t, None),
    };
    head.len() == 5
        && head.chars().all(|c| c.is_ascii_digit())
        && tail.is_none_or(|t4| t4.len() == 4 && t4.chars().all(|c| c.is_ascii_digit()))
}

/// Title-case a pass-through label, preserving what the recorder wrote where it is already mixed
/// case (`"Na h-Eileanan an Iar"`, `"O'Brien"`) — only an all-lower or all-upper token is recased.
fn titled(raw: &str) -> String {
    let s = raw.trim();
    let uniform = s.chars().filter(|c| c.is_alphabetic()).all(char::is_lowercase)
        || s.chars().filter(|c| c.is_alphabetic()).all(char::is_uppercase);
    if !uniform {
        return s.to_string();
    }
    s.split(' ')
        .map(|w| {
            let mut chars = w.chars();
            match chars.next() {
                Some(f) => f.to_uppercase().collect::<String>() + &chars.as_str().to_lowercase(),
                None => String::new(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

const IRISH_COUNTIES: [&str; 32] = [
    "Antrim", "Armagh", "Carlow", "Cavan", "Clare", "Cork", "Derry", "Donegal", "Down", "Dublin",
    "Fermanagh", "Galway", "Kerry", "Kildare", "Kilkenny", "Laois", "Leitrim", "Limerick",
    "Longford", "Louth", "Mayo", "Meath", "Monaghan", "Offaly", "Roscommon", "Sligo", "Tipperary",
    "Tyrone", "Waterford", "Westmeath", "Wexford", "Wicklow",
];

const US_STATES: [(&str, &str); 51] = [
    ("AL", "Alabama"), ("AK", "Alaska"), ("AZ", "Arizona"), ("AR", "Arkansas"),
    ("CA", "California"), ("CO", "Colorado"), ("CT", "Connecticut"), ("DE", "Delaware"),
    ("DC", "District of Columbia"), ("FL", "Florida"), ("GA", "Georgia"), ("HI", "Hawaii"),
    ("ID", "Idaho"), ("IL", "Illinois"), ("IN", "Indiana"), ("IA", "Iowa"), ("KS", "Kansas"),
    ("KY", "Kentucky"), ("LA", "Louisiana"), ("ME", "Maine"), ("MD", "Maryland"),
    ("MA", "Massachusetts"), ("MI", "Michigan"), ("MN", "Minnesota"), ("MS", "Mississippi"),
    ("MO", "Missouri"), ("MT", "Montana"), ("NE", "Nebraska"), ("NV", "Nevada"),
    ("NH", "New Hampshire"), ("NJ", "New Jersey"), ("NM", "New Mexico"), ("NY", "New York"),
    ("NC", "North Carolina"), ("ND", "North Dakota"), ("OH", "Ohio"), ("OK", "Oklahoma"),
    ("OR", "Oregon"), ("PA", "Pennsylvania"), ("RI", "Rhode Island"), ("SC", "South Carolina"),
    ("SD", "South Dakota"), ("TN", "Tennessee"), ("TX", "Texas"), ("UT", "Utah"),
    ("VT", "Vermont"), ("VA", "Virginia"), ("WA", "Washington"), ("WV", "West Virginia"),
    ("WI", "Wisconsin"), ("WY", "Wyoming"),
];

#[cfg(test)]
mod tests {
    use super::*;

    fn p(place: &str) -> PlacePath {
        normalize(Some(place), None)
    }

    #[test]
    fn irish_townland_resolves_the_whole_ladder() {
        assert_eq!(
            p("Raheen, Clashmore, Co. Waterford, Ireland"),
            PlacePath {
                country: Some("Ireland".into()),
                admin: Some("Co. Waterford".into()),
                locality: Some("Raheen".into()),
            }
        );
    }

    /// The fold that matters most: the corpus writes one county three ways, and a chart that
    /// keeps them apart splits a branch's origin across three slices.
    #[test]
    fn county_synonyms_fold_together() {
        for s in [
            "Cork, Co. Cork, Ireland",
            "Cork, County Cork, Ireland",
            "Cork, Cork, Ireland",
        ] {
            assert_eq!(p(s).admin.as_deref(), Some("Co. Cork"), "{s}");
        }
        // The city survives as the locality — folding the admin must not consume it.
        assert_eq!(p("Cork, Co. Cork, Ireland").locality.as_deref(), Some("Cork"));
    }

    #[test]
    fn us_states_fold_abbreviation_to_name() {
        assert_eq!(
            p("Pickens County, SC, USA"),
            PlacePath {
                country: Some("United States".into()),
                admin: Some("South Carolina".into()),
                locality: Some("Pickens County".into()),
            }
        );
        assert_eq!(p("Amelia County, Virginia, USA").admin.as_deref(), Some("Virginia"));
        // Two components: the state is the admin, and there is no finer place to name.
        assert_eq!(
            p("Kentucky, USA"),
            PlacePath {
                country: Some("United States".into()),
                admin: Some("Kentucky".into()),
                locality: None,
            }
        );
    }

    #[test]
    fn uk_postcodes_are_stripped_not_treated_as_places() {
        assert_eq!(
            p("Moulin, Pitlochry PH16 5EP, UK"),
            PlacePath {
                country: Some(UNITED_KINGDOM.into()),
                admin: Some("Pitlochry".into()),
                locality: Some("Moulin".into()),
            }
        );
    }

    /// A bare `UK` behind a constituent country is a geocoder artefact. Scotland vs England is
    /// the distinction a Y project reads by, so the constituent country wins.
    #[test]
    fn constituent_country_outranks_a_trailing_uk() {
        assert_eq!(p("Chelmsford, England, UK").country.as_deref(), Some("England"));
        assert_eq!(p("Isle of Lewis, Scotland").country.as_deref(), Some("Scotland"));
        // Nothing to promote to: no gazetteer says which country this council area sits in, and
        // guessing would be worse than leaving it.
        assert_eq!(p("Na h-Eileanan an Iar, UK").country.as_deref(), Some(UNITED_KINGDOM));
    }

    #[test]
    fn country_only_strings_and_spelling_variants() {
        assert_eq!(
            p("Ireland"),
            PlacePath { country: Some("Ireland".into()), admin: None, locality: None }
        );
        for s in ["Republic of Ireland", "ireland", "  IRELAND  "] {
            assert_eq!(p(s).country.as_deref(), Some("Ireland"), "{s}");
        }
    }

    /// The recorded country field carries the answer when the place string has no country — and
    /// the place parts below it stay usable.
    #[test]
    fn falls_back_to_the_recorded_country_field() {
        let path = normalize(Some("Ballyvaughan, Co. Clare"), Some("Ireland"));
        assert_eq!(path.country.as_deref(), Some("Ireland"));
        assert_eq!(path.admin.as_deref(), Some("Co. Clare"));
        assert_eq!(path.locality.as_deref(), Some("Ballyvaughan"));

        // Country alone — what the §2.3 precision ladder yields with no ancestor birth year.
        assert_eq!(
            normalize(None, Some("Scotland")),
            PlacePath { country: Some("Scotland".into()), admin: None, locality: None }
        );
        assert!(normalize(None, None).is_empty());
    }

    /// An unrecognized division is kept, not dropped: it is still a real distinction, and
    /// discarding it would silently merge two origins.
    #[test]
    fn unknown_admin_passes_through_title_cased() {
        assert_eq!(p("Bergen, hordaland, Norway").admin.as_deref(), Some("Hordaland"));
        // Already mixed-case stays exactly as recorded.
        assert_eq!(p("Foo, Na h-Eileanan an Iar, Scotland").admin.as_deref(), Some("Na h-Eileanan an Iar"));
    }

    #[test]
    fn label_at_falls_back_coarser_never_to_unknown() {
        let only_country = normalize(None, Some("Ireland"));
        assert_eq!(only_country.label_at(Level::Country), Some("Ireland"));
        assert_eq!(only_country.label_at(Level::Admin), Some("Ireland"));
        assert_eq!(only_country.label_at(Level::Locality), Some("Ireland"));

        let full = p("Raheen, Clashmore, Co. Waterford, Ireland");
        assert_eq!(full.label_at(Level::Country), Some("Ireland"));
        assert_eq!(full.label_at(Level::Admin), Some("Co. Waterford"));
        assert_eq!(full.label_at(Level::Locality), Some("Raheen"));

        // Nothing recorded stays nothing — the view must draw this as its own slice.
        assert_eq!(normalize(None, None).label_at(Level::Country), None);
    }

    /// A postcode-shaped token is narrow on purpose: ordinary words and ordinals must survive.
    #[test]
    fn postcode_detection_does_not_eat_real_words() {
        assert!(is_uk_postcode_token("PH16"));
        assert!(is_uk_postcode_token("5EP"));
        assert!(!is_uk_postcode_token("Cork"));
        assert!(!is_uk_postcode_token("de"));
        assert!(!is_uk_postcode_token("1234"));
        assert_eq!(p("Sligo, Co. Sligo, Ireland").locality.as_deref(), Some("Sligo"));
    }

    /// US ZIPs left in place made every town its own admin — dozens of `Va 24521` singletons
    /// across the reference corpus, all of them Virginia.
    #[test]
    fn us_zips_are_stripped_so_the_state_folds() {
        assert!(is_us_zip_token("24521"));
        assert!(is_us_zip_token("22554-7232"));
        assert!(!is_us_zip_token("1783"), "a four-digit year is not a ZIP");
        assert!(!is_us_zip_token("Cork"));

        for s in ["Amherst, VA 24521, USA", "Amherst, Virginia, USA", "Amherst, VA, USA"] {
            assert_eq!(p(s).admin.as_deref(), Some("Virginia"), "{s}");
        }
    }

    /// A bare trailing state token is a US address. This runs after country matching, so the
    /// codes that collide with countries are already claimed and cannot be stolen back.
    #[test]
    fn a_trailing_state_implies_the_united_states() {
        let path = p("Blount Co., AL");
        assert_eq!(path.country.as_deref(), Some("United States"));
        assert_eq!(path.admin.as_deref(), Some("Alabama"));
        assert_eq!(path.locality.as_deref(), Some("Blount Co."));

        // Country codes win: these must not be re-read as Canada/Germany/India state tokens.
        assert_eq!(p("Toronto, CA").country.as_deref(), Some("Canada"));
        assert_eq!(p("Berlin, DE").country.as_deref(), Some("Germany"));
        assert_eq!(p("Mumbai, IN").country.as_deref(), Some("India"));
    }

    /// A parenthetical qualifier on a country field is an ancestry note, not a country.
    #[test]
    fn parenthetical_country_qualifiers_are_dropped() {
        assert_eq!(
            normalize(None, Some("United States (Native American)")).country.as_deref(),
            Some("United States")
        );
    }

    /// A declared country field is trusted even when the synonym table has never heard of it.
    /// Requiring recognition here dropped every origin outside the table into "no locality
    /// recorded" — 21 rows of the reference corpus that plainly had one.
    #[test]
    fn an_unrecognized_country_field_is_taken_at_its_word() {
        for c in ["Israel", "Guernsey", "Isle of Man", "Slovenia"] {
            assert_eq!(normalize(None, Some(c)).country.as_deref(), Some(c), "{c}");
        }
        // A place string below it still resolves against that country.
        let path = normalize(Some("Havana, Cuba"), Some("Cuba"));
        assert_eq!(path.country.as_deref(), Some("Cuba"));
        assert_eq!(path.locality.as_deref(), Some("Havana"));
        // An empty country field is still nothing.
        assert!(normalize(None, Some("   ")).is_empty());
    }
}
