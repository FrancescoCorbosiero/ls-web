# Gate 0 — percorso, stack e risposte predefinite

Riepilogo per il titolare, 2026-10-17. Fase 0 completata: 24 letture del brief e dei
repository legacy (`docs/phase0/readers/`), il piano (`docs/plan.md`), la decisione sul
percorso e sullo stack (`docs/adr/0001-path-and-stack.md`) e la matrice di parità
(`docs/parity-matrix.md`). Rispondi citando l'ID (es. «Q-05: importo IVA esclusa»): ogni
risposta viene registrata nel capitolo 11 e nell'ADR corrispondente. Nessuna domanda
blocca il lavoro: senza risposta vale il valore predefinito indicato sotto.

## 0. Prima di tutto: il repository è pubblico

**Cosa abbiamo trovato.** Il 2026-10-16 (e di nuovo il 2026-10-17) l'API di GitHub
risponde per `FrancescoCorbosiero/ls-web`: `visibility: public`, `private: false`.

**Perché conta.** Nel repository ci sono `docs/brief/07-pricing.md` e `docs/brief/data/`
(il listino di un cliente e i costi della rete partner) e i capitoli 11 e 12, che
descrivono le falle ancora aperte del sistema in produzione (endpoint senza login, chiavi
nel repository `ls`). Chiunque può leggerli.

**Cosa fare adesso.** Su GitHub: Settings → General → Danger Zone → Change visibility →
Private; poi conferma in chat. In alternativa: un nuovo repository privato, oppure togliere
`07-pricing.md` e `data/` dal repository e dalla sua storia (vale anche a repository
privato, se altri vi hanno accesso).

**Stato.** Il 2026-10-17 hai deciso di proseguire anche se pubblico («procedi su ls-web
anche se pubblico», Q-54): gli agenti quindi pubblicano sul repository. Finora sul ramo
`claude/great-einstein-4mfvfz` ci sono solo i documenti della Fase 0, nessun codice. Il
rischio resta aperto (`docs/plan.md` §9, R-01): nessun prezzo, costo, segreto o dato
personale entrerà mai nel codice, nei test o nei log, ma i file riservati restano
leggibili finché non cambi la visibilità.

## 1. Rischi urgenti nel sistema in produzione oggi

Riguardano il sistema attuale, non la nuova app. I comandi sono in `COME-USARLO.md`
(punti 1–6); gli agenti non toccano la produzione.

* **Q-21 — dati leggibili senza login (confermato nel codice).** `POST /mono/api/order/pending`, `/order/active`, `/order/delivered`, `POST /mono/cms/order/report` e i link `GET /mono/report` e `/mono/download-report` rispondono senza token: ordini con e-mail, nomi, indirizzi e telefoni; il report di qualsiasi cliente; e-mail inviate dalla vostra identità SES a qualunque indirizzo. Oggi: il tampone nginx di `COME-USARLO.md` punto 1 (anche per i due link e per `/mono/cms/order/report`), poi la correzione nel codice (`@PreAuthorize`), l'export dei log nginx (solo conteggi) e la valutazione con il consulente della notifica al Garante (art. 33, 72 ore).
* **Q-53 — segreti e log nel repository `ls`.** Chiavi AWS in formato reale, chiave JWT e password come valori predefiniti, `.env`, collezione Postman con password, 11 MB di log con dati di clienti. Oggi: controlla in IAM se la coppia è attiva e disattivala, ruota la chiave SES, metti un `JWT_KEY` nuovo sul server, cambia le password dello staff, controlla chi legge il repository; poi ripulire la storia (punto 2).
* **Q-48 — backup.** Di default restano sullo stesso server, 7 giorni, non cifrati, ripristino mai provato. Oggi: una copia cifrata fuori dal server e una prova di ripristino su un'altra macchina (punto 3). Nessun lavoro di migrazione tocca la produzione prima.
* **Q-49 — certificato HTTPS di `app.`.** Il rinnovo automatico è probabilmente rotto (certbot `--standalone` con nginx sulla porta 80, certificato mai ricopiato). Oggi: controlla la scadenza e sistema il rinnovo (punto 4).
* **Q-22 — codice malevolo nel CMS.** Dal portale ordini chiunque può scrivere nei campi un codice che si esegue nel browser dell'ufficio appena il CMS carica gli elenchi; il backend non filtra nulla. Fino al passaggio: il meta tag CSP sul CMS (con il tuo via libera), un profilo del browser separato, password dello staff cambiate al passaggio (punto 5).
* **Q-23 — chiave Google Maps nel repository pubblico `ls-home` (ramo `v1`).** È in quattro pagine dal template gratuito: probabilmente non è vostra. Controlla in Google Cloud; se è vostra cancellala o limitala; non va mai riusata (punto 6).

