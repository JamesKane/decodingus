//! VCF variant reader for the variant-ingest path (YBrowse/HipSTR-style inputs).
//!
//! VCF is line-oriented text, so the variant columns (CHROM POS ID REF ALT) are
//! parsed directly here — sufficient for de-identified variant-catalog ingest.
//! Raw-read formats (BAM/CRAM) and variant *calling* are out of scope for the
//! AppView (done in Navigator); full-spec VCF typing isn't needed for ingest.

use crate::error::BioError;
use std::io::BufRead;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VcfVariant {
    pub chrom: String,
    /// 1-based VCF position.
    pub pos: i64,
    pub ids: Vec<String>,
    pub reference: String,
    pub alternate: Vec<String>,
}

/// Parse VCF records from a reader. Header (`##`/`#CHROM`) and blank lines are
/// skipped. `.` IDs/ALTs are treated as empty.
pub fn parse<R: BufRead>(reader: R) -> Result<Vec<VcfVariant>, BioError> {
    let mut out = Vec::new();
    for (n, line) in reader.lines().enumerate() {
        let line = line?;
        let line = line.trim_end();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let f: Vec<&str> = line.split('\t').collect();
        if f.len() < 5 {
            return Err(BioError::Parse(format!("VCF line {}: fewer than 5 columns", n + 1)));
        }
        let pos = f[1]
            .parse::<i64>()
            .map_err(|_| BioError::Parse(format!("VCF line {}: bad POS {:?}", n + 1, f[1])))?;
        out.push(VcfVariant {
            chrom: f[0].to_string(),
            pos,
            ids: split_field(f[2]),
            reference: f[3].to_string(),
            alternate: split_field(f[4]),
        });
    }
    Ok(out)
}

/// VCF multi-value fields are `;`/`,` separated; `.` means none.
fn split_field(s: &str) -> Vec<String> {
    if s == "." || s.is_empty() {
        return Vec::new();
    }
    s.split([';', ',']).filter(|t| !t.is_empty()).map(str::to_string).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const VCF: &str = "\
##fileformat=VCFv4.2
#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO
chrY\t2787319\tM269;PF6517\tC\tT\t.\t.\tYBROWSE=1
chrY\t13668077\tL21\tG\tA\t.\t.\t.
chrY\t2781000\t.\tA\tT\t.\t.\t.
";

    #[test]
    fn parses_variant_columns_and_multi_ids() {
        let v = parse(VCF.as_bytes()).unwrap();
        assert_eq!(v.len(), 3);
        assert_eq!(v[0].chrom, "chrY");
        assert_eq!(v[0].pos, 2_787_319);
        assert_eq!(v[0].ids, vec!["M269", "PF6517"]);
        assert_eq!(v[0].reference, "C");
        assert_eq!(v[0].alternate, vec!["T"]);
        assert_eq!(v[1].ids, vec!["L21"]);
        // "." id -> empty
        assert!(v[2].ids.is_empty());
    }
}
