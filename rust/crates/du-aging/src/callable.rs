//! Callable-bp extraction from a GATK CallableLoci BED.
//!
//! The BED's 4th column is the state (`CALLABLE`, `LOW_COVERAGE`,
//! `POOR_MAPPING_QUALITY`, …); `du_bio::callable::summarize_bed` would count
//! every interval, so we filter to `CALLABLE` here. We then intersect the
//! merged callable intervals with the Y sequence classes from
//! `core.genome_region` to partition into x-degenerate / ampliconic /
//! palindromic bp — the het-consistent denominator the age model wants.

use std::fs;
use std::path::Path;

use anyhow::{Context, Result};
use du_bio::callable::{merge, Interval};

/// Merged `CALLABLE` intervals on `contig` from a GATK CallableLoci BED.
pub fn callable_intervals(bed_path: &Path, contig: &str) -> Result<Vec<Interval>> {
    let text = fs::read_to_string(bed_path).with_context(|| format!("read {}", bed_path.display()))?;
    let mut ivs = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with("track") || line.starts_with("browser") {
            continue;
        }
        let mut cols = line.split('\t');
        let c = cols.next().unwrap_or("");
        let start = cols.next().and_then(|s| s.parse::<i64>().ok());
        let end = cols.next().and_then(|s| s.parse::<i64>().ok());
        let state = cols.next().unwrap_or("");
        if c != contig || state != "CALLABLE" {
            continue;
        }
        if let (Some(start), Some(end)) = (start, end) {
            ivs.push(Interval { start, end });
        }
    }
    Ok(merge(ivs))
}

/// Total bp across a set of (merged) intervals.
pub fn total_bp(ivs: &[Interval]) -> i64 {
    ivs.iter().map(|i| i.end - i.start).sum()
}

/// Intersection bp between two sorted, merged interval sets (linear merge-scan).
pub fn intersect_bp(a: &[Interval], b: &[Interval]) -> i64 {
    let (mut i, mut j, mut bp) = (0usize, 0usize, 0i64);
    while i < a.len() && j < b.len() {
        let lo = a[i].start.max(b[j].start);
        let hi = a[i].end.min(b[j].end);
        if lo < hi {
            bp += hi - lo;
        }
        // advance whichever interval ends first
        if a[i].end < b[j].end {
            i += 1;
        } else {
            j += 1;
        }
    }
    bp
}

/// Per-sample callable summary the age model consumes.
#[derive(Debug, Default, Clone)]
pub struct CallableBp {
    pub total: i64,
    pub xdegen: i64,
    pub ampliconic: i64,
    pub palindromic: i64,
}

/// Partition merged callable intervals against the Y region classes (each already
/// merged). Classes may overlap the catalog differently; intersection is per-class.
pub fn partition(callable: &[Interval], xdegen: &[Interval], ampliconic: &[Interval], palindromic: &[Interval]) -> CallableBp {
    CallableBp {
        total: total_bp(callable),
        xdegen: intersect_bp(callable, xdegen),
        ampliconic: intersect_bp(callable, ampliconic),
        palindromic: intersect_bp(callable, palindromic),
    }
}
