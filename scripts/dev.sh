#!/usr/bin/env bash
# One command to try the app locally: local PostgreSQL, build (when needed), data, then the app
# on http://localhost:8080 with the local profile.
#
#   scripts/dev.sh                   synthetic data in ls_web (Ctrl-C stops the app; the database keeps running)
#   scripts/dev.sh --legacy <dump>   the legacy dump (lsdb_*.sql.gz) in its own database ls_web_legacy, rebuilt
#                                    from scratch: the customers and the orders of ADR 0048, plus the seed staff
#   scripts/dev.sh --legacy          the database ls_web_legacy as it was left (changes kept), new passwords
#   PORT=9090 scripts/dev.sh         another port
#   scripts/dev.sh --rebuild         force a new jar
#
# Every e-mail the app would send (sign-in links included) is written to .local/mail/*.eml: with
# the legacy data nothing reaches a real address. The dump and its import file hold real personal
# data: they stay under .local/ (gitignored) and the import file is deleted once loaded.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
PORT="${PORT:-8080}"

REBUILD=0
LEGACY=0
DUMP=""
while (($#)); do
  case "$1" in
    --rebuild) REBUILD=1 ;;
    --legacy)
      LEGACY=1
      if [[ -n "${2:-}" && "$2" != --* ]]; then
        DUMP="$2"
        shift
      fi
      ;;
    *)
      sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//' >&2
      exit 1
      ;;
  esac
  shift
done

PG_BIN="${LS_PG_BIN:-/usr/lib/postgresql/16/bin}"
if [[ -x "$PG_BIN/initdb" ]]; then
  echo "▸ Database locale (PostgreSQL 16 in .local/pg, porta 54329)"
  scripts/local-db.sh start >/dev/null
  eval "$(scripts/local-db.sh env)"
  PG_MODE=local
elif command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  echo "▸ Database locale in Docker (postgres:16, porta 54329; compose.yaml)"
  docker compose up -d --wait db >/dev/null
  export TEST_DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web_test TEST_DATABASE_USER=ls TEST_DATABASE_PASSWORD=ls
  PG_MODE=docker
else
  cat >&2 <<'EOF'
dev.sh: serve PostgreSQL 16 in una di queste due forme:
  - i binari server (initdb, pg_ctl, psql): macOS  brew install postgresql@16  e  export LS_PG_BIN=$(brew --prefix postgresql@16)/bin
                                             Debian/Ubuntu  sudo apt install postgresql-16  (binari in /usr/lib/postgresql/16/bin)
  - oppure Docker in esecuzione: il database parte da compose.yaml
EOF
  exit 1
fi

# psql on database $1 as the role ls; the other arguments and stdin go to psql.
sql() {
  local db="$1"
  shift
  if [[ "$PG_MODE" == local ]]; then
    scripts/local-db.sh psql "$db" -v ON_ERROR_STOP=1 -q "$@"
  else
    docker compose exec -T db psql -U ls -d "$db" -v ON_ERROR_STOP=1 -q "$@"
  fi
}

JAR="$(ls target/ls-web-*.jar 2>/dev/null | head -1 || true)"
if [[ "$REBUILD" == 1 || -z "$JAR" || -n "$(find src pom.xml -newer "$JAR" -print -quit 2>/dev/null)" ]]; then
  echo "▸ Build del jar (senza test, 1-2 minuti la prima volta)"
  ./mvnw -B -ntp -q -DskipTests package
  JAR="$(ls target/ls-web-*.jar | head -1)"
  touch "$JAR" # reproducible builds date the jar 1980: without this every start would rebuild
fi

DATABASE=ls_web
[[ "$LEGACY" == 1 ]] && DATABASE=ls_web_legacy
DB=(--spring.profiles.active=local
    --DATABASE_URL=jdbc:postgresql://localhost:54329/$DATABASE
    --DATABASE_USER=ls --DATABASE_PASSWORD=ls)
QUIET=(--logging.level.root=warn --logging.level.it.logisticasanchez=warn --spring.main.banner-mode=off)
seed() {
  java -Dstdout.encoding=UTF-8 -jar "$JAR" dev-seed "${DB[@]}" "${QUIET[@]}" "$@"
}

if [[ "$LEGACY" == 0 ]]; then
  echo "▸ Dati di prova (le password degli utenti staff cambiano a ogni avvio)"
  seed
  CUSTOMER_HINT="(e-mail: cliente.attivo@cliente.example)"
elif [[ -n "$DUMP" ]]; then
  if [[ ! -f "$DUMP" ]]; then
    echo "dev.sh: dump non trovato: $DUMP" >&2
    exit 1
  fi
  command -v python3 >/dev/null || { echo "dev.sh: serve python3 per leggere il dump" >&2; exit 1; }
  IMPORT=.local/legacy/dev-import.sql
  mkdir -p .local/legacy
  trap 'rm -f "$IMPORT"' EXIT
  echo "▸ File di importazione dal dump (ADR 0048; regole del cap. 12b §5.5)"
  python3 -I tools/legacy-import/generate_import_sql.py "$DUMP" "$IMPORT" | sed 's/^/   /'
  echo "▸ Database ls_web_legacy ricreato da zero (ls_web con i dati sintetici resta com'è)"
  sql ls_web -c "DROP DATABASE IF EXISTS ls_web_legacy WITH (FORCE)"
  if [[ "$PG_MODE" == local ]]; then
    scripts/local-db.sh createdb ls_web_legacy >/dev/null
  else
    sql ls_web -c "CREATE DATABASE ls_web_legacy"
  fi
  echo "▸ Schema (migrazioni) e utenti staff di prova"
  seed --staff-only
  echo "▸ Caricamento dei dati legacy"
  sql ls_web_legacy < "$IMPORT"
  rm -f "$IMPORT"
  echo "▸ Una password comune per i clienti importati (solo in locale)"
  seed --customers-password
  CUSTOMER_HINT="(l'e-mail di un cliente, la vedi in Gestione → Clienti, + la password qui sopra)"
else
  if [[ "$(sql ls_web -Atc "SELECT count(*) FROM pg_database WHERE datname = 'ls_web_legacy'")" != 1 ]]; then
    echo "dev.sh: ls_web_legacy non esiste ancora: la prima volta lancia  scripts/dev.sh --legacy <dump.sql.gz>" >&2
    exit 1
  fi
  echo "▸ Database ls_web_legacy come l'hai lasciato; nuove password staff e clienti"
  seed --staff-only --customers-password
  CUSTOMER_HINT="(l'e-mail di un cliente, la vedi in Gestione → Clienti, + la password qui sopra)"
fi

cat <<EOF

▸ Avvio su http://localhost:$PORT  (Ctrl-C per fermare)
   Sito pubblico   http://localhost:$PORT/
   Area clienti    http://localhost:$PORT/area-clienti/   $CUSTOMER_HINT
   Back office     http://localhost:$PORT/gestione/       (utente: admin.seed, password qui sopra)
   E-mail inviate  .local/mail/*.eml  (il link di accesso all'area clienti è lì)

EOF
exec env SERVER_PORT="$PORT" java -Dstdout.encoding=UTF-8 -jar "$JAR" "${DB[@]}"
