# Runbook — ambiente locale

Come far girare l'app sul proprio computer, eseguire i test e provare i flussi con dati
sintetici. Comandi e identificatori restano in inglese; vedi anche `README.md` e `CLAUDE.md`
§Commands.

## Prerequisiti

* JDK 21 (`java -version`) e Node 22 (`node --version`, solo per i test E2E). Maven non serve:
  `./mvnw` lo scarica.
* PostgreSQL 16 in locale, in una di due forme:
  * **binari server** (`initdb`, `pg_ctl`, `psql`): su macOS `brew install postgresql@16` e
    `export LS_PG_BIN=$(brew --prefix postgresql@16)/bin`; su Debian/Ubuntu
    `sudo apt install postgresql-16` (binari in `/usr/lib/postgresql/16/bin`, il percorso
    predefinito). Verifica: `$LS_PG_BIN/initdb --version`.
  * **Docker**: `docker compose up -d db` avvia `postgres:16` su `localhost:54329` con le stesse
    credenziali e gli stessi database (`ls_web`, `ls_web_test`, `ls_web_e2e`); il `compose.yaml`
    alla radice include `deploy/compose.dev.yml`, `scripts/dev.sh` lo usa da solo se non trova i
    binari. Con `docker compose up --build` gira in Docker anche l'app (porta 8080, e-mail in
    `.local/mail/`); i dati di prova, una volta sola, con `docker compose run --rm app dev-seed`;
    `docker compose down` ferma tutto (`-v` cancella anche il database).
* Con un demone Docker disponibile i test possono anche usare Testcontainers (basta non
  impostare `TEST_DATABASE_URL`).

## 1. Database locale

```sh
scripts/local-db.sh start          # istanza PostgreSQL 16 in .local/pg, porta 54329, ruolo ls/ls,
                                   # database ls_web (app) e ls_web_test (test)
eval "$(scripts/local-db.sh env)"  # esporta TEST_DATABASE_URL / _USER / _PASSWORD per i test
scripts/local-db.sh status | stop | reset
scripts/local-db.sh psql           # psql su ls_web
```

Le credenziali `ls`/`ls` valgono solo in locale.

## 2. Build e test

```sh
./mvnw -B -ntp verify              # formato (Spotless), test unitari (*Test), test di integrazione
                                   # (*IT) su PostgreSQL, jar in target/
./mvnw spotless:apply              # riformatta i sorgenti
./mvnw -B -ntp -DskipTests package # solo il jar
```

## 3. Avvio con dati sintetici

Il modo rapido è un solo comando, che fa tutto quanto segue (database, build se serve, dati
di prova, app sulla porta 8080) e stampa le password degli utenti staff a ogni avvio:

```sh
scripts/dev.sh             # PORT=9090 scripts/dev.sh per un'altra porta; --rebuild forza il jar
```

Passo per passo:

```sh
JAR=target/ls-web-0.1.0-SNAPSHOT.jar
DB="--spring.profiles.active=local --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls"
java -jar $JAR dev-seed $DB        # admin.seed / operatore.seed (password stampate una sola volta),
                                   # quattro clienti .example in ogni stato, quattro ordini
SERVER_PORT=8080 java -jar $JAR $DB
```

* Sito pubblico: <http://localhost:8080/>
* Area clienti: <http://localhost:8080/area-clienti/> — inserisci `cliente.attivo@cliente.example`;
  il link di accesso viene scritto in `.local/mail/*.eml` (entro ~10 s): aprilo e premi «Accedi».
* Back office: <http://localhost:8080/gestione/> con `admin.seed` e la password stampata da
  `dev-seed` (`operatore.seed` vede tutto tranne «Impostazioni»).
* Primo ADMIN reale (mai da seed): `java -jar $JAR create-staff-user --username=admin --role=ADMIN
  --name="Nome Cognome" $DB` stampa un link `/gestione/imposta-password?t=…` valido una volta.

Fuori dalla produzione nessuna e-mail esce dalla macchina: il profilo `local` scrive file `.eml`
in `.local/mail/`.

## 4. Test end-to-end (Playwright)

```sh
cd e2e && npm ci && cd ..          # una volta; i browser sono già installati in /opt/pw-browsers
e2e/run.sh                         # database ls_web_e2e ricreato, dev-seed, app sulla porta 8089,
                                   # suite completa (percorso cliente → ufficio → tracking → e-mail,
                                   # smoke di tutte le pagine pubbliche, axe)
e2e/run.sh --project=desktop tests/public.spec.ts   # un sottoinsieme
cd e2e && npx playwright show-report                # rapporto HTML dell'ultima esecuzione
```

Mai eseguire `npx playwright install` in questo ambiente (i browser ci sono già); in CI lo fa il
workflow. Log dell'app: `.local/e2e/app.log`; e-mail della suite: `.local/mail/e2e/`.

## 5. Matrice di parità

```sh
tools/parity-matrix.py             # rigenera la colonna Status di docs/parity-matrix.md dai tag
                                   # (@Tag("CUS-04"), titoli "[CUS-04]") e dagli ultimi rapporti
tools/parity-matrix.py --check     # esce 1 se il file non è aggiornato
```

## Problemi frequenti

* `local-db: … initdb not found` → installa i binari di PostgreSQL 16 o imposta `LS_PG_BIN`.
* I test di integrazione falliscono con «connection refused» → `scripts/local-db.sh start` e
  `eval "$(scripts/local-db.sh env)"` nella stessa shell.
* `dev-seed` rifiuta di partire → è permesso solo con il profilo `local` o `test`.
