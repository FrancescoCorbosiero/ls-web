# Phase 0 legacy reader — `ls-home-v1` (old public site) and the old URLs

Read on 2026-10-17 against `legacy/ls-home-v1` = `ls-home@v1` = `8201716` (single-commit
shallow clone, `git rev-parse HEAD` = `82017161a0…`). Citations without a ref point to that
checkout. Map used: `docs/brief/05-legacy-public-site.md` (573 lines, read in full).
Personal data (phone numbers, e-mail, street address, VAT, handles) and the Maps key are
referred to by place only, never copied.

## 1. What was read (paths)

* `legacy/ls-home-v1`: `index.html` (467 lines, in full), `js/custom.js` (94 lines, in
  full), `tracking_section.txt` (38 lines, in full), `about.html`, `contact.html`,
  `service.html` (targeted greps on every line the brief cites), `css/style.scss:1-12`,
  `css/style.css:1-5, 539-640, 719-724`, `css/font-awesome.min.css:4`, `css/bootstrap.css:2`,
  `js/bootstrap.js:2, 560-575`, `js/jquery-3.4.1.min.js:1`, `README.md` (bytes),
  `.gitignore`; `file`/`identify` on every file of `images/`; `git ls-tree -r HEAD`.
* `legacy/ls-home` (`9b78785`, shallow, 14 commits): `public/_redirects:1-16`,
  `public/js/main.js:200-240`, `public/index.html:243`, `netlify.toml`, page list under
  `public/`; history checks on `0732746` and `bca0776` (`git show --stat`, `git diff --stat
  0732746 8201716`, `git grep` on both refs).
* `legacy/ls`: one `git grep` for `#tracking` and the S3 tracking host, with
  `':!logs' ':!api' ':!.env*' ':!local-setup.sh'` (one hit, in an e-mail template).
* Brief: chapter 05 in full; 09 (PUB-*, TRK-*, API-*); 11 (Q-03, Q-06, Q-15, Q-16, Q-20,
  Q-23, Q-27, Q-29, Q-39); 06 §5.2, §6.3, §6.8; 10 §7; 02 §10.3; 12d §1 hosts table;
  `docs/phase0/readers/05-05-legacy-public-site.md` (to avoid duplicating it).
* Maps key check, run exactly as instructed:
  `git -C /home/user/ls-web/legacy/ls-home-v1 grep -c 'maps/api/js?key=' HEAD` →
  `about.html:1`, `contact.html:1`, `index.html:1`, `service.html:1` (4 files, 1 each).
  The same count holds at the template import (`ls-home` clone, `git grep -c … 0732746`).

## 2. Verified facts (claim → status → evidence)

