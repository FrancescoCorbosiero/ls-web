# Come usare questo pacchetto

Questo pacchetto contiene tutto ciò che serve a Claude Code per costruire la nuova web
app di Logistica Sanchez: il prompt da incollare (`PROMPT.md`), le istruzioni permanenti
per il repository (`CLAUDE.md`), il `.gitignore` e il brief tecnico (`docs/brief/`).

Le vecchie codebase servono da riferimento, non da vincolo: dove sono ambigue, incomplete
o sbagliate, la nuova app sceglie da sé la soluzione migliore e la registra (ADR). Nessuna
informazione mancante blocca il lavoro: le domande del capitolo 11 hanno tutte una
risposta predefinita.

## Prima di tutto: i rischi urgenti nel sistema attuale

Riguardano il sistema in produzione **oggi**, non la nuova app (capitolo 11, §0). Vengono
dalla lettura del codice del backend (`ls`, branch `migration`).

### 1. Dati di ordini e clienti leggibili senza login (Q-21) — confermato dal codice

Senza alcun token:

* `POST /mono/api/order/pending` restituisce tutti gli ordini in attesa degli ultimi 6
  mesi, con e-mail del mittente, nome, indirizzo e telefono del destinatario, DDT e note;
* `POST /mono/api/order/active` e `/mono/api/order/delivered` restituiscono tutti gli
  ordini approvati degli ultimi 6 mesi;
* `GET /mono/download-report?email=…` e `GET /mono/report?email=…` danno il report di
  qualsiasi cliente, a chi conosce la sua e-mail;
* `POST /mono/cms/order/report` fa partire e-mail dal vostro account SES.

