# 0012 · Public-site port: verbatim pages, fragments, generated files and the PUB-01 allow-list

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** PUB-01, PUB-02, PUB-03, PUB-05, PUB-08, PUB-09, TRK-01 · **Questions:** Q-31, PD-02, PD-03, PD-17 · **Findings:** D-39, D-58, D-101, D-102

## Context

The never-published static site `ls-home@9b78785` is the public site of the monolith
(chapter 06). PUB-01 wants every page at the same URL with the same content and metadata,
with an explicit, ADR-listed allow-list of differences; PUB-02 wants the business data in one
place; PUB-03 the sitemap, robots and security.txt generated; PUB-09 the 404 page with status
404. The pages carry the phone number 312 times, the e-mail 195 times (chapter 06 §7.1).

## Decision

* **One template per page, content verbatim.** `tools/port-ls-home-pages.py` turns each
  `public/**/index.html` into `templates/site/<key>.html` (and `404.html` into
  `templates/error/404.html`): the four partial blocks become fragment calls of
  `layout/public` (`htmlHead`, `siteHeader(<data-page>)`, `cta`, `siteFooter`), everything
  else — title, description, canonical, Open Graph, JSON-LD, `<body data-page>`, `<main>` — is
  copied as it is. The script is committed and `--check` reports drift; the templates are
  committed too, so the build never needs the legacy checkout.
* **Business data through `${business}`** (ADR 0027): canonical, `og:url`, `og:image` and
  every URL in the page JSON-LD use `business.siteUrl`; `tel:`, `wa.me`, `mailto:` and the
  visible phone, e-mail, VAT and Instagram use the configuration; the contact page's meta
  description with the phone and e-mail is an expression. JSON-LD blocks render with text
  inlining and `layout.json()` (a JSON string literal with `<` escaped), not Thymeleaf's
  JavaScript inlining, which writes `\/` for every slash and would make the graph differ
  from the static site byte for byte.
* **Routes from one inventory** (`SitePages`): 25 pages (24 indexable plus `/grazie/`), the
  handler mappings registered programmatically, the security routes, the sitemap and the
  trailing-slash redirects (ADR 0013) all read it.
* **Generated files** (`SiteMetaController`): `sitemap.xml` — the 24 indexable pages, home
  first then alphabetical, priorities 1.0 / 0.3 (legal) / 0.8 (first level) / 0.6, `lastmod`
  = the inventory's content date (2026-10-16 for every page today; PD-17); `robots.txt` as the
  legacy file with the configured site URL; `/.well-known/security.txt` (and `/security.txt`)
  with `Contact` = the office e-mail setting and `Expires` renewed by itself (first day of the
  month eleven months ahead, under RFC 9116's one year). The copied `robots.txt` and
  `security.txt` are removed from `static/`.
* **404 and `/grazie/`** (PUB-09, PD-03): the legacy 404 page is the app's 404 template for
  every unknown URL, including `/404.html`, with status 404 through the error model;
  `/grazie/` keeps its `noindex, follow` meta and also sends `X-Robots-Tag: noindex, nofollow`.
  `X-Robots-Tag` is sent on `/area-clienti/`, `/gestione/`, `/api/`, `/mono/` by the platform
  header filter (PUB-03).
* **Caching** (PD-02, D-101): HTML, CSS and JS `no-cache` + ETag; fonts immutable one year;
  images one week — the stage-3 rules, asserted on the real pages by `SitePagesIT`.
* **Zone dataset** (PUB-08): `DeliveryZones` is the Java twin of `eta.js` (prefixes, regions,
  zones, minor islands, lagoon); `DeliveryZonesTest` parses `eta.js` and fails on any drift.
  Legends and tables in the HTML stay verbatim (M1); the unified source is ENH-10.

### The PUB-01 allow-list (every difference between a rendered page and `ls-home@9b78785`)

| # | Difference | Where | Why |
|---|---|---|---|
| 1 | «Portale ordini» → «Area clienti» `/area-clienti/` (no `target=_blank`); the home hero's «Effettua un ordine» → «Area clienti» `/area-clienti/` | header, drawer, footer, home | chapter 06 §7.1, chapter 09 PUB-01 |
| 2 | `config.js`: `TRACKING.endpoint` = `/mono/api/order/trace/state` (same origin), `FORMS`, business values from configuration | `/js/config.js` | stage 5, ADR 0027 |
| 3 | CSP `connect-src 'self'` instead of `'self' https://app.logisticasanchez.com` | headers | tracking is same-origin |
| 4 | Business values (phone, WhatsApp, e-mail, VAT, Instagram, site URL) rendered from `ls.business.*`; with the defaults the output is identical | every page | PUB-02 |
| 5 | `logisticasanchezmb@<wbr>gmail.com` on the contact page loses its `<wbr>` (the address is a setting) | `/contatti/` | PUB-02 |
| 6 | Footer year rendered at request time | footer | `{{year}}` of `sync.mjs` |
| 7 | The 404 page carries no JSON-LD graph and no speculation rules (`htmlHead(false)`) | `/404.html`, unknown URLs | stage 5 decision: no SEO script on a `noindex` error page |
| 8 | Speculation rules unchanged on the public pages; none on error pages (see 7) | head | D-102 |
| 9 | `security.txt` `Expires` renewed by the app; `sitemap.xml` `lastmod` from the inventory | generated files | PD-17 |
| 10 | Whitespace between the fragment call and the page content (the partial markers are gone) | source only | no DOM difference |

Nothing else differs: `SitePagesIT` compares `<title>`, meta description, canonical, `<h1>` and
the whole `<main>` of every page with the legacy file (whitespace-normalised, items 1 and 5
applied), when the checkout is present.

## Consequences

* Copy changes the owner asks for (Q-31) are edits to the template, re-ported by hand: the
  script would overwrite them, so from the first owner edit the script is run only with
  `--check` on the pages that stayed verbatim, or retired.
* Address and opening hours inside page bodies stay literal (the header, footer and JSON-LD
  take them from the configuration): a change of address is a template search too.
* PUB-10 (Lighthouse, axe) and the E2E of PUB-01 are the E2E stage; this ADR fixes the server
  side.

## References

Chapter 06 §3, §5.3, §6.2, §6.7, §6.8, §8.1–8.2, §8.5; chapter 09 PUB-01…03, PUB-05, PUB-08,
PUB-09; chapter 11 Q-31; ADR 0013, 0014, 0018, 0027. Code: `tools/port-ls-home-pages.py`,
`it.logisticasanchez.web.site.*`, `templates/site/**`, `SitePagesIT`, `DeliveryZonesTest`.
