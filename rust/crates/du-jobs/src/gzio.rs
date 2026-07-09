//! Transparent gzip / BGZF decompression for the job file intakes.
//!
//! Reference assets in bioinformatics ship compressed — UCSC liftover chains as
//! plain gzip (`*.over.chain.gz`), reference FASTAs as samtools-BGZF (`*.fasta.gz`
//! with a sibling `.gzi` block index). These jobs run at most nightly, so it isn't
//! worth decompressing multi-GB files onto disk just to read them: decompress on
//! read instead.
//!
//! - [`read_to_string`] covers whole-file text intakes (chain files): gzip and BGZF
//!   both decode as a gzip-member stream, so one path handles either.
//! - [`BgzfReader`] adds `.gzi`-indexed *random* access for the faidx FASTA reader,
//!   which only ever pulls a single contig out of a multi-GB reference.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use flate2::read::MultiGzDecoder;

/// The gzip magic number (`1f 8b`). BGZF is a gzip-member stream, so it shares it.
const GZIP_MAGIC: [u8; 2] = [0x1f, 0x8b];

/// Whether `path` starts with the gzip magic. Sniffs the bytes rather than trusting
/// the extension, so a `.chain` that is actually gzipped (or vice-versa) still works.
pub fn is_gzip(path: &Path) -> Result<bool> {
    let mut f = File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut magic = [0u8; 2];
    Ok(f.read(&mut magic)? == 2 && magic == GZIP_MAGIC)
}

/// Read a whole text file, transparently decompressing if it is gzip/BGZF. Chain
/// files are small enough to hold in memory (the parser wants `&str` anyway).
pub fn read_to_string(path: &Path) -> Result<String> {
    let file = File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut s = String::new();
    if is_gzip(path)? {
        MultiGzDecoder::new(file)
            .read_to_string(&mut s)
            .with_context(|| format!("gunzip {}", path.display()))?;
    } else {
        std::io::BufReader::new(file)
            .read_to_string(&mut s)
            .with_context(|| format!("read {}", path.display()))?;
    }
    Ok(s)
}

/// Append an extension to a path, keeping any existing one (`foo.fa` → `foo.fa.gzi`).
fn with_suffix(path: &Path, suffix: &str) -> PathBuf {
    let mut p = path.as_os_str().to_owned();
    p.push(suffix);
    PathBuf::from(p)
}

/// Random access into a BGZF file via its sibling `.gzi` block index (as written by
/// `samtools faidx` / `bgzip -i`). BGZF is a stream of independent gzip members
/// ("blocks"); the `.gzi` records each block boundary as (compressed, uncompressed)
/// offsets, letting us seek to the block covering a target *uncompressed* offset and
/// decode forward from there.
pub struct BgzfReader {
    file: File,
    /// Block starts as `(uncompressed_offset, compressed_offset)`, ascending by the
    /// uncompressed offset, including the implicit first block at `(0, 0)`.
    blocks: Vec<(u64, u64)>,
}

impl BgzfReader {
    /// Open a BGZF file using `<path>.gzi`. Errors if the index is absent (callers
    /// fall back / warn), since random access is impossible without it.
    pub fn open(path: &Path) -> Result<Self> {
        let gzi = with_suffix(path, ".gzi");
        let raw = std::fs::read(&gzi)
            .with_context(|| format!("read bgzf index {}", gzi.display()))?;
        if raw.len() < 8 {
            bail!("bgzf index {} truncated", gzi.display());
        }
        // Layout (little-endian): u64 entry count, then that many
        // (u64 compressed_offset, u64 uncompressed_offset) block starts. The first
        // block (0, 0) is implicit — not stored — so seed it ourselves.
        let n = u64::from_le_bytes(raw[0..8].try_into().unwrap()) as usize;
        let mut blocks = Vec::with_capacity(n + 1);
        blocks.push((0u64, 0u64));
        let mut p = 8;
        for _ in 0..n {
            if p + 16 > raw.len() {
                bail!("bgzf index {} shorter than its declared {} entries", gzi.display(), n);
            }
            let c = u64::from_le_bytes(raw[p..p + 8].try_into().unwrap());
            let u = u64::from_le_bytes(raw[p + 8..p + 16].try_into().unwrap());
            blocks.push((u, c));
            p += 16;
        }
        blocks.sort_unstable_by_key(|&(u, _)| u);
        let file = File::open(path).with_context(|| format!("open {}", path.display()))?;
        Ok(BgzfReader { file, blocks })
    }

