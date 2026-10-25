#!/usr/bin/env bash
# One command to try the app locally: local PostgreSQL, build (when needed), synthetic
# data, then the app on http://localhost:8080 with the local profile.
#
#   scripts/dev.sh            start everything (Ctrl-C stops the app; the database keeps running)
#   PORT=9090 scripts/dev.sh  another port
#   scripts/dev.sh --rebuild  force a new jar
#
# Every e-mail the app would send (sign-in links included) is written to .local/mail/*.eml.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
PORT="${PORT:-8080}"

echo "▸ Database locale (PostgreSQL 16 in .local/pg, porta 54329)"
scripts/local-db.sh start >/dev/null
eval "$(scripts/local-db.sh env)"

JAR="$(ls target/ls-web-*.jar 2>/dev/null | head -1 || true)"
if [[ "${1:-}" == "--rebuild" || -z "$JAR" || -n "$(find src pom.xml -newer "$JAR" -print -quit 2>/dev/null)" ]]; then
  echo "▸ Build del jar (senza test, 1-2 minuti la prima volta)"
  ./mvnw -B -ntp -q -DskipTests package
  JAR="$(ls target/ls-web-*.jar | head -1)"
fi

DB=(--spring.profiles.active=local
    --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web
    --DATABASE_USER=ls --DATABASE_PASSWORD=ls)

echo "▸ Dati di prova (le password degli utenti staff cambiano a ogni avvio)"
java -Dstdout.encoding=UTF-8 -jar "$JAR" dev-seed "${DB[@]}" --logging.level.root=warn --logging.level.it.logisticasanchez=warn --spring.main.banner-mode=off

cat <<EOF

▸ Avvio su http://localhost:$PORT  (Ctrl-C per fermare)
   Sito pubblico   http://localhost:$PORT/
   Area clienti    http://localhost:$PORT/area-clienti/   (e-mail: cliente.attivo@cliente.example)
   Back office     http://localhost:$PORT/gestione/       (utente: admin.seed, password qui sopra)
   E-mail inviate  .local/mail/*.eml  (il link di accesso all'area clienti è lì)

EOF
exec env SERVER_PORT="$PORT" java -Dstdout.encoding=UTF-8 -jar "$JAR" "${DB[@]}"
