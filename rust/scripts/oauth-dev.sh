#!/usr/bin/env bash
# One-command local AT Protocol OAuth dev stack: Postgres + PDS + Caddy (TLS),
# wired so du-web's dev login (/login/atproto/dev) completes the handshake over
# the PDS's canonical https://pds.test. Runtime-agnostic: Apple `container` or
# Docker (du-web itself runs on the host via cargo — the Docker image build is
# still blocked by unpushed sibling path-deps, and the CA/IP wiring is dynamic,
# so a pure `compose up` can't do all of it; this script is the orchestrator).
#
#   ./scripts/oauth-dev.sh up        # boot stack, extract CA, create account, write env
#   ./scripts/oauth-dev.sh web       # run du-web on the host with the dev env (foreground)
#   ./scripts/oauth-dev.sh env       # print the env file
#   ./scripts/oauth-dev.sh down      # stop + remove PDS/Caddy (Postgres left to test-db.sh)
#
# Or via the Makefile: `make oauth-dev` (= up + web).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
STATE="$ROOT/.oauth-dev"
PDS_NAME="du-oauth-pds"
CADDY_NAME="du-oauth-caddy"
PDS_HOST="pds.test"
ACCT_HANDLE="alice.pds.test"
ACCT_PASSWORD="alice-pw-12345"
ACCT_EMAIL="alice@example.test"
WEB_PORT="9000"

rt() { if command -v container >/dev/null 2>&1; then echo container; elif command -v docker >/dev/null 2>&1; then echo docker; else echo ""; fi; }
RT="$(rt)"
[ -n "$RT" ] || { echo "ERROR: need Apple \`container\` or \`docker\`." >&2; exit 1; }

# Container IP on its runtime network (used for cross-container + host reach).
ctr_ip() {
  local name="$1"
  if [ "$RT" = container ]; then
    container ls 2>/dev/null | awk -v n="$name" '$1==n{print $6}' | cut -d/ -f1
  else
    docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$name" 2>/dev/null
  fi
}
ctr_rm() { if [ "$RT" = container ]; then container rm -f "$1" 2>/dev/null || true; else docker rm -f "$1" 2>/dev/null || true; fi; }
ctr_exec() { local n="$1"; shift; if [ "$RT" = container ]; then container exec "$n" "$@"; else docker exec "$n" "$@"; fi; }

wait_http() { # url, tries
  local url="$1" tries="${2:-30}"
  for _ in $(seq 1 "$tries"); do curl -fsS -m 3 "$url" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}

