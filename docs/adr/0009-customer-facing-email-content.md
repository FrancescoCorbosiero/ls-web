# 0009 · Customer-facing e-mails: wording, blocks and recipients of EML-02…06 and EML-09

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** EML-01…EML-06, EML-09 (chapter 09) · **Questions:** Q-07 (new e-mails EML-02/EML-04), Q-37 (brand), Q-43 (office address) · **Findings:** 12c §1.6 defects, D-05, D-06

## Context

The legacy backend sent HTML-only e-mails without Reply-To, subjects prefixed «Sanchez
Logistica - », enum names instead of labels, staff notes in the delivery e-mail and a
registration notice on every click of the verification link (12c §1). Chapter 09 asks to reuse
the content without the defects, add EML-02 and EML-04, set Reply-To where a person should
answer and let the owner review the copy at the next gate. ADR 0015 holds the catalogue, the
outbox and the sender.

## Decision

* One shared shape, `notifications.MailLayout`: a title, paragraphs, label/value tables, one
  button, the brand signature and a footer; **plain text and HTML from the same blocks**; the
  HTML is a single inline-styled table in the site's colours. Subjects keep the legacy form
  **«Logistica Sanchez - …»** (`ls.business.brand-name`, Q-37).
* **Customer e-mails** carry `Reply-To` = the office address (`ls.business.office-email`,
  Q-43); their footer replaces the legacy «Si prega di non rispondere» with an invitation to
  answer or call. **Office e-mails** carry `Reply-To` = the customer the notice is about and a
  link to `/gestione/`.
* Content per e-mail (legacy wording through the Phase 0 reader notes, defects removed):
  EML-02 «Registrazione ricevuta» (L-E… `email-success` text: «Abbiamo verificato il tuo
  indirizzo email», the pending sentence); EML-03 «Conferma nuovo cliente» (L-E3) / «Registrazione
  non accettata» (L-E4 body, truthful subject, the reason); EML-04 «Richiesta di spedizione
  ricevuta» (`request-success` text, the temporary code, the detail blocks); EML-05 «Conferma
  ordine N. {codice}» (L-E6 blocks «Dettaglio spedizione», «Dettaglio ordine», «Pallet e colli»,
  «I tuoi dati», labels not enum names, euro with the comma, the tracking link) / «Aggiornamento
  ordine rifiutato N. {codice}» (L-E7 plus the code and the reason); EML-06 «Nuovo cliente in
  attesa di conferma» once per verified registration and «Nuova richiesta di spedizione in
  attesa di conferma» per order (L-E2, L-E5, with the customer's data and the code); EML-09
  «Consegna effettuata per ordine N. {codice}» (L-E8, the customer's note only, D-05).
* Lines are printed **grouped as typed** (type, dimensions, unit weight, quantity), not one row
  per physical unit as the legacy did.
* The e-mails of the one-time links (EML-01: sign-in, registration, e-mail change and its
  notice) keep the stage 4 minimal shape of `AuthMails` with the token placeholder.

## Consequences

* Every e-mail has an integration test asserting kind, recipient, subject, Reply-To and the key
  sentences; the owner reviews the texts listed in the step B2 log at the next gate.
* Branded Thymeleaf templates can replace `MailLayout` later without touching the modules.
