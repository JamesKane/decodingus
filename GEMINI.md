# Project Context: Decoding Us

## Project Overview

**Decoding Us** is a collaborative web platform designed for genetic genealogy and population research. It leverages citizen science to build high-resolution haplogroup trees and facilitate privacy-preserving IBD (Identity by Descent) segment matching. The application connects individual genomic data (processed on secure Edge nodes) with global research efforts.

The project is built using **Scala 3** and the **Play Framework**, employing a modern, scalable architecture. It features a hybrid API approach using **Tapir** for OpenAPI documentation and standard Play controllers for implementation. The frontend utilizes **HTMX** for dynamic interactions without heavy client-side state.

## Technology Stack

*   **Language:** Scala 3.3.6
*   **Web Framework:** Play Framework (with `play-slick`)
*   **Database:** PostgreSQL (using Slick 6.2.0 for access)
*   **API Documentation:** Tapir (OpenAPI/Swagger UI)
*   **Concurrency/Jobs:** Apache Pekko (Actors & Streams), `pekko-quartz-scheduler`
*   **Frontend:** HTMX, Bootstrap 5
*   **Dependency Injection:** Guice
*   **Cloud Integration:** AWS SDK (Secrets Manager, SES)
*   **Containerization:** Docker

## Building and Running

The project uses **sbt** (Scala Build Tool) for all build and lifecycle management tasks.

### Prerequisites
*   Java Development Kit (JDK) compatible with Scala 3.
*   sbt installed.
*   PostgreSQL database running and configured.

### Key Commands

*   **Run the application:**
    ```bash
    sbt run
    ```
    The application typically starts on `http://localhost:9000`.

*   **Run tests:**
    ```bash
    sbt test
    ```

*   **Compile code:**
    ```bash
    sbt compile
    ```

*   **Generate IDE configuration:**
    (If using IntelliJ IDEA, it generally handles this automatically via BSP import).

## Architecture & Project Structure

The project follows a standard Layered Architecture within the Play Framework structure:

*   **`app/api/`**: **API Definitions (Tapir).** Defines the shape of endpoints (inputs/outputs) for OpenAPI generation. *Does not contain business logic.*
*   **`app/controllers/`**: **Web Layer.** Handles HTTP requests. Implements the logic for API endpoints and serves HTML pages.
*   **`app/services/`**: **Business Logic Layer.** Contains the core application logic. Controllers delegate complex operations here.
*   **`app/repositories/`**: **Data Access Layer.** Handles all database interactions using Slick.
*   **`app/models/`**: **Domain Layer.** Contains Case Classes for API DTOs and Slick Table definitions.
*   **`app/modules/`**: **Configuration.** Guice modules for DI and application lifecycle (e.g., `StartupModule`).
*   **`app/actors/`**: **Background Processing.** Pekko actors for asynchronous tasks.
*   **`conf/`**: **Configuration.** `application.conf` (main config) and `routes` (URL mappings).

## Development Conventions

*   **Hybrid API Pattern:**
    1.  Define the endpoint signature in `app/api/` (using Tapir).
    2.  Add the route in `conf/routes`.
    3.  Implement the logic in a Controller within `app/controllers/`.
    4.  Ensure the Controller delegates to a Service, which uses a Repository.

*   **Database Access:**
    *   Use **Slick** for type-safe database queries.
    *   Define table schemas in `app/models/dal/DatabaseSchema.scala` (or similar DAL files).
    *   Repositories should encapsulate all DB queries.

*   **Frontend Development:**
    *   Use **Twirl** templates (`.scala.html` files in `app/views/`) for server-side rendering.
    *   Use **HTMX** attributes in HTML for dynamic behavior (e.g., `hx-get`, `hx-post`, `hx-target`). Avoid writing custom JavaScript unless necessary.

*   **Testing:**
    *   Write tests using **ScalaTest** and `scalatestplus-play`.
    *   Place tests in the `test/` directory, mirroring the `app/` package structure.
    *   Ensure new features have corresponding Controller, Service, and Repository tests.

*   **Dependency Injection:**
    *   Use **@Inject()** annotation for constructor injection in classes.
    *   Bind interfaces to implementations in Module files (e.g., `app/modules/ApplicationModule.scala`) if necessary.
