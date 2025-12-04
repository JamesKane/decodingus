# Database Schema Review: Alignment with Application Goals

This document reviews the current database schema (`app/models/dal/`) against the application's stated goals of becoming a "App Layer in the Atmosphere" for citizen science genetic research.

## Summary of Findings

The database schema is remarkably mature and well-aligned with the project's complex domain requirements (Pangenome, Haplogroups, IBD). It already includes sophisticated structures for:
*   **Decentralized Identity:** Native support for DIDs in user and donor tables.
*   **Reputation:** A built-in system for tracking user contributions.
*   **Privacy-Preserving Discovery:** Specialized tables for IBD matches that verify PDS attestations without exposing raw data.

However, specific gaps exist regarding the **operational management** of the Edge Node fleet and the **auditability** of specific data submissions.

## Detailed Alignment Analysis

### 1. Goal: Collaborative Haplogroup Tree Resolution
*   **Status:** **Strong Support**
*   **Evidence:** `HaplogroupsTable`, `HaplogroupRelationshipsTable`, and `HaplogroupVariantMetadataTable` provide a complete graph structure for storing the tree.
*   **Gap:** **Submission Provenance.** While the *result* (the tree) is stored, there is no obvious table (e.g., `HaplogroupCallSubmissions`) to track *which* Edge Node proposed a specific variant or branch change before it was accepted. Tracking this is crucial for the "Collaborative" aspect and resolving conflicts.

### 2. Goal: Privacy-Preserving Genetic Relative Discovery (IBD)
*   **Status:** **Excellent Support**
*   **Evidence:**
    *   `IbdDiscoveryIndicesTable`: Stores the *existence* of a match and its strength (cM) without storing the raw segment data, perfectly aligning with the privacy goal.
    *   `IbdPdsAttestationsTable`: Links matches to `attesting_pds_guid` and includes an `attestation_signature`. This is a critical feature for a distributed trust model, allowing PDS owners to cryptographically sign off on matches.

### 3. Goal: Edge Computing Participation (Citizen Science)
*   **Status:** **Mixed**
*   **Evidence (Positive):**
    *   `UserPdsInfoTable`: Explicitly links Users to a `pds_url` and `did`.
    *   `ReputationEventsTable` & `UserReputationScoresTable`: A complete gamification/credit system is already defined in the schema, allowing the system to reward users for contributions.
*   **Evidence (Negative):**
    *   **Missing Operational State:** There is no table to track the *live status* of Edge Nodes. If a user has 5 computers running the Edge software, the database has no way to know which are Online, Offline, or their current load.
    *   **Missing Device Registry:** `UserPdsInfo` links a *User* to a PDS. It does not clearly support a User having *multiple* distinct compute nodes (devices) with different capabilities.

### 4. Goal: Secure Data Interaction (AT Protocol)
*   **Status:** **Good Support**
*   **Evidence:**
    *   `SequenceAtpLocationTable`: Directly maps sequence files to AT Protocol concepts (`repo_did`, `record_cid`), enabling the "App Layer" to reference data stored in the distributed network.
    *   `SpecimenDonorsTable`: Contains `citizen_biosample_did`, facilitating the link between physical samples and their digital twins on the AT Protocol.

## Recommendations

To fully support the "App Layer" vision, we recommend the following schema additions:

1.  **Edge Node Registry Table:**
    *   Create `EdgeNodesTable` (or `UserComputeNodes`) to track individual devices associated with a user/PDS.
    *   Columns: `node_id (UUID)`, `user_id`, `last_heartbeat (Timestamp)`, `status (Online/Offline/Busy)`, `software_version`.

2.  **Submission Audit Tables:**
    *   Create `HaplogroupSubmissionsTable` to log incoming calls from Edge Nodes before they are merged into the main `BiosampleHaplogroupsTable`.
    *   Columns: `submission_id`, `biosample_id`, `edge_node_id`, `proposed_haplogroup`, `confidence_score`, `algorithm_version`, `submission_timestamp`.

3.  **Job Assignment Table (Optional):**
    *   If the server intends to *dispatch* work to Edge Nodes (rather than just accepting results), a `ComputeJobsTable` will be needed to track which node was assigned which task.

## Conclusion

The schema requires only minor additions to support the operational aspects of the "Edge Node" fleet. The core scientific and identity data models are robust and ready for production.