Inoltre: non lanciare mai `scripts/deploy.sh` né `docker compose down -v` sul VPS attuale
(F-11: cancellerebbe il volume del database).

## 2. Cosa ci sarebbe utile (facoltativo)

* **Q-02** — lo schema del database di produzione senza dati (`mysqldump --no-data lsdb`) con il numero di righe per tabella e, se possibile, l'output delle query di verifica di `docs/brief/12b-backend-domain-and-data.md` §1.8 (collation, `@@time_zone`). Più avanti una copia anonimizzata per lo staging. Senza, l'importatore viene costruito sullo schema ricostruito dal codice (12b §1.7) e il controllo sul dump reale lo fai tu al passaggio.
* **Q-34** — i listini Excel degli altri clienti e i file originali MOSCA e RKS: servono solo per i prezzi (M2, dopo il passaggio). Non caricarli nel repository.
* **Q-17** — quanti account dello staff esistono, con quali nomi e ruoli; **Q-16** chi gestisce il DNS di `logisticasanchez.com` e dove sono ospitati oggi il sito, `ordini.` e `dashboard.`; **Q-50** l'host dei link in una e-mail di verifica recente; **Q-47** cosa sa fare il CMS in produzione oltre a `ls-cms@dev` (correzioni dopo l'approvazione? report al cliente?). Non bloccano la costruzione: servono per il piano di passaggio.

## 3. Sonde di sola lettura P-01…P-03 (Q-27)

Tre chiamate di sola lettura al backend attuale confermerebbero sul sistema vivo ciò che il
codice dice: P-01 le enumerazioni, P-02 le risposte del tracking per codici che ci dai tu,
P-03 le origini CORS ammesse. **Predefinito: no, gli agenti non le eseguono.** Se vuoi,
scrivi «Q-27: ok P-01, P-02, P-03»; le altre sonde (P-04…P-07) le esegui solo tu. Gli
agenti non ricevono mai credenziali o token; le risposte con dati personali non entrano nel
repository né nella sessione.

## 4. Decisione Gate 0: percorso e stack

**Proposta: percorso B** — una nuova applicazione, scritta da zero sullo stesso stack del
backend attuale, usando il codice di `ls` come riferimento per ogni regola e testo
(`docs/adr/0001-path-and-stack.md`).

