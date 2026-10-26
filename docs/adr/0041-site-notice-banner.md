# 0041 · The notice banner of the public site (ENH-17)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-17 (flag `site.noticeBanner`); touches PUB-01, PUB-05, NFR-02 · **Owner:** request of 2026-10-26 («procedi con quelli che puoi fare ora»)

## Context

Chapter 09: «Staff-managed notice banner (text, link, level, start/end) rendered server-side in
the header fragment; no inline style or script.» The public pages are the ported pages of the new
site (PUB-01: their `<main>` is verbatim) around the hand-kept fragments of `layout/public`; HTML
is served `no-cache` with an ETag (PD-02), so every visit revalidates.

## Decision

* **«Avvisi sul sito»** under «Impostazioni» (`/gestione/impostazioni/avvisi/`, ADMIN only, like
  the rest of «Impostazioni»): the notices — on the site now, scheduled, ended — with «Chiudi ora»
  and «Elimina», and the form of a new one: text (≤ 300 characters), an optional link (a page of
  the site such as `/contatti/`, or an `https://` address; no other scheme, no `//host`, no
  quotes or spaces) with its text (≤ 60), the level («Informazione» or «Attenzione») and an
  optional period, typed in Europe/Rome (no start: from now; no end: until closed).
* **V021 `site_notice`.** One notice is shown at a time: the one in force that started last.
* **Rendering:** the `siteHeader` fragment of `layout/public` draws it above the top bar, by the
  server, with classes of `main.css` (no inline style, no script), as a region labelled «Avviso»;
  the ported pages are unchanged. The site module's `@ControllerAdvice` gives the pages
  `siteNotice`; `SiteNoticeService.current()` keeps it 30 seconds and forgets it at every change
  made from the back office, so a closed notice disappears at once. Error pages, outside the site
  module, never show it.
* Audit: `site.notice.create`, `site.notice.end`, `site.notice.delete`.

### Flag off

No banner, even with a notice in force in the table; no link on «Impostazioni»; the notice routes
answer 404 (`SiteNoticesOffIT`). The flag is «Disponibile» in «Impostazioni».

## Consequences

* The office can announce closures and delays without a deploy.
* The ETag of a public page changes while a notice is shown: browsers fetch it again, as for any
  content change.
* New Italian texts wait for the owner's review.

## References

Chapter 09 ENH-17, PUB-01, PUB-05; PD-02; ADR 0012 (public site), 0025 (flags). Code:
`site.notices.SiteNoticeService`, `SiteNoticeRepository`, `SiteNoticeModelAttributes`,
`backoffice.BackofficeNoticesController`, `templates/layout/public.html` (`siteHeader`),
`templates/backoffice/notices.html`; V021. Tests: `SiteNoticesIT`, `SiteNoticesOffIT`,
`e2e/tests/m2.spec.ts` [ENH-17].
