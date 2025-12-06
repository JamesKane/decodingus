# Decoding Us — Development Guidelines (Project‑Specific)

This document captures build, configuration, testing, and development tips that are specific to this repository (Scala 3 + Play 3.x). It assumes an advanced Scala/Play developer.

## Build & Configuration

- Toolchain
  - Scala: 3.3.6 (see `build.sbt`)
  - Play: sbt plugin `org.playframework:sbt-plugin:3.0.9` (see `project/plugins.sbt`)
  - sbt: Use a recent sbt 1.10.x; the repo relies on conventional Play/sbt layout.
- Project settings
  - Module: single root project, `enablePlugins(PlayScala)` in `build.sbt`.
  - JVM: tested with Temurin JDK 21 (Docker runtime uses `eclipse-temurin:21-jre-jammy`).
  - Scalac flag: `-Xmax-inlines 128` is set — keep in mind for heavy inline usages/macros.
- Key library versions (selected)
  - play-slick 6.2.0 with Postgres JDBC 42.7.8
  - slick‑pg 0.23.1 (+ jts, play-json integrations)
  - Tapir 1.11.50 (core, play-server, json-play, swagger-ui-bundle)
  - Apache Pekko 1.1.5 (pinned; see Quartz note below)
  - pekko‑quartz‑scheduler 1.3.0-pekko-1.1.x
  - scalatestplus‑play 7.0.2 (Test)
- Pekko/Quartz pin
  - `APACHE_PEKKO_VERSION` is deliberately pinned to 1.1.5 because Quartz requires 1.1.x. Bumping beyond this can cause startup errors. Update Quartz first if you need to lift the pin.

### Application configuration (conf/application.conf)

- Secrets and toggles
  - `play.http.secret.key` can be overridden by `APPLICATION_SECRET`.
  - Sessions are disabled (`play.http.session.disabled = true`). Re‑enable if/when needed.
  - Recaptcha: `recaptcha.enable` (env `ENABLE_RECAPTCHA`), keys from env `RECAPTCHA_SECRET_KEY`, `RECAPTCHA_SITE_KEY`.
- Modules
  - Enabled: `modules.BaseModule`, `ServicesModule`, `RecaptchaModule`, `StartupModule`, `ApplicationModule`, `ApiSecurityModule`, and Caffeine cache module (`play.api.cache.caffeine.CaffeineCacheModule`). Startup work is performed by `modules.StartupService` (see `app/modules/StartupModule.scala`).
- Caching
  - Caffeine is the cache provider; default cache and an explicit `sitemap` cache are configured.
- Database (play-slick)
  - Profile: `slick.jdbc.PostgresProfile$`
  - JDBC: `jdbc:postgresql://localhost:5432/decodingus_db`
  - Default dev creds: `decodingus_user` / `decodingus_password` (override in prod via env/Secrets Manager).
- Evolutions
  - `play.evolutions.autocommit = true` enables automatic application of evolutions. Disable for production and manage via CI/migrations.
- AWS & misc
  - AWS region default: `us-east-1`; example secrets path included.
  - Contact recipient: override via `CONTACT_RECIPIENT_EMAIL`.
  - `biosample.hash.salt` configurable via `BIOSAMPLE_HASH_SALT`.
- Logging
  - Pekko loglevel DEBUG by default; consider overriding in production.

### Local run

- With sbt (recommended during development):
  - `sbt run` — starts Play dev server on :9000. Ensure Postgres is available if you touch DB‑backed pages or services.
- With Docker (prebuilt stage)
  - The `Dockerfile` expects a staged distribution at `target/universal/stage`.
  - Build a universal distribution: `sbt stage`
  - Build and run image:
    - `docker build -t decodingus:local .`
    - `docker run -p 9000:9000 --env APPLICATION_SECRET=... decodingus:local`
  - You must also provide DB connectivity (e.g., link a Postgres container or env JDBC overrides) for features requiring DB.

### Database quickstart (developer machine)

