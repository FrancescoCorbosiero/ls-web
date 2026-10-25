#!/usr/bin/env bash
# Encrypted database backup of the ls-web Compose project (NFR-05; runbook: docs/runbooks/deploy.md).
#
#   deploy/backup.sh                 dump ls_web, encrypt it, keep the last 7 daily, 4 weekly, 12 monthly
#   deploy/backup.sh --check         fail when the newest backup is older than LS_BACKUP_MAX_AGE_HOURS (default 26)
#   deploy/backup.sh --restore FILE  restore an encrypted dump into the (empty) database of the project
#
# Encryption: age (https://age-encryption.org) with the public key in LS_BACKUP_AGE_RECIPIENT; the
# private key stays off the server. Off-site copy: when LS_BACKUP_RCLONE_REMOTE is set (e.g.
# "offsite:ls-web-backups") the encrypted file is copied there with rclone. The script reads
# deploy/.env for POSTGRES_* and the LS_BACKUP_* variables; nothing is printed except file names.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${LS_ENV_FILE:-$DEPLOY_DIR/.env}"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi
BACKUP_DIR="${LS_BACKUP_DIR:-/var/backups/ls-web}"
RECIPIENT="${LS_BACKUP_AGE_RECIPIENT:-}"
REMOTE="${LS_BACKUP_RCLONE_REMOTE:-}"
MAX_AGE_HOURS="${LS_BACKUP_MAX_AGE_HOURS:-26}"
DB="${POSTGRES_DB:-ls_web}"
DB_USER="${POSTGRES_USER:-ls}"
compose() { docker compose --env-file "$ENV_FILE" -f "$DEPLOY_DIR/compose.yml" "$@"; }

do_backup() {
  [[ -n "$RECIPIENT" ]] || { echo "backup: LS_BACKUP_AGE_RECIPIENT (age public key) is not set" >&2; exit 1; }
  command -v age >/dev/null || { echo "backup: age is not installed (apt install age)" >&2; exit 1; }
  mkdir -p "$BACKUP_DIR"/{daily,weekly,monthly}
  local stamp file
  stamp="$(date +%Y%m%d-%H%M%S)"
  file="$BACKUP_DIR/daily/ls_web-$stamp.sql.gz.age"
  compose exec -T db pg_dump -U "$DB_USER" -d "$DB" --no-owner --no-privileges \
    | gzip -9 \
    | age -r "$RECIPIENT" -o "$file"
  echo "$file"
  # Rotation 7 / 4 / 12: the Sunday dump is also the weekly one, the first of the month the monthly one.
  if [[ "$(date +%u)" == "7" ]]; then cp "$file" "$BACKUP_DIR/weekly/"; fi
  if [[ "$(date +%d)" == "01" ]]; then cp "$file" "$BACKUP_DIR/monthly/"; fi
  ls -1t "$BACKUP_DIR"/daily/*.age 2>/dev/null | tail -n +8 | xargs -r rm -f
  ls -1t "$BACKUP_DIR"/weekly/*.age 2>/dev/null | tail -n +5 | xargs -r rm -f
  ls -1t "$BACKUP_DIR"/monthly/*.age 2>/dev/null | tail -n +13 | xargs -r rm -f
  if [[ -n "$REMOTE" ]]; then
    command -v rclone >/dev/null || { echo "backup: rclone is not installed" >&2; exit 1; }
    rclone copy "$file" "$REMOTE/daily/"
  fi
}

do_check() {
  local newest age_hours
  newest="$(ls -1t "$BACKUP_DIR"/daily/*.age 2>/dev/null | head -n 1 || true)"
  [[ -n "$newest" ]] || { echo "backup check: no backup found in $BACKUP_DIR/daily" >&2; exit 2; }
  age_hours=$(( ( $(date +%s) - $(stat -c %Y "$newest") ) / 3600 ))
  if (( age_hours > MAX_AGE_HOURS )); then
    echo "backup check: newest backup is $age_hours h old (limit $MAX_AGE_HOURS h): $newest" >&2
    exit 2
  fi
  echo "backup check: ok ($newest, $age_hours h old)"
}

do_restore() {
  local file="$1"
  [[ -f "$file" ]] || { echo "restore: $file not found" >&2; exit 1; }
  [[ -n "${LS_BACKUP_AGE_IDENTITY:-}" ]] || { echo "restore: LS_BACKUP_AGE_IDENTITY (path of the age private key) is not set" >&2; exit 1; }
  echo "restore: stopping the app, restoring $file into $DB"
  compose stop app
  age -d -i "$LS_BACKUP_AGE_IDENTITY" "$file" | gunzip | compose exec -T db psql -U "$DB_USER" -d "$DB" -v ON_ERROR_STOP=1 -q
  compose start app
  echo "restore: done; check https://\${LS_DOMAIN}/actuator/health and a sign-in"
}

case "${1:-}" in
  "") do_backup ;;
  --check) do_check ;;
  --restore) shift; do_restore "${1:?file}" ;;
  *) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
