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
   firewall: 22 e, da Cloudflare soltanto, 80 e 443.
2. `git clone https://github.com/FrancescoCorbosiero/ls-web.git /srv/logistica-sanchez` (repository
   pubblico). Tutti i comandi `docker compose` si lanciano da `/srv/logistica-sanchez/deploy`: Compose
   legge da solo il file `.env` di quella cartella.
3. `cp deploy/.env.example deploy/.env && chmod 600 deploy/.env` e compila le voci `(required)`:
   `POSTGRES_PASSWORD`, `LS_SECURITY_IP_HASH_KEY`, `LS_TOKEN_ENCRYPTION_KEY` (ognuna con
   `openssl rand -base64 32`, generate sul server) e le credenziali SES
   (`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`). Il resto ha già il valore giusto. Regole del file:
   i commenti solo su righe proprie (con `CHIAVE=   # testo` il commento diventerebbe il valore) e i
   valori con spazi tra virgolette doppie; una variabile facoltativa che non usi resta commentata,
   mai vuota. Tutte le righe del file arrivano all'app (`env_file` in `compose.yml`).
   In staging: `SPRING_PROFILES_ACTIVE=staging`, `LS_NOTIFICATIONS_SINK=directory` oppure `ses`
   **con** `LS_NOTIFICATIONS_ALLOW_LIST` (senza allow-list nessuna e-mail parte).
4. Cloudflare (il firewall del VPS lascia entrare solo Cloudflare):
   * record A/AAAA di `logisticasanchez.com`, `www`, `app`, `ordini`, `dashboard` verso il VPS,
     **proxati** (nuvola arancione);
   * SSL/TLS → modalità **Full** (Caddy cifra il tratto Cloudflare → server con un proprio
     certificato interno: nessun certificato da copiare o rinnovare);
   * i record di SES (DKIM, SPF, DMARC, MAIL FROM) copiati così come sono, **solo DNS** (nuvola
     grigia).
5. L'immagine `ghcr.io/francescocorbosiero/ls-web` è pubblica: nessun `docker login`.
   Reverse proxy: sul VPS di produzione le porte 80/443 sono di un **caddy-docker-proxy** condiviso
   (container `caddy`, rete `caddy`); `compose.yml` avvia solo `app` e `db`, collega l'app a quella
   rete e le dà le etichette `caddy.*` (i cinque domini, certificato interno verso Cloudflare, IP del
   visitatore da `CF-Connecting-IP`): nulla da modificare nel proxy condiviso. Su un server senza
   proxy: `docker network create caddy && docker compose --profile own-proxy up -d` usa il Caddy del
   progetto (`deploy/caddy/Caddyfile`).
6. `docker compose config --quiet` (deve stare zitto), poi
   `docker compose up -d`. Flyway crea lo schema al primo avvio; il database
   parte **vuoto**; i dati del vecchio sistema si caricano una volta sola al cut-over (§6, Q-55
   riaperta il 2026-10-28, ADR 0048).
7. Primo amministratore, con nome utente e password scelti da te (la password si digita due volte,
   non compare a schermo; almeno 12 caratteri): `docker compose run --rm app create-staff-user
   --username=<nome> --role=ADMIN --name="Nome Cognome" --ask-password`. Senza `--ask-password` il
   comando stampa invece un link `https://<dominio>/gestione/imposta-password?t=…` valido 24 ore.
   Con la verifica in due passaggi attiva (`security.adminTotp`, ADR 0039), un amministratore che
   ha perso il telefono e non riesce a entrare: `docker compose run --rm app
   reset-staff-totp --username=<nome>`; al prossimo accesso configura di nuovo l'app.
8. Verifica: `curl -fsS https://<dominio>/actuator/health` → `{"status":"UP"}`; home page, un
   accesso cliente con un indirizzo di prova (in staging l'e-mail finisce nell'allow-list o nei
   file del sink `directory`), login al back office.

## 2. Aggiornamento

CI pubblica l'immagine a ogni push su `main` e `phase-*` con i tag `sha-<commit>` e `<branch>`
(`latest` solo da `main`). Sul server:

```sh
cd /srv/logistica-sanchez/deploy
sed -i 's/^LS_IMAGE_TAG=.*/LS_IMAGE_TAG=sha-<commit>/' .env
docker compose pull app
docker compose up -d app       # riavvio della sola app; Flyway applica le migrazioni
docker compose logs -f app     # fino a "Started LsWebApplication"
```

Prima di un aggiornamento con nuove migrazioni esegui `deploy/backup.sh` (§4).

## 3. Rollback

Le migrazioni sono solo in avanti: il rollback dell'app è possibile finché la versione
precedente conosce lo schema corrente (migrazioni additive: sempre in M1).

