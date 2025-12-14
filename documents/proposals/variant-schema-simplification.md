# Proposal: Variant Schema Simplification (Implemented)

**Status:** ✅ Implemented
**Date:** 2025-12-14

This proposal has been fully implemented. The documentation has been split into focused guides for operational use.

## Documentation Index

| Topic | Document | Description |
|-------|----------|-------------|
| **Schema Design** | [Universal Variant Schema](../schema/universal-variant-schema.md) | Technical reference for the `variant_v2` table, JSONB structures, and multi-reference model. |
| **Migration** | [Migration Guide](../deployment/variant-migration-guide.md) | Instructions for migrating legacy data to the new schema and dropping old tables. |
| **Performance** | [Performance Tuning](../deployment/performance-tuning-variant-ingestion.md) | Lessons learned optimizing the GFF ingestion pipeline (batch upserts, indexing). |
| **Naming** | [Naming Authority](../planning/variant-naming-authority.md) | Workflows for assigning `DU` names to novel variants. |

---

## Executive Summary of Changes

The project successfully migrated from a "row-per-reference" model to a **Universal Variant Model** using `variant_v2`.

*   **Unified Storage**: A single database row now represents a variant, with coordinates for multiple assemblies (GRCh37, GRCh38, hs1) stored in a `coordinates` JSONB column.
*   **Flexible Aliases**: `aliases` JSONB column replaces the rigid `variant_alias` table.
*   **High-Performance Ingestion**: The `YBrowseVariantIngestionService` uses optimized batch upserts (`INSERT ... ON CONFLICT`) to handle millions of variants efficiently.
*   **Pangenome Ready**: The JSONB coordinate structure allows for future addition of graph-based coordinates without schema changes.