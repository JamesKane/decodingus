# DecodingUs Variant Naming Authority

> **Implementation status (2026-06, Rust):** Core BUILT. Migration 0016 makes
> `core.variant.canonical_name` nullable (NULL = unnamed, by coordinates) with a
> partial unique index, and adds `core.du_variant_name_seq` + `core.next_du_name()`.
> `du_db::naming` provides the naming queue, DU minting (old name → alias), the
> lifecycle (`UNNAMED`→`PENDING_REVIEW`→`NAMED`), and a local same-coordinate
> dedup check. Curator UI at `/curator/naming`; propagation feed at
> `GET /api/v1/variants/export.gff` (GFF3). **Not yet:** a live external-source
> (YBrowse/ISOGG/YFull) dedup lookup, and surfacing unnamed variants in the public
> API/domain (the shared `du-domain::Variant.canonical_name` is still `String` —
> a cross-repo change since Navigator shares it).

**Objective:** Establish DecodingUs as a recognized naming authority for Y-DNA variants, using the `DU` prefix.

## Naming Strategy

### DU Naming Sequence
We use a dedicated sequence for assigning stable, unique identifiers to variants discovered or curated within our platform.

*   **Format:** `DU00001`, `DU00002`, ... (No zero padding is specified in ISOGG guidelines, but fixed width is often preferred for sorting. *Correction from proposal: We implemented standard string format `DU` + number.*)
*   **Database:** `du_variant_name_seq` sequence in PostgreSQL.

### Curator Naming Workflow

When a curator promotes a "Proposed Branch" or validates a "Novel Variant":

1.  **Check External Names**:
    *   Does this variant already exist in YBrowse/ISOGG/YFull?
    *   If yes, use the established name (e.g., `BY12345`, `FGC9876`).
    *   Record it as `canonical_name` and `naming_status = 'NAMED'`.

2.  **Assign DU Name (If Novel)**:
    *   If no external name exists, the curator requests a DU name.
    *   System calls `nextDuName()`.
    *   Variant is updated: `canonical_name = 'DU12345'`, `naming_status = 'NAMED'`.

3.  **Leave Unnamed**:
    *   Private variants or those with insufficient evidence remain `UNNAMED` and `canonical_name = NULL`.
    *   Identified by coordinates (e.g., `chrY:12345:G>A`) in the UI.

## YBrowse Integration

To integrate with the wider community (YBrowse aggregation):

1.  **Prefix Registration**: The `DU` prefix identifies variants managed by DecodingUs.
2.  **GFF/VCF Export**: We provide a public dump of our named variants.
3.  **Metadata**: Exports include `ref` (Source: DecodingUs), `comment` (Context), and evidence counts.

## Lifecycle of a Novel Variant

1.  **Discovery**: Ingested from a user's VCF. `naming_status = 'UNNAMED'`.
2.  **Proposal**: Linked to a tree branch proposal. `naming_status = 'PENDING_REVIEW'`.
3.  **Publication**: Proposal accepted. Name assigned (`DU...`). `naming_status = 'NAMED'`.
4.  **Propagation**: Included in nightly GFF export. Picked up by YBrowse.