| Chapter 05 claim | Status | Evidence |
|---|---|---|
| At a glance: 1 adapted page + 3 untouched template pages + `tracking_section.txt`; 29-file template import | VERIFIED | `git ls-tree -r HEAD` (34 files); about/contact/service.html:16 `<title> Carint </title>`, :72 "Carint"; ls-home `git show --stat 0732746` = 29 files |
| One backend call, `POST {HOST}/mono/api/order/trace/state`, body `{codice}`, reads `status`; no page has the button, so the last statement throws (LPS-01) | VERIFIED | js/custom.js:67-74, 76-83, 94; `grep tracking-btn-id *.html` → none (only tracking_section.txt:17) |
| No form sends data; template forms have no handler and no `name` | VERIFIED | contact.html:126 `<form action="">`; `name=` occurs only on `<meta>` lines 9-13 in all four pages; about/contact/service.html:232/245/298 `<form action="#">` |
| Maps JS API key in all four pages, same since template import (LPS-S1) | VERIFIED (count only) | the `git grep -c` above on HEAD and on `0732746` |
| §1.1 history `bca0776` (README UTF-16 LE), `0732746` (template import; title and plain-text contacts with empty links at index.html:16, 49, 55, 61) | VERIFIED | ls-home `git show bca0776:README.md` starts `ff fe`; `file README.md` = UTF-16 LE, CRLF; `git show 0732746:index.html` lines 16, 46-63 (`href=""`) |
| §1.1 rows `203c11a` (= `origin/dev`), `8d6fa0a`, `e5b9298`; "`custom.js` identical in `dev` and `v1`"; every `ls-home@dev:…` citation; LPS-10 | NOT FOUND (locally) | neither clone has these objects or `origin/dev`/`origin/v1` (`git rev-parse` fails); only `bca0776`, `0732746`, `8201716` exist. Consistent evidence: `0732746:js/custom.js` has 40 lines and no `trace/state`; `git diff --stat 0732746 8201716` touches only `index.html`, `js/custom.js`, `tracking_section.txt` and images |
| §1.2 Bootstrap 4.3.1 local; jQuery 3.4.1 local; Popper 1.16.0 jsDelivr + SRI; Owl 2.3.4 cdnjs and no `.owl-carousel` anywhere; Font Awesome 4.7.0 twice on the home; Roboto `<link>` + Lato/Open Sans `@import`, only Open Sans used; colours in `style.scss:3-5`; no build/hosting config | VERIFIED | css/bootstrap.css:2; js/bootstrap.js:2; js/jquery-3.4.1.min.js:1; index.html:20, 23, 26, 29-30, 449, 451, 454, 456; `grep owl-carousel *.html` → none; css/style.css:1, 3; css/style.scss:3, 5; tree has no package/CI file, `.gitignore` = `/.idea/` |
| §1.2 carousel 5 s, pause on hover (Bootstrap defaults) | VERIFIED | js/bootstrap.js:567-572 (`interval: 5000`, `pause: 'hover'`); index.html:116 `data-ride="carousel"` |
| §1.3 sections and line ranges of the home (top bar 46-65 … footer 438-445); home links only to `index.html` (71, 85, 416) and anchors; template pages link to each other (about.html:82-93), nothing links to them | VERIFIED | index.html:46-65, 70-106, 112-205, 212-260, 267-304, 308-310, 313-357, 362-433, 438-445; about.html:83-92; `grep -l 'about.html' index.html` → none |
| §2.1 `/index.html` not in the new site's `_redirects`; `about/service/contact(.html)` → 301; `servizi/contatti/tracking/preventivo.html` absent from v1 and the template import | VERIFIED | ls-home@9b78785:public/_redirects:1-16 (no `/index.html` rule); ls-home `git grep -l -E 'servizi\.html|contatti\.html|tracking\.html|preventivo\.html' 8201716 0732746` → none (`dev` not checkable) |
| §2.2 anchors `#servizi` (212; menu 88, Indice 422), `#chisiamo` (267; 91, 419), `#contattaci` (313; 94, 425); `#tracking` only in the parked snippet; new home keeps `id="servizi"`; client-side map on `/` only | VERIFIED | index.html as cited; tracking_section.txt:1; ls-home@9b78785:public/index.html:243; public/js/main.js:220-224 (`location.pathname === '/'`, `location.replace`) |
| §2.3 external links and line numbers (S3 "fe" 138, 251, 291, 372; S3 "tracking" 166, 233, 372; main WhatsApp 47, 188, 337, 391, 406; second WhatsApp 185 with `id="whatsapp-contact"`; mailto 53, 342, 397; Instagram 189, 332, 407; Maps place 59; embed 351 `loading="lazy"`; info address `href=""` 385; html.design credit about:250, contact:263, service:316); tab behaviour (`target="_blank"` on 47, 59, 138, 251, 372-portal; none on 166, 233, 291, 372-tracking) | VERIFIED | index.html as cited; `grep -c 'tel:' *` → 0 (no `tel:` link) |
| §2.4 numbers and placeholders: main number shown at 50, 340, 394; second number never shown, 185 only; template "+01…" at about/contact/service.html:49 and :166/:179/:232; the template placeholder e-mail at :55 and :172/:185/:238; e-mail 56, 345, 400; address 62, 388; Instagram handle 335 | VERIFIED | all lines checked by grep |
| §3.2-3.6 tracking client: `codice` raw (49), empty → hide (50-51), `innerHTML` (56-57), catch → "Codice non valido" (59-62), non-2xx → `Error("Rest call failed")` (85-89), UAT host over `http://` (68); snippet verbatim with `hidden` | VERIFIED | js/custom.js:43-94; tracking_section.txt:1-38 byte-identical to the block quoted in §3.6 |
| §4.1 copy and line numbers (slogan 124-125, 287, 16, 441-442; slides 123-140, 153-168, 181-189; services 217-253; Chi Siamo 274-293; Contattaci 322; Info 371-375; labels 85-94, 413-427) | VERIFIED | index.html as cited; "Gratutito" at 183, "In tutta italia" at 154 |
| §4.3 assets: logo 1080×400 JPEG; `fevicon.png` 512×512; `slider-bg.jpg` 1920×1080; `about-img.jpg` 1872×2048, 736,543 B; `insta.png` is WebP, 622,302 B; `whats.webp`, `email.webp`; `email.jpg` unused; `track-bg.jpg` only in a comment; `s2/s4.png` template pages only; `style.css.map` present | VERIFIED | `identify images/*`; `grep -rn 'email.jpg'` → none; tracking_section.txt:3; service.html:148, 188 |
| §4.4 leftovers: empty metas 11-13; "Address" 382; malformed comment 74; commented Login/search 96-103; `nav-bullet.png` referenced but absent (about:209, contact:222, service:275) | VERIFIED | index.html as cited; `ls images/` has no `nav-bullet.png` |
| LPS-02 New York centre, `#googleMap` only in contact.html | VERIFIED | js/custom.js:34-40; `grep -rn 'id="googleMap"'` → contact.html:150 only |
| LPS-05 no `lang`, empty description, title with leading space, `href=""` 385, `href="#"` 442; alt "Sanchez-Logistica-Whatsapp" | VERIFIED, one precision | index.html:2 `<html>`, 12, 16, 385, 442; the alt is on all three tiles 332, 337, 342 (not only Instagram and e-mail) |
| LPS-09 CSS targets `form input`/`form button`, markup has no form | VERIFIED | css/style.css:578-637 (`.track_section form …`); tracking_section.txt:16-19 |
| At a glance: `ls-home` is public on GitHub | NOT VERIFIABLE here | GitHub API returns 403 from this session (repository not attached); not re-checked, no `add_repo` requested |
| LPS-S1 locate command `git -C ls-home grep -c "maps/api/js?key=" origin/v1` | DIFFERS | `origin/v1` does not exist in either local clone; the chapter 11 (Q-23) form with `HEAD` on `legacy/ls-home-v1` is the one that works |
| Chapter 09 PUB-04: delivery e-mails link `https://www.logisticasanchez.com/#tracking` | VERIFIED | ls@migration:src/main/resources/templates/tracking-state.html:48 |

