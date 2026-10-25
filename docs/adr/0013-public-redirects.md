# 0013 · Public redirects: the `_redirects` table, trailing slashes, `index.html`, host rules

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** PUB-04 · **Questions:** Q-29, Q-30, Q-47 · **Findings:** D-32, D-34, D-98

## Context

Netlify applied `public/_redirects` (ten 301 rules from the old template site's URLs and
earlier `.html` names, plus the `/mono/*` proxy line) and its own conventions: a directory URL
without the trailing slash redirects to the slash form, `/index.html` is the home. The
delivery e-mails of the legacy backend link `https://www.logisticasanchez.com/#tracking`
(D-34); the old portal and CMS hosts must land on the new areas (Q-30, Q-47).

## Decision

* **`SiteRedirectFilter`** (site module, right after host routing) holds the table: the ten
  `_redirects` rules verbatim; `/index.html` → `/`; for every page of `SitePages` the URL
  without its trailing slash (`/zone` → `/zone/`) and its `index.html` form
  (`/zone/index.html` → `/zone/`). Every rule is a **301** with a path-only `Location`, the
  query string kept (`/tracking?code=X` → `/tracking/?code=X`), `Cache-Control: max-age=3600`,
  for `GET` and `HEAD` only.
* The `/mono/*` proxy line is **not** a redirect (D-98): the app answers those paths (ADR 0010,
  0011).
* **Fragments stay client-side**: `/#chisiamo`, `/#contattaci`, `/#tracking` are handled by
  `main.js` as on the static site; `www` → apex keeps path and query so the browser keeps the
  fragment (D-34). Host rules (`www`, `ordini.`, `dashboard.`) are the platform's
  `HostRoutingFilter` (stage 3).
* **Q-29** (URLs found by crawling the live site before the cut-over) is appended to
  `LEGACY_REDIRECTS` as data when the crawl exists; nothing is invented meanwhile. The v1 asset
  names of D-32 answer 404 like any unknown URL.

## Consequences

* `SiteRedirectsIT` is table-driven: every rule of the filter's table is requested, plus the
  chapter 06 §6.3 rules spelled out, so a page added to the inventory gets its slash
  redirect for free and a removed page fails the test.
* A redirect never reaches the security chain, so it needs no route declaration and leaves
  public pages cookie-free.

## References

Chapter 06 §6.3, §6.8; chapter 09 PUB-04; chapter 11 Q-29, Q-30, Q-47; plan D-32, D-34, D-98;
ADR 0012. Code: `SiteRedirectFilter`, `SiteRedirectsIT`, `HostRoutingIT`.
