//! Vendor Y-STR export parsing + import into `genomics.biosample_str_profile`.
//!
//! Source today is FTDNA Y-DNA "DYS Results" exports — one CSV per kit, in the
//! wide layout: a header row of marker names and a single value row aligned to
//! it. Values are space-padded and may be quoted (`" 13"`, `13`, `"13"`):
//!   * simple repeat   — `13`            → {type:simple, repeats:13}
//!   * multi-copy      — `11-15`, `15-15-16-17` (DYS385/DYS464/CDY/YCAII/…)
//!                                        → {type:multiCopy, copies:[…]}
//!   * missing         — `-` (or blank)  → omitted
//!   * other (e.g. partial-repeat `10.2`) → {type:complex, raw:"…"} (preserved,
//!     unscored — same treatment fed profiles give palindromic/complex values)
//!
//! Output `markers` is the lexicon's `strMarkerValue[]` JSONB, identical to the
//! federated shape [`crate::ystr::parse_markers`] reads, so both feed one model.
//!
//! Non-wide inputs (empty exports, HTML error pages saved as `.csv`, the rare
//! `marker;value` long layout) are reported as [`SkipReason`] and skipped — the
//! loader counts them rather than guessing.

use crate::DbError;
use serde_json::{json, Value};
use sqlx::PgPool;
use std::collections::HashMap;
use uuid::Uuid;

/// A parsed vendor STR export ready to upsert.
#[derive(Debug, Clone, PartialEq)]
pub struct ParsedProfile {
    /// Lexicon `strMarkerValue[]` — missing markers omitted.
    pub markers: Value,
    /// Count of present (non-missing) markers.
    pub total_markers: i32,
}

/// Why a file could not be parsed as a wide-format vendor export.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SkipReason {
    /// No header+value rows (blank/whitespace-only export).
    Empty,
    /// An HTML page (FTDNA error/login) saved with a `.csv` name.
    Html,
    /// The `marker;value` per-line long layout (not handled in v1).
    LongFormat,
    /// Header/value present but every marker was missing.
    AllMissing,
}

impl SkipReason {
    pub fn as_str(self) -> &'static str {
        match self {
            SkipReason::Empty => "empty",
            SkipReason::Html => "html",
            SkipReason::LongFormat => "long-format",
            SkipReason::AllMissing => "all-missing",
        }
    }
}

/// Split a CSV line into trimmed, unquoted fields. The vendor exports never
/// embed a comma inside a value (marker names use `-`, not `,`), so a plain
/// comma split — then strip surrounding quotes + whitespace — is sufficient and
/// handles the quoted, unquoted, and space-padded variants alike.
fn fields(line: &str) -> Vec<String> {
    line.split(',')
        .map(|f| f.trim().trim_matches('"').trim().to_string())
        .collect()
}

/// Parse one vendor token into a lexicon `strValue`, or `None` if missing.
pub fn parse_token(raw: &str) -> Option<Value> {
    let t = raw.trim().trim_matches('"').trim();
    if t.is_empty() || t == "-" {
        return None;
    }
    let parts: Vec<&str> = t.split('-').map(str::trim).filter(|p| !p.is_empty()).collect();
    let ints: Option<Vec<i32>> = parts.iter().map(|p| p.parse::<i32>().ok()).collect();
    Some(match ints {
        Some(v) if v.len() == 1 => json!({ "type": "simple", "repeats": v[0] }),
        Some(v) if v.len() > 1 => json!({ "type": "multiCopy", "copies": v }),
        // Unparseable (partial repeats, footnote marks): keep losslessly, unscored.
        _ => json!({ "type": "complex", "raw": t }),
    })
}

/// Parse a wide-format vendor CSV (header row of markers + one value row) into a
/// lexicon profile. `Err(SkipReason)` for inputs that aren't a wide export.
pub fn parse_wide_csv(text: &str) -> Result<ParsedProfile, SkipReason> {
    let lower = text.to_ascii_lowercase();
    if lower.contains("<!doctype") || lower.contains("<html") {
        return Err(SkipReason::Html);
    }
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    if lines.len() < 2 {
        return Err(SkipReason::Empty);
    }
    // The `marker;value;` long layout has one marker per line and no comma header.
    if lines[0].contains(';') && !lines[0].contains(',') {
        return Err(SkipReason::LongFormat);
    }
    let header = fields(lines[0]);
    let values = fields(lines[1]);
    let mut markers = Vec::new();
    for (name, raw) in header.iter().zip(values.iter()) {
        if name.is_empty() {
            continue;
        }
        if let Some(value) = parse_token(raw) {
            markers.push(json!({ "marker": name, "value": value }));
        }
    }
    if markers.is_empty() {
        return Err(SkipReason::AllMissing);
    }
    let total_markers = markers.len() as i32;
    Ok(ParsedProfile { markers: Value::Array(markers), total_markers })
}

