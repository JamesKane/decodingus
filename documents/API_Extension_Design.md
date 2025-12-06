# API Extension Design Document: ExternalBiosample and Project Entities

## 1. Introduction

This document outlines the design for implementing new API endpoints for `ExternalBiosample` and a new `Project` entity within the Decoding Us application. These new endpoints are required to facilitate direct API integration with a "Firehose" team during an MVP phase, preceding a Kafka-based solution. The design will leverage existing API security mechanisms and incorporate soft delete and optimistic locking (`at_cid`) functionalities.

## 2. Current State Analysis

### 2.1 Existing Architecture

The Decoding Us application is built with Scala 3 and the Play Framework, utilizing Slick for database interactions and Tapir for API definition. API security is handled via a token mechanism (e.g., `secureApi` actions).

### 2.2 ExternalBiosample Entity

*   **Model:** The `Biosample` case class (`app/models/domain/genomics/Biosample.scala`) defines the core data structure. However, the "External" biosamples are fundamentally "Citizen" biosamples (`BiosampleType.Citizen`).
*   **Database Schema:** A `citizen_biosample` table exists in the database schema (evolutions) but is currently not represented by a Scala case class or Slick table definition in the codebase.
*   **Request DTO:** `ExternalBiosampleRequest` (`app/models/api/ExternalBiosampleRequest.scala`) serves as the payload.
*   **Service:** `ExternalBiosampleService` (`app/services/ExternalBiosampleService.scala`) currently operates on the generic `Biosample` entity.
*   **Controller:** `ExternalBiosampleController` (`app/controllers/ExternalBiosampleController.scala`) exposes a `create` endpoint using `secureApi.jsonAction`.
*   **Current Delete Behavior:** The existing `deleteBiosample` performs a hard delete using `biosampleDataService.fullyDeleteBiosampleAndDependencies`.

### 2.3 Project Entity

*   **Conceptual:** Currently, there is no direct, standalone `Project` entity with associated CRUD APIs. The term "Project" appears in the context of `GenomicStudy` (e.g., NCBI BioProject) and within UI/documentation elements.
*   **Requirement:** The request implies a new, distinct `Project` entity based on `com.decodingus.atmosphere.project` lexicon definition.

### 2.4 Data Modeling Consideration: Citizen Biosamples

The "External Biosamples" ingested via the Firehose are identified as `BiosampleType.Citizen`. There is an existing, unused `citizen_biosample` table in the database schema intended for this purpose. We must decide whether to utilize this separate table or integrate these records into the main `biosample` table.

**Option A: Separate Table (`citizen_biosample`)**
*   **Pros:**
    *   **Segregation:** Keeps "Citizen" data distinct from other biosample types (Standard, PGP, etc.), which may have different privacy or data retention requirements.
    *   **Schema Specificity:** Allows for columns specific to Citizen biosamples (e.g., `citizen_biosample_did`) without cluttering the main table.
    *   **Performance:** Potentially better performance for type-specific queries if volume is high.
*   **Cons:**
    *   **Complexity:** Requires joining with `biosample` (if shared fields exist there) or duplicating shared columns (description, sex, etc.).
    *   **Maintenance:** Requires creating and maintaining new Scala models (`CitizenBiosample`), tables, and repositories.
    *   **Fragmentation:** Logic acting on "all biosamples" becomes more complex.

**Option B: Unified Table (`biosample` with Type)**
*   **Pros:**
    *   **Simplicity:** Single table and model for all biosamples.
    *   **Unified Querying:** Easier to query "all biosamples" regardless of type.
    *   **Existing Tooling:** Leverages existing `BiosampleRepository` and services.
*   **Cons:**
    *   **Sparse Columns:** Columns specific to Citizen biosamples (like `citizen_biosample_did`) will be null for other types.
    *   **Table Bloat:** Table grows with all types combined.

**Decision:** The original design intent was segregation. Given the specific requirements for Citizen biosamples (DIDs, potential different lifecycle), **we will proceed with Option A (Separate Table)** to align with the original schema design. This requires plumbing the `citizen_biosample` table into the application layer.

## 3. Proposed API Endpoints

The following endpoints are to be implemented, using the existing API security layer:

### 3.1 ExternalBiosample API Endpoints

*   **Create ExternalBiosample:**
    *   **Method:** `POST`
    *   **Path:** `/api/external-biosamples`
    *   **Request Body:** JSON payload conforming to `ExternalBiosampleRequest`.
    *   **Response:** `201 Created` with the created resource's ID (e.g., `sampleGuid`).
*   **Update ExternalBiosample:**
    *   **Method:** `PUT`
    *   **Path:** `/api/external-biosamples/{sampleGuid}` (using `sampleGuid` as the unique identifier for updates)
    *   **Request Body:** JSON payload conforming to `ExternalBiosampleRequest`, including the `atCid` for optimistic locking.
    *   **Response:** `200 OK` or `204 No Content`.
