#!/usr/bin/env bash
# Local Postgres (PostGIS) for tests/dev on a Docker-less Apple-Silicon Mac.
#
# Uses Apple's `container` CLI by default (this Mac Studio has no Docker). Apple
# `container` gives each container its own routable IP (no localhost port
# forwarding), so `up` discovers that IP and prints the matching DATABASE_URL.
# The same PostGIS image is used in production via compose.yaml.
#
#   eval "$(./scripts/test-db.sh up)"   start, wait, migrate, export DATABASE_URL
#   ./scripts/test-db.sh down           stop and remove the container
#   ./scripts/test-db.sh reset          down then up
#   ./scripts/test-db.sh url            print DATABASE_URL for the running container
#
# If $DATABASE_URL is already set, `up`/`url` use it as-is and DO NOT start a
# container (native-Postgres fallback — see plan §9).
set -euo pipefail

NAME="${DU_PG_NAME:-du-pg}"
# imresamu/postgis publishes linux/arm64 (the official postgis/postgis is amd64-only
# and Apple `container` runs arm64 VMs without emulation).
IMAGE="${DU_PG_IMAGE:-docker.io/imresamu/postgis:16-3.4}"
PASSWORD="${DU_PG_PASSWORD:-dev}"
DB="${DU_PG_DB:-decodingus}"
USER="${DU_PG_USER:-postgres}"
HERE="$(cd "$(dirname "$0")" && pwd)"

runtime() {
  if command -v container >/dev/null 2>&1; then echo "container"
  elif command -v docker >/dev/null 2>&1; then echo "docker"
  else echo ""; fi
}

require_runtime() {
  local rt; rt="$(runtime)"
  if [ -z "$rt" ]; then
    cat >&2 <<'EOF'
ERROR: neither `container` (Apple) nor `docker` is installed.
  Apple container: https://github.com/apple/container/releases
  Or set DATABASE_URL to an existing Postgres and skip the container entirely.
EOF
    exit 1
  fi
  echo "$rt"
}

# Resolve host:port for the running container under the given runtime.
container_host() {
  local rt="$1"
  if [ "$rt" = "container" ]; then
    # Each container has its own IP; read it from `container ls`.
    container ls 2>/dev/null | awk -v n="$NAME" '$1==n {print $6}' | cut -d/ -f1
  else
    echo "localhost"
  fi
}

build_url() { echo "postgres://${USER}:${PASSWORD}@${1}:5432/${DB}?sslmode=disable"; }

wait_ready() {
  local host="$1"
  echo "waiting for postgres on ${host}:5432 ..." >&2
  for _ in $(seq 1 60); do
    if timeout 2 bash -c "(exec 3<>/dev/tcp/${host}/5432)" 2>/dev/null; then
      sleep 1
      echo "postgres is accepting connections." >&2
      return 0
    fi
    sleep 1
  done
  echo "ERROR: postgres did not become ready in time" >&2
  exit 1
}

apply_migrations() {
  local url="$1"
  if command -v sqlx >/dev/null 2>&1; then
    echo "applying migrations via sqlx-cli ..." >&2
    DATABASE_URL="$url" sqlx migrate run --source "${HERE}/../migrations" >&2
  else
    echo "NOTE: sqlx-cli not found — migrations not auto-applied." >&2
    echo "      \`cargo test\` applies them via du_db::run_migrations, or install sqlx-cli." >&2
  fi
}

cmd="${1:-up}"
case "$cmd" in
  up)
    if [ -n "${DATABASE_URL:-}" ]; then
      wait_ready "$(echo "$DATABASE_URL" | sed -E 's#.*@([^:/]+).*#\1#')"
      apply_migrations "$DATABASE_URL"
      echo "export DATABASE_URL=\"$DATABASE_URL\""
      exit 0
    fi
    rt="$(require_runtime)"
    echo "starting $NAME ($IMAGE) via $rt ..." >&2
    if [ "$rt" = "container" ]; then
      container run -d --name "$NAME" \
        -e POSTGRES_PASSWORD="$PASSWORD" -e POSTGRES_DB="$DB" "$IMAGE" >/dev/null
    else
      docker run -d --name "$NAME" -p 5432:5432 \
        -e POSTGRES_PASSWORD="$PASSWORD" -e POSTGRES_DB="$DB" "$IMAGE" >/dev/null
    fi
    # Give Apple `container` a moment to assign an IP.
    host=""; for _ in $(seq 1 20); do host="$(container_host "$rt")"; [ -n "$host" ] && break; sleep 1; done
    [ -n "$host" ] || { echo "ERROR: could not resolve container IP" >&2; exit 1; }
    url="$(build_url "$host")"
    wait_ready "$host"
    apply_migrations "$url"
    echo "export DATABASE_URL=\"$url\""
    ;;
  down)
    rt="$(require_runtime)"
    "$rt" rm -f "$NAME" >/dev/null 2>&1 || true
    echo "removed $NAME" >&2
    ;;
  reset)
    "$0" down || true
    "$0" up
    ;;
  url)
    if [ -n "${DATABASE_URL:-}" ]; then echo "$DATABASE_URL"; exit 0; fi
    rt="$(require_runtime)"; host="$(container_host "$rt")"
    [ -n "$host" ] || { echo "ERROR: container '$NAME' not running" >&2; exit 1; }
    build_url "$host"
    ;;
  *)
    echo "usage: $0 {up|down|reset|url}" >&2
    exit 2
    ;;
esac
