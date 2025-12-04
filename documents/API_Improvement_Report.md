# API Improvement Report: Decoding Us as an App Layer for Edge Nodes

This report outlines the strategy for evolving the Decoding Us API. It distinguishes between the existing **Research/Curator APIs** (used for academic data maintenance) and the required **Edge/Atmosphere APIs** (needed for the distributed citizen science network).

## 1. Current State: The Research/Curator API

The application currently contains a set of "Private APIs" (implemented as standard Play Controllers) primarily used for:
*   **Academic Data Integration:** Importing and managing data from research papers.
*   **Public Repository Sync:** Maintaining reference data from sources like the 1000 Genomes Project (1KG).
*   **Biosample Management:** Creation of samples by trusted administrators or researchers.

**Status:**
*   **Authentication:** Relies on `ApiKeyFilter`. This is generally **adequate** for this specific use case, as these endpoints are intended for a limited set of trusted internal users or automated service accounts managing reference data.
*   **Definition:** Implemented purely as Play Actions. While functional, the lack of Tapir definitions makes it harder to generate client SDKs for internal tools.

## 2. Future State: The Edge/Atmosphere API

To function as an "App Layer in the Atmosphere" for thousands of distributed Personal Data Servers (PDS), a distinct set of APIs is required. These APIs operate in a fundamentally different trust domain (untrusted/semi-trusted distributed nodes) compared to the Curator APIs.

### Critical Improvements Required

#### A. Formalize Edge-Facing APIs (Tapir)
*   **Goal:** Define the interface for the distributed Edge application.
*   **Action:** Create new Tapir definitions for all Edge interactions. This allows the Edge application (likely written in a different language/context) to use auto-generated clients, ensuring robust communication.
*   **Scope:**
    *   `EdgeOpsEndpoints.scala`: Heartbeats, Config, Updates.
    *   `IngestionEndpoints.scala`: Submission of results (calls, segments).

#### B. Implement DID-Based Authentication (The "Atmosphere" Layer)
*   **Context:** The current `ApiKeyFilter` is insufficient for the Edge layer. We cannot issue and manage static secrets for thousands of citizen scientists.
*   **Action:** Implement a decentralized auth flow.
    *   **Registration:** `POST /api/v1/edge/register` - Edge Node exchanges its DID and Public Key.
    *   **Verification:** Middleware that verifies requests signed by the PDS's private key against the registered DID.
*   **Distinction:** This auth mechanism specifically protects the *Edge* endpoints, while the *Curator* endpoints can potentially remain on the simpler API Key system (or migrate later).

#### C. Operational Management (Fleet Control)
*   **Gap:** The current system has no visibility into the "Fleet".
*   **Action:** Implement "Atmosphere" control endpoints.
    *   **Heartbeat:** Nodes report `online`, `idle`, `processing`.
    *   **Configuration:** Server pushes distinct configurations (e.g., "Focus analysis on Haplogroup R-M269").
    *   **Manifest:** Server publishes the "Target State" for the Edge software version.

#### D. Granular Data Ingestion
*   **Gap:** Current ingestion is "Sample Creation" focused. Edge nodes need to submit *results* for existing samples.
*   **Action:** Create specialized endpoints for result submission.
    *   `POST /api/v1/ingest/haplogroup-call`: "I found this variant."
    *   `POST /api/v1/ingest/ibd-segments`: "I found this match (hash)."
    *   `POST /api/v1/ingest/stats`: "I processed 5GB of data."

## Summary of Recommendations

| Feature Area | Curator API (Existing) | Edge/Atmosphere API (New) |
| :--- | :--- | :--- |
| **Primary User** | Internal Researchers / Scripts | Citizen Scientist PDS Nodes |
| **Trust Model** | Trusted (Internal) | Untrusted/Semi-Trusted (Distributed) |
| **Auth Method** | API Key (Current is OK) | **DID + Request Signing (Required)** |
| **Definition** | Play Controllers | **Tapir Endpoints (Required)** |
| **Action** | Maintain / Doc Improvements | **Build New Layer** |

**Immediate Next Step:** Begin implementing `EdgeOpsEndpoints.scala` and the DID-based authentication middleware to establish the secure channel for the new layer.