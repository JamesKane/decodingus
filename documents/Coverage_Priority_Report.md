# Code Coverage Analysis & Prioritization

## Current Status
*   **Overall Statement Coverage:** ~5.72%
*   **Overall Branch Coverage:** ~3.44%
*   **Tested Areas:**
    *   `HomeController` (Partial, rendering only)
    *   `PgpBiosampleService` (Creation logic)
    *   `AccessionNumberGenerator` (ID generation logic)
    *   `AccessionNumberGenerator` (Infrastructure logic via decoupling)

## Analysis of Gaps

The application has significant gaps in its testing strategy. Most controllers and services are completely untested.

### High Criticality (Core Business Logic)
These areas handle data integrity, ingestion, and the core value proposition (Trees, Biosamples).

1.  **`BiosampleDataService.scala`**
    *   **Role:** Orchestrates linking publications and adding raw sequence data to biosamples.
    *   **Risk:** High. Failures here mean data loss or corruption during ingestion. Complex nested `Future` chains.
    *   **Status:** 0% Coverage.

2.  **`HaplogroupTreeService.scala` & `TreeImporter.scala`**
    *   **Role:** Manages the phylogenetic trees (Y-DNA/mtDNA).
    *   **Risk:** High. The tree is the central data structure of the application.
    *   **Status:** 0% Coverage.

3.  **`BiosampleUpdateService.scala`**
    *   **Role:** Handling modifications to existing records.
    *   **Risk:** Medium-High. Potential for unauthorized or incorrect data overwrites.
    *   **Status:** 0% Coverage.

### Medium Criticality (Controllers & Display)
1.  **`ExternalBiosampleController.scala`**
    *   **Role:** Entry point for creating non-PGP biosamples.
    *   **Risk:** Medium. Similar to `PgpBiosampleController` but less restrictive.
    *   **Status:** 0% Coverage.

2.  **`BiosampleController.scala`**
    *   **Role:** Retrieval and viewing of samples.
    *   **Risk:** Low-Medium (Read-only mostly).
    *   **Status:** 0% Coverage.

## Prioritized Action Plan

We recommend addressing coverage in the following order to maximize stability and reliability:

| Priority | Component | Rationale |
| :--- | :--- | :--- |
| **1** | **`BiosampleDataService`** | Complex data orchestration (Library -> File -> Checksum -> Location) is prone to bugs. |
| **2** | **`ExternalBiosampleService`** | Completes the coverage for "Ingestion" workflows (pairing with PGP service). |
| **3** | **`BiosampleUpdateService`** | Ensures data modification safety. |
| **4** | **`HaplogroupTreeService`** | Core domain logic, though often static/read-heavy. |
| **5** | **`ExternalBiosampleController`** | API surface testing. |

## Tech Debt Note
*   `BiosampleDataService` relies heavily on multiple repositories. Following the pattern used in `BiosampleAccessionGenerator`, we should strictly mock these repositories rather than trying to use an in-memory DB, to keep tests fast and focused on the orchestration logic.
