//! Callable-loci computation from BED intervals — total callable bp and region
//! count per contig (used for mutation-rate / branch-age inputs). BED is
//! 0-based half-open `[start, end)`, so a region contributes `end - start` bp.
//!
//! Replaces the htsjdk-backed coverage interval math in the legacy app.

use crate::error::BioError;
use std::collections::BTreeMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Interval {
    pub start: i64,
    pub end: i64,
}

/// Sort and merge overlapping/adjacent intervals.
pub fn merge(mut intervals: Vec<Interval>) -> Vec<Interval> {
    intervals.sort_by_key(|i| (i.start, i.end));
    let mut out: Vec<Interval> = Vec::with_capacity(intervals.len());
    for iv in intervals {
        if iv.end <= iv.start {
            continue; // drop empty/invalid
        }
        match out.last_mut() {
            Some(last) if iv.start <= last.end => last.end = last.end.max(iv.end),
            _ => out.push(iv),
        }
    }
    out
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CallableSummary {
    pub contig: String,
    pub total_callable_bp: i64,
    pub region_count: i64,
}

/// Parse a BED document (`contig\tstart\tend` per line; extra columns ignored;
/// `#`/`track`/`browser` lines skipped) and summarize callable loci per contig.
pub fn summarize_bed(bed: &str) -> Result<Vec<CallableSummary>, BioError> {
    let mut by_contig: BTreeMap<String, Vec<Interval>> = BTreeMap::new();
    for (n, line) in bed.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with("track") || line.starts_with("browser") {
            continue;
        }
        let mut cols = line.split('\t');
        let contig = cols.next().unwrap_or("");
        let start = cols
            .next()
            .and_then(|s| s.parse::<i64>().ok())
            .ok_or_else(|| BioError::Parse(format!("BED line {}: bad start", n + 1)))?;
        let end = cols
            .next()
            .and_then(|s| s.parse::<i64>().ok())
            .ok_or_else(|| BioError::Parse(format!("BED line {}: bad end", n + 1)))?;
        by_contig.entry(contig.to_string()).or_default().push(Interval { start, end });
    }

    Ok(by_contig
        .into_iter()
        .map(|(contig, ivs)| {
            let merged = merge(ivs);
            CallableSummary {
                contig,
                total_callable_bp: merged.iter().map(|i| i.end - i.start).sum(),
                region_count: merged.len() as i64,
            }
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merges_overlapping_and_adjacent() {
        let m = merge(vec![
            Interval { start: 0, end: 10 },
            Interval { start: 5, end: 15 },   // overlaps -> merge to [0,15)
            Interval { start: 15, end: 20 },  // adjacent -> merge to [0,20)
            Interval { start: 30, end: 40 },  // separate
        ]);
        assert_eq!(m, vec![Interval { start: 0, end: 20 }, Interval { start: 30, end: 40 }]);
    }

    #[test]
    fn summarizes_bed_per_contig_with_overlap_dedup() {
        let bed = "\
# header
chrY\t100\t200
chrY\t150\t250
chrY\t1000\t1100
chr1\t0\t50
";
        let mut s = summarize_bed(bed).unwrap();
        s.sort_by(|a, b| a.contig.cmp(&b.contig));
        assert_eq!(s.len(), 2);
        // chr1: one region of 50 bp
        assert_eq!(s[0].contig, "chr1");
        assert_eq!(s[0].total_callable_bp, 50);
        assert_eq!(s[0].region_count, 1);
        // chrY: [100,250)=150 + [1000,1100)=100 => 250 bp, 2 regions
        assert_eq!(s[1].contig, "chrY");
        assert_eq!(s[1].total_callable_bp, 250);
        assert_eq!(s[1].region_count, 2);
    }
}
