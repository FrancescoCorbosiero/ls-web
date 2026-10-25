# Runbook — deploy, aggiornamento, rollback, backup e restore, rotazione chiavi, cut-over

Ambiente di produzione (e staging) su **un VPS nuovo** con Docker Compose: `proxy` (Caddy 2,
HTTPS automatico), `app` (immagine `ghcr.io/francescocorbosiero/ls-web`), `db` (PostgreSQL 16
con volume). File: `Dockerfile`, `deploy/compose.yml`, `deploy/caddy/Caddyfile`,
`deploy/.env.example`, `deploy/backup.sh`. Decisioni: ADR 0001, ADR 0022.

**Stato (2026-10-25):** procedura scritta e verificata in locale (`docker compose config`,
immagine costruita da CI); **nessuna prova su un server reale** finché il proprietario non
fornisce VPS, DNS e credenziali SES (NFR-05/06/07 restano «ready-for-owner»; la prova va
registrata con data ed esito in `docs/rehearsals/`).

## 1. Prima installazione (staging o produzione)

1. VPS Ubuntu LTS con Docker e il plugin Compose; utente non root con accesso a Docker;
   firewall: solo 22, 80, 443.
2. `git clone` del repository (o copia della sola cartella `deploy/`) in `/opt/ls-web`.
3. `cp deploy/.env.example deploy/.env && chmod 600 deploy/.env` e compila **tutte** le voci
   `(required)`: `LS_ACME_EMAIL`, `POSTGRES_PASSWORD`, `LS_SECURITY_IP_HASH_KEY`,
   `LS_TOKEN_ENCRYPTION_KEY` (`openssl rand -base64 32`), le credenziali SES
   (`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` di un utente IAM limitato a
   `ses:SendEmail`/`ses:SendRawEmail`), `LS_IMAGE_TAG` (un tag `sha-…` preciso, non `latest`).
   In staging: `SPRING_PROFILES_ACTIVE=staging`, `LS_NOTIFICATIONS_SINK=directory` oppure `ses`
   **con** `LS_NOTIFICATIONS_ALLOW_LIST` (senza allow-list nessuna e-mail parte).
4. DNS: record A/AAAA di `logisticasanchez.com`, `www.`, `app.`, `ordini.`, `dashboard.` verso il
   VPS (in staging usa un dominio di prova e `LS_DOMAIN`). Caddy ottiene i certificati da solo.
5. `cd deploy && docker login ghcr.io` (token GitHub con `read:packages` se il pacchetto è privato).
6. `docker compose --env-file .env config --quiet` (deve stare zitto), poi
   `docker compose --env-file .env up -d`. Flyway crea lo schema al primo avvio; il database
   parte **vuoto** (decisione Q-55: nessuna migrazione dal vecchio sistema).
7. Primo amministratore: `docker compose --env-file .env run --rm app create-staff-user
   --username=<nome> --role=ADMIN --name="Nome Cognome"` stampa un link
   `https://<dominio>/gestione/imposta-password?t=…` valido una volta: aprilo e scegli la password.
8. Verifica: `curl -fsS https://<dominio>/actuator/health` → `{"status":"UP"}`; home page, un
   accesso cliente con un indirizzo di prova (in staging l'e-mail finisce nell'allow-list o nei
   file del sink `directory`), login al back office.

## 2. Aggiornamento

CI pubblica l'immagine a ogni push su `main` e `phase-*` con i tag `sha-<commit>` e `<branch>`
(`latest` solo da `main`). Sul server:

```sh
cd /opt/ls-web/deploy
sed -i 's/^LS_IMAGE_TAG=.*/LS_IMAGE_TAG=sha-<commit>/' .env
docker compose --env-file .env pull app
docker compose --env-file .env up -d app       # riavvio della sola app; Flyway applica le migrazioni
docker compose --env-file .env logs -f app     # fino a "Started LsWebApplication"
```

Prima di un aggiornamento con nuove migrazioni esegui `deploy/backup.sh` (§4).

## 3. Rollback

Le migrazioni sono solo in avanti: il rollback dell'app è possibile finché la versione
precedente conosce lo schema corrente (migrazioni additive: sempre in M1).

```sh
sed -i 's/^LS_IMAGE_TAG=.*/LS_IMAGE_TAG=sha-<commit precedente>/' .env
docker compose --env-file .env up -d app
```