*   **Delete ExternalBiosample (Soft Delete):**
    *   **Method:** `DELETE`
    *   **Path:** `/api/external-biosamples/{sampleGuid}`
    *   **Request Body:** (Optional) Minimal JSON body for confirmation or reason.
    *   **Response:** `204 No Content`.

### 3.2 Project API Endpoints

*   **Create Project:**
    *   **Method:** `POST`
    *   **Path:** `/api/projects`
    *   **Request Body:** JSON payload conforming to the new `ProjectRequest` DTO.
    *   **Response:** `201 Created` with the created resource's ID (e.g., `projectGuid`).
*   **Update Project:**
    *   **Method:** `PUT`
    *   **Path:** `/api/projects/{projectGuid}`
    *   **Request Body:** JSON payload conforming to `ProjectRequest`, including the `atCid` for optimistic locking.
    *   **Response:** `200 OK` or `204 No Content`.
*   **Delete Project (Soft Delete):**
    *   **Method:** `DELETE`
    *   **Path:** `/api/projects/{projectGuid}`
    *   **Request Body:** (Optional) Minimal JSON body for confirmation or reason.
    *   **Response:** `204 No Content`.

## 4. ExternalBiosample Design

### 4.0 ExternalBiosample DTO Definitions

The detailed definitions for `ExternalBiosampleRequest` and `ExternalBiosampleResponse`, including all nested data structures, can be found in the generated OpenAPI (Swagger) specification available at `/api-docs/swagger-ui`.

### 4.1 Model and Database Schema Changes

To support the "Citizen Biosample" segregation strategy:

*   **New Model (`app/models/domain/genomics/CitizenBiosample.scala`):**
    *   Create a case class `CitizenBiosample` mapping to the `citizen_biosample` table.
    *   Fields: `id`, `citizenBiosampleDid`, `sourcePlatform`, `collectionDate`, `sex`, `geocoord`, `description`, `sampleGuid`, `deleted`, `atCid`, `createdAt`, `updatedAt`.
*   **New Table (`app/models/dal/domain/genomics/CitizenBiosamplesTable.scala`):**
    *   Define the Slick table mapping for `citizen_biosample`.
*   **Database Migration:**
    *   A Slick evolution script is required to add `deleted`, `atCid`, `createdAt`, and `updatedAt` columns to the existing `citizen_biosample` table.
*   **`app/models/domain/genomics/Biosample.scala` (Optional):**
    *   Adding `deleted`, `atCid`, etc., to the main `Biosample` table is valid for general enhancements but the primary focus here is the `citizen_biosample` implementation.

### 4.2 DTO Changes

*   **`app/models/api/ExternalBiosampleRequest.scala`:**
    *   As shown in the full definition above, `atCid: Option[String] = None` has been added.

### 4.3 Service Layer Changes (`app/services/ExternalBiosampleService.scala`)

*   **Repository Integration:**
    *   Inject the new `CitizenBiosampleRepository` (and `CitizenBiosampleTable` access).
    *   Update logic to write to `citizen_biosample` table for these requests.
*   **Soft Delete Implementation:**
    *   Modify `deleteBiosample(sampleGuid: UUID): Future[Boolean]`.
    *   Instead of the hard delete, it will query the `CitizenBiosample` by `sampleGuid`, set `deleted = true`, and update via repository.
*   **Optimistic Locking Implementation:**
    *   Modify `createBiosampleWithData` to handle `atCid` for updates.
    *   Check `atCid` against `CitizenBiosample.atCid`.
    *   Update `CitizenBiosample` record on success.
*   **Handling `sampleGuid`:** The service will need to resolve `sampleGuid` against the `citizen_biosample` table.
*   **Mapping:** Convert `ExternalBiosampleRequest` fields to `CitizenBiosample` model. Note: Some fields in request (like `centerName`) might not map directly if `citizen_biosample` lacks them; strict validation or table schema updates might be needed.

### 4.4 Controller Layer Changes (`app/controllers/ExternalBiosampleController.scala`)

*   **Route Updates in `conf/routes`:**
    *   `POST   /api/external-biosamples` to `ExternalBiosampleController.create`
    *   `PUT    /api/external-biosamples/:sampleGuid` to `ExternalBiosampleController.update(sampleGuid: UUID)`
    *   `DELETE /api/external-biosamples/:sampleGuid` to `ExternalBiosampleController.delete(sampleGuid: UUID)`
*   **New `update` method:** A new `update` action will be added, taking `sampleGuid` from the path and `ExternalBiosampleRequest` from the body. It will call the appropriate service method.
*   **New `delete` method:** A new `delete` action will be added, taking `sampleGuid` from the path and calling the service's soft delete method.

### 4.5 Impact on Existing Functionalities

