#!/usr/bin/env bash
# End-to-end run: fresh database, dev-seed, the packaged jar on port 8089 with the local
# profile and the directory mail sink, then the Playwright suite. Everything is synthetic:
# the office address is overridden with an .example address and no e-mail leaves the machine.
#
# Usage:  e2e/run.sh [playwright args…]        e.g. e2e/run.sh --project=desktop tests/public.spec.ts
#
# Environment:
#   E2E_DATABASE_URL / E2E_DATABASE_USER / E2E_DATABASE_PASSWORD
#         JDBC connection of an EMPTY database to use (CI: the postgres service). When unset,
#         the script drops and recreates ls_web_e2e on the local instance (scripts/local-db.sh).
#   E2E_PORT (default 8089), E2E_JAR (default target/ls-web-*.jar; built when missing),
#   E2E_KEEP_APP=1 leaves the app running after the suite (local debugging).
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E_DIR="$REPO_DIR/e2e"
PORT="${E2E_PORT:-8089}"
BASE_URL="http://localhost:$PORT"
MAIL_DIR="$REPO_DIR/.local/mail/e2e"
LOG_DIR="$REPO_DIR/.local/e2e"
APP_LOG="$LOG_DIR/app.log"
DB_NAME="ls_web_e2e"
APP_PID=""

log() { echo "e2e: $*" >&2; }

cleanup() {
  if [[ -n "$APP_PID" && "${E2E_KEEP_APP:-0}" != "1" ]]; then
    log "stopping the app (pid $APP_PID)"
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# 1. The jar under test.
JAR="${E2E_JAR:-}"
if [[ -z "$JAR" ]]; then
  JAR="$(ls -1 "$REPO_DIR"/target/ls-web-*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc\|\.original' | head -n 1 || true)"
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  log "no packaged jar found: building it (./mvnw -B -ntp -DskipTests package)"
  (cd "$REPO_DIR" && ./mvnw -B -ntp -q -DskipTests package)
  JAR="$(ls -1 "$REPO_DIR"/target/ls-web-*.jar | grep -v -- '\.original' | head -n 1)"
fi
log "jar: $JAR"

# 2. An empty database.
if [[ -n "${E2E_DATABASE_URL:-}" ]]; then
  DB_URL="$E2E_DATABASE_URL"
  DB_USER="${E2E_DATABASE_USER:-ls}"
  DB_PASSWORD="${E2E_DATABASE_PASSWORD:-ls}"
  log "database: $DB_URL (provided; must be empty)"
else
  "$REPO_DIR/scripts/local-db.sh" start >/dev/null
  "$REPO_DIR/scripts/local-db.sh" psql postgres -q -c "DROP DATABASE IF EXISTS $DB_NAME" >/dev/null
  "$REPO_DIR/scripts/local-db.sh" createdb "$DB_NAME" >/dev/null
  DB_URL="jdbc:postgresql://localhost:${LS_PG_PORT:-54329}/$DB_NAME"
  DB_USER="ls"
  DB_PASSWORD="ls"
  log "database: $DB_URL (fresh)"
fi

APP_ARGS=(
  "--spring.profiles.active=local"
  "--DATABASE_URL=$DB_URL"
  "--DATABASE_USER=$DB_USER"
  "--DATABASE_PASSWORD=$DB_PASSWORD"
  # Synthetic office address: the office notices land in the mail sink, never in a real mailbox.
  "--ls.business.office-email=ufficio@logisticasanchez.example"
  "--ls.business.site-url=$BASE_URL"
)

# 3. dev-seed (migrations run here; the admin password is printed once and captured).
rm -rf "$MAIL_DIR" "$LOG_DIR"
mkdir -p "$MAIL_DIR" "$LOG_DIR"
log "seeding synthetic data (dev-seed)"
SEED_OUTPUT="$(java -jar "$JAR" dev-seed "${APP_ARGS[@]}" 2>"$LOG_DIR/dev-seed.log")"
ADMIN_PASSWORD="$(printf '%s\n' "$SEED_OUTPUT" | sed -n 's/^ *admin\.seed (ADMIN) *password: *\([^ ]*\).*$/\1/p' | head -n 1)"
if [[ -z "$ADMIN_PASSWORD" ]]; then
  log "dev-seed did not print the admin.seed password; output follows"
  printf '%s\n' "$SEED_OUTPUT" >&2
  cat "$LOG_DIR/dev-seed.log" >&2
  exit 1
fi

# 4. The app.
log "starting the app on $BASE_URL (log: $APP_LOG)"
java -jar "$JAR" "${APP_ARGS[@]}" \
  "--SERVER_PORT=$PORT" \
  "--ls.notifications.directory.path=$MAIL_DIR" \
  "--ls.notifications.worker.interval=2s" \
  >"$APP_LOG" 2>&1 &
APP_PID=$!
for _ in $(seq 1 90); do
  if curl -fsS -o /dev/null "$BASE_URL/actuator/health" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    log "the app exited during start-up; log follows"
    cat "$APP_LOG" >&2
    exit 1
  fi
  sleep 1
done
if ! curl -fsS -o /dev/null "$BASE_URL/actuator/health"; then
  log "the app did not answer /actuator/health within 90 s; log follows"
  cat "$APP_LOG" >&2
  exit 1
fi
log "app ready"

# 5. The suite.
cd "$E2E_DIR"
if [[ ! -d node_modules ]]; then
  log "installing the Playwright tooling (npm ci)"
  npm ci --no-audit --no-fund
fi
set +e
E2E_BASE_URL="$BASE_URL" E2E_MAIL_DIR="$MAIL_DIR" E2E_ADMIN_USER="admin.seed" E2E_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  npx playwright test "$@"
STATUS=$?
set -e
if [[ "$STATUS" -ne 0 ]]; then
  log "suite failed (exit $STATUS); app log tail follows"
  tail -n 60 "$APP_LOG" >&2
fi
exit "$STATUS"
