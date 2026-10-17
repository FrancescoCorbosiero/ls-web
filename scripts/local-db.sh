#!/usr/bin/env bash
# Local PostgreSQL 16 for development and tests, without Docker.
#
# Runs a single PostgreSQL instance under .local/pg (gitignored) on port 54329 with the
# role "ls" (password "ls", local use only) and the databases ls_web (development, profile
# "local") and ls_web_test (integration tests). Phase 2 module worktrees create their own
# databases with "createdb ls_<module>" on the same instance.
#
# Usage:
#   scripts/local-db.sh start            initialise (first time) and start the instance
#   scripts/local-db.sh stop             stop the instance
#   scripts/local-db.sh status           show whether the instance accepts connections
#   scripts/local-db.sh reset            stop, delete the data directory and start afresh
#   scripts/local-db.sh psql [db] [...]  open psql on ls_web (or the given database)
#   scripts/local-db.sh createdb <name>  create another database owned by "ls"
#   scripts/local-db.sh env              print the TEST_DATABASE_* exports for the tests
#
# Environment overrides: LS_PG_BIN (default /usr/lib/postgresql/16/bin), LS_PG_PORT
# (default 54329), LS_PG_DIR (default <repo>/.local/pg), LS_PG_USER (the OS user that owns
# the server processes when the script is run as root; default "postgres" - PostgreSQL
# refuses to run as root, so the cluster is initialised and started as that user).
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_BIN="${LS_PG_BIN:-/usr/lib/postgresql/16/bin}"
PG_PORT="${LS_PG_PORT:-54329}"
PG_DIR="${LS_PG_DIR:-$REPO_DIR/.local/pg}"
PG_DATA="$PG_DIR/data"
PG_LOG="$PG_DIR/postgres.log"
PG_SOCKET_DIR="$PG_DIR/run"
PG_ROLE="ls"
PG_ROLE_PASSWORD="ls"
PG_DATABASES=("ls_web" "ls_web_test")
PG_OS_USER="${LS_PG_USER:-postgres}"

# PostgreSQL refuses to run as root: when the script is run as root, server-side commands
# (initdb, pg_ctl, the superuser psql) are run as $PG_OS_USER, who must own the data directory.
run_pg() {
  if [[ "$(id -u)" -eq 0 ]]; then
    if ! id "$PG_OS_USER" >/dev/null 2>&1; then
      echo "local-db: running as root but the OS user '$PG_OS_USER' does not exist (set LS_PG_USER)" >&2
      exit 1
    fi
    runuser -u "$PG_OS_USER" -- "$@"
  else
    "$@"
  fi
}

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

require_binaries() {
  for bin in initdb pg_ctl postgres psql createdb; do
    if [[ ! -x "$PG_BIN/$bin" ]]; then
      echo "local-db: $PG_BIN/$bin not found (set LS_PG_BIN)" >&2
      exit 1
    fi
  done
}

pg_running() {
  run_pg "$PG_BIN/pg_ctl" -D "$PG_DATA" status >/dev/null 2>&1
}

pg_ready() {
  "$PG_BIN/pg_isready" -h localhost -p "$PG_PORT" -q 2>/dev/null
}

as_superuser() {
  # The bootstrap superuser is the OS user that ran initdb; connect over the local socket.
  run_pg "$PG_BIN/psql" -h "$PG_SOCKET_DIR" -p "$PG_PORT" -v ON_ERROR_STOP=1 -q -d postgres "$@"
}

