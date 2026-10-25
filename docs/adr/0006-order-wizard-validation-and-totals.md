# 0006 · Order wizard: Italian validation rules and decimal totals

* **Status:** Accepted; the page layout is amended by [ADR 0029](0029-private-area-interface.md) (2026-10-25: one form with the lines as rows, then the summary, instead of three pages; the rules and totals below are unchanged)
* **Date:** 2026-10-24
* **Requirement IDs:** CUS-04 (chapter 09) · **Questions:** Q-24 (DDT required), Q-35 (integers ≥ 1, warnings), Q-37 (brand) · **Findings:** OP-01…OP-07, OP-12, OP-14, OP-16, OP-17, OP-23, OP-25 (chapter 03 §10), D-13, D-23

## Context

The legacy wizard (chapter 03 §6) accepted `0` and leading zeros in every line field, no
decimals, had one `typeSelected` flag for two menus (a line could be sent without a type and
became EPAL, OP-04), computed running float sums written raw into the page
(`0.06999999999999999`, OP-06), reused DOM ids between the registration and recipient forms
(OP-01), kept the previous line's values in the dialog (OP-25), let «Indietro» resubmit the
same order (OP-02) and collected «Numero Colli» without sending it (OP-07). Its formulas and
copy are the rules customers know (OP-R4…OP-R9).

## Decision

* **Three server-rendered pages**, each its own form and state, under
  `/area-clienti/ordini/nuovo/`: (1) «Inserisci i dati dell'ordine» — the totals, the line table,
  the «Aggiungi Pallet/Colli» form and the order fields; (2) «Inserisci i dati del destinatario»;
  (3) «Controlla i dati inseriti» with «Conferma l'ordine». The draft lives in the HTTP session
  (`OrderDraft`); lines get **stable ids** that are never reused (OP-05); the add-line form
  starts empty after each line (OP-25).
* **Line rules** (`OrderValidator`): the type is required per line (OP-04) — «Pallet
  Generico/Colli» (`GENERIC`) or «Epal» (`EPAL`) —; height, length, width (cm), unit weight
  (kg) and quantity are **integers ≥ 1** (`^[0-9]+$`, Q-35; `ls.orders.lines.integers-only`
  false admits a decimal weight with two decimals); no maximum unless
  `ls.orders.lines.max-height-cm` / `max-weight-kg` are set; the summary shows non-blocking
  warnings above 240 cm and 1 200 kg per unit (Q-35).
* **Totals** (`OrderTotals`, decimal arithmetic, OP-06): line weight = `peso × quantità`; line
  volume = `altezza × lunghezza × larghezza / 1 000 000 × quantità` rounded **half-up to two
  decimals per line** (a 10×10×10 cm box counts 0,00; the §6.3 example gives 1 236 kg and
  4,53 m³); the order totals are the sums of the lines, recomputed from the lines every time,
  shown in Italian («1.236,00 kg · 4,53 m³»). D-23: on exact decimal ties (0,015) the result
  may differ from the legacy `toFixed(2)` on binary floats; the decimal rule wins.
* **Order fields**: «Documento di trasporto» **required** after trim (Q-24,
  `ls.orders.transport-document.required`), at most 100 characters; «Numero Colli (facoltativo)»
  digits only, **stored** as `package_count` (OP-07); «Seleziona servizio» lists the active
  accessory services with «Nessun servizio accessorio» first and default, stored as null (V005);
  «Note (facoltativo)» a real textarea up to 2 000 characters.
* **Recipient** (OP-R7): name, street, province, city required; **CAP five digits**; phone
  optional, normalised to E.164 when given; **recipient e-mail optional** (new, OP-14), never
  the sender's.
* At least one line is required («Aggiungi almeno una riga di pallet o colli»); «Avanti»
  refuses with every error shown at once; the recipient and summary pages redirect back when
  the earlier steps are incomplete.
* The **final screen** is terminal (OP-02): «Il tuo ordine è stato inoltrato correttamente! Ti
  preghiamo di attendere la conferma via email dal nostro centro logistico. Grazie per aver
  scelto Logistica Sanchez!» (Q-37), with the temporary code and links to the order and to a
  new order; reloading it goes to the list.

## Consequences

* Legacy values that this build can no longer produce (zeros, untyped lines, US formats) exist
  only in the legacy data, which is not migrated (Q-55).
* Live totals without a page reload (JavaScript on top of the server-rendered forms) are an
  enhancement for the E2E/polish stage; the server totals are the authority.