* **Perché non il percorso A (evolvere il backend attuale).** Il codice si compila (verificato: 38 secondi con JDK 21), ma gira su Spring Boot 3.2.2 (supporto terminato a fine 2024), AWS SDK v1 (fine supporto dicembre 2025), iText 2.1.7 (2009), Hibernate 6.4, senza migrazioni di schema (`ddl-auto: update`), senza health check, senza test utili, con il modello di sicurezza da rifare comunque. L'aggiornamento tocca ogni strato: stima 20–35 % di lavoro in più rispetto a B, con più rischio di regressioni. Ciò che A avrebbe conservato (regole di business, dieci e-mail, layout del PDF) è già documentato nel capitolo 12 e viene riportato leggendo il codice.
* **Versioni fissate:** Java 21 (JDK presente qui e in GitHub Actions); Maven con wrapper; Spring Boot 4.1.1 (l'ultima versione stabile su Maven Central al 2026-10-17; l'agente della Fase 1 ricontrolla prima di generare lo scheletro); Thymeleaf e htmx 2.0.11 per le pagine; PostgreSQL 16 con Flyway; AWS SDK v2 solo per SES (eu-north-1, identità già verificata, **nuova chiave per un utente IAM dedicato** creata da te); nessun S3 in M1 (il PDF del report viene generato al momento del download autenticato; S3 arriva con i documenti in M2); OpenPDF 3.0.5 per il PDF; Playwright 1.56.1, axe e Lighthouse CI per i test.
* **Hosting e database (Q-52):** **un nuovo VPS Contabo** (il server attuale resta intatto per un eventuale ritorno indietro), Docker Compose con proxy Caddy 2 (HTTPS automatico), l'app e PostgreSQL 16; staging sullo stesso VPS protetto da password; backup cifrati fuori dal server (7 giornalieri, 4 settimanali, 12 mensili) con prova di ripristino mensile. Costi da approvare prima di attivarli: il VPS, il registro immagini GHCR, un controllo esterno di uptime/scadenza TLS, uno spazio EU per i backup; Contabo come responsabile del trattamento (DPA).
* **Gli account dello staff non vengono migrati** dalla tabella `users`: li ricrei con un comando che stampa un link di prima impostazione password (ADM-01).

Rispondi «Q-01: B» e «Q-52: nuovo VPS, PostgreSQL» per confermare, oppure indica cosa cambiare.

## 5. Risposte predefinite applicate

Ogni voce è già implementabile così com'è, dietro un'unica impostazione o un unico metodo
(`docs/plan.md` §8). Per cambiarla basta rispondere con l'ID, ad esempio «Q-05: importo
IVA esclusa, mai zero».