```sh
sed -i 's/^LS_IMAGE_TAG=.*/LS_IMAGE_TAG=sha-<commit precedente>/' .env
docker compose up -d app
```

Se lo schema non è compatibile: fermare l'app (`docker compose stop app`), ripristinare l'ultimo
backup (§4), riavviare con il tag precedente. Mai `docker compose down -v`: cancella il volume
del database.

## 4. Backup e restore (NFR-05)

* Senza configurare nulla: `deploy/backup.sh` salva il database in
  `/var/backups/ls-web/daily/ls_web-<data>.sql.gz`, leggibile solo dal proprietario del file.
* Facoltativo, più avanti: una copia fuori dal server. Serve una coppia di chiavi `age`
  (`age-keygen` sul tuo computer; sul server solo la pubblica, `LS_BACKUP_AGE_RECIPIENT=age1…`) e
  `rclone` verso uno storage esterno (`LS_BACKUP_RCLONE_REMOTE=offsite:ls-web-backups`): lo script
  cifra il file e lo copia; senza chiave non copia nulla fuori.
* Pianificazione (cron dell'utente che gestisce Docker):
  ```
  15 3 * * *  /srv/logistica-sanchez/deploy/backup.sh >> /var/log/ls-web-backup.log 2>&1
  45 8 * * *  /srv/logistica-sanchez/deploy/backup.sh --check || echo "backup ls-web in ritardo" | mail -s "ls-web backup" <ufficio>
  ```
  Rotazione: 7 giornalieri, 4 settimanali (domenica), 12 mensili (giorno 1); il controllo
  fallisce se l'ultimo backup ha più di 26 ore.
* Restore (prova almeno una volta in staging e registra la data in `docs/rehearsals/`):
  ```sh
  deploy/backup.sh --restore /var/backups/ls-web/daily/ls_web-<data>.sql.gz
  ```
  (un file cifrato `….sql.gz.age` chiede anche `LS_BACKUP_AGE_IDENTITY=/path/chiave-privata.txt`).
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

## 6. Cut-over dal vecchio sistema (schema; dettagli al Gate 1)

I clienti e gli ordini degli ultimi sei mesi del vecchio sistema si caricano **una volta sola** con un
file SQL generato dal dump (Q-55 riaperta il 2026-10-28, ADR 0048, `tools/legacy-import/README.md`).
Ordine delle operazioni, tutte reversibili finché il vecchio VPS resta acceso:

1. Staging verde con la stessa immagine e `docker compose config` pulito; prova di restore fatta.
2. Produzione installata (§1) su un dominio tecnico; smoke test con l'allow-list e-mail.
3. Abbassare il TTL dei record DNS a 300 s almeno un giorno prima.
4. Finestra: nel vecchio nginx bloccare i percorsi di scrittura (chapter 10 §7), poi il dump del
   vecchio `lsdb` e l'importazione sul nuovo server, da `/srv/logistica-sanchez`:
   ```sh
   mkdir -p .local/legacy && chmod 700 .local/legacy
   # copia qui il dump (….sql.gz) ed exclude.txt: un'e-mail per riga, i clienti da non importare
   python3 -I tools/legacy-import/generate_import_sql.py .local/legacy/<dump>.sql.gz \
       .local/legacy/import_legacy.sql --exclude-emails .local/legacy/exclude.txt
   cd deploy && ./backup.sh
   docker compose exec -T db psql -U ls -d ls_web -v ON_ERROR_STOP=1 < ../.local/legacy/import_legacy.sql
   rm -f ../.local/legacy/*
   ```
   Ordini degli ultimi sei mesi a partire dal giorno del comando (`--months N` per cambiarli); il
   primo amministratore deve già esistere (§1 punto 7); il file è un'unica transazione e un secondo
   caricamento viene rifiutato. Poi spostare i record A/AAAA di apex, `www`, `app`, `ordini`,
   `dashboard` sul nuovo VPS (Cloudflare, §1 punto 4). Verificare `/`, `/area-clienti/`,
   `/gestione/`, `POST /mono/api/order/trace/state`, un vecchio link `/mono/email-confirm?…` →
   pagina 410.
5. Rimuovere i record DNS pendenti (`api.` e altri non più usati), poi ruotare le vecchie
   credenziali (chiave SES del vecchio backend, `JWT_KEY`, password staff; §5).
6. Rollback: riportare i record DNS al vecchio VPS (TTL 300 s) e riaprire i percorsi di
   scrittura; gli ordini inseriti nel nuovo sistema dopo lo switch vanno ripresi a mano nel vecchio
   (l'importazione va solo dal vecchio al nuovo).
