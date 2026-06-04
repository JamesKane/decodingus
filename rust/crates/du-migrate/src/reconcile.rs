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
    // Legacy variants are per-(build, direction); the target folds them by SNP
    // SITE (see transform::variant). Compare against the folded site count.
    Check {
        label: "variant",
        legacy_sql: "SELECT count(*)::bigint FROM ( \
            SELECT 1 FROM ( \
              SELECT COALESCE(v.common_name, v.rs_id, gc.common_name||':'||v.position::text) AS cname, \
                     dense_rank() OVER (PARTITION BY COALESCE(v.common_name, v.rs_id, gc.common_name||':'||v.position::text), \
                       COALESCE(gc.reference_genome,'GRCh38') ORDER BY v.position) AS site_idx \
              FROM public.variant v JOIN public.genbank_contig gc ON gc.genbank_contig_id=v.genbank_contig_id \
            ) b GROUP BY cname, site_idx) x",
        target_sql: "SELECT count(*) FROM core.variant",
    },
    Check { label: "haplogroup", legacy_sql: "SELECT count(*) FROM tree.haplogroup", target_sql: "SELECT count(*) FROM tree.haplogroup" },
    Check { label: "haplogroup_relationship", legacy_sql: "SELECT count(*) FROM tree.haplogroup_relationship", target_sql: "SELECT count(*) FROM tree.haplogroup_relationship" },
    // Links collapse to one per (haplogroup, folded SNP) — the legacy table
    // links each SNP once per build.
    Check {
        label: "haplogroup_variant",
        legacy_sql: "SELECT count(*)::bigint FROM ( \
            SELECT DISTINCT hv.haplogroup_id, r.fold_id FROM tree.haplogroup_variant hv \
            JOIN ( \
              SELECT variant_id AS legacy_id, min(variant_id) OVER (PARTITION BY cname, site_idx) AS fold_id FROM ( \
                SELECT v.variant_id, \
                       COALESCE(v.common_name, v.rs_id, gc.common_name||':'||v.position::text) AS cname, \
                       dense_rank() OVER (PARTITION BY COALESCE(v.common_name, v.rs_id, gc.common_name||':'||v.position::text), \
                         COALESCE(gc.reference_genome,'GRCh38') ORDER BY v.position) AS site_idx \
                FROM public.variant v JOIN public.genbank_contig gc ON gc.genbank_contig_id=v.genbank_contig_id \
              ) b \
            ) r ON r.legacy_id = hv.variant_id) x",
        target_sql: "SELECT count(*) FROM tree.haplogroup_variant",
    },
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
    // genomics group. sequencer_instrument may show target<legacy (instrument_id
    // de-duplication); genotype_data target<=legacy (soft-deleted rows skipped).
    Check { label: "genbank_contig", legacy_sql: "SELECT count(*) FROM public.genbank_contig", target_sql: "SELECT count(*) FROM genomics.genbank_contig" },
    Check { label: "sequencing_lab", legacy_sql: "SELECT count(*) FROM public.sequencing_lab", target_sql: "SELECT count(*) FROM genomics.sequencing_lab" },
    Check { label: "sequencer_instrument", legacy_sql: "SELECT count(DISTINCT instrument_id) FROM public.sequencer_instrument", target_sql: "SELECT count(*) FROM genomics.sequencer_instrument" },
    Check { label: "test_type_definition", legacy_sql: "SELECT count(*) FROM public.test_type_definition", target_sql: "SELECT count(*) FROM genomics.test_type_definition" },
    Check { label: "pangenome_graph", legacy_sql: "SELECT count(*) FROM public.pangenome_graph", target_sql: "SELECT count(*) FROM genomics.pangenome_graph" },
    Check { label: "pangenome_path", legacy_sql: "SELECT count(*) FROM public.pangenome_path", target_sql: "SELECT count(*) FROM genomics.pangenome_path" },
    Check { label: "canonical_pangenome_variant", legacy_sql: "SELECT count(*) FROM public.canonical_pangenome_variant", target_sql: "SELECT count(*) FROM genomics.canonical_pangenome_variant" },
    Check { label: "sequence_library", legacy_sql: "SELECT count(*) FROM public.sequence_library", target_sql: "SELECT count(*) FROM genomics.sequence_library" },
    Check { label: "sequence_file", legacy_sql: "SELECT count(*) FROM public.sequence_file", target_sql: "SELECT count(*) FROM genomics.sequence_file" },
    Check { label: "alignment_metadata", legacy_sql: "SELECT count(*) FROM public.alignment_metadata", target_sql: "SELECT count(*) FROM genomics.alignment_metadata" },
    Check { label: "pangenome_alignment_metadata", legacy_sql: "SELECT count(*) FROM public.pangenome_alignment_metadata", target_sql: "SELECT count(*) FROM genomics.pangenome_alignment_metadata" },
    Check { label: "reported_variant_pangenome", legacy_sql: "SELECT count(*) FROM public.reported_variant_pangenome", target_sql: "SELECT count(*) FROM genomics.reported_variant_pangenome" },
    Check { label: "genotype_data", legacy_sql: "SELECT count(*) FROM public.genotype_data WHERE deleted IS NOT TRUE", target_sql: "SELECT count(*) FROM genomics.genotype_data" },
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