## 3. Discrepancies and corrections to the brief (for `docs/plan.md`)

1. **Chapter 05 §5 LPS-S1, locate command.** `origin/v1` is not a ref in the legacy
   checkouts (single-commit clone of `8201716`; `ls-home` has only the
   `claude/determined-lovelace-ggeitw` branch). Replace with the chapter 11 Q-23 form:
   `git -C legacy/ls-home-v1 grep -c 'maps/api/js?key=' HEAD` (evidence: §1 above).
2. **Chapter 05 §1.1, §3.1, §3.4, LPS-10 cite `ls-home@dev` (`203c11a`), `8d6fa0a`,
   `e5b9298`.** None of these commits is fetchable from the checkouts listed in `PROMPT.md`;
   every claim sourced only there is unverifiable in this session (status NOT FOUND, not
   contradicted). The plan should state that the v1 tracking box history is taken on trust
   from the brief, or that the integrator fetches `origin/dev` read-only if a decision ever
   depends on it (none in M1 does: the box is not ported, TRK-01).
3. **Chapter 05 LPS-05.** The alt text "Sanchez-Logistica-Whatsapp" is on all three contact
   tiles (index.html:332, 337, 342); only for 332 and 342 is it wrong. Cosmetic.
4. **Chapter 05 §2.1, `/fonts/…` row.** The requested URLs are
   `/fonts/fontawesome-webfont.{eot,woff2,woff,ttf,svg}?v=4.7.0` (css/font-awesome.min.css:4):
   `.eot` and `.svg` are not in the repository (`ls fonts/`: ttf, woff, woff2 only). The 404
   rule for old assets must match the path ignoring the query string.
