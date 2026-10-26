# 0044 · The screens show the time, the day only when needed

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** CUS-05, ADM-05 (and every screen that shows when something happened) · **Owner:** request of 2026-10-26 («al mio cliente che lavora "a giornata" è molto comodo conoscere l'orario oltre che la data, che dovrebbe essere esplicitata solo dove può risultare ambigua»)

## Context

The customer area and the back office showed instants as `24/10/2026` (lists, «Oggi») or
`24/10/2026 15:30` (details, timelines). The office works by the day: what matters is the time,
and the day only when it is not today.

## Decision

* `fmt.when(instant)` for cells, lists and timelines, in Europe/Rome: `14:32` today, `ieri 14:32`,
  `domani 14:32`, `12/10 14:32` in the same year, `12/10/2025 14:32` otherwise. The element
  carries the full date and time as `title` (`fmt.dateTime`), shown on hover.
* `fmt.at(instant)` inside a sentence: `oggi alle 14:32`, `ieri alle 14:32`, `il 12/10 alle 14:32`,
  `il 12/10/2025 alle 14:32`; the messages lose their «il» before the value («Inviato {0}»,
  «Rifiutato {0}. Motivo: …», «Hai chiesto di cambiare questi dati {0}: …»). Column labels follow:
  «Inviato», «Registrato», «Richiesta», «Arrivata».
* «Today» is the Europe/Rome day of the application clock at the time the page is drawn; the pages
  are not cached, so the word is never stale.
* **Unchanged:** documents and messages read later keep the full date — e-mails, PDFs, CSV
  exports, the quote page (`/preventivi/…`, a document with its PDF) — and so do schedules, whose
  day is the point: the notices of the site (their period) and the closures, the pick-up and
  delivery days (`fmt.day`, `fmt.longDate`).

## Consequences

* Lists read at a glance during the day; older rows still say their day.
* New Italian texts (the messages and labels above) wait for the owner's review.

## References

Code: `platform.web.Formats`, `PlatformModelAttributes`; templates of `orders`, `customers` and
`backoffice`; `messages.properties`. Tests: `FormatsTest`.
