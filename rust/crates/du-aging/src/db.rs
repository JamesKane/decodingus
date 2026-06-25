//! Database side of the callable-loci preloader: load the Y region classes,
//! look up samples by accession, and seed `genomics.biosample_callable_loci`.
//!
//! Per-sample private SNP branches are already extracted by the tree producer and
//! seeded into `tree.biosample_private_variant`; the only missing SNP-Poisson
//! age-model input is the per-sample callable-bp denominator, which this fills.

use anyhow::Result;
use du_bio::callable::{merge, Interval};
use sqlx::{PgConnection, PgPool};
use uuid::Uuid;

use crate::callable::CallableBp;

/// Disjoint Y sequence classes (hs1), each merged, for the callable partition.
pub struct YRegions {
    pub xdegen: Vec<Interval>,
    pub ampliconic: Vec<Interval>,
    pub palindromic: Vec<Interval>,
}

async fn region_intervals(pool: &PgPool, sql: &str) -> Result<Vec<Interval>> {
    let rows: Vec<(i64, i64)> = sqlx::query_as(sql).fetch_all(pool).await?;
    Ok(merge(rows.into_iter().map(|(s, e)| Interval { start: s, end: e }).collect()))
}

/// Load X-degenerate (+X-transposed), ampliconic, and palindromic Y classes.
/// Ampliconic excludes palindromic so the age model's (xdegen+ampl+palin) sum is
/// a disjoint callable denominator.
pub async fn load_y_regions(pool: &PgPool) -> Result<YRegions> {
    let coord = |sql: &str| -> String {
        format!(
            "SELECT (coordinates->'hs1'->>'start')::bigint, (coordinates->'hs1'->>'end')::bigint \
             FROM core.genome_region WHERE {sql}"
        )
    };
    let xdegen = region_intervals(
        pool,
        &coord("region_type='sequence_class' AND (name LIKE 'X-DEG%' OR name LIKE 'XTR%')"),
    )
    .await?;
    let ampl_all =
        region_intervals(pool, &coord("region_type='sequence_class' AND name LIKE 'AMPL%'")).await?;
    let palindromic = region_intervals(pool, &coord("region_type='palindromic'")).await?;
    let ampliconic = subtract(&ampl_all, &palindromic);
    Ok(YRegions { xdegen, ampliconic, palindromic })
}

/// Interval-set difference a \ b (both merged, sorted).
fn subtract(a: &[Interval], b: &[Interval]) -> Vec<Interval> {
    let mut out = Vec::new();
    let mut j = 0usize;
    for iv in a {
        let mut cur = iv.start;
        while j < b.len() && b[j].end <= iv.start {
            j += 1;
        }
        let mut k = j;
        while k < b.len() && b[k].start < iv.end {
            if b[k].start > cur {
                out.push(Interval { start: cur, end: b[k].start.min(iv.end) });
            }
            cur = cur.max(b[k].end);
            k += 1;
        }
        if cur < iv.end {
            out.push(Interval { start: cur, end: iv.end });
        }
    }
    out
}

/// The `core.biosample` guid for an accession (None if the sample isn't loaded —
/// e.g. a NAS sample not represented in this tree).
pub async fn guid_by_accession(pool: &PgPool, accession: &str) -> Result<Option<Uuid>> {
    Ok(sqlx::query_scalar("SELECT sample_guid FROM core.biosample WHERE accession = $1")
        .bind(accession)
        .fetch_optional(pool)
        .await?)
}

/// Idempotently replace a sample's chrY callable row.
pub async fn seed_callable(
    tx: &mut PgConnection,
    guid: Uuid,
    cbp: &CallableBp,
    region_count: i64,
    bed_hash: &str,
) -> Result<()> {
    sqlx::query("DELETE FROM genomics.biosample_callable_loci WHERE sample_guid = $1 AND chromosome = 'chrY'")
        .bind(guid)
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "INSERT INTO genomics.biosample_callable_loci \
           (sample_guid, chromosome, total_callable_bp, region_count, y_xdegen_callable_bp, \
            y_ampliconic_callable_bp, y_palindromic_callable_bp, bed_file_hash) \
         VALUES ($1, 'chrY', $2, $3, $4, $5, $6, $7)",
    )
    .bind(guid)
    .bind(cbp.total)
    .bind(region_count)
    .bind(cbp.xdegen)
    .bind(cbp.ampliconic)
    .bind(cbp.palindromic)
    .bind(bed_hash)
    .execute(&mut *tx)
    .await?;
    Ok(())
}