*   **Queries:** Since `CitizenBiosample` data resides in a separate table, existing `Biosample` queries will not be affected (they won't see these records). New queries targeting `citizen_biosample` must respect the `deleted` flag.
*   **`deleteBiosample` in `ExternalBiosampleController`:** The existing `deleteBiosample` in the controller should be removed or adapted to the new soft delete logic and path.

### 4.6 Firehose API Specification

For direct consumption by the Firehose team, the OpenAPI (Swagger) specification for the `ExternalBiosample` endpoints will be made available.

*   **Swagger UI Endpoint:** The full interactive API documentation will be accessible at `/api-docs/swagger-ui`.
*   **Endpoints:** The relevant endpoints are:
    *   `POST /api/external-biosamples` (Create ExternalBiosample)
    *   `PUT /api/external-biosamples/{sampleGuid}` (Update ExternalBiosample)
    *   `DELETE /api/external-biosamples/{sampleGuid}` (Soft Delete ExternalBiosample)
*   **Authentication:** All endpoints are protected by the API security layer. API key authentication will be required (details to be provided separately).
*   **Data Transfer Objects (DTOs):**
    *   **Request:** `ExternalBiosampleRequest` (defined in `4.0 ExternalBiosample DTO Definitions`).
    *   **Response:** `ExternalBiosampleResponse` (defined in `4.0 ExternalBiosample DTO Definitions`).
*   **Optimistic Locking:** For `PUT` operations, ensure the `atCid` from the latest `GET` or `POST` response is included in the request body to prevent concurrent modification conflicts.

## 5. Project Design

The `Project` entity will be entirely new.

### 5.1 Model Definition (`app/models/domain/Project.scala`)

```scala
package models.domain

import java.time.LocalDateTime
import java.util.UUID

case class Project(
                    id: Option[Int] = None,
                    projectGuid: UUID,
                    name: String,
                    description: Option[String] = None,
                    ownerDid: String, // Decentralized Identifier of the project owner
                    createdAt: LocalDateTime,
                    updatedAt: LocalDateTime,
                    deleted: Boolean = false,
                    atCid: Option[String] = None // For optimistic locking
                  )
```

### 5.2 Database Schema (`app/models/dal/ProjectTable.scala`)

A new Slick table definition `ProjectTable` in `app/models/dal/domain/ProjectTable.scala` will be created, mirroring the `Project` case class fields.

*   `id` (PrimaryKey, AutoInc)
*   `projectGuid` (UUID, Unique)
*   `name` (String)
*   `description` (Option[String])
*   `ownerDid` (String)
*   `createdAt` (LocalDateTime)
*   `updatedAt` (LocalDateTime)
*   `deleted` (Boolean, Default `false`)
*   `atCid` (Option[String])
*   **Database Migration:** A new Slick evolution script will be required to create the `project` table.

### 5.3 Repository Layer (`app/repositories/ProjectRepository.scala`)

A new `ProjectRepository` will be created to handle database CRUD operations for the `Project` entity using Slick.

*   `create(project: Project): Future[Project]`
*   `findByProjectGuid(projectGuid: UUID): Future[Option[Project]]` (will filter `deleted = false`)
*   `update(project: Project): Future[Int]` (returns number of updated rows)
*   `softDelete(projectGuid: UUID): Future[Int]`

### 5.4 Service Layer (`app/services/ProjectService.scala`)

A new `ProjectService` will be created to encapsulate the business logic for Project operations.

*   `createProject(request: ProjectRequest, ownerDid: String): Future[UUID]`
    *   Generates `projectGuid`, `createdAt`, `updatedAt`, initial `atCid`.
    *   Calls `projectRepository.create()`.
*   `updateProject(projectGuid: UUID, request: ProjectRequest): Future[UUID]`
    *   Fetches existing `Project` by `projectGuid` (ensuring `deleted = false`).
    *   Performs optimistic locking check with `request.atCid`.
    *   Updates fields, generates new `atCid`, sets `updatedAt`.
    *   Calls `projectRepository.update()`.
*   `softDeleteProject(projectGuid: UUID): Future[Boolean]`
    *   Calls `projectRepository.softDelete()`.

### 5.5 Controller Layer (`app/controllers/ProjectController.scala`)

A new `ProjectController` will expose the API endpoints.

*   Inject `ProjectService` and the `ApiSecurityAction`.
*   Implement `create`, `update`, and `delete` actions using `secureApi.jsonAction`.
    *   `create` will take `ProjectRequest` and return `201 Created`.
    *   `update` will take `projectGuid` from path, `ProjectRequest` from body, perform optimistic locking, and return `200 OK` or `204 No Content`.
    *   `delete` will take `projectGuid` from path and return `204 No Content`.

### 5.6 DTO Definitions (`app/models/api/ProjectRequest.scala`, `app/models/api/ProjectResponse.scala`)

```scala
// app/models/api/ProjectRequest.scala
package models.api

import play.api.libs.json.{Json, OFormat}
import java.util.UUID

case class ProjectRequest(
                           name: String,
                           description: Option[String] = None,
                           atCid: Option[String] = None // For optimistic locking during updates
                         )

object ProjectRequest {
  implicit val format: OFormat[ProjectRequest] = Json.format
}

// app/models/api/ProjectResponse.scala
package models.api

import play.api.libs.json.{Json, OFormat}
import java.time.LocalDateTime
import java.util.UUID

case class ProjectResponse(
                            projectGuid: UUID,
                            name: String,
                            description: Option[String],
                            ownerDid: String,
                            createdAt: LocalDateTime,
                            updatedAt: LocalDateTime,
                            atCid: Option[String]
                          )

object ProjectResponse {
  implicit val format: OFormat[ProjectResponse] = Json.format
}
```

### 5.7 Routing (`conf/routes`)

*   `POST   /api/projects` to `ProjectController.create`
*   `PUT    /api/projects/:projectGuid` to `ProjectController.update(projectGuid: UUID)`
*   `DELETE /api/projects/:projectGuid` to `ProjectController.delete(projectGuid: UUID)`

## 6. API Security

Both `ExternalBiosampleController` and `ProjectController` will utilize the existing `secureApi` action provided by the framework, ensuring that all new endpoints are protected by the token mechanism. The `ownerDid` field in `Project` (and the `citizenDid` for `ExternalBiosample` operations if applicable) will be used for authorization checks within the service layer to ensure users can only modify their own resources.

## 7. Optimistic Locking Strategy (`at_cid`)

*   **Mechanism:** An `atCid: Option[String]` field will be added to both `CitizenBiosample` and `Project` models. This `atCid` will act as a version identifier.
*   **Generation:** A new `atCid` (e.g., a UUID or a hash of the content) will be generated and stored whenever a resource is created or successfully updated.
*   **Validation:** For `PUT` (update) operations, the incoming `request.atCid` must match the `atCid` currently stored in the database for that resource. If they do not match, it indicates a concurrent modification, and the update will be rejected with a `409 Conflict` status.
*   **Response:** The new `atCid` will be returned as part of the `ProjectResponse` or the `ExternalBiosample` update response, allowing the client to maintain the correct version for subsequent updates.

## 8. Soft Delete Strategy

*   **Mechanism:** A `deleted: Boolean` field (default `false`) will be added to both `CitizenBiosample` and `Project` models.
*   **Deletion:** Instead of physically removing records, a "delete" operation will set the `deleted` flag to `true` and update the `updatedAt` timestamp.
*   **Retrieval:** All standard read operations (e.g., `findByProjectGuid`, `findAll`) in the repositories and services must implicitly filter out records where `deleted = true`. Specific administrative endpoints could potentially retrieve deleted records if required.
*   **Hard Delete:** The `biosampleDataService.fullyDeleteBiosampleAndDependencies` currently performs a hard delete on standard biosamples. This will be reserved for system cleanup or administrative purposes, distinct from the user-facing "delete" operation on `CitizenBiosample` and `Project`.

## 9. Open Questions / Assumptions

*   **`com.decodingus.atmosphere.project` Lexicon Definition:** The specific fields and their types for the `Project` entity are assumed based on common project management attributes. Further clarification on the exact "Lexicon's main definition" would be beneficial to refine the `Project` model.
*   **`at_uri` vs. `sampleGuid`/`projectGuid`:** The prompt mentions `/{at_uri}` for paths. This document assumes that `sampleGuid` (for ExternalBiosample) and `projectGuid` (for Project) will serve as the unique identifiers in the URL paths, and `at_uri` is a conceptual identifier from the Nexus service that maps to our internal GUIDs. If `at_uri` is a distinct, externally managed identifier that needs to be stored and used directly, the models and routing would need adjustment.
*   **`at_cid` Generation Logic:** The exact algorithm for generating `at_cid` (e.g., simple UUID, hash of content, incrementing version number) needs to be decided. For this design, a UUID or simple version string is assumed.
*   **Authorization for Project:** For the `Project` entity, the design assumes an `ownerDid` field, and authorization will ensure only the `ownerDid` can modify/delete their own projects.
*   **Error Handling:** Standard Play Framework error handling will be used for `409 Conflict` (optimistic locking) and `404 Not Found`.
*   **Tapir Integration:** While the endpoints are described, the explicit Tapir definitions (`app/api/`) are not detailed but will be created as part of the implementation.
*   **Existing `BiosampleController`:** The `BiosampleController` will remain in place to serve existing UI interactions or other API consumers, operating on the non-`deleted` biosamples. The `ExternalBiosampleController` will handle the Firehose team's specific integration.