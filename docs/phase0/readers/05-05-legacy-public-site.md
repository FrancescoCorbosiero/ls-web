# Phase 0 reader — chapter 05 · Legacy public site (`ls-home@v1`)

Reader of `docs/brief/05-legacy-public-site.md` (573 lines, read in full on 2026-10-16). Citations without a ref point to `ls-home@v1` (= `8201716`), as in the chapter.

## 1. Scope

The chapter documents the old Bootstrap-template site (branch `v1`, one adapted home page, three orphan template pages, a parked tracking box): its URLs and anchors, its external links (S3 portal and tracking hosts, WhatsApp, Instagram, Maps), the single backend call `POST /mono/api/order/trace/state`, the copy and business claims, the brand assets, 15 quirks/security findings (LPS-S1…S5, LPS-01…10) and 9 unknowns (LPS-U1…U9). It is a proxy for the live site (a rewrite whose code is unavailable), not a copy of it; the public pages themselves are ported from chapter 06.
Reading map: **site** module → §2 (URL inventory), §4.1 (copy); **tracking + compatibility** → §3.7 (what is kept/changed); the cut-over/migration runbook (MIG-03) also needs §2.5 (S3 hosts), §5 LPS-S4 and §6.3/§6.4 (Q-29, Q-46); Phase 1 foundations need §2.1–2.2 (host/redirect routing) and §5 LPS-S3/LPS-S5 (no environment hosts in pages, consent).

## 2. Requirement IDs touched

| ID | Sections that specify or constrain it |
|---|---|
| PUB-04 | §2.1 paths (incl. `/index.html` → `/`, 404s), §2.2 anchors, §2.5 hosts, §6.3 LPS-U1/U5 (crawl, Q-29) |
| PUB-02 | §2.4 contact points, §5 LPS-04 (one WhatsApp number from configuration), §6.1 company data |
| PUB-01 | §4.1 copy (reference only), §4.2 claims (Q-31), §4.3 assets, §5 LPS-07 (brand name, Q-37), §6.2 live-site copy |
| PUB-08, NFR-03 | §5 LPS-S1 (Maps key dropped), LPS-S5 (consent-gated map, fonts, privacy pages) |
| PUB-09 | §2.1: `/tracking_section.txt`, repository files and v1 assets answer 404 |
| PUB-10, NFR-01 | §5 LPS-05 (a11y/SEO), LPS-08 (performance): "the new site already does" |
| TRK-01 | §3.3–3.5 (what the legacy box did wrong), §3.7 "do not port the v1 box" |
| TRK-02 | §3.2 body "no trim" (v1), §3.7, §6.3 LPS-U3 (case rule) |
| TRK-03, TRK-04 | §3.7 fixes (no personal data; rate limit) |
| API-01 | §3.2 the call, §3.7 keep the contract, §6.1–6.2 (live caller, origin), LPS-U3, LPS-U9 |
| API-02 | §3.2 six states and labels (the `status` label must be one of them) |
| API-03 | §2.5 (`app.`, `ordini.` per chapter 10 §7); S3 hosts stay outside the app |
| NFR-02 | §5 LPS-S1 (no key), LPS-S2 (escaping), LPS-S3 (HTTPS only, no UAT host), LPS-S4 |
| MIG-03 | §2.5 S3 redirects (owner), §5 LPS-S4 never delete buckets, §6.3 LPS-U4/U5/U7, Q-29/Q-46 |
| ENH-05 | §3.7 "extra response fields are safe" (bounded by TRK-03) |

## 3. Rules to keep (M1 parity)

| Rule | Citation (chapter 05) | ID |
|---|---|---|
| `POST /mono/api/order/trace/state`, JSON `{"codice": …}`, no auth, no cookies; answer read as `status` | js/custom.js:67-74, 76-83; owner's curl (§6.1) | API-01 |
| Known code → 2xx with a non-empty string `status` = the label; never 2xx with missing/`null` `status` (v1 prints "undefined") | js/custom.js:72-73; §3.4; §3.7 | API-01 |
| Unknown or temporary code → `200 {"status":"Non gestito"}`; empty code → 400 (as 12a §1.4 D1) | §3.2 table "Status code for an unknown code" | API-01, TRK-02 (Q-38) |
| The six labels: "Non gestito", "Presa in carico", "Transito", "Terminal destinatario", "Assegnato", "Consegnato" | ls-cms@dev:js/components/data/user-data.js:22-53 (§3.2) | API-02, ADM-10 |
| CORS incl. preflight (`OPTIONS`, `Content-Type`) for `https://logisticasanchez.com`; v1 sends only `Content-Type`, no `Accept` | js/custom.js:79-81; §3.2 CORS row | API-01 |
| Accept codes as the legacy clients send them: untrimmed (v1), spaces removed (new site) | js/custom.js:49, 69 | TRK-02 |
| Serve the endpoint on `app.logisticasanchez.com` and the apex | §3.7; §6.1 | API-01, API-03 |
| 301: `/index.html` → `/`; `/about(.html)` → `/chi-siamo/`; `/service(.html)` → `/servizi/`; `/contact(.html)` → `/contatti/`; the four guessed `.html` names | §2.1; ls-home@fe4b056:public/_redirects:2-11; index.html:71, 85, 416 | PUB-04 |
| Anchors: `#chisiamo` → `/chi-siamo/`, `#contattaci` → `/contatti/`, `#tracking` → `/tracking/` client-side on `/` only; `#servizi` stays (new home keeps `id="servizi"`); fragment survives the `/index.html` 301 | §2.2; ls-home@fe4b056:public/js/main.js:218-224; public/index.html:243 | PUB-04 |
| `/tracking_section.txt`, `/README.md`, `/.gitignore`, `/css/style.scss`, `/css/style.css.map`, v1 template/brand assets → 404 (unless the Q-29 crawl finds hot-links) | §2.1 | PUB-09, PUB-04 |
| Keep the S3 bucket names `sanchez-logistica-fe`, `-tracking` (and `-cms`, 12d) for good; turn them into redirects (owner decision) | §2.5; §5 LPS-S4 | MIG-03 |
| One official phone/WhatsApp number, e-mail, address, Instagram handle, hours and VAT number from configuration (values in §2.4, §6.1) | §2.4; §6.1 | PUB-02 |

## 4. Deliberate fixes (each needs an ADR)

| Fix (legacy not reproduced) | Source | Proposed ADR title |
|---|---|---|
| Public tracking UI from the new site's widget, not the v1 box (trim, Enter key, loading state, "not found" ≠ "service down", no stale heading) — LPS-01, LPS-03, LPS-09 | §3.5, §3.7; 09 TRK-01 | "Public tracking page: new-site widget replaces the legacy box" |
| Output written as text, escaped server-side (DOM XSS in v1) — LPS-S2 | §5 | "All output escaped; tracking result rendered as text" |
| HTTPS only; endpoints from per-environment configuration; no UAT/S3 hosts in public pages — LPS-S3 | §5 | "No environment hosts in public pages; HTTPS-only external links" |
| CORS on the compatibility endpoint: only `https://logisticasanchez.com`, `POST, OPTIONS`, `Content-Type`, **no credentials** (legacy: list + credentials true, all methods) | §3.7; 02 §10.2 #5, FX-07; Q-39 | "CORS policy of the compatibility endpoint" |
| Code matching trimmed, spaces removed, case-insensitive by default (legacy: raw SQL equality per collation) | §3.7; 09 TRK-02; Q-03 | "Tracking code normalisation" |
| Per-IP rate limit with 429 on tracking (legacy: none, 12a §2.15); clients show any non-2xx as "not found" | §3.7; 06 §5.7 | "Rate limiting of public tracking and the 429 trade-off" |
| Answer whitelist: never a field with personal data or a document link (`pod`) | §3.7; 09 TRK-03 | "Public tracking answer schema" |
| Google Maps JS API key and callback dropped; consent-gated iframe; self-hosted fonts; privacy/cookie pages; VAT in footer — LPS-S1, LPS-S5, LPS-02 | §5 | "No third-party resources before consent; no Maps API key" |
| Template pages dropped and 301'd (LPS-06); `/index.html` 301 added beyond `_redirects`; v1 leftovers 404 | §2.1, §4.4 | "Legacy URL redirect map (PUB-04)" |
| Single WhatsApp number from configuration; second number dropped unless Q-15 says otherwise — LPS-04 | §2.4, §5 | "Business contacts as configuration" (Q-15 seam) |
| Brand "Logistica Sanchez S.r.l." + "Il pallet espresso"; typos not reproduced — LPS-07 | §4.1, §5; Q-37 | covered by the copy ADR / Q-37 decision |

## 5. Unknowns by ID (recommended default)

* **Q-03** `status` label vs name; case rule → keep label; match trimmed, case-insensitive (12a D1 confirms label).
* **Q-06** code format → staff type codes; uniqueness trimmed/case-insensitive; no generator.
* **Q-15** second WhatsApp number (index.html:185) → not carried over; only the official number.
* **Q-16** DNS/hosting of apex, `ordini.`, `dashboard.` → new VPS; owner changes A records.
* **Q-20** retire Netlify site → yes, after DNS switch (it calls `app.` cross-origin until then).
* **Q-23** Maps key in public repo (LPS-S1, LPS-U8) → check ownership in Google Cloud; never reuse; urgent list (ch. 11 §0).
* **Q-29** live URL inventory, old hosts in print/e-mail (LPS-U1, LPS-U5) → crawl before cut-over (owner go-ahead, Q-27); 301 everything found; never delete buckets.
* **Q-31** claims (§4.2: 24–72 h, direct distribution Lombardy/Turin/Verona, free quote, "2000+" etc.) → publish only what the owner confirms.
* **Q-37** "Sanchez Logistica" → "Logistica Sanchez" (LPS-07) → yes.
* **Q-38** TEMP codes answer "Non gestito" → yes, as today.
* **Q-39** CORS origins (LPS-U9) → apex only until probe P-03.
* **Q-46** AWS leftovers (S3 buckets, EB UAT) → inventory; keep bucket names as redirects (owner).
* **LPS-U1** live URL list → Q-29. **LPS-U2** live tracking UI → look at the live site; replaced anyway.
* **LPS-U3** exact `trace/state` answers → mostly answered by 12a §1.4 D1 (label; 200 "Non gestito"; 400 empty; `StatusRepr` has only `status`); residual: production collation → P-02.
* **LPS-U4** S3 tracking app source/online? → owner, AWS console; redirect its host. **LPS-U5** buckets still serving, hot-linked assets → owner.
* **LPS-U6** live contact/quote forms and numbers → look at the live site (Q-15). **LPS-U7** analytics/consent/Maps key on live site, DNS → owner (Q-16).
* **LPS-U8** was v1 ever published; key ownership → owner (Q-23). **LPS-U9** backend CORS list → 12a §2.10 gives the committed list (apex, `www`, `app.`); server value unknown → P-03.
* **LPS-S1…S5, LPS-01…10** → decisions as in §5 (Fix/Drop); none kept. **OP-S1** (login-less portal) referenced in §4.1 → fixed by CUS-01. **CU-01/CU-02/CU-14** via Q-03/Q-39.

## 6. Contradictions and spot checks