Se lo schema non è compatibile: fermare l'app (`docker compose stop app`), ripristinare l'ultimo
backup (§4), riavviare con il tag precedente. Mai `docker compose down -v`: cancella il volume
del database.

## 4. Backup e restore (NFR-05)

* Chiavi: genera una coppia `age` sul computer del proprietario (`age-keygen`), copia **solo la
  chiave pubblica** nel server (`LS_BACKUP_AGE_RECIPIENT=age1…` in `deploy/.env`); la chiave
  privata resta fuori dal server (gestore di password).
* Copia off-site: configura `rclone` verso uno storage esterno e imposta
  `LS_BACKUP_RCLONE_REMOTE=offsite:ls-web-backups`.
* Pianificazione (cron dell'utente che gestisce Docker):
  ```
  15 3 * * *  /opt/ls-web/deploy/backup.sh >> /var/log/ls-web-backup.log 2>&1
  45 8 * * *  /opt/ls-web/deploy/backup.sh --check || echo "backup ls-web in ritardo" | mail -s "ls-web backup" <ufficio>
  ```
  Rotazione: 7 giornalieri, 4 settimanali (domenica), 12 mensili (giorno 1); il controllo
  fallisce se l'ultimo backup ha più di 26 ore.
* Restore (prova almeno una volta in staging e registra la data in `docs/rehearsals/`):
  ```sh
  LS_BACKUP_AGE_IDENTITY=/path/chiave-privata.txt deploy/backup.sh --restore /var/backups/ls-web/daily/ls_web-<data>.sql.gz.age
  ```
  Il comando ferma l'app, carica il dump nel database del progetto e riavvia l'app. Su un
  database non vuoto ricreare prima il volume (`docker compose stop app db`, `docker volume rm
  ls-web_pg_data`, `docker compose up -d db`).

## 5. Rotazione delle chiavi (Q-53)

| Segreto | Come ruotare | Effetto |
|---|---|---|
| `POSTGRES_PASSWORD` | `ALTER ROLE ls PASSWORD '…'` via `docker compose exec db psql -U ls`, poi aggiorna `.env` e `up -d app` | nessuno per gli utenti |
| Credenziali SES | nuovo utente/chiave IAM, aggiorna `.env`, `up -d app`, revoca la vecchia | nessuno |
| `LS_TOKEN_ENCRYPTION_KEY` | nuova chiave in `.env`, `up -d app` | i link ancora in coda nell'outbox non sono più decifrabili: le righe `QUEUED` falliscono e il cliente richiede il link |
| `LS_SECURITY_IP_HASH_KEY` | nuova chiave, `up -d app` | gli hash IP precedenti non sono più confrontabili (solo rate limit e retention) |
| Password staff | ADMIN crea un nuovo utente da «Impostazioni → Utenti» (link imposta-password) e disattiva il vecchio | sessioni del vecchio utente chiuse |
| Token GitHub per GHCR | nuovo token con `read:packages`, `docker login ghcr.io` | nessuno |

## 6. Cut-over dal vecchio sistema (schema; dettagli al Gate 1)

Database **vuoto** al via (Q-55): i clienti si registrano di nuovo dall'area clienti. Ordine
delle operazioni, tutte reversibili finché il vecchio VPS resta acceso:

1. Staging verde con la stessa immagine e `docker compose config` pulito; prova di restore fatta.
2. Produzione installata (§1) su un dominio tecnico; smoke test con l'allow-list e-mail.
3. Abbassare il TTL dei record DNS a 300 s almeno un giorno prima.
4. Finestra: nel vecchio nginx bloccare i percorsi di scrittura (chapter 10 §7); spostare i
   record A/AAAA di apex, `www`, `app`, `ordini`, `dashboard` sul nuovo VPS; Caddy emette i
   certificati entro pochi minuti; verificare `/`, `/area-clienti/`, `/gestione/`,
   `POST /mono/api/order/trace/state`, un vecchio link `/mono/email-confirm?…` → pagina 410.
5. Rimuovere i record DNS pendenti (`api.` e altri non più usati), poi ruotare le vecchie
   credenziali (chiave SES del vecchio backend, `JWT_KEY`, password staff; §5).
6. Rollback: riportare i record DNS al vecchio VPS (TTL 300 s) e riaprire i percorsi di
   scrittura; nulla è stato migrato, quindi nessun dato da riconciliare.