cmd_up() {
  mkdir -p "$STATE/pdsdata/blocks"

  # 1. Postgres (reuse the shared dev DB helper; idempotent).
  echo "==> postgres (test-db.sh)"
  "$HERE/test-db.sh" up >/dev/null 2>&1 || true
  DATABASE_URL="$("$HERE/test-db.sh" url 2>/dev/null || true)"
  [ -n "$DATABASE_URL" ] || { echo "could not get DATABASE_URL (is the dev Postgres up?)" >&2; exit 1; }
  export DATABASE_URL

  # 2. PDS.  (Plain string flags, not arrays — macOS bash 3.2 + `set -u`.)
  echo "==> PDS ($PDS_NAME)"
  ctr_rm "$PDS_NAME"
  local run="container" pds_ports="" caddy_ports=""
  if [ "$RT" = docker ]; then run="docker"; pds_ports="-p 3000:3000"; caddy_ports="-p 443:443"; fi
  $run run -d --name "$PDS_NAME" $pds_ports -v "$STATE/pdsdata:/pds" \
    -e PDS_HOSTNAME="$PDS_HOST" -e PDS_PORT=3000 \
    -e PDS_JWT_SECRET="$(openssl rand --hex 16)" \
    -e PDS_ADMIN_PASSWORD="$(openssl rand --hex 16)" \
    -e PDS_PLC_ROTATION_KEY_K256_PRIVATE_KEY_HEX="$(openssl rand --hex 32)" \
    -e PDS_DATA_DIRECTORY=/pds -e PDS_BLOBSTORE_DISK_LOCATION=/pds/blocks \
    -e PDS_DID_PLC_URL=https://plc.directory -e PDS_INVITE_REQUIRED=false -e PDS_DEV_MODE=true \
    ghcr.io/bluesky-social/pds:latest >/dev/null

  local pds_ip; for _ in $(seq 1 20); do pds_ip="$(ctr_ip "$PDS_NAME")"; [ -n "$pds_ip" ] && break; sleep 1; done
  local pds_reach; [ "$RT" = docker ] && pds_reach="127.0.0.1" || pds_reach="$pds_ip"
  wait_http "http://$pds_reach:3000/xrpc/_health" 30 || { echo "PDS not healthy" >&2; exit 1; }
  echo "    PDS healthy at http://$pds_reach:3000 (network ip $pds_ip)"

  # 3. Caddy TLS proxy → PDS, canonical https://pds.test.
  echo "==> Caddy ($CADDY_NAME)"
  ctr_rm "$CADDY_NAME"
  printf '{\n  auto_https disable_redirects\n}\n%s {\n  tls internal\n  reverse_proxy %s:3000\n}\n' "$PDS_HOST" "$pds_ip" > "$STATE/Caddyfile"
  $run run -d --name "$CADDY_NAME" $caddy_ports -v "$STATE/Caddyfile:/etc/caddy/Caddyfile" \
    docker.io/library/caddy:2 >/dev/null
  local caddy_ip; for _ in $(seq 1 20); do caddy_ip="$(ctr_ip "$CADDY_NAME")"; [ -n "$caddy_ip" ] && break; sleep 1; done
  local caddy_reach; [ "$RT" = docker ] && caddy_reach="127.0.0.1" || caddy_reach="$caddy_ip"
  sleep 2

  # 4. Caddy internal CA → trust file.
  for _ in $(seq 1 15); do
    ctr_exec "$CADDY_NAME" cat /data/caddy/pki/authorities/local/root.crt > "$STATE/caddy_ca.crt" 2>/dev/null && \
      [ -s "$STATE/caddy_ca.crt" ] && break
    sleep 1
  done
  [ -s "$STATE/caddy_ca.crt" ] || { echo "could not extract Caddy CA" >&2; exit 1; }
  curl -fsS --resolve "$PDS_HOST:443:$caddy_reach" --cacert "$STATE/caddy_ca.crt" \
    "https://$PDS_HOST/.well-known/oauth-authorization-server" >/dev/null \
    || { echo "HTTPS via Caddy failed" >&2; exit 1; }
  echo "    Caddy serving https://$PDS_HOST (reach $caddy_reach), CA → .oauth-dev/caddy_ca.crt"

  # 5. Test account (idempotent).
  echo "==> test account ($ACCT_HANDLE)"
  curl -s -m 20 -X POST "http://$pds_reach:3000/xrpc/com.atproto.server.createAccount" \
    -H "Content-Type: application/json" \
    -d "{\"handle\":\"$ACCT_HANDLE\",\"email\":\"$ACCT_EMAIL\",\"password\":\"$ACCT_PASSWORD\"}" \
    | grep -q '"did"' && echo "    created" || echo "    (already exists or skipped)"

  # 6. Env file for du-web.
  cat > "$STATE/env" <<EOF
# Generated by scripts/oauth-dev.sh — source before \`cargo run -p du-web\`.
export DATABASE_URL="$DATABASE_URL"
export APP_SECRET="local-oauth-dev-secret-at-least-32-chars"
export PORT="$WEB_PORT"
export OAUTH_BASE_URL="http://127.0.0.1:$WEB_PORT"
export DU_OAUTH_DEV_PDS="https://$PDS_HOST"
export DU_OAUTH_DEV_CA="$STATE/caddy_ca.crt"
export DU_OAUTH_DEV_RESOLVE="$PDS_HOST:$caddy_reach"
export DU_OAUTH_LOOPBACK="http://127.0.0.1:$WEB_PORT"
EOF
  echo
  echo "Stack up. Account: $ACCT_HANDLE / $ACCT_PASSWORD"
  echo "Next: 'make oauth-web' (or ./scripts/oauth-dev.sh web)."
  echo "Browser consent needs a hosts entry: $caddy_reach $PDS_HOST  (+ trust .oauth-dev/caddy_ca.crt)."
}

cmd_web() {
  [ -f "$STATE/env" ] || { echo "run 'oauth-dev.sh up' first" >&2; exit 1; }
  # shellcheck disable=SC1091
  set -a; . "$STATE/env"; set +a
  echo "==> du-web on http://127.0.0.1:$WEB_PORT  (dev login: /login/atproto/dev?handle=$ACCT_HANDLE)"
  exec cargo run -p du-web
}

cmd_env() { cat "$STATE/env"; }

cmd_down() {
  ctr_rm "$PDS_NAME"; ctr_rm "$CADDY_NAME"
  echo "PDS + Caddy removed. (Postgres: ./scripts/test-db.sh down). State in .oauth-dev/ kept."
}

case "${1:-}" in
  up) cmd_up ;;
  web) cmd_web ;;
  env) cmd_env ;;
  down) cmd_down ;;
  *) echo "usage: $0 {up|web|env|down}" >&2; exit 2 ;;
esac