5. **Chapter 05 §4.3, about photo.** It is *not* the template's image: the template import
   shipped a 92,656-byte `about-img.jpg`, replaced in v1 by the 736,543-byte forklift photo
   (ls-home `git diff --stat 0732746 8201716`). The author chose it; whether it is the
   company's own photo is unknown. The brief's "stock-looking (inferred)" stands, but the
   owner should be asked before it is reused or dropped (fold into Q-31).
6. **Chapter 05 §2.2, `#tracking` row.** The anchor is not only a v1 relic: the production
   backend's delivery e-mail links `https://www.logisticasanchez.com/#tracking`
   (ls@migration:src/main/resources/templates/tracking-state.html:48). Chapter 09 PUB-04
   already carries this; chapter 05 should cite it, because it makes the `www` → apex 301
   (fragment preserved) plus the client-side `#tracking` → `/tracking/` redirect
   M1-critical for e-mails already in customers' inboxes, and it is evidence (not proof)
   that the live site has an in-page `#tracking` section (LPS-U2).

## 4. Facts the brief does not mention

* v1 never names `logisticasanchez.com`, `www`, Netlify or CloudFront (grep over all text
  files): the only hosts are the two S3 website endpoints, the UAT Elastic Beanstalk host,
  Google (fonts, maps JS, maps embed, place page), cdnjs, jsDelivr, WhatsApp, Instagram and
  html.design. Netlify appears only in the new site (`ls-home@9b78785:netlify.toml`, Q-20).
* The template import (`0732746`) contained `images/client-1.png` and `client-2.png`
  (testimonials), removed by v1; `custom.js` grew from 40 to 94 lines (tracking added).
* `images/next.png` and `prev.png` are referenced only by the Owl nav CSS
  (css/style.css:719, 724): dead, since no `.owl-carousel` exists.
* Every page requests `/images/fevicon.png` via `rel="shortcut icon"` (index.html:14):
  after cut-over it is a 404 like the other v1 assets (the new site has its own icons,
  PUB-03); browsers' implicit `/favicon.ico` request must also be answered (PUB-03).
* The Maps embed iframe is 800×650 with `referrerpolicy="no-referrer-when-downgrade"`
  (index.html:351); the place link carries the place id and coordinates of the hub
  (index.html:59): a URL that identifies the company's address, keep it out of fixtures.
* `.gitignore` is `/.idea/` only: an IntelliJ project uploaded as static files (consistent
  with "no build, hosting unknown").
* `index.html:185` is the only link in the whole site that goes to the second WhatsApp
  number, and it wraps the *free-quote* text of slide 3 (`<a id="whatsapp-contact">`);
  the icon below it (188) goes to the main number. If the live site inherited this, quote
  requests via WhatsApp may still reach that second number (Q-15, LPS-U6).
* The v1 HTML has no `<form>` with a `method`, no cookies, no `localStorage`, no analytics
  (grep): nothing on the client side needs a privacy carry-over except the Google loads.

## 5. Rules and texts to port (with chapter 09 IDs)

### 5.1 Redirect map, old URL → new URL (site module, PUB-04; hosts: MIG-03, API-03)

