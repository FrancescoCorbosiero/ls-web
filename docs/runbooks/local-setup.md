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
    credenziali e gli stessi database (`ls_web`, `ls_web_test`, `ls_web_e2e`); `scripts/dev.sh` lo usa da solo se non trova i binari. Con `docker compose up --build` gira in Docker anche l'app (porta 8080, e-mail in
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

### Con i dati del vecchio sistema (solo sul tuo computer)

Per vedere la piattaforma con i clienti e gli ordini reali e confrontarla con il vecchio sistema
serve il dump di `lsdb` (lo stesso dell'importazione una tantum, ADR 0048), copiato in
`.local/legacy/` (ignorata da git).

**Con Docker**, al posto di `docker compose run --rm app dev-seed`:

```sh
docker compose down -v                     # solo se vuoi un database vuoto (cancella anche i dati sintetici)
docker compose run --rm --build legacy     # genera il file dal dump più recente di .local/legacy/, lo carica, lo cancella
docker compose up --build                  # l'app su http://localhost:8080
```

* Stampa la password di `admin.seed` / `operatore.seed` e una password comune per i clienti
  importati (solo in locale). Il database resta tra un avvio e l'altro; per nuove password:
  `docker compose run --rm app dev-seed --staff-only --customers-password --logging.level.root=warn --logging.level.it.logisticasanchez=warn --spring.main.banner-mode=off`.
* Un secondo caricamento sullo stesso database viene rifiutato: per ricaricare da capo,
  `docker compose down -v` e di nuovo `run --rm --build legacy`. Un dump diverso dal più recente:
  `LEGACY_DUMP=<nome del file> docker compose run --rm --build legacy`.
* Se l'app è già accesa (`docker compose up` in un altro terminale) basta il comando `run`: i dati
  compaiono subito.
* Un altro amministratore oltre ad `admin.seed`: «Impostazioni → Utenti della gestione» oppure
  `docker compose run --rm app create-staff-user --username=<nome> --role=ADMIN --name="Nome Cognome"`
  (il link per la password è stampato e arriva in `.local/mail/`).
* Non serve altro sul computer: il file si genera in un container `python:3.13-alpine`.

**Senza Docker per l'app** (database locale o Docker, app con `scripts/dev.sh`):

```sh
scripts/dev.sh --legacy .local/legacy/lsdb_full_<data>.sql.gz   # ricrea ls_web_legacy da zero dal dump
scripts/dev.sh --legacy                                         # riparte su ls_web_legacy com'era, modifiche incluse
```

* Usa un database a parte, `ls_web_legacy`: `ls_web` con i dati sintetici resta com'è e
  `scripts/dev.sh` senza opzioni continua a usarlo.
* Back office: `admin.seed` (o `operatore.seed`) con la password stampata a ogni avvio. Per
  un altro amministratore: «Impostazioni → Utenti della gestione» (il link per la password
  arriva in `.local/mail/`) oppure `create-staff-user` con
  `--DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web_legacy`.
* Area clienti: l'e-mail di un cliente importato (la trovi in «Gestione → Clienti») con la
  password comune stampata a ogni avvio (solo in locale; in produzione i clienti entrano con il
  link e poi scelgono la loro password).
* Nessuna e-mail esce: finiscono in `.local/mail/*.eml`. Il dump va tenuto in `.local/legacy/`
  (ignorata da git); il file SQL intermedio viene cancellato appena caricato. Servono `python3` e,
  con Docker, il servizio `db` di `compose.yaml`.

Passo per passo:

```sh
JAR=target/ls-web-0.1.0-SNAPSHOT.jar
DB="--spring.profiles.active=local --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls"
java -jar $JAR dev-seed $DB        # admin.seed / operatore.seed (password stampate una sola volta),
                                   # quattro clienti .example in ogni stato, cinque ordini (uno per stato)
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
