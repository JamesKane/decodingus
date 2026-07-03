//! Minimal `.fai`-indexed FASTA random access (std-only; no htslib/noodles).
//!
//! The coordinate-lift job needs to read the *target* reference base at a lifted position
//! to orient alleles (set ancestral = the base actually present on the target build, and
//! detect reverse-strand polarity flips). We only ever touch one contig per reference (chrY),
//! so rather than random single-byte seeks across millions of variants, [`Reference`] loads
//! the whole contig into memory once (~60 MB for chrY) and serves O(1) `base_at`.

use std::collections::HashMap;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

/// One `.fai` record: byte offset of the sequence + line geometry.
#[derive(Debug, Clone, Copy)]
struct FaidxEntry {
    length: u64,
    offset: u64,
    /// Bases per line (excludes the newline).
    linebases: u64,
    /// Bytes per line (includes the newline(s)).
    linewidth: u64,
}

/// A `.fai`-indexed FASTA. `open` reads only the tiny index; sequence bytes are read lazily
/// by [`load_contig`](Reference::load_contig).
pub struct Reference {
    fasta: PathBuf,
    index: HashMap<String, FaidxEntry>,
}

impl Reference {
    /// Open `<fasta>` using its sibling `<fasta>.fai` index (must already exist — every
    /// reference under `~/.decodingus/references` ships one).
    pub fn open(fasta: &Path) -> Result<Self> {
        let fai = {
            let mut p = fasta.as_os_str().to_owned();
            p.push(".fai");
            PathBuf::from(p)
        };
        let text = std::fs::read_to_string(&fai)
            .with_context(|| format!("read faidx {}", fai.display()))?;
        let mut index = HashMap::new();
        for line in text.lines() {
            let f: Vec<&str> = line.split('\t').collect();
            if f.len() < 5 {
                continue;
            }
            let parse = |i: usize| f[i].parse::<u64>().ok();
            if let (Some(length), Some(offset), Some(linebases), Some(linewidth)) =
                (parse(1), parse(2), parse(3), parse(4))
            {
                index.insert(f[0].to_string(), FaidxEntry { length, offset, linebases, linewidth });
            }
        }
        Ok(Reference { fasta: fasta.to_path_buf(), index })
    }

    /// Whether the reference has the given contig.
    #[allow(dead_code)] // used in tests + a convenience for callers
    pub fn has_contig(&self, name: &str) -> bool {
        self.index.contains_key(name)
    }

    /// Load one contig's bases into memory, uppercased, newlines stripped. The returned buffer
    /// is 0-based; [`Contig::base_at`] takes 1-based positions.
    pub fn load_contig(&self, name: &str) -> Result<Contig> {
        let e = *self
            .index
            .get(name)
            .with_context(|| format!("contig {name} not in {}", self.fasta.display()))?;
        let mut file = File::open(&self.fasta)
            .with_context(|| format!("open {}", self.fasta.display()))?;
        file.seek(SeekFrom::Start(e.offset))?;
        // Bytes spanning the whole contig incl. newlines: full lines + the (possibly short)
        // final line. linewidth - linebases is the newline width (1 for \n, 2 for \r\n).
        let full_lines = e.length / e.linebases;
        let rem = e.length % e.linebases;
        let nl = e.linewidth.saturating_sub(e.linebases);
        let raw_len = full_lines * e.linewidth + if rem > 0 { rem + nl } else { 0 };
        let mut raw = vec![0u8; raw_len as usize];
        file.read_exact(&mut raw)?;
        let mut seq = Vec::with_capacity(e.length as usize);
        for b in raw {
            if b == b'\n' || b == b'\r' {
                continue;
            }
            seq.push(b.to_ascii_uppercase());
        }
        seq.truncate(e.length as usize);
        Ok(Contig { seq })
    }
}

/// An in-memory contig sequence, 1-based `base_at`.
pub struct Contig {
    seq: Vec<u8>,
}

impl Contig {
    /// Number of bases in the contig.
    #[allow(dead_code)] // used in tests + sanity checks
    pub fn len(&self) -> usize {
        self.seq.len()
    }

    /// The base at 1-based `pos`, uppercased, or `None` if out of range.
    pub fn base_at(&self, pos: i64) -> Option<u8> {
        if pos < 1 {
            return None;
        }
        self.seq.get((pos - 1) as usize).copied()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Reads a known base from the real GRCh38 reference (V3739 = chrY:16966397 G>C, so the
    /// GRCh38 reference base there is G). Skips when the reference isn't present.
    #[test]
    fn reads_known_grch38_chry_base() {
        let fa = dirs_next_home()
            .join(".decodingus/references/GRCh38.fa");
        if !fa.exists() {
            eprintln!("{} absent — skipping faidx read test", fa.display());
            return;
        }
        let r = Reference::open(&fa).expect("open GRCh38");
        assert!(r.has_contig("chrY"));
        let chr_y = r.load_contig("chrY").expect("load chrY");
        assert_eq!(chr_y.len(), 57_227_415, "GRCh38 chrY length");
        // V3739 ancestral (GRCh38 reference base) at 16,966,397 is G.
        assert_eq!(chr_y.base_at(16_966_397), Some(b'G'));
    }

    fn dirs_next_home() -> PathBuf {
        PathBuf::from(std::env::var("HOME").expect("HOME"))
    }
}