1. **Internal/stale vs 12 (12 wins):** §3.2 still says whether `status` is label or name "is unknown (Q-03)" and "other response fields (unknown)", while the same section and 12a §1.4 D1 (`OrderController.java:36-38`, `status(state.getLabel())`) settle it: label, no other field. LPS-U3/LPS-U9 in §6.3 are likewise partly answered by 12a §1.4 D1 and §2.10.
2. **05 §2.1 vs 06 §8.1 / 10 §2:** §2.1 says `/css/…`, `/js/…`, `/fonts/…` answer 404, but the new site serves its own `/css`, `/js`, `/fonts`, `/img`. Read as "the v1 file names" (`/css/style.css`, `/js/custom.js`, `/images/*`); the redirect ADR must list files, not directories.
3. **05 §2.5 vs 12d §1 (host table line 127):** chapter 05 names two S3 buckets (`-fe`, `-tracking`); 12a §2.10/12d add `sanchez-logistica-cms`. The LPS-S4 rule ("never delete") must cover all three.
4. **05 §2 omits** `www` → apex and the legacy delivery e-mail link `https://www.logisticasanchez.com/#tracking` (12d §1, `templates/tracking-state.html:48`); PUB-04 and 06 §6.1 cover them. Also `api.logisticasanchez.com` (§6.1, a comment in ls-cms) is not in the DNS list of 10 §7/MIG-03; 08 §4.9 says to remove dangling records.
5. **05 §3.7 vs 06 §5.7 (not a contradiction, a trade-off):** TRK-04's 429 is shown as "not found" by every known client; 06 §5.7 offers a small change to `tracking.js`, which would be a PUB-01 allow-list difference.
6. **PROMPT.md:** none; its "Legacy at a glance" matches §3.7 and 12a D1.
7. Minor: §3.6 cites `tracking_section.txt:1-38`; the file has 37 lines.

Spot checks (legacy/, never quoting secrets or personal data):
* `ls-home@v1:js/custom.js:67-74` — **VERIFIED**: line 68 UAT host + `/mono/api/order/trace/state`, 69 `{codice: codice}`, 72-73 `response.json()` → `.status`; also 49 no trim, 56-57 `innerHTML`, 59-62 "Codice non valido", 94 `onclick`.
* `ls-home@v1:index.html:166, 233, 372` (tracking → S3 tracking host) and `138, 251, 291` (portal → S3 fe host) — **VERIFIED**; 138/251/372 `target="_blank"`, 291 same tab; 185 second WhatsApp link with `id="whatsapp-contact"`.
* `ls-home@fe4b056:public/_redirects:1-11` (checked at `9b78785`) — **VERIFIED**: line 1 comment, 2-11 the ten 301s, no `/index.html` rule; extra `/mono/*` proxy rule at 13-16 as 06 §6.3 says.
* `ls-home@fe4b056:public/js/main.js:218-224` (at `9b78785`) — **VERIFIED**: `redirectLegacyAnchors` maps the three anchors, runs only when `location.pathname === '/'` and no element with that id exists; `public/index.html:243` has `id="servizi"`.
* `ls@migration:…/api/controller/OrderController.java:33-39` and `…/business/service/crud/SpedizioneService.java:116-119` — **VERIFIED**: `status(state.getLabel())`; `findTrackingByCodice(codice).orElse(TrackingState.NON_GESTITO)` (via `OrderService.java:158-159`); `OrderDetailReq.java:16-18` `@NotEmpty @Size(min=1)`.

## 7. Planned tests

| ID | Tests |
|---|---|
| PUB-04 | Integration `@Tag("PUB-04")`, table-driven from §2.1 (status + `Location`): `/index.html`→`/`, `/about(.html)`, `/service(.html)`, `/contact(.html)`, the four guessed `.html`; `Host: www.…` → 301 apex keeping path; `Host: ordini.…` → 301 `/area-clienti/`; `Host: dashboard.…` → 301 `/gestione/`; the Q-29 crawl list loaded from a data file (URLs only). E2E `[PUB-04]`: `/#chisiamo`, `/#contattaci`, `/#tracking`, `/index.html#chisiamo` land on their pages; `/#servizi` stays on `/` with `#servizi` visible. |
| PUB-09 | Integration `@Tag("PUB-09")`: `/tracking_section.txt`, `/README.md`, `/.gitignore`, `/css/style.scss`, `/css/style.css.map`, `/js/custom.js`, `/images/fevicon.png` → 404 page, status 404. |
| PUB-02 | Integration `@Tag("PUB-02")`: every `api.whatsapp.com/send?phone=` on rendered public pages uses the one configured number; changing it changes all pages; no second number. |
| NFR-02 | Quality `@Tag("NFR-02")`: scan of static resources and rendered pages — no `maps/api/js?key=`, no `elasticbeanstalk.com`, no `s3-website`, no `http://` external link. |
| PUB-08, NFR-03 | E2E `[PUB-08]`: network intercept on load — no request to Google Maps/Fonts before consent; CSP `connect-src 'self'` (PUB-05 header test). |
| TRK-01 | E2E `[TRK-01]`: six states + unknown code (neutral "no updates yet"); Enter submits; `?code=` deep link; v1 strings absent is not asserted (reference only). |
| TRK-02 | Unit `@Tag("TRK-02")`: `" ABC123 "`, `"abc123"`, `"A B C 123"` match `ABC123`; whitespace-only → no lookup (400 at the API); TEMP code → "Non gestito"; Q-03 seam switchable. |
| TRK-03 | Contract `@Tag("TRK-03")` FX-10: response keys ⊆ {status, updatedAt, eta, origin, destination, events[time,status,location]}; never `pod`; `ENH-05` on keeps it. |
| TRK-04 | Integration `@Tag("TRK-04")` FX-09: over the per-IP limit → 429 JSON; another IP unaffected. |
| API-01 | Contract `@Tag("API-01")` FX-01…FX-08: label per state; unknown → `200 {"status":"Non gestito"}`; `{}`/`""` → 400; padded/lower-case code matches; `status` never null; `Content-Type: application/json`; request without `Accept` (v1 style) → 200; unknown extra request field tolerated (record in ADR); preflight from apex → 204 with Allow-Origin/Methods POST/Headers content-type/Max-Age, no credentials; from `https://example.org` → no Allow-Origin; same answers with `Host: app.…` and apex. |
| API-02 | Contract `@Tag("API-02")` FX-11/12 exact arrays; cross-check: every label API-01 can return is in `statoTracking`. |
| API-03 | Integration `@Tag("API-03")`: compatibility paths answer on `Host: app.…`; retired paths 410 (owned by tracking/compat). |
| MIG-03 | Rehearsal record: S3 bucket redirect rules (fe, tracking, cms) verified by the owner; DNS zone export shows no record to S3/EB/Netlify/`api.`; crawl-based 301 list run through the PUB-04 table test on staging. |
| PUB-01, PUB-10 | Allow-list test (06); Lighthouse/axe on `/tracking/` already in PUB-10; v1 copy reused only via Q-31 entries. |

## 8. Notes for the planner

* **Ownership (10 §2):** `site` — PUB-04 redirect table (paths + host rules), PUB-09 404s, PUB-02 business-data config, PUB-08 consent; `tracking` — TRK-01…04, API-01/02 and the CORS proposal (security config is shared → integrator); `platform` — host-aware routing (apex, `www`, `app.`, `ordini.`, `dashboard.`), rate limiter, CSP/headers; `migration`/infra — MIG-03 S3 redirect and DNS steps (owner executes).
* **Phase 1 foundations needed:** a redirect table as data (path → target, host → target) so the Q-29 crawl output is appended without code; one business-data configuration bean (number, e-mail, address, VAT, hours, Instagram) feeding fragments and `config.js`; CORS extension point per endpoint (Q-39 seam, no credentials); rate-limit infrastructure shared with CUS-01/PUB-06; static resources from `ls-home@9b78785` under the new site's directory names; `connect-src 'self'`.
* **Risks:** live URL inventory unknown until the crawl (LPS-U1); three S3 bucket names in public links forever and the AWS account unknown (LPS-S4, Q-46); leaked Maps key in a public repo (Q-23, urgent list); 429/5xx shown as "not found" by all clients; a live-site fetch with `credentials: 'include'` would break under "no credentials" (unknown, LPS-U2; moot after cut-over since the apex becomes same-origin); hot-linked v1 logo (`/images/logistica-sanchez-logo.jpg`) may need a 301 instead of 404 (LPS-U5); `api.` possibly a dangling DNS record.
* **ADR 0001 / Gate 0 summary:** record the `/index.html` 301 and the v1 404 list as additions to the new site's behaviour; the 429 policy (parity vs small `tracking.js` change); CORS no-credentials; code normalisation default (Q-03); bucket redirects as an owner decision touching production (Q-29, Q-46); Q-15, Q-31, Q-37 defaults applied; LPS-U3/U9 marked as answered by chapter 12 except collation/P-02/P-03.