| Old URL (as v1 exposed it) | New app answer | Source |
|---|---|---|
| `/` | home, 200 | index.html |
| `/index.html` (+ any query/fragment) | 301 → `/` (fragment preserved by the browser) | index.html:71, 85, 416; not in ls-home@9b78785:public/_redirects |
| `/about.html`, `/about` | 301 → `/chi-siamo/` | ls-home@9b78785:public/_redirects:2, 5 |
| `/service.html`, `/service` | 301 → `/servizi/` | _redirects:3, 6 |
| `/contact.html`, `/contact` | 301 → `/contatti/` | _redirects:4, 7 |
| `/servizi.html`, `/contatti.html`, `/tracking.html`, `/preventivo.html` | 301 → `/servizi/`, `/contatti/`, `/tracking/`, `/preventivo/` (guessed names; harmless) | _redirects:8-11 |
| `/#chisiamo`, `/#contattaci`, `/#tracking` (also after the `/index.html` and `www` 301s) | client-side `location.replace` → `/chi-siamo/`, `/contatti/`, `/tracking/`, on `/` only, unless an element with that id exists | ls-home@9b78785:public/js/main.js:220-224; ls@migration:…/templates/tracking-state.html:48 |
| `/#servizi` | stays on the home; keep `id="servizi"` | index.html:212; ls-home@9b78785:public/index.html:243 |
| `/tracking_section.txt`, `/README.md`, `/.gitignore`, `/css/style.scss`, `/css/style.css.map` | 404 page, status 404 | tree |
| `/css/*`, `/js/*`, `/fonts/*?v=4.7.0`, `/images/*` (17 files incl. `fevicon.png`, `logistica-sanchez-logo.jpg`) | 404 (PUB-09); optional 301 of `/images/logistica-sanchez-logo.jpg` to the new logo only if the Q-29 crawl or the owner reports hot-links (LPS-U5) | index.html:14, 72, 114, 224, 242, 298, 332, 337, 342; css/font-awesome.min.css:4 |
| `/mono/*` | served by the app itself (API-01, API-03), never proxied as the Netlify rule does | ls-home@9b78785:public/_redirects:16; 06 §6.3 |
| `https://www.logisticasanchez.com/*` | 301 → apex, same path and query (fragment survives) | 09 PUB-04; 10 §7 step 4 |
| `https://ordini.logisticasanchez.com/*` | 301 → `/area-clienti/` | 10 §7 step 4 |
| `https://dashboard.logisticasanchez.com/*` | 301 → `/gestione/` | 10 §7 step 4; 12d §1 |
| `https://app.logisticasanchez.com/mono/…` | served (compatibility paths, old e-mail links) | 09 API-01, API-03 |
| `http://sanchez-logistica-fe.s3-website.eu-north-1.amazonaws.com/index.html` | outside the app: S3 routing rule → `https://logisticasanchez.com/area-clienti/`; bucket never deleted (owner, LPS-S4) | index.html:138, 251, 291, 372 |
| `http://sanchez-logistica-tracking.s3-website.eu-north-1.amazonaws.com/index.html` | outside the app: S3 routing rule → `https://logisticasanchez.com/tracking/`; never deleted | index.html:166, 233, 372 |
| `http://uat-sanchez-logistica.eu-north-1.elasticbeanstalk.com/mono/api/order/trace/state` | not ours; never referenced by the new app (LPS-S3); fate per Q-46 | js/custom.js:68 |

Acceptance for the site module: a contract test per row (status + `Location`), and the E2E
of chapter 05 §2.2 (`/#chisiamo`, `/#contattaci`, `/#tracking`, `/index.html#chisiamo`
land; `/#servizi` stays) plus `www.…/#tracking` → `/tracking/`.

### 5.2 Other rules and texts

| Rule / text | Citation | IDs |
|---|---|---|
| Tracking contract as the v1 client expects it: `POST /mono/api/order/trace/state`, JSON `{"codice"}`, no auth, no cookies, 2xx with a non-empty string `status`; never 2xx without `status` | js/custom.js:67-91 | API-01, TRK-02 |
| CORS preflight (`OPTIONS`, `Content-Type`) for the apex origin; v1 sends only `Content-Type` | js/custom.js:79-81 | API-01 (Q-39) |
| Code sent untrimmed by v1; the new app trims and removes spaces (fix) | js/custom.js:49, 69 | TRK-02 |
| v1 strings, reference only: "Tracking spedizione", "Inserisci il codice di riferimento della spedizione per effettuare il tracking.", placeholder "Inserisci il codice di riferimento", "Track", "Stato spedizione N. {codice}", "La tua spedizione attualmente è in stato di:", "Codice non valido", default "Non Gestito" | tracking_section.txt:10, 14, 16, 18, 28, 32; js/custom.js:56, 61 | TRK-01 (new-site widget wins) |
| Contacts: one WhatsApp/phone (the main number, index.html:47-50), one e-mail (53-56), address (59-62), Instagram handle (335), all from configuration; the second number (185) dropped unless Q-15 says otherwise | index.html as cited | PUB-02 |
| Copy worth reusing (owner review, Q-31): slogan "Il pallet espresso." and the slide/about/info paragraphs of chapter 05 §4.1; claims to confirm: 24-72 h, direct distribution Lombardy/Turin/Verona, partners for the rest, free quote, "Basta solo la tua email." | index.html:124-135, 156-163, 181-185, 228-249, 278-289, 371-375 | PUB-01 |
| Menu labels "Home · Servizi · Chi siamo · Contattaci"; footer "Indice" | index.html:85-94, 413-427 | PUB-01 |
| Logo `images/logistica-sanchez-logo.jpg` is the only brand asset; favicon, hero photo, icons are template leftovers | index.html:72; `identify` | PUB-01, PUB-03 |
| Drop: Maps JS API + key, `myMap`, Owl, second Font Awesome, Roboto/Lato, New York map, template pages | index.html:26, 30, 456, 461; js/custom.js:12-40 | PUB-08, PUB-10, NFR-02 (LPS-S1, LPS-02, LPS-08) |
| Fix in the new app: text-only rendering of the tracking result (LPS-S2); HTTPS only, no UAT host (LPS-S3); consent-gated map, self-hosted fonts, legal footer (LPS-S5) | js/custom.js:56-57, 68; index.html:23, 351, 461 | TRK-01, NFR-02, NFR-03 |

