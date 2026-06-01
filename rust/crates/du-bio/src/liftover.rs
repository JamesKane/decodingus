//! Cross-build coordinate liftover via UCSC chain files. There is no drop-in
//! Rust crate, so this ports the chain interval-mapping logic (replacing the
//! htsjdk `LiftOver`). A position inside an aligned block maps to the
//! corresponding target position; positions in gaps (indels) return None.
//!
//! Reverse-strand (`q-` ) targets are handled; only `t+` source strand is
//! supported (as in standard UCSC chains).

use crate::error::BioError;

#[derive(Debug, Clone, Copy)]
pub struct Block {
    /// Aligned block length.
    pub size: i64,
    /// Gap in the target (source-build) sequence before the next block.
    pub dt: i64,
    /// Gap in the query (dest-build) sequence before the next block.
    pub dq: i64,
}

#[derive(Debug, Clone)]
pub struct Chain {
    pub t_name: String,
    pub t_start: i64,
    pub q_name: String,
    pub q_start: i64,
    pub q_size: i64,
    pub q_strand: char,
    pub blocks: Vec<Block>,
}

impl Chain {
    /// Map a position on the source (`t`) build to the dest (`q`) build.
    pub fn lift(&self, t_pos: i64) -> Option<i64> {
        let mut t = self.t_start;
        let mut q = self.q_start;
        for b in &self.blocks {
            if t_pos >= t && t_pos < t + b.size {
                let q_pos = q + (t_pos - t);
                return Some(if self.q_strand == '-' { self.q_size - 1 - q_pos } else { q_pos });
            }
            t += b.size + b.dt;
            q += b.size + b.dq;
        }
        None
    }
}

#[derive(Debug, Clone, Default)]
pub struct Liftover {
    pub chains: Vec<Chain>,
}

impl Liftover {
    /// Parse one or more chains from UCSC chain-file text.
    pub fn parse(text: &str) -> Result<Liftover, BioError> {
        let mut chains = Vec::new();
        let mut current: Option<Chain> = None;
        for (n, raw) in text.lines().enumerate() {
            let line = raw.trim();
            if line.is_empty() {
                if let Some(c) = current.take() {
                    chains.push(c);
                }
                continue;
            }
            let tok: Vec<&str> = line.split_whitespace().collect();
            if tok[0] == "chain" {
                if let Some(c) = current.take() {
                    chains.push(c);
                }
                if tok.len() < 12 {
                    return Err(BioError::Parse(format!("chain header line {}: too few fields", n + 1)));
                }
                let p = |i: usize| -> Result<i64, BioError> {
                    tok[i].parse().map_err(|_| BioError::Parse(format!("chain line {}: bad int", n + 1)))
                };
                current = Some(Chain {
                    t_name: tok[2].to_string(),
                    t_start: p(5)?,
                    q_name: tok[7].to_string(),
                    q_size: p(8)?,
                    q_strand: tok[9].chars().next().unwrap_or('+'),
                    q_start: p(10)?,
                    blocks: Vec::new(),
                });
            } else {
                let c = current
                    .as_mut()
                    .ok_or_else(|| BioError::Parse(format!("block line {} before chain header", n + 1)))?;
                let nums: Vec<i64> = tok.iter().filter_map(|s| s.parse().ok()).collect();
                let block = match nums.len() {
                    1 => Block { size: nums[0], dt: 0, dq: 0 },
                    3 => Block { size: nums[0], dt: nums[1], dq: nums[2] },
                    _ => return Err(BioError::Parse(format!("block line {}: expected 1 or 3 ints", n + 1))),
                };
                c.blocks.push(block);
            }
        }
        if let Some(c) = current.take() {
            chains.push(c);
        }
        Ok(Liftover { chains })
    }

    /// Lift `(contig, pos)` to the dest build, returning `(dest_contig, dest_pos)`.
    pub fn lift(&self, contig: &str, pos: i64) -> Option<(String, i64)> {
        self.chains
            .iter()
            .filter(|c| c.t_name == contig)
            .find_map(|c| c.lift(pos).map(|q| (c.q_name.clone(), q)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CHAIN: &str = "\
chain 1000 chrZ 1000 + 0 1000 chrZp 1000 + 0 1000 1
100 50 0
200
";

    #[test]
    fn lifts_positions_and_skips_gaps() {
        let lo = Liftover::parse(CHAIN).unwrap();
        // block 1: t[0,100) -> q[0,100)
        assert_eq!(lo.lift("chrZ", 50), Some(("chrZp".to_string(), 50)));
        // gap t[100,150): unmapped
        assert_eq!(lo.lift("chrZ", 120), None);
        // block 2: t[150,350) -> q[100,300) (dq=0, so q resumes at 100)
        assert_eq!(lo.lift("chrZ", 150), Some(("chrZp".to_string(), 100)));
        assert_eq!(lo.lift("chrZ", 200), Some(("chrZp".to_string(), 150)));
        // unknown contig / out of range
        assert_eq!(lo.lift("chrOther", 50), None);
        assert_eq!(lo.lift("chrZ", 9999), None);
    }

    #[test]
    fn reverse_strand_target() {
        let rev = "chain 1 chrA 100 + 0 10 chrB 100 - 0 10 1\n10\n";
        let lo = Liftover::parse(rev).unwrap();
        // q_strand '-': q_pos 0 -> q_size-1-0 = 99
        assert_eq!(lo.lift("chrA", 0), Some(("chrB".to_string(), 99)));
        assert_eq!(lo.lift("chrA", 5), Some(("chrB".to_string(), 94)));
    }
}