init_cluster() {
  mkdir -p "$PG_DIR" "$PG_SOCKET_DIR"
  if [[ "$(id -u)" -eq 0 ]]; then
    chown "$PG_OS_USER" "$PG_DIR" "$PG_SOCKET_DIR"
    chmod o+x "$REPO_DIR" "$REPO_DIR/.local"
  fi
  if [[ ! -f "$PG_DATA/PG_VERSION" ]]; then
    echo "local-db: initialising cluster in $PG_DATA"
    run_pg "$PG_BIN/initdb" -D "$PG_DATA" --encoding=UTF8 --locale=C.UTF-8 --auth-local=trust --auth-host=scram-sha-256 >"$PG_DIR/initdb.log" 2>&1
    cat >>"$PG_DATA/postgresql.conf" <<EOF

# scripts/local-db.sh
listen_addresses = 'localhost'
port = $PG_PORT
unix_socket_directories = '$PG_SOCKET_DIR'
timezone = 'Europe/Rome'
log_timezone = 'Europe/Rome'
max_connections = 100
shared_buffers = 128MB
fsync = off
synchronous_commit = off
full_page_writes = off
EOF
  fi
}

start_cluster() {
  if pg_running; then
    echo "local-db: already running on port $PG_PORT"
  else
    run_pg "$PG_BIN/pg_ctl" -D "$PG_DATA" -l "$PG_LOG" -o "-p $PG_PORT" -w -t 60 start >/dev/null
    echo "local-db: started on port $PG_PORT (log: $PG_LOG)"
  fi
}

ensure_role_and_databases() {
  if ! as_superuser -tAc "SELECT 1 FROM pg_roles WHERE rolname = '$PG_ROLE'" | grep -q 1; then
    as_superuser -c "CREATE ROLE $PG_ROLE LOGIN PASSWORD '$PG_ROLE_PASSWORD'"
    echo "local-db: created role $PG_ROLE"
  fi
  for db in "${PG_DATABASES[@]}"; do
    ensure_database "$db"
  done
}

ensure_database() {
  local db="$1"
  if ! as_superuser -tAc "SELECT 1 FROM pg_database WHERE datname = '$db'" | grep -q 1; then
    as_superuser -c "CREATE DATABASE \"$db\" OWNER $PG_ROLE"
    echo "local-db: created database $db (owner $PG_ROLE)"
  fi
}

cmd_start() {
  require_binaries
  init_cluster
  start_cluster
  ensure_role_and_databases
  cmd_env
}

cmd_stop() {
  require_binaries
  if pg_running; then
    run_pg "$PG_BIN/pg_ctl" -D "$PG_DATA" -m fast -w stop >/dev/null
    echo "local-db: stopped"
  else
    echo "local-db: not running"
  fi
}

cmd_status() {
  require_binaries
  if [[ ! -f "$PG_DATA/PG_VERSION" ]]; then
    echo "local-db: not initialised ($PG_DATA); run 'scripts/local-db.sh start'"
    exit 3
  fi
  if pg_running && pg_ready; then
    echo "local-db: running and accepting connections on localhost:$PG_PORT"
  else
    echo "local-db: not running"
    exit 3
  fi
}

cmd_reset() {
  require_binaries
  cmd_stop
  rm -rf "$PG_DATA"
  echo "local-db: data directory removed"
  cmd_start
}

cmd_psql() {
  require_binaries
  local db="${1:-ls_web}"
  shift || true
  PGPASSWORD="$PG_ROLE_PASSWORD" "$PG_BIN/psql" -h localhost -p "$PG_PORT" -U "$PG_ROLE" -d "$db" "$@"
}

cmd_createdb() {
  require_binaries
  local db="${1:-}"
  if [[ -z "$db" || ! "$db" =~ ^[a-z][a-z0-9_]*$ ]]; then
    echo "local-db: createdb needs a database name such as ls_orders" >&2
    exit 1
  fi
  if ! pg_running; then
    echo "local-db: start the instance first" >&2
    exit 3
  fi
  ensure_database "$db"
}

cmd_env() {
  cat <<EOF
export TEST_DATABASE_URL=jdbc:postgresql://localhost:$PG_PORT/ls_web_test
export TEST_DATABASE_USER=$PG_ROLE
export TEST_DATABASE_PASSWORD=$PG_ROLE_PASSWORD
EOF
}

case "${1:-}" in
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  reset) cmd_reset ;;
  psql) shift; cmd_psql "$@" ;;
  createdb) shift; cmd_createdb "$@" ;;
  env) cmd_env ;;
  *) usage ;;
esac
