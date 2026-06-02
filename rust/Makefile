# DecodingUs (Rust) dev tasks.
.PHONY: help test db-up db-down oauth-up oauth-web oauth-dev oauth-env oauth-down

help:
	@echo "Targets:"
	@echo "  test       cargo test --workspace (set DATABASE_URL for live-DB tests)"
	@echo "  db-up      start the dev Postgres (prints DATABASE_URL)"
	@echo "  db-down    stop the dev Postgres"
	@echo "  oauth-dev  bring up the local OAuth stack (PDS+Caddy+PG) AND run du-web"
	@echo "  oauth-up   bring up the OAuth stack only (PDS+Caddy+PG, CA, test account)"
	@echo "  oauth-web  run du-web on the host with the dev OAuth env"
	@echo "  oauth-env  print the generated dev env"
	@echo "  oauth-down stop+remove PDS+Caddy"

test:
	cargo test --workspace

db-up:
	@./scripts/test-db.sh up

db-down:
	@./scripts/test-db.sh down

# One command: stack up, then du-web (Ctrl-C stops du-web; `make oauth-down` clears containers).
oauth-dev: oauth-up oauth-web

oauth-up:
	@./scripts/oauth-dev.sh up

oauth-web:
	@./scripts/oauth-dev.sh web

oauth-env:
	@./scripts/oauth-dev.sh env

oauth-down:
	@./scripts/oauth-dev.sh down
