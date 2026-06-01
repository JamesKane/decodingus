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
    Check { label: "specimen_donor", legacy_sql: "SELECT count(*) FROM public.specimen_donor", target_sql: "SELECT count(*) FROM core.specimen_donor" },
    Check {
        label: "biosample",
        legacy_sql: "SELECT (SELECT count(*) FROM public.biosample) + (SELECT count(*) FROM public.citizen_biosample) + (SELECT count(*) FROM public.pgp_biosample)",
        target_sql: "SELECT count(*) FROM core.biosample",
    },
    Check { label: "variant", legacy_sql: "SELECT count(*) FROM public.variant", target_sql: "SELECT count(*) FROM core.variant" },
    Check { label: "haplogroup", legacy_sql: "SELECT count(*) FROM tree.haplogroup", target_sql: "SELECT count(*) FROM tree.haplogroup" },
    Check { label: "haplogroup_relationship", legacy_sql: "SELECT count(*) FROM tree.haplogroup_relationship", target_sql: "SELECT count(*) FROM tree.haplogroup_relationship" },
    Check { label: "haplogroup_variant", legacy_sql: "SELECT count(*) FROM tree.haplogroup_variant", target_sql: "SELECT count(*) FROM tree.haplogroup_variant" },
    Check { label: "genomic_study", legacy_sql: "SELECT count(*) FROM public.genomic_studies", target_sql: "SELECT count(*) FROM pubs.genomic_study" },
    Check { label: "publication", legacy_sql: "SELECT count(*) FROM public.publication", target_sql: "SELECT count(*) FROM pubs.publication" },
    Check {
        label: "publication_biosample",
        legacy_sql: "SELECT (SELECT count(*) FROM public.publication_biosample) + (SELECT count(*) FROM public.publication_citizen_biosample)",
        target_sql: "SELECT count(*) FROM pubs.publication_biosample",
    },
    Check { label: "publication_study", legacy_sql: "SELECT count(*) FROM public.publication_ena_study", target_sql: "SELECT count(*) FROM pubs.publication_study" },
    // ident group. Note: ident.roles is pre-seeded with base roles (Admin/
    // Curator/TreeCurator); if production lacks one a MISMATCH (target > legacy)
    // is expected and benign.
    Check { label: "users", legacy_sql: "SELECT count(*) FROM public.users", target_sql: "SELECT count(*) FROM ident.users" },
    Check { label: "roles", legacy_sql: "SELECT count(*) FROM auth.roles", target_sql: "SELECT count(*) FROM ident.roles" },
    Check { label: "permissions", legacy_sql: "SELECT count(*) FROM auth.permissions", target_sql: "SELECT count(*) FROM ident.permissions" },
    Check { label: "role_permissions", legacy_sql: "SELECT count(*) FROM auth.role_permissions", target_sql: "SELECT count(*) FROM ident.role_permissions" },
    Check { label: "user_roles", legacy_sql: "SELECT count(*) FROM auth.user_roles", target_sql: "SELECT count(*) FROM ident.user_roles" },
    Check { label: "user_login_info", legacy_sql: "SELECT count(*) FROM auth.user_login_info", target_sql: "SELECT count(*) FROM ident.user_login_info" },
    Check { label: "user_oauth2_info", legacy_sql: "SELECT count(*) FROM auth.user_oauth2_info", target_sql: "SELECT count(*) FROM ident.user_oauth2_info" },
    Check { label: "user_pds_info", legacy_sql: "SELECT count(*) FROM auth.user_pds_info", target_sql: "SELECT count(*) FROM ident.user_pds_info" },
    Check { label: "cookie_consents", legacy_sql: "SELECT count(*) FROM auth.cookie_consents", target_sql: "SELECT count(*) FROM ident.cookie_consents" },
    Check {
        label: "atproto_metadata",
        legacy_sql: "SELECT (SELECT count(*) FROM auth.atprotocol_authorization_servers) + (SELECT count(*) FROM auth.atprotocol_client_metadata)",
        target_sql: "SELECT (SELECT count(*) FROM ident.atprotocol_authorization_servers) + (SELECT count(*) FROM ident.atprotocol_client_metadata)",
    },
    Check { label: "audit_log", legacy_sql: "SELECT count(*) FROM curator.audit_log", target_sql: "SELECT count(*) FROM ident.audit_log" },
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