| ID | Domanda in una riga | Predefinito applicato |
|---|---|---|
| Q-01 | Evolvere il backend attuale (A) o nuova app (B)? | B, Java + Spring Boot attuali, `ls` come riferimento |
| Q-02 | Puoi dare lo schema del DB di produzione? | Facoltativo; importatore costruito sullo schema ricostruito dal codice |
| Q-03 | Il tracking pubblico deve rispondere come oggi? | Sì: etichetta, `Non gestito` per codici sconosciuti, 400 se vuoto; confronto senza spazi e maiuscole |
| Q-04 | Cosa significa ASSEGNATO per il cliente? Serve uno stato «anomalia»? | «Affidata al mezzo per la consegna»; nessun nuovo stato in M1 |
| Q-05 | L'importo digitato all'approvazione è il prezzo al cliente? IVA? Può essere 0? | Prezzo al cliente, IVA esclusa, maggiore di 0; salvato con un indicatore IVA |
| Q-06 | Chi assegna i codici definitivi di spedizione (numeri a 12 cifre del corriere?) | Li digita l'ufficio; unicità senza spazi e maiuscole; l'app non ne propone |
| Q-07 | Le dieci e-mail attuali vanno bene? Aggiungere «registrazione ricevuta» e «ordine ricevuto»? | Testi attuali riusati senza i difetti; le due nuove e-mail aggiunte; revisione dei testi al Gate 1 |
| Q-09 | Un cliente rifiutato resta e può registrarsi di nuovo? | Sì: stato esplicito «rifiutato», nuova registrazione ammessa |
| Q-10 | Possono registrarsi anche privati? Partita IVA / codice fiscale obbligatori? | Sì anche privati; P.IVA e codice fiscale facoltativi |
| Q-11 | Un accesso per cliente o più utenti per azienda? | Uno (l'e-mail del cliente) in M1 |
| Q-13 | Il ritiro è sempre all'indirizzo registrato? | Sì, come oggi |
| Q-15 | Il secondo numero WhatsApp del vecchio sito è ancora in uso? | No: solo il numero ufficiale, da configurazione |
| Q-16 | Chi gestisce il DNS? Dove sono sito, `ordini.`, `dashboard.`? | Nuova app su nuovo VPS; i record A li cambi tu |
| Q-17 | Quanti account staff, con quali ruoli? Doppio fattore? | Ruoli ADMIN e OPERATOR; 2FA in M2 |
| Q-24 | Il documento di trasporto è obbligatorio? Cosa indica «Numero Colli»? | Obbligatorio; «Numero Colli» salvato come conteggio facoltativo |
| Q-25 | I sei servizi accessori (0 = nessuno) | Confermati dal codice: seminati così |
| Q-26 | Note interne separate dalla nota del cliente? | Sì: la nota del cliente resta, le note interne sono a parte |
| Q-27 | Chi esegue le sonde P-01…P-03? | Nessuno senza il tuo via libera |
| Q-28 | Approvare solo clienti con e-mail verificata? | Sì, come oggi |
| Q-35 | Limiti per riga d'ordine (altezza, peso, decimali)? | Interi ≥ 1, nessun massimo, avviso oltre 240 cm o 1.200 kg |
| Q-36 | Telefono obbligatorio alla registrazione? | Sì (il backend lo richiede già) |
| Q-37 | «Logistica Sanchez» al posto di «Sanchez Logistica»? | Sì, ovunque, anche negli oggetti delle e-mail |
| Q-38 | I codici temporanei rispondono «Non gestito»? | Sì, come oggi |
| Q-39 | Quali origini CORS oltre a `https://logisticasanchez.com`? | Nessuna, fino alla sonda P-03 |
| Q-45 | Cosa gira sul VPS attuale? | Non serve ora; prima del passaggio i comandi di sola lettura di 12d §1.15 |
| Q-47 | Quale CMS gira in produzione? Cosa chiamano `ordini.` e `dashboard.`? | Back office nuovo da capitoli 04 e 12; si assume che chiamino solo `app.` |
| Q-51 | `ls-web` è un altro progetto? | Chiuso: questo repository è la nuova app |
| Q-52 | Dove gira la nuova app, con quale database? | Nuovo VPS Contabo, PostgreSQL 16 |

Scelte del pianificatore senza una domanda nel capitolo 11 (`docs/plan.md` §8.2, P-01…P-15),
da confermare o cambiare: la pagina di tracking mostra «riprova tra poco» invece di «codice
non trovato» quando il server risponde 429 o 5xx; cache «no-cache + ETag» per HTML/CSS/JS;
`/404.html` risponde 404; il modulo preventivo accetta ancora «2,5» pallet come oggi; le
due enumerazioni mai chiamate (`tipoPallet`, `statoConferma`) rispondono 410; in
«In Approvazione» compaiono solo i clienti già verificati (come oggi); reimpostare lo
stesso stato di tracking non rimanda l'e-mail di consegna; il report usa i giorni in
ora italiana (oggi UTC), «Dt/Cons.» = data dell'evento «Consegnato» e omette
l'intestazione del partner; sessioni di 60 min / 12 h per i clienti e 30 min / 10 h per lo
staff; **correzioni di importo o codice dopo l'approvazione: non previste in M1** — se
l'ufficio le fa oggi, dillo (P-12).

Valgono dalla Fase 1 anche i predefiniti del Gate 1 (Q-08 report PDF scaricabile solo con
accesso, destinatari da una lista gestita da ADMIN; Q-18 conservazione: richieste 24 mesi,
spedizioni 10 anni, log 30 giorni; Q-43 la casella dell'ufficio diventa un'impostazione, da
spostare su una casella aziendale prima del go-live; Q-30/Q-50 i vecchi link `/mono/…`
nelle e-mail già inviate portano a una pagina verso l'area clienti, senza mai confermare
indirizzi né servire report).

## 6. Cosa succede dopo

Mi hai chiesto di procedere in autonomia (2026-10-17): quindi **non mi fermo qui**. La
**Fase 1 — fondamenta** parte subito con i valori predefiniti di §4 e §5; puoi cambiarne
uno in qualsiasi momento rispondendo con l'ID. Restano fermi i punti che richiedono te:
spese (VPS, registro, backup esterni), produzione, DNS, dati reali, credenziali. La Fase 1
è un lavoro sequenziale: scheletro del progetto, CI su
GitHub Actions, ambiente locale, lo schema completo del database come prime migrazioni,
accesso dello staff e accesso dei clienti con link via e-mail (finiti), layout e
design system del nuovo sito, configurazione di sicurezza, testi, feature flag, registro
delle azioni, coda delle e-mail, blocco anti-invio in staging, scheletro di ogni modulo.
Poi la **Fase 2** (moduli in parallelo: sito, tracking, clienti, ordini, back office,
e-mail e report, migrazione), la **Fase 3** (revisioni fino a esaurimento dei rilievi) e la
**Fase 4** che si ferma al **Gate 1** con la matrice di parità, i report dei test, la prova
di migrazione, i runbook in italiano e la checklist di collaudo per l'ufficio (una
settimana su staging). Il passaggio in produzione lo facciamo insieme, in una finestra di
sabato pomeriggio (Q-19). Nel frattempo niente tocca la produzione, il DNS o dati reali;
le e-mail fuori produzione non raggiungono destinatari veri.

