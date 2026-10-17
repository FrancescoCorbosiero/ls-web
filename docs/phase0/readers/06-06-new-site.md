# Phase 0 reader — chapter 06 · New static site (ls-home), porting guide

Source: `docs/brief/06-new-site.md` (1,399 lines), read in full on 2026-10-16. Citations keep the
chapter's convention (`ls-home@fe4b056:<path>:<line>`; the porting baseline is the branch head
`9b78785`, which differs only in `README.md`, `public/_redirects`, `public/js/tracking.js` and
`public/tracking/index.html` — confirmed with `git diff --stat fe4b056 9b78785`). Company contact
values (phone, e-mail, address, VAT, social handle, agency credit) are referred to by place, never copied.

## 1. Scope

Chapter 06 documents the never-published static site that becomes the monolith's public site: 26 pages
with their metadata and JSON-LD (§3), the design system (§4), the eight vanilla-JS widgets and the one
Netlify Function (§5), SEO/platform behaviour (§6), business data and copy to review (§7), the porting
guide and parity checks (§8), quirks (§9) and unknowns (§10). Reading map: **Phase 1 foundations** §4.10,
§6.4, §8.1, §8.5; **site** §8 first, then §3–§7; **tracking + compatibility** §5.7; **notifications** §5.6
(the form e-mail); every other module reuses §4 (design system) under PROMPT rule 2 "one design".

## 2. Requirement IDs touched

| ID | Sections that specify or constrain it |
|---|---|
| PUB-01 | §1, §3.1–3.4 (inventory, metadata, JSON-LD, page families), §8.1–8.2, §8.6 |
| PUB-02 | §4.10 (fragments, active menu), §7.1 (business data, 312 phone occurrences), §8.2, §8.4 |
| PUB-03 | §6.2 (sitemap rules, robots), §6.7 (manifest, icons, security.txt), §8.4 |
| PUB-04 | §5.2 (legacy anchors), §6.1 (apex/www), §6.3 (`_redirects`, `/mono/*` proxy), §6.8 (trailing slash) |
| PUB-05 | §4.1 (no inline style/script), §6.4 (headers, CSP, caching), §8.5 (CSP pitfalls, HTMX settings) |
| PUB-06 | §4.13 (no-JS), §5.1, §5.5 (wizard fields, prefill, summary, WhatsApp), §5.6 (pipeline), §8.3–8.5 |
| PUB-07 | §5.6 (contact form fields, client messages, server cases) |
| PUB-08 | §5.3 (CAP data, algorithm, test cases), §5.4 (coverage map, Maps consent), §4.13 |
| PUB-09 | §3.1, §3.4 (utility pages), §6.8 (404 status) |
| PUB-10, NFR-01 | §1 (weights), §4.11 (accessibility), §4.12 (motion/print), §8.5 (performance budget) |
| PUB-11 | §5.6 "In the monolith" (store each submission as a lead), §8.4 |
| TRK-01 | §5.7 (markup, flow, six states, matching, card, "ask us", recent codes), §4.13, §8.6 |
| TRK-02, TRK-03, TRK-04 | §5.7 (code regex and whitespace; no `pod`/personal data; 429 read as "not found") |
| API-01, API-03 | §5.7 request/response, §6.1 (CORS), §6.3 (`/mono/*` proxy disappears), §8.3 |
| ADM-06, ADM-10 | §5.7 (state ids/names/labels mirror the CMS enumeration; events feed the timeline), §8.4 |
| EML-08 | §4.3 (e-mail palette), §5.6 (subject, Reply-To, sections, footer, HTML buttons, SES configuration) |
| NFR-02 | §6.4, §8.5 (CSRF exemption of `/api/form`, Origin/Referer check, strict CSP for private areas) |
| NFR-03 | §1 (storage keys), §5.4 (`ls_maps_ok`), §7.3 (privacy/cookie pages name Netlify and SES), §8.5 |
| NFR-06 | §4.10 (sync checks become CI tests), §8.2, §8.6 |
| ENH-04, ENH-05, ENH-06, ENH-10, ENH-12, ENH-14, ENH-22 | §5.5 (public estimate), §5.7 (richer answer, no `pod`), §8.4 (leads), §5.3 (unified times), §7.3 (stats) |
| CUS-01 (entry link) | §7.1: "Portale ordini"/"Effettua un ordine" become "Area clienti" → `/area-clienti/` |

