# Project Analysis: DecodingUs

This document provides a comprehensive analysis of the DecodingUs project, a Scala-based web application. It is intended to guide developers in understanding the architecture, extending its features, and improving test coverage.

## 1. Project Overview

DecodingUs is a modern web application built with Scala 3 and the Play Framework. It serves as a platform for genomic data analysis, with features related to haplogroups, biosamples, and scientific publications. The application exposes a REST API, serves web pages, and runs background jobs for data processing.

## 2. Technology Stack

The project leverages a modern technology stack:

- **Backend Framework:** [Play Framework](https://www.playframework.com/)
- **Language:** [Scala 3](https://www.scala-lang.org/)
- **Database:** PostgreSQL
- **Database Access:** [Slick](https://scala-slick.org/)
- **API Definition:** [Tapir](https://tapir.softwaremill.com/) for OpenAPI/Swagger documentation
- **Asynchronous Jobs:** [Apache Pekko](https://pekko.apache.org/) with `pekko-quartz-scheduler`
- **Dependency Injection:** [Guice](https://github.com/google/guice)
- **Testing:** [ScalaTest](https://www.scalatest.org/) with `scalatestplus-play`
- **Build Tool:** [sbt](https://www.scala-sbt.org/)

## 3. Project Structure

The project follows a standard Play application layout with some key directories:

- **`app/`**: Contains the core application source code.
  - **`app/api/`**: Tapir endpoint definitions. These define the API structure for OpenAPI generation but do not contain business logic.
  - **`app/controllers/`**: Play controllers that handle HTTP requests. Some controllers implement the logic for the API endpoints defined in `app/api/`.
  - **`app/services/`**: The business logic layer. Controllers delegate to services to perform operations.
  - **`app/repositories/`**: The data access layer, responsible for all database interactions using Slick.
  - **`app/models/`**: Domain models, API request/response objects, and Slick table definitions.
  - **`app/modules/`**: Guice modules for dependency injection and application startup lifecycle.
  - **`app/actors/`**: Pekko actors for concurrent and background processing tasks.
- **`conf/`**: Configuration files.
  - **`application.conf`**: The main configuration file for the application, including database connections, module loading, and scheduler settings.
  - **`routes`**: The main routing file that maps HTTP requests to controller actions.
- **`test/`**: Contains automated tests.
- **`build.sbt`**: The sbt build definition file, where all project dependencies are managed.

## 4. API Architecture: A Hybrid Approach

The project uses a hybrid approach for its API:

1.  **Declarative Endpoints with Tapir:** The `app/api/` directory contains endpoint definitions using Tapir. These definitions describe the API's shape (URL, methods, inputs, outputs) and are used to generate a unified OpenAPI specification.
2.  **Implementation in Play Controllers:** The actual logic for handling API requests is implemented in standard Play controllers located in `app/controllers/`.
3.  **Routing:** The `conf/routes` file maps API paths (e.g., `/api/...`) to `controllers.ApiRouter`, which serves the Swagger UI. The same routes file also directs requests to the appropriate Play controllers that contain the business logic.

This separation allows for clear API documentation while leveraging the familiar Play Framework controller pattern for implementation.

## 5. Application Lifecycle

The application's startup and lifecycle are managed by Guice modules in the `app/modules/` directory.

- **`StartupModule.scala`**: This module eagerly binds a `StartupService`.
- **`StartupService`**: This service is responsible for initializing the application on startup. A key task it performs is seeding the database with essential data, such as importing haplogroup trees via the `TreeInitializationService`.
- **`Scheduler.scala`**: This module configures and schedules background jobs using the Pekko Quartz Scheduler. Job schedules are defined in `conf/application.conf`.

## 6. Testing Guide

Tests are located in the `test/` directory and are written using ScalaTest.

### Running Tests

To run the existing test suite, execute the following command in your sbt shell:

```bash
sbt test
```

### Adding New Tests

When adding new features, it is crucial to add corresponding tests.

- **Controller Tests:** For new controllers, add a new spec file in `test/controllers/`. Use `scalatestplus-play` to help create a test application instance and make requests to your controller actions.
- **Service Tests:** For new services, create a new spec file in a corresponding package under `test/`. Mock any repository dependencies to isolate the business logic for unit testing.
- **Repository Tests:** For repository tests, you may need an in-memory or test database to verify database queries.

## 7. Development Guide: Adding a New Feature

Here is a step-by-step guide to adding a new feature (e.g., a new API endpoint):

1.  **Define the Endpoint (Tapir):** If it's a new API endpoint, first define its structure in a new file within `app/api/`. This makes it part of the OpenAPI specification.
2.  **Add Route:** Add a new entry in the `conf/routes` file to route the new URL to a new controller action.
3.  **Create Controller:** Create a new controller in `app/controllers/` or add a new action to an existing one. This controller will handle the HTTP request.
4.  **Implement Service Logic:** Create a new service in `app/services/` to contain the business logic. The controller should call this service.
5.  **Implement Repository Logic:** If the feature requires database access, create a new repository in `app/repositories/` or add a new query method to an existing one. The service will use this repository.
6.  **Add Models:** Define any new data structures (case classes) needed for the API request/response or for the database schema in the `app/models/` directory.
7.  **Write Tests:** Add new tests for the controller, service, and repository to ensure the feature works correctly and is protected against future regressions.