## 7. Discrepanze trovate

Lista completa con citazioni in `docs/plan.md` §7 (126 voci). Le più importanti, una riga
ciascuna:

* Gli endpoint aperti senza token sono **sei**, non due come dice 12d: anche `order/active`, `order/delivered` e i due link del report (D-01); `POST /mono/cms/order/report` permette a chiunque di far inviare e-mail dalla vostra identità SES (D-07).
* L'approvazione via API senza il blocco `req` rende l'ordine «registrato» con codice temporaneo, senza importo né data: tali righe possono esistere nel database e la migrazione le elenca (D-03).
* Il report attuale fallisce con errore 400 se un ordine non ha importo o data di approvazione: il nuovo report stampa «-» (D-02); «Dt/Cons.» oggi non è la data di consegna (D-08).
* L'e-mail di consegna stampa la nota che l'ufficio modifica dal CMS: le note interne arrivano al cliente; nella nuova app non più (D-05). L'e-mail di rifiuto ordine non cita il codice e può andare a un cliente diverso (D-04, D-06).
* L'importo digitato è `importo` (dal 16-07-2024): la regola di migrazione è `importo`, altrimenti `importo_assicurato` — il capitolo 08 diceva il contrario (D-10).
* Il volume dell'ordine è salvato arrotondato al metro cubo intero: i totali si ricalcolano dalle righe, mai confrontati con il valore salvato (D-13); nel portale l'arrotondamento era in virgola mobile, nella nuova app è decimale (D-23).
* I codici di spedizione possono avere spazi iniziali, essere vuoti o lunghi fino a 255 caratteri: colonne e controlli adeguati (D-09, D-111).
* Telefono obbligatorio (il backend lo richiede, il portale diceva facoltativo), interi nelle righe, P.IVA/codice fiscale facoltativi: i capitoli 03 e 08 vengono allineati al capitolo 09/11 (D-67, D-109, D-110).
* Il tracking pubblico risponde `200 Non gestito` per codici sconosciuti (12a/12b riportano ancora un vecchio «404»: nota superata, D-62); il CMS nel repository non può approvare né inviare report contro il backend attuale (D-26, D-28: Q-47).
* L'e-mail di consegna linka `https://www.logisticasanchez.com/#tracking`: il reindirizzo `www` → dominio principale deve conservare l'ancora (D-34); `/index.html` e il reindirizzo del vecchio portale si aggiungono alla lista (D-102).
* Le liste del CMS mostrano solo gli ultimi 6 mesi: il nuovo back office mostra tutto, con paginazione (D-77).
* I timestamp legacy sono salvati in UTC (da verificare su un ordine di cui conosci la data) e il dump usa `--add-drop-database`: ripristino solo in un contenitore isolato (D-42, D-48).
* Il repository `ls-web` è ora quello della nuova app, non il vecchio prompt per un sito statico (D-41).