- Create local Postgres role and database (adjust to your local policy):
  - `createuser -P decodingus_user` (password `decodingus_password`), `createdb -O decodingus_user decodingus_db`.
- On first app start with evolutions enabled, Play will apply SQL files from `conf/evolutions/default` in order.
- For tests, prefer isolating DB‑heavy specs using test containers or an in‑memory profile; current suite primarily uses Play functional tests and does not require DB for the simple Home page.

## Testing

- Frameworks
  - ScalaTest + scalatestplus‑play. Styles vary by spec; the existing suite uses `PlaySpec` and `GuiceOneAppPerTest`.
- Running tests
  - Run full suite: `sbt test`
  - Run a single suite: `sbt "testOnly controllers.HomeControllerSpec"`
  - By pattern: `sbt "testOnly *HomeControllerSpec"`
  - Run a single test by name (when supported by the style): `sbt "testOnly controllers.HomeControllerSpec -- -z \"router\""`
- Application DI in tests
  - Prefer DI for controllers/services in Play tests to keep constructor wiring aligned with production. Example from `test/controllers/HomeControllerSpec.scala`:
    ```scala
    class HomeControllerSpec extends PlaySpec with GuiceOneAppPerTest with Injecting {
      "HomeController GET" should {
        "render the index page from the application" in {
          val controller = inject[HomeController]
          val home = controller.index().apply(FakeRequest(GET, "/"))
          status(home) mustBe OK
          contentType(home) mustBe Some("text/html")
          contentAsString(home) must include ("Welcome to Play")
        }
      }
    }
    ```
  - Avoid manual `new Controller(...)` unless you supply all constructor dependencies. The controller constructors in this repo often include `Cached` and `SyncCacheApi` which are bound by Play.
- Demo test (validated process)
  - A temporary pure unit test was created and executed to validate commands:
    ```scala
    // test/utils/DemoSpec.scala
    package utils
    import org.scalatest.funsuite.AnyFunSuite
    class DemoSpec extends AnyFunSuite {
      test("math sanity holds") {
        assert(1 + 1 == 2)
      }
    }
    ```
  - It was run successfully via the test runner; afterward it was removed to avoid leaving extra files in the repo.

### Adding new tests

- Controller tests: use `GuiceOneAppPerTest` or `GuiceOneAppPerSuite` depending on reuse and cost. Use `route(app, FakeRequest(...))` for end‑to‑end route testing.
- Service/utility tests: prefer pure unit tests with ScalaTest `AnyFunSuite` or `AnyFlatSpec` where Play isn’t needed.
- DB‑backed components: consider a separate test configuration/profile and a disposable Postgres schema. If introducing containerized tests, add `testcontainers` and wire a Play `Application` with test DB settings.

## Development Tips (Repo‑specific)

- Tapir
  - OpenAPI/Swagger UI bundle is included; the site likely serves docs at `/api/docs` (see `HomeController.sitemap()` for the URL hint).
- Caching
  - `HomeController.sitemap()` and `robots()` responses are cached for 24h using Caffeine via `Cached` action. If you change sitemap structure, remember cache keys.
- Startup behaviors
  - `StartupService` performs tree initialization by calling `TreeInitializationService.initializeIfNeeded()` asynchronously at app start. Watch logs to understand conditional imports.
- Views & HTMX
  - HTMX is available via WebJars. Views are Twirl templates under `app/views`. The landing page content is used by tests to assert presence of "Welcome to Play".
- Security/config
  - Replace the default `play.http.secret.key`, recaptcha keys, and salts in any non‑dev environment. Sessions are disabled by default.
- Code style
  - Follow existing formatting and idioms in the repo. Keep controllers lean, services injected. Non‑trivial logic belongs in services under `app/services/*`.

## Common Tasks & Commands

- Start dev server: `sbt run`
- Compile: `sbt compile`
- Stage distribution: `sbt stage`
- Run tests: `sbt test`
- Single test: `sbt "testOnly *YourSpec"`