    /// Read `len` decompressed bytes starting at uncompressed offset `off`.
    pub fn read_at(&mut self, off: u64, len: usize) -> Result<Vec<u8>> {
        // Largest block whose uncompressed start is <= off (partition_point gives the
        // first block *after* off; step back one). `blocks` always has (0, 0), so this
        // never underflows for off >= 0.
        let idx = self.blocks.partition_point(|&(u, _)| u <= off).saturating_sub(1);
        let (blk_u, blk_c) = self.blocks[idx];
        self.file
            .seek(SeekFrom::Start(blk_c))
            .with_context(|| format!("seek to bgzf block @ {blk_c}"))?;
        let mut dec = MultiGzDecoder::new(&mut self.file);
        // Discard the bytes between the block start and our target offset.
        let mut skip = (off - blk_u) as usize;
        let mut scratch = [0u8; 16 * 1024];
        while skip > 0 {
            let want = skip.min(scratch.len());
            let n = dec.read(&mut scratch[..want])?;
            if n == 0 {
                bail!("bgzf stream ended while seeking to uncompressed offset {off}");
            }
            skip -= n;
        }
        let mut buf = vec![0u8; len];
        dec.read_exact(&mut buf)
            .with_context(|| format!("read {len} bytes @ uncompressed offset {off}"))?;
        Ok(buf)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::io::Write;

    /// Round-trip a plain gzip file through `read_to_string`.
    #[test]
    fn read_to_string_handles_gzip_and_plaintext() {
        let dir = std::env::temp_dir();
        let plain = dir.join("gzio_plain.txt");
        std::fs::write(&plain, "chain 1 chrY 0 +\n").unwrap();
        assert_eq!(read_to_string(&plain).unwrap(), "chain 1 chrY 0 +\n");

        let gz = dir.join("gzio_test.txt.gz");
        let mut enc = GzEncoder::new(Vec::new(), Compression::default());
        enc.write_all(b"chain 1 chrY 0 +\n").unwrap();
        std::fs::write(&gz, enc.finish().unwrap()).unwrap();
        assert_eq!(read_to_string(&gz).unwrap(), "chain 1 chrY 0 +\n");

        let _ = std::fs::remove_file(&plain);
        let _ = std::fs::remove_file(&gz);
    }

    /// Build a BGZF-style multi-member gzip file + matching `.gzi`, then random-read
    /// across block boundaries — exercising the seek + skip + cross-block decode path.
    #[test]
    fn bgzf_reader_reads_across_blocks() {
        let data: Vec<u8> = (0..1000u32).map(|i| b'A' + (i % 26) as u8).collect();
        let block = 100usize; // uncompressed bytes per synthetic block

        let mut compressed = Vec::new();
        let mut gzi_entries: Vec<(u64, u64)> = Vec::new(); // (compressed, uncompressed) block starts
        let mut u_off = 0u64;
        for chunk in data.chunks(block) {
            if u_off > 0 {
                // .gzi records block starts *after* the first.
                gzi_entries.push((compressed.len() as u64, u_off));
            }
            let mut enc = GzEncoder::new(Vec::new(), Compression::default());
            enc.write_all(chunk).unwrap();
            compressed.extend(enc.finish().unwrap());
            u_off += chunk.len() as u64;
        }

        let dir = std::env::temp_dir();
        let fa = dir.join("gzio_bgzf.bin.gz");
        std::fs::write(&fa, &compressed).unwrap();
        let mut gzi = (gzi_entries.len() as u64).to_le_bytes().to_vec();
        for (c, u) in &gzi_entries {
            gzi.extend(c.to_le_bytes());
            gzi.extend(u.to_le_bytes());
        }
        std::fs::write(with_suffix(&fa, ".gzi"), &gzi).unwrap();

        let mut r = BgzfReader::open(&fa).unwrap();
        // First block, spanning into the second (offset 50, length 100).
        assert_eq!(r.read_at(50, 100).unwrap(), data[50..150]);
        // A read starting mid-file at a non-block-aligned offset.
        assert_eq!(r.read_at(333, 200).unwrap(), data[333..533]);
        // The final bytes.
        assert_eq!(r.read_at(900, 100).unwrap(), data[900..1000]);

        let _ = std::fs::remove_file(&fa);
        let _ = std::fs::remove_file(with_suffix(&fa, ".gzi"));
    }
}