No MIG-* ID: the legacy stores no leads and the site has no data to migrate (08 §2.3 `lead`).

## 3. Rules to keep (M1 parity)

| Rule | Citation (ls-home@fe4b056 unless stated) | Serves |
|---|---|---|
| 26 pages at the §3.1 URLs with the listed `<title>`, canonical, one `<h1>`, OG image, `data-page`; `/grazie/` and 404 `noindex, follow`, no canonical/OG | tools/sync.mjs:150-161; public/grazie/index.html:25; public/404.html:25 | PUB-01, PUB-09 |
| Page JSON-LD types and fixed `@id`s of §3.2–3.3; site graph on every page | partials/head.html:16 | PUB-01 |
| Four partials between markers; `aria-current="page"` for the page key, `"true"` for an ancestor; the 13 nav keys | tools/sync.mjs:92-98, 102-110; partials/header.html:25-88 | PUB-02 |
| Business data (name, slogan, VAT, address, phone, WhatsApp, e-mail, hours, Instagram, site URL) from one source | public/js/config.js:5-45; partials/header.html:10; public/contatti/index.html:156-160 | PUB-02 |
| Sitemap: 24 URLs, home first then alphabetical, priorities 1.0/0.8/0.6/0.3; robots allows all | tools/sync.mjs:236-282; public/robots.txt:1-4 | PUB-03 |
| Ten 301s of `_redirects`; anchors `#chisiamo`, `#contattaci`, `#tracking` client-side on `/` only; `/zone` → `/zone/`; `www` → apex | public/_redirects:1-11; public/js/main.js:218-224 (VERIFIED); §6.8 | PUB-04 |
| Headers and CSP of `_headers` (incl. `'inline-speculation-rules'`, Google `frame-src`); fonts immutable 1 year, images 1 week | public/_headers:1-16 | PUB-05 |
| Wizard: 4 steps, every field name/value (incl. `servizi_extra[]`, `form-name`, `bot-field`), prefill `servizio/tipo/da/a/pallet`, summary rows, WhatsApp text, confirmation copy | public/preventivo/index.html:152-329; public/js/quote.js:10-15, 43-78, 107-208 | PUB-06 |
| `/api/form`: JSON vs native by `Accept`; 20,000-char limit → 413; honeypot → fake success; `unknown_form` 400; check order → 422 `invalid:<field>`; 502 `send_failed`; 303 `/grazie/`; HTML error page texts | netlify/functions/form.mjs:19-22, 39, 52-129, 234-288 (VERIFIED 19-39, 244-250) | PUB-06, PUB-07 |
| Office e-mail: subjects, Reply-To = customer, text + HTML sections, footer line, call/WhatsApp buttons, escaping | netlify/functions/form.mjs:155-210 | EML-08 |
| CAP widget: 20 regions and prefixes, 23 island CAPs, lagoon regex, messages, `?cap=` prefill, `eta:region` event; the §5.3 test table | public/js/eta.js:8-98 (VERIFIED 8-12, 46-51) | PUB-08 |
| Coverage SVG inlined same-origin with tooltips/legend highlight; Google Maps only after a click; `ls_maps_ok` revocable on `/cookie/` | public/js/coverage.js:8-101; public/js/map.js:6-40; public/js/main.js:199-211 | PUB-08, NFR-03 |
| Tracking page: code regex `^[^\s<>"'`]{1,64}$`, `?code=` deep link, 8 s timeout, the six states (id/name/label/title/step/icon), matching normalisation, `Non gestito` → neutral message (9b78785), recent codes max 4 | public/js/tracking.js:14-21 (VERIFIED), 23-30, 203-238; ls-cms@dev:js/components/data/user-data.js:22-53 | TRK-01, ADM-10 |
| Request `{"codice"}` → `{"status": "<label>"}`; unknown/temporary → `200 {"status":"Non gestito"}` | public/js/config.js:31 (VERIFIED); 12a §1.4 D1 | API-01, TRK-02 |
| No-JS paths: native POST → 303 or HTML error; GET forms to `/tracking/`, `/zone/`, `/preventivo/`; tabs stacked | public/css/main.css:3745-3816, 1346-1408; public/tracking/index.html:162 | PUB-06, TRK-01 |
| Assets at the same root-relative paths; sprite and `italia-copertura.svg` same-origin | §8.1; public/img/icons.svg:1-2 | PUB-01, PUB-05 |
| Skip link, `<dialog>` drawer, ARIA tabs, live regions, reduced motion, print rules | public/js/main.js:37-145; public/css/main.css:135-139, 4481-4579 | PUB-10, NFR-01 |

## 4. Deliberate fixes (each needs an ADR)

| Fix | Where | ADR title |
|---|---|---|
| Over-long values and CAPs that are not exactly 5 digits → 422, never truncated | §5.5, §5.6; 09 PUB-06 | "Public form validation rejects instead of truncating" |
| Form e-mail through the outbox; submission stored as a `lead` in the same transaction; 502 means "not queued" | §5.6, §8.4; PUB-11, EML-08 | "Public forms: outbox delivery and lead storage" |
| `POST /api/form` exempt from CSRF, guarded by honeypot, rate limit, Origin/Referer check | §8.5; 10 §4 | "CSRF exemption and abuse controls for /api/form" |
| Tracking same-origin (`TRACKING.endpoint` relative, `connect-src 'self'`); `/mono/*` proxy replaced by the app on apex and `app.` | §5.7, §6.3, §8.3 | "Same-origin tracking endpoint and compatibility host" |
| Tracking answer without `pod`, documents or personal data; extra fields only from real data | §5.7; TRK-03 | "Public tracking payload limits" |
| 429/5xx shown as "not found" by `tracking.js`: keep (parity) or treat as network error | §5.7 quirk 2; TRK-04 | "Tracking page handling of 429 and 5xx" |
| Allowed HTML differences: order-portal links → `/area-clienti/`, `config.js`, privacy/cookie, Q-31 copy, speculation-rule exclusions | §7.1, §7.3, §8.5; PUB-01 | "Allowed differences from the static site" |
| Sitemap/robots generated from the route registry; `lastmod` from a content date, not git | §8.4 | "Generated sitemap and robots" |
| CSS/JS/HTML caching `no-cache` + ETag (no fingerprinting); fonts/images as today | §8.5 | "Static asset caching and versioning" |
| Legends, tables and `config.js` rendered from one business/zone dataset (eta.js data unchanged) | §5.3, §7.1 | "Single sources for business and zone data" |
| Privacy and cookie pages name the new processors and cookies; staging `noindex`, no office e-mail | §7.3, §8.5; NFR-03 | "Privacy notice and cookie page for the monolith" |
| SES IAM user may write to customers too: the README's office-only recipient condition is not reused | §5.6 | fold into the infrastructure/IAM ADR |

## 5. Unknowns by ID (recommended defaults from chapter 11 / 06)

| ID | Raised in 06 | Default |
|---|---|---|
| Q-03 | §5.7, §10: what `status` holds; page accepts id, name or label | Label, matched trimmed and case-insensitive |
| Q-04 | §5.7, quirk 3: meaning of `ASSEGNATO`; no exception state styled-but-unused | "Affidata al mezzo per la consegna"; no new state in M1 |
| Q-06 | §5.7, §10: code format and case; page sends the code as typed minus spaces | Staff type codes; uniqueness trimmed, case-insensitive |
| Q-14 (ENH-04, ENH-10) | §5.3, §7.3: site zones vs price-list times (07 §2.4 differs for most regions); public price bands | M1 keeps `eta.js` data; unified times and public estimate off until the owner decides |
| Q-18 | via PUB-11 lead storage | Leads kept 24 months |
| Q-20 | §1, §6.1: Netlify site never published; retire it | Yes, once DNS points to the app; delete its IAM key |
| Q-29 | §6.3: `.html` redirects added as guesses; old hosts | Crawl the live site before cut-over; 301 everything |
| Q-31 | §7.3: stats, delivery promise, insurance, Saturday hours missing on two pages, footer credit, town lists, stock photo | Keep only what the owner confirms; no copy change before |
| Q-38 | §5.7: temporary codes answer `Non gestito` | Yes, as today |
| Q-39 (LPS-U9, CU-14, SEC-18) | §6.1, §8.5: CORS origins and credentials on `app.` | Apex only, no credentials, until P-03 says otherwise |
| Q-43 | §5.6: `MAIL_TO` example is the office consumer mailbox | Business mailbox before go-live; address is a setting |
| Q-50 | §6.3: e-mail links built on the apex (`EMAIL_ORIGIN`) | Answer `/mono/**` on the apex and `app.` |
| CU-01, CU-02 | §5.7 via Q-03 | As Q-03 |
| no ID (§10) | OG image generator; CSP applied to the SVG's internal `<style>` as `<img>`; SES sandbox/verified identities; backend richer fields (answered by 12a D1: `StatusRepr` has only `status`) | Hand-made OG images for new pages; verify SVG in three browsers; owner checks SES |

## 6. Contradictions and spot checks

1. **Internal (stale):** 06 §10 still lists "which HTTP status the backend returns for an unknown code" as unknown and tracking.js:178 says "an error status", but 06 §5.7 and 12a §1.4 D1 (ls@migration SpedizioneService.java:117-118, `orElse(NON_GESTITO)`) say `200 {"status":"Non gestito"}`. The source wins; the page's "not found" branch only fires on the new app's non-2xx.
2. **06 §5.7 vs ls-home-v1:** "the old template site made the same call" — ls-home@v1:js/custom.js:68 posts the same body to the same path but on an `http://` Elastic Beanstalk UAT host, not `app.`; chapter 05 §3.2 governs the v1 call.
3. **Caching, 06 §8.5 vs 10 §2 vs 09 PUB-05:** 06 recommends `no-cache` + ETag for CSS, JS and HTML; 10 §2 says "ETag/short caching for public HTML only"; PUB-05 asks for "same caching rules" (which cover only fonts and images). One rule must be chosen (Gate 0).
4. **09 PUB-01 allow-list vs 06 §8.5:** excluding `/area-clienti/*`, `/gestione/*`, `/api/*` from the speculation rules changes `partials/head.html:17` on every page, but the allow-list of PUB-01 does not mention it. Likewise `/index.html` → `/` (09 PUB-04, 05 §2.1) is missing from 06 §6.3, which lists only `_redirects`.
5. **06 quirk 5 vs 08 §2.3 vs 09 PUB-06:** decimal `numero` ("2,5") is accepted by the server; 08 keeps `lead.quantity numeric(5,2)`; PUB-06 fixes only over-long values and CAPs. Decide integer-only or parity.
6. **06 §7.3 vs chapter 01:** `/preventivo/` aside and `/contatti/` phone card say "Lun–Ven 8:00–18:00" without Saturday, against the hours of chapter 01 and every other place; PUB-01 forbids changing copy without Q-31.
7. **06 §6.1 vs 12a §2.10:** the README says the backend accepts only the apex, the source allows the committed `ALLOWED_ORIGINS` list (apex, `www`, `app.`) with credentials; 06 already notes it and 02 §10.2 rule 5 decides. No conflict left.
8. **PROMPT.md:** none. Its claim that only the files listed in 06 changed between `fe4b056` and `9b78785` is confirmed (`git diff --stat`: README.md, `_redirects`, `tracking.js`, `tracking/index.html`).

Spot check of five citations (opened in `legacy/ls-home` and `legacy/ls-cms`):

| Citation | Result | Evidence |
|---|---|---|
| ls-home@fe4b056:public/js/config.js:31 | VERIFIED | `endpoint: 'https://app.logisticasanchez.com/mono/api/order/trace/state'`, `portalUrl`/`demoCode` empty, `timeoutMs: 8000` |
| ls-home@fe4b056:public/js/eta.js:8-12, 46-51 | VERIFIED | three zones with the quoted labels; 23 island CAPs in a `Set`; lagoon regex `^301(2[1-6]|3[1-5]|4[12])$` |
| ls-home@fe4b056:public/js/main.js:218-224 | VERIFIED | three anchors mapped on `location.pathname === '/'` only, `location.replace`, skipped when an element with that id exists |
| ls-home@fe4b056:public/js/tracking.js:14-21, 178-179 | VERIFIED | six `STATES` with id/name/label/title/step/icon exactly as §5.7; `if (!res.ok) return { notFound: true }` (quirk 2) |
| ls-home@fe4b056:netlify/functions/form.mjs:19-22, 39, 244-250 | VERIFIED | `path: '/api/form'`, `MAX_BODY = 20_000`, `reader` slices to `max` before checks (truncation quirk), JSON mode from `Accept`, 413/303 as described |

Also opened, not counted: ls-cms@dev:js/components/data/user-data.js:22-53 (the six states, ids 0–5, same names/labels as `tracking.js`) and `public/_headers:1-7` at `9b78785` (CSP string identical to §6.4).

## 7. Planned tests

| ID | Tests (layer · assertion · tag) |
|---|---|
| PUB-01 | E2E `[PUB-01]` walks the 26 URLs of §3.1: 200, `<title>`, canonical, one `<h1>`, `data-page`, JSON-LD types of §3.2; integration `@Tag("PUB-01")` diffs each rendered page against ls-home@9b78785 after normalisation, allowing only the ADR allow-list |
| PUB-02 | integration `@Tag("PUB-02")`: change the phone in the business configuration → header, footer, JSON-LD, `config.js`, security.txt, error page all change; unit: hours formatted in the three legacy formats |
| PUB-03 | integration: sitemap = exactly the 24 indexable URLs, order and priorities of §6.2, validates against the sitemap XSD; `X-Robots-Tag` on `/area-clienti/`, `/gestione/`, `/api/`, `/mono/`; manifest and security.txt served |
| PUB-04 | integration table-driven: ten `_redirects` rows, `/index.html` → `/`, `/zone` → `/zone/`, `www` → apex (301 + `Location`); E2E `[PUB-04]`: `/#chisiamo`, `/#contattaci`, `/#tracking`, `/index.html#chisiamo` land on their pages, `/#servizi` stays |
| PUB-05 | integration: every public route sends the §6.4 headers and CSP; `/fonts/*` immutable, `/img/*` one week; E2E: a CSP-violation listener fails the run |
| PUB-06 | unit `@Tag("PUB-06")` validator: field table of §5.5, check order, over-long/6-digit CAP → 422; integration: the 12 server cases of §5.6 in JSON and native mode (incl. 413, honeypot, `unknown_form`); E2E `[PUB-06]` wizard with JS on and off, prefill `?servizio&tipo&da&a&pallet`, summary rows, WhatsApp text, confirmation copy |
| PUB-07 | same layers for the contact form (`messaggio` ≥ 10, `oggetto` free text ≤ 100, optional phone) |
| PUB-08 | unit (Node test on `eta.js`): every row of the §5.3 test table; integration: server-side zone dataset equals `eta.js`; E2E `[PUB-08]`: `/zone/?cap=20831`, legend hover highlight, `eta:region` tooltip, Maps facade loads only after click and remembers `ls_maps_ok` |
| PUB-09 | E2E: unknown URL → 404 page, status 404, `noindex`; `/grazie/` `noindex, follow` |
| PUB-10 | quality: Lighthouse CI ≥ 95 ×4 on `/`, `/servizi/spedizione-pallet/`, `/preventivo/`, `/tracking/`, `/contatti/`; axe no serious/critical; no console errors |
| PUB-11 | integration: valid quote → one `lead` (`NEW`, `form=PREVENTIVO`) + one outbox row in one transaction; honeypot → neither; `retain_until` set |
| TRK-01 | E2E `[TRK-01]` per state (six cards: status line, step, icon), unknown status rendered raw, `Non gestito` neutral message, not-found (non-2xx), network error (aborted route), recent codes |
| TRK-02 / TRK-03 / TRK-04 | unit matching (trim, inner spaces, case); contract: answer keys ⊆ {`status`, `updatedAt`, `eta`, origin/destination city, `events{time,status,location}`}; integration: over-limit → 429 |
| API-01 / API-03 | contract fixtures FX-01…FX-08 of 02 §10.4 on apex and `app.` hosts; `/mono/*` no longer proxied: `GET /mono/email-confirm` → 410 page, logo served |
| ADM-10 | seed test: `tracking_state` rows equal the ids/names/labels of `tracking.js` STATES and 02 §9.1 |
| EML-08 | integration: subject formats, Reply-To = customer, the plain-text sample of §5.6 (sections, omitted empty rows, footer), WhatsApp button rule for Italian mobiles |
| NFR-02 / NFR-03 | integration: `/api/form` refused with a foreign `Origin`; HTMX config meta present, no `unsafe-inline`; E2E content test: privacy/cookie pages name the processors and the session/CSRF cookies |

## 8. Notes for the planner

* **Ownership (10 §2):** `site` owns PUB-01…11 and the EML-08 template content (sender via `notifications`); `tracking` owns TRK-*, API-01/02 and the enumeration seed (ADM-10, shared with `backoffice` for ADM-06); `platform` owns headers/CSP, rate limits, the Origin/Referer check and the business configuration; `notifications` owns the outbox.
* **Phase 1 foundations this chapter needs:** static assets copied from ls-home@9b78785 (§8.1); the four fragments plus a page-key helper reproducing sync.mjs:92-98; business configuration bean feeding fragments, JSON-LD, generated `config.js`, security.txt; `SITE_URL`; CSP/header filter set by the app (not the proxy) so tests can assert it; route registry for sitemap and `noindex`; `lead` table and outbox in the first migrations; rate limiter keyed on the proxy's `X-Forwarded-For`; HTMX config meta; Italian message bundles seeded with the verbatim strings of §5.5–5.7; CI jobs for Lighthouse, axe, HTML diff.
* **Risks:** the HTML parity diff is brittle (whitespace, Thymeleaf attribute order) — define the normalisation early; any framework error page or CSRF meta script breaks the CSP; speculation rules will prerender `/area-clienti/` once the link is same-origin; zone data lives in four places (JS, HTML, SVG, server) and the price list disagrees (07 §2.4); OG images have no generator; `italia-copertura.svg` as `<img>` under the strict CSP is unverified; `btn-primary` has no CSS rule (keep it so); the mobile menu needs JS (accepted quirk); blog dates and stats are unverified content (Q-31); the SES identity will also write to customers; Lighthouse in CI without Docker.
* **For ADR 0001 / Gate 0 summary:** caching rule for CSS/JS/HTML (recommend 06 §8.5: `no-cache` + ETag); `tracking.js` on 429/5xx (recommend the small change: show the "error" block, listed on the allow-list); `numero` integer-only server-side (recommend the fix, with 08 column kept); speculation-rule exclusion added to the PUB-01 allow-list; server-side result for `/zone/?cap=` (recommend yes) and `/tracking/?code=` (recommend no in M1); ask the owner the Q-31 items including Saturday hours on two pages and the footer credit; `http` and `www` → apex at the proxy, headers in the app; confirm Q-39 apex-only CORS and Q-20 retirement of the Netlify site.
