//! Reconciliation: compare legacy vs new row counts per aggregate. Unified
//! aggregates sum the contributing legacy tables. Mismatches are flagged (a
//! lower target count for links can be legitimate de-duplication).

use sqlx::PgPool;

struct Check {
    label: &'static str,
    legacy_sql: &'static str,
    target_sql: &'static str,
}

const CHECKS: &[Check] = &[
    Check { label: "specimen_donor", legacy_sql: "SELECT count(*) FROM specimen_donor", target_sql: "SELECT count(*) FROM core.specimen_donor" },
    Check {
        label: "biosample",
        legacy_sql: "SELECT (SELECT count(*) FROM biosample) + (SELECT count(*) FROM citizen_biosample) + (SELECT count(*) FROM pgp_biosample)",
        target_sql: "SELECT count(*) FROM core.biosample",
    },
    Check { label: "variant", legacy_sql: "SELECT count(*) FROM variant_v2", target_sql: "SELECT count(*) FROM core.variant" },
    Check { label: "haplogroup", legacy_sql: "SELECT count(*) FROM tree.haplogroup", target_sql: "SELECT count(*) FROM tree.haplogroup" },
    Check { label: "haplogroup_relationship", legacy_sql: "SELECT count(*) FROM tree.haplogroup_relationship", target_sql: "SELECT count(*) FROM tree.haplogroup_relationship" },
    Check { label: "haplogroup_variant", legacy_sql: "SELECT count(*) FROM tree.haplogroup_variant", target_sql: "SELECT count(*) FROM tree.haplogroup_variant" },
    Check { label: "genomic_study", legacy_sql: "SELECT count(*) FROM genomic_studies", target_sql: "SELECT count(*) FROM pubs.genomic_study" },
    Check { label: "publication", legacy_sql: "SELECT count(*) FROM publication", target_sql: "SELECT count(*) FROM pubs.publication" },
    Check {
        label: "publication_biosample",
        legacy_sql: "SELECT (SELECT count(*) FROM publication_biosample) + (SELECT count(*) FROM publication_citizen_biosample)",
        target_sql: "SELECT count(*) FROM pubs.publication_biosample",
    },
];

pub async fn run(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let mut mismatches = 0;
    println!("{:<26} {:>10} {:>10}  status", "aggregate", "legacy", "target");
    for c in CHECKS {
        let l: i64 = sqlx::query_scalar(c.legacy_sql).fetch_one(legacy).await?;
        let t: i64 = sqlx::query_scalar(c.target_sql).fetch_one(target).await?;
        let status = if l == t {
            "ok"
        } else {
            mismatches += 1;
            "MISMATCH"
        };
        println!("{:<26} {:>10} {:>10}  {}", c.label, l, t, status);
    }
    if mismatches > 0 {
        tracing::warn!(mismatches, "reconciliation found count mismatches (review before cutover)");
    } else {
        tracing::info!("reconciliation: all aggregate counts match");
    }
    Ok(())
}
