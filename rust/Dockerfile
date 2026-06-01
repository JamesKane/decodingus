# DecodingUs (Rust) — multi-stage build to a single static-ish binary.
# No JRE, no htslib system lib (genomics is pure-Rust via noodles).
#
#   docker build -t decodingus -f rust/Dockerfile rust
#   (or via compose.yaml)

# ── builder ──────────────────────────────────────────────────────────────────
FROM rust:1-bookworm AS builder
WORKDIR /build

# Cache dependencies: copy manifests first, then sources.
COPY Cargo.toml Cargo.lock ./
COPY crates ./crates
COPY migrations ./migrations
# SQLx is built in offline mode in CI/Docker (no DB at build time). Once a dev DB
# exists, commit the `.sqlx/` cache and this picks it up automatically.
ENV SQLX_OFFLINE=true
RUN cargo build --release --bin decodingus --bin decodingus-jobs --bin decodingus-migrate

# ── runtime ──────────────────────────────────────────────────────────────────
FROM debian:bookworm-slim AS runtime
# curl for the healthcheck; ca-certificates for outbound TLS (OpenAlex/ENA/AWS).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r decodingus && useradd -r -g decodingus decodingus
WORKDIR /app

COPY --from=builder /build/target/release/decodingus /usr/local/bin/decodingus
COPY --from=builder /build/target/release/decodingus-jobs /usr/local/bin/decodingus-jobs
COPY --from=builder /build/target/release/decodingus-migrate /usr/local/bin/decodingus-migrate
# Vendored static assets + migrations shipped alongside the binary. (Askama
# templates and locale catalogs are embedded into the binary at compile time.)
COPY --chown=decodingus:decodingus crates/du-web/assets ./assets
COPY --chown=decodingus:decodingus migrations ./migrations

USER decodingus
EXPOSE 9000
ENV PORT=9000
ENV DU_ASSETS_DIR=/app/assets

HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:9000/health || exit 1

CMD ["decodingus"]
