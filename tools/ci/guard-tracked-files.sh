#!/usr/bin/env bash
# Fails when git tracks a file that must never be committed (PROMPT.md, CLAUDE.md, .gitignore):
#   - anything under legacy/ (the read-only checkouts) or logs/
#   - *.sql outside **/db/migration/ and **/src/test/resources/**
#   - .env and .env.* except .env.example
#   - *.xlsx / *.xlsm except *-synthetic.xlsx under src/test/resources
#   - *.dump, *.sql.gz, *.bak, *.log
# Run locally from the repository root: tools/ci/guard-tracked-files.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

violations=()

while IFS= read -r -d '' path; do
  case "$path" in
    legacy/*|*/legacy/*) violations+=("$path: legacy checkout") ;;
    logs/*|*/logs/*) violations+=("$path: log directory") ;;
    *.sql.gz|*.dump|*.bak|*.log) violations+=("$path: dump, backup or log file") ;;
    *.sql)
      case "$path" in
        */db/migration/*|db/migration/*|*/src/test/resources/*|src/test/resources/*) ;;
        *) violations+=("$path: SQL file outside db/migration and src/test/resources") ;;
      esac ;;
    .env|.env.*|*/.env|*/.env.*)
      case "$path" in
        .env.example|*/.env.example) ;;
        *) violations+=("$path: environment file") ;;
      esac ;;
    *.xlsx|*.xlsm)
      case "$path" in
        */src/test/resources/*-synthetic.xlsx|src/test/resources/*-synthetic.xlsx) ;;
        *) violations+=("$path: spreadsheet (only *-synthetic.xlsx under src/test/resources is allowed)") ;;
      esac ;;
  esac
done < <(git ls-files -z)

if ((${#violations[@]} > 0)); then
  echo "guard-tracked-files: ${#violations[@]} forbidden tracked file(s):" >&2
  printf '  - %s\n' "${violations[@]}" >&2
  exit 1
fi

echo "guard-tracked-files: ok ($(git ls-files | wc -l | tr -d ' ') tracked files checked)"