## 6. Risks and unknowns

* **LPS-U1 / Q-29** — the live site's URL list is still unknown; v1 is a proxy. The map in
  §5.1 is complete for v1 (34 files, 3 anchors, 2 S3 hosts) but not for production.
* **LPS-U2** — new evidence that the live site has `#tracking` on its home
  (tracking-state.html:48 links to it); its wording and code handling remain unknown.
* **LPS-U4 / LPS-U5 / Q-29** — whether the two S3 buckets still serve and who links them;
  the redirect rules there are an owner action (production, AWS).
* **LPS-U6 / Q-15** — the second WhatsApp number receives the free-quote clicks of slide 3
  (index.html:185); if production kept that link, dropping the number silently loses a
  contact path. Default per chapter 11: main number only, behind the PUB-02 configuration.
* **LPS-U8 / Q-23** — the key's ownership is unknown; it is in four files at HEAD and at
  the template import, so it most likely came with the template. Nothing to rotate from
  this repository; the owner checks Google Cloud. The key must never enter `ls-web`.
* **Unverifiable history** — `203c11a`, `8d6fa0a`, `e5b9298`, `origin/dev` are not in the
  checkouts (discrepancy 2); the brief's at-a-glance "public repository" claim could not
  be re-checked from this session (API 403).
* **Fragment redirects depend on JavaScript** — `/#chisiamo` etc. cannot be done
  server-side; a no-JS visitor with an old link lands on the home (acceptable: the home
  keeps `#servizi` and links to every target page). Document in the PUB-04 ADR.
* **Query strings on old asset URLs** (`?v=4.7.0`) — a strict path matcher is fine; a
  matcher that includes the query would miss them (discrepancy 4).

## 7. Notes for the planner

* **ADR 0001 inputs:** nothing in v1 constrains the stack. Hosting of v1 was never in the
  repository (no build, `.idea` only); the S3 website endpoints are HTTP-only, so the
  redirects of §5.1 that touch them live in AWS, not in the app (MIG-03 runbook, owner).
* **Module ownership:** `site` owns every path row of §5.1 and the client-side anchor
  redirect (PUB-04, PUB-09); `tracking + compatibility` owns `/mono/api/order/trace/state`
  and its CORS (API-01); the `www`, `ordini.`, `dashboard.` host rules are Phase 1
  foundations (host-based routing, one place) and are rehearsed in MIG-03; S3 and Elastic
  Beanstalk are owner-only (Q-29, Q-46).
* **Phase 1 needs:** host-aware routing with a redirect table loaded from configuration;
  a 404 handler that ignores query strings; business contacts (one phone/WhatsApp, e-mail,
  address, Instagram, hours, VAT) as one configuration source with a `Q-15` seam
  (PUB-02); a CSP without Google Maps JS (`frame-src` only for the consent-gated embed).
* **Tests to plan:** contract test over the §5.1 table; E2E for the four fragment cases
  plus `www…/#tracking`; a test that no page of the new app contains the S3, Elastic
  Beanstalk or `maps.googleapis.com/maps/api/js` hosts (LPS-S3, LPS-S1).
* **Gate 0 items:** Q-15 (second number: default drop), Q-23 (key ownership; nothing to
  do in this repo), Q-29 (crawl of the live site and S3 redirect rules: owner), Q-31
  (v1 copy, claims and the about photo of discrepancy 5).