La regola scritta per proteggere `/mono/cms/**` non si applica mai (per via del percorso
`/mono` dell'applicazione): proteggono solo le annotazioni sui singoli metodi, e su questi
mancano.

**Verifica** (solo codice di stato e dimensione, mai il contenuto):

```bash
for p in order/pending order/active order/delivered customer/pending; do
  printf '%s ' "$p"
  curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -X POST \
    -H 'Content-Type: application/json' -d '{}' \
    "https://app.logisticasanchez.com/mono/api/$p"
done
```

`customer/pending` è protetto: senza token risponde `500`. Un `200` con dimensione
maggiore di zero sugli altri vuol dire che quei dati sono pubblici.

**Tampone in giornata** (sul VPS, senza ricompilare il backend). In
`nginx/conf.d/app-https.conf`, in cima al file, fuori dai blocchi `server`:

```nginx
map "$request_method:$http_authorization" $ls_no_token {
    default             1;
    "~^OPTIONS:"        0;
    "~^[A-Z]+:Bearer "  0;
}
```

e nel blocco `server` della porta 443, prima di `location /mono/ {`:

```nginx
# Q-21: endpoint senza controllo nel backend. Senza token: 401.
location ~ ^/mono/(api/order/(pending|active|delivered)|cms/order/report)$ {
    if ($ls_no_token) { return 401; }
    proxy_pass http://logistica_backend;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
# Q-21: link dei report. Solo dall'IP dell'ufficio (senza la riga allow: bloccati per tutti).
location ~ ^/mono/(report|download-report)$ {
    allow 203.0.113.10;   # sostituisci con l'IP pubblico dell'ufficio
    deny all;
    proxy_pass http://logistica_backend;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

Poi `docker exec logistica-nginx nginx -t && docker exec logistica-nginx nginx -s reload`.
Il CMS continua a funzionare perché manda sempre il token, e il backend rifiuta già con
`401` i token non validi. Subito dopo apri il CMS: le liste devono caricarsi come prima;
se una non si carica, togli la regola e ricarica nginx. Se rilanci `scripts/ssl-setup.sh`,
controlla che le righe ci siano ancora. La correzione vera è nel codice: aggiungere
`@PreAuthorize(MonoConstants.ADMIN_PRE_AUTH)` ai quattro metodi (12d, F-01), ricompilare
e pubblicare a mano (non usare `scripts/deploy.sh`, è rotto).

Poi: conta nei log di nginx (volume `nginx_logs`, file `https_access.log`) le chiamate a
quei percorsi, per giorno e per IP, per capire chi li ha usati e da quando; con il tuo
consulente valuta la notifica al Garante entro 72 ore dalla scoperta (art. 33 GDPR) e,
se il rischio è alto, ai clienti (art. 34). Tieni traccia di fatti e decisioni.

### 2. Segreti e log nel repository `ls` (Q-53)

Il repository (privato) contiene: una coppia di chiavi AWS nel formato di chiavi vere
(nelle configurazioni dev e local; non è quella che avevi dichiarato fittizia), la chiave
di firma JWT e password del database come valori predefiniti, un `.env`, la collezione
Postman con password di accesso, e 11 MB di log di sviluppo con dati di clienti, password
in chiaro e token. Oggi:

1. in AWS IAM controlla se quella chiave è attiva; se sì, disattivala e guarda CloudTrail e
   le statistiche di invio di SES; ruota comunque la chiave SES del backend;
2. sul server metti in `.env` un `JWT_KEY` nuovo e casuale (`openssl rand -base64 48`) e
   ricrea il container dell'app con lo stesso comando con cui hai avviato lo stack
   (probabilmente `docker compose -p logisticasanchez up -d app`); se
   `config/application-prod.yml` contiene una chiave JWT, cambia quella. Chi è nel CMS
   dovrà rientrare. Se il `.env` del server riusa una chiave del repository, oggi chiunque
   legga il repository può firmarsi un token da amministratore;
3. cambia le password dello staff e controlla chi ha accesso al repository.

Con calma, dopo: ripulire la storia del repository (`logs/`, `api/`, `.env*`, valori
predefiniti) e valutare con il consulente i dati personali nei log.

### 3. Backup (Q-48)

Di default i backup del database restano sullo stesso server, per 7 giorni, non cifrati
(se il job è installato). Se il VPS si guasta, i dati sono persi. Controlla che ci sia un
backup recente (`ls -lh /opt/logistica-sanchez/backup/mysql | tail -3`), copiane uno
cifrato fuori dal server e prova a ripristinarlo su un'altra macchina.

### 4. Certificato HTTPS di `app.` (Q-49)

Il rinnovo automatico probabilmente non funziona; se il certificato scade si fermano
portale, CMS e tracking. Controlla la scadenza:

```bash
echo | openssl s_client -connect app.logisticasanchez.com:443 \
  -servername app.logisticasanchez.com 2>/dev/null | openssl x509 -noout -enddate
```

### 5. Codice malevolo nel CMS (Q-22)

Chiunque, dal portale ordini, può scrivere nei campi un codice che si esegue nel browser
dell'ufficio appena il CMS carica gli elenchi dopo il login, e può leggere la password
rimasta nel campo di accesso; il backend non filtra nulla. Non aprire le voci non basta.
Fino al passaggio al nuovo sistema: aggiungi al CMS la protezione indicata nel capitolo 11
(Q-22), usa il CMS in un profilo del browser separato, e al passaggio cambia tutte le
password dello staff.

### 6. Chiave Google Maps nel repository pubblico ls-home (Q-23)

È nel branch `v1` e probabilmente arriva dal template gratuito. Controlla in Google Cloud
se è vostra: se sì, cancellala (o, se il sito attuale la usa ancora, limitala al vostro
dominio e ruotala).

## Passi

1. **Crea un repository GitHub privato** (per esempio `ls-webapp`). Deve essere privato:
   `docs/brief/07-pricing.md` e `docs/brief/data/` contengono il listino di un cliente
   (MOSCA) e i costi della rete RKS, e i capitoli 11 e 12 descrivono le falle ancora aperte
   del sistema attuale. Claude Code controlla che lo sia prima di iniziare.
2. **Copia nel repository** `CLAUDE.md`, `PROMPT.md`, `.gitignore` e la cartella `docs/` di
   questo pacchetto, poi fai commit e push: è il primo commit. Questo file
   (`COME-USARLO.md`) puoi tenerlo o cancellarlo.
3. **Accesso ai repository di partenza.** Claude Code clona in `legacy/` i repository
   `ls` (il backend, privato), `ls-order`, `ls-cms` e `ls-home` ai commit indicati in
   `PROMPT.md`. Se non riesce a leggere `ls` (privato), te lo segnala e prosegue con il
   capitolo 12, che lo descrive. Dei file di `ls` con log e segreti non apre nulla e delle
   configurazioni legge solo i nomi delle chiavi.
4. **Se li hai, sono utili** (nessuno è obbligatorio):
   * lo **schema** del database di produzione, senza dati (`mysqldump --no-data lsdb`), con
     il numero di righe per tabella: puoi darlo alla sessione. Senza, Claude Code usa lo
     schema ricostruito dal codice (capitolo 12b);
   * i **listini Excel di altri clienti** e i file originali MOSCA e RKS: servono solo per le
     funzioni sui prezzi (M2). Caricali nella sessione quando arrivate lì, non nel
     repository.

   Serviranno solo al **passaggio in produzione**, e li gestisci tu sul server: l'accesso
   al DNS, il dump reale del database (non va né nel repository né nella sessione: lo usi
   tu con i comandi che Claude Code ti prepara) e una chiave SES per la nuova app.

   **Non incollare mai password o chiavi in chat:** aggiungile come segreti dell'ambiente di
   Claude Code.
5. **ls-home:** il brief fa riferimento al commit `9b78785` del branch
   `claude/determined-lovelace-ggeitw`. Unisci la PR #3 con «Create a merge commit» (non
   «Squash»), così il commit resta nella storia di `main`, e non cancellare il branch
   finché la nuova app non è in produzione.
6. **Apri una sessione Claude Code** sul nuovo repository e scegli il modello più capace
   disponibile.
7. **Incolla come primo messaggio il contenuto di `PROMPT.md`.** La prima riga
   ("Ultracode.") attiva la modalità a più agenti. Il primo messaggio di Claude Code ti
   riepilogherà i rischi qui sopra e ti dirà cosa potrebbe esserti utile fornire; poi va
   avanti da solo fino al Gate 0, senza aspettare.
8. Claude Code si fermerà a ogni **gate** per chiederti conferme:
   * **Gate 0** — percorso e stack (consigliato: nuova app sullo stesso stack Java +
     Spring Boot, capitolo 10 §1) e le risposte predefinite che ha applicato alle domande
     del capitolo 11 (puoi cambiarle quando vuoi);
   * **Gate 1** — prova su staging e data del passaggio in produzione (che farete insieme);
   * **Gate 2** — attivazione delle funzioni M2, una alla volta, dopo il passaggio.
9. **Rispondi alle domande citando l'ID** (es. «Q-05: importo IVA esclusa»): Claude Code
   registra la risposta nel capitolo 11 e nelle decisioni (ADR), così non si perde tra una
   sessione e l'altra.
10. **Se apri una nuova sessione** scrivi: «Riprendi il lavoro: leggi CLAUDE.md e
    docs/plan.md (sezione Status) e continua dalla fase indicata.»

## Cosa c'è nel brief

| Capitolo | Contenuto |
|---|---|
| 01 | Contesto, obiettivi, milestone |
| 02 | Contratto delle API del backend attuale, ricavato dai frontend e corretto con il codice del backend; verifiche sulla produzione |
| 03 | Portale ordini attuale |
| 04 | CMS attuale |
| 05 | Vecchio sito pubblico e vecchi indirizzi |
| 06 | Nuovo sito statico (la base grafica) |
| 07 | Listini e costi (**riservato**) |
| 08 | Modello dati e migrazione |
| 09 | Funzionalità: parità (M1), miglioramenti sicuri (M2), idee per dopo |
| 10 | Architettura, sicurezza, privacy, test, passaggio in produzione |
| 11 | Domande aperte per te, con le risposte consigliate |
| 12 | Il backend attuale letto dal codice: sintesi e quattro parti (API e sicurezza; dati e migrazione; e-mail, report e prezzi; server e repository) |

Il brief è in inglese (è scritto per gli agenti); tutti i testi dell'interfaccia, i
riepiloghi dei gate e i runbook per te restano in italiano.
