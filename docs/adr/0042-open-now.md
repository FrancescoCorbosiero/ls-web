# 0042 · «Aperto ora / Chiuso ora» next to the office hours (ENH-18)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-18 (flag `site.openNow`); touches PUB-02, PUB-05 · **Owner:** request of 2026-10-26 («procedi con quelli che puoi fare ora»)

## Context

Chapter 09: «"Aperto ora / Chiuso" next to the office hours, computed in Europe/Rome from the
business-hours configuration (PUB-02) plus staff-managed closures, by a small vanilla module so
cached HTML is never stale.» The hours are configuration (`ls.business.hours`: «8:00–18:00»
Monday to Friday, «8:00–12:00» on Saturday) and the public header shows them in the top bar
(desktop) and the footer. Public HTML is `no-cache` + ETag (PD-02); the CSP allows scripts of the
site only.

## Decision

* **The server computes, the browser compares.** Each public page carries, on a hidden badge after
  the hours (top bar and footer), the openings of the next **14 days** as pairs of epoch
  milliseconds, already in Europe/Rome hours (a day after a daylight-saving change opens at the
  right instant) and without the closure days. `/js/open-now.js` (a module loaded only while the
  flag is on) compares them with the browser's clock at load and every minute and writes «Aperto
  ora» or «Chiuso ora»: a page from a cache, or left open past closing time, is right. Without
  JavaScript the badge stays hidden; the hours are there as before.
* **The rule** (`OpeningHours`, unit-tested): Monday to Friday the weekday range, Saturday its
  range, Sunday closed, a closure day closed all day; the ranges are read from the configuration
  («8:00–18:00», en dash, em dash or hyphen, a point for the colon; refused at start-up when not a
  range).
* **Closures** (V022 `office_closure`, a Rome day and an optional reason never shown on the site):
  «Chiusure dell'ufficio» under «Impostazioni» (ADMIN only) adds a day or a range (at most 62
  days, from today on) and removes a day; the page says what the site writes now. The openings
  are kept 60 seconds and forgotten at every change made there. Audit: `office.closure.add`,
  `office.closure.remove`.
* The ported pages are unchanged (PUB-01): the badge lives in the `layout/public` fragments.

### Flag off

No badge and no script on the public pages, no link on «Impostazioni», the closure routes answer
404 (`OpenNowOffIT`). The flag is «Disponibile» in «Impostazioni».

## Consequences

* Visitors see at a glance whether someone answers the phone now.
* The office must keep the closures up to date (Christmas, August): an outdated table shows «Aperto
  ora» on a closed day.
* New Italian texts wait for the owner's review.

## References

Chapter 09 ENH-18, PUB-02, PUB-05; PD-02; ADR 0025 (flags), 0027 (business data). Code:
`site.hours.OpeningHours`, `OfficeClosureService`, `OpenNowModelAttributes`,
`backoffice.BackofficeClosuresController`, `static/js/open-now.js`, `templates/layout/public.html`;
V022. Tests: `OpeningHoursTest`, `OpenNowIT`, `OpenNowOffIT`, `e2e/tests/m2.spec.ts` [ENH-18].