/// Map the given accessions to their `core.biosample.sample_guid` (absent
/// accessions are simply missing from the result — e.g. a NAS sample not loaded
/// into this database). The cohort `subject_id` is stored as the accession.
pub async fn guids_by_accessions(
    pool: &PgPool,
    accessions: &[String],
) -> Result<HashMap<String, Uuid>, DbError> {
    let rows: Vec<(String, Uuid)> = sqlx::query_as(
        "SELECT accession, sample_guid FROM core.biosample \
         WHERE accession = ANY($1) AND deleted = false",
    )
    .bind(accessions)
    .fetch_all(pool)
    .await?;
    Ok(rows.into_iter().collect())
}

/// Upsert a sample's imported STR profile. A sample with several vendor kits
/// keeps the most complete one (highest marker count); re-imports of the same
/// kit are idempotent.
pub async fn upsert_profile(
    pool: &PgPool,
    sample_guid: Uuid,
    imported_from: &str,
    panel: &str,
    profile: &ParsedProfile,
    source_file: &str,
) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO genomics.biosample_str_profile \
           (sample_guid, source, imported_from, panel, total_markers, markers, source_file) \
         VALUES ($1, 'IMPORTED', $2, $3, $4, $5, $6) \
         ON CONFLICT (sample_guid) DO UPDATE SET \
           imported_from = EXCLUDED.imported_from, panel = EXCLUDED.panel, \
           total_markers = EXCLUDED.total_markers, markers = EXCLUDED.markers, \
           source_file = EXCLUDED.source_file, imported_at = now() \
         WHERE EXCLUDED.total_markers >= genomics.biosample_str_profile.total_markers",
    )
    .bind(sample_guid)
    .bind(imported_from)
    .bind(panel)
    .bind(profile.total_markers)
    .bind(&profile.markers)
    .bind(source_file)
    .execute(pool)
    .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_tokens() {
        assert_eq!(parse_token(" 13"), Some(json!({"type":"simple","repeats":13})));
        assert_eq!(parse_token("\"13\""), Some(json!({"type":"simple","repeats":13})));
        assert_eq!(parse_token(" 11-15"), Some(json!({"type":"multiCopy","copies":[11,15]})));
        assert_eq!(
            parse_token(" 15-15-16-17"),
            Some(json!({"type":"multiCopy","copies":[15,15,16,17]}))
        );
        assert_eq!(parse_token(" -"), None);
        assert_eq!(parse_token("  "), None);
        // partial-repeat / footnoted values are preserved as complex, not dropped
        assert_eq!(parse_token("10.2"), Some(json!({"type":"complex","raw":"10.2"})));
    }

    #[test]
    fn parses_wide_csv_quoted() {
        let csv = "DYS393,DYS390,DYS385,DYS459\n\" 13\",\" 24\",\" 11-15\",\" 9-10\"\n";
        let p = parse_wide_csv(csv).unwrap();
        assert_eq!(p.total_markers, 4);
        let arr = p.markers.as_array().unwrap();
        assert_eq!(arr[0], json!({"marker":"DYS393","value":{"type":"simple","repeats":13}}));
        assert_eq!(arr[2], json!({"marker":"DYS385","value":{"type":"multiCopy","copies":[11,15]}}));
    }

    #[test]
    fn parses_wide_csv_unquoted_and_skips_missing() {
        let csv = "DYS393,DYS390,DYS19\n13,24,-\n";
        let p = parse_wide_csv(csv).unwrap();
        assert_eq!(p.total_markers, 2); // DYS19 = '-' omitted
    }

    #[test]
    fn classifies_non_wide_inputs() {
        assert_eq!(parse_wide_csv("   \n"), Err(SkipReason::Empty));
        assert_eq!(parse_wide_csv("<!DOCTYPE html>\n<html></html>"), Err(SkipReason::Html));
        assert_eq!(parse_wide_csv("DYS393;13;\nDYS390;24;\n"), Err(SkipReason::LongFormat));
    }
}
