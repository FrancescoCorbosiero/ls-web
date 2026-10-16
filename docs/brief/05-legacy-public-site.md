# 05 · Legacy public site (`ls-home@v1`)

The old public website lives in branch `v1` of repository `ls-home`: a free Bootstrap
template ("Carint") of which only the home page was adapted to Logistica Sanchez, in
May–June 2024. It is **not** the site that is live today: production
`https://logisticasanchez.com` is a rewrite of this template with another UI library, and
its code is not available (owner). v1 is still the only source for the old URLs and
anchors, the old copy, the links to the order portal and to the old tracking app, and the
one backend call a public page makes (tracking). Use this chapter to decide which legacy
URLs and contracts the monolith must keep (PUB-04, API-01), which copy is worth reusing,
and what must not be carried over. The public pages themselves are ported from the new
static site (chapter 06).

* Sources: `ls-home@v1` = `origin/v1` @ `8201716` (2024-06-04), the newest state of the old
  site (the extracted copy used for this chapter is identical to `git archive origin/v1`,
  verified). `ls-home@dev` = `origin/dev` @ `203c11a` (2024-05-31), the first "Home v1"
  commit and the only state with a working in-page tracking box. `ls-home@0732746` = the
  template import. `ls-home@fe4b056` = the new static site (chapter 06). `ls-cms@dev` = the
  CMS (chapter 04). `owner` = stated by the owner.
* Citations without a ref (`index.html:16`, `js/custom.js:68`) point to `ls-home@v1`.
* Italian strings are quoted verbatim, typos included; "[sic]" marks the ones that matter.

## At a glance

| | |
|---|---|
| What | Free HTML template "Carint" by html.design (Bootstrap 4, jQuery). Only `index.html` was adapted; `about.html`, `service.html` and `contact.html` are untouched template pages (about.html:16, 72, 249-250). |
| Live? | No: production is a separate rewrite (owner). Whether v1 itself was ever published, where and when, is (unknown). |
| Repository | `ls-home` is public on GitHub (GitHub API `"visibility": "public"`, checked 2026-10-16), so everything in v1 is public, including the Maps key (LPS-S1). |
| Pages | 1 real page (a one-page home with anchors) + 3 orphan template pages + `tracking_section.txt` (a parked HTML snippet). |
| Backend calls | One: `POST {HOST}/mono/api/order/trace/state` with `{"codice": …}`, reads `status` (js/custom.js:67-74). At v1 HEAD the tracking box is in no page, so this code is dead and throws on load (LPS-01). |
| Forms that send data | None. The template forms on `contact.html`, `about.html`, `service.html` have no handler and no field names (contact.html:126-144). |
| External services | Google Fonts, cdnjs, jsDelivr, Google Maps (embed + JavaScript API), WhatsApp and Instagram links. No analytics, no cookie banner. |
| Secrets | A Google Maps JavaScript API key is hard-coded in all four pages (LPS-S1). It is deliberately not reproduced in this brief. |

## 1. What the old site is

### 1.1 Origin and history

All commits by FrancescoCorbosiero (`git -C ls-home log origin/v1`).

| Commit | Date | What changed |
|---|---|---|
| `bca0776` | 2024-05-30 | `README.md` only ("# ls-home", saved as UTF-16 LE). |
| `0732746` | 2024-05-30 | Template import (29 files): four pages, Bootstrap, jQuery, Owl Carousel, Font Awesome, template images. The home already had the title " Sanchez Logistica, il pallet espresso." and the company's WhatsApp, e-mail and address as plain text with empty links (ls-home@0732746:index.html:16, 49, 55, 61). |
| `203c11a` (= `origin/dev`) | 2024-05-31 | Home adapted: logo, Italian menu with anchors, three hero slides, "Servizi Logistici Online", "Chi Siamo", in-page tracking box `#tracking` (ls-home@dev:index.html:310-347), contact tiles, Google Maps iframe, info band, footer; tracking JavaScript added (ls-home@dev:js/custom.js:42-94). Removed: the template's testimonials carousel and its contact and subscribe forms. |
| `8d6fa0a` | 2024-05-31 | Adds the logo and the Instagram, WhatsApp and e-mail images that `203c11a` already referenced. |
| `e5b9298` | 2024-05-31 | Removes the missing `nav-bullet.png` images from the home footer. |
| `8201716` (= `origin/v1`) | 2024-06-04 | Tracking box removed from the home and parked in `tracking_section.txt` with a `hidden` attribute; the three tracking links now open the separate tracking app on S3 (index.html:166, 233, 372). |

### 1.2 Stack and third-party resources

| Part | Version / origin | Where | Notes |
|---|---|---|---|
| Bootstrap (CSS + JS) | 4.3.1, local | css/bootstrap.css:2; js/bootstrap.js:2; index.html:20, 454 | Hero carousel and mobile-menu collapse. |
| jQuery | 3.4.1, local | js/jquery-3.4.1.min.js:1; index.html:449 | Needed by Bootstrap 4 JS and Owl. |
| Popper.js | 1.16.0, jsDelivr, with SRI hash | index.html:451 | |
| Owl Carousel | 2.3.4, cdnjs | index.html:26, 456; js/custom.js:12-27 | Initialised on `.owl-carousel`, which no page contains since the testimonials were removed in `203c11a`: dead weight. |
| Font Awesome | 4.7.0, local; the home also loads the same version from cdnjs | css/font-awesome.min.css:2; index.html:29-30 | Loaded twice on the home. |
| Web fonts | Google Fonts: Roboto (`<link>`), Lato + Open Sans (`@import`) | index.html:23; css/style.css:1 | Only Open Sans is used (css/style.css:3). |
| Template CSS | SCSS source, compiled CSS with source map, `responsive.css` | css/style.scss; css/style.css; css/style.css.map; css/responsive.css | Colours `$primary1: #0a97b0` (teal) and `$primary2: #04233b` (navy) (css/style.scss:3-5). |
| Site JS | `js/custom.js`, 94 lines | | Footer year (2-8), Owl init (12-27), Google Maps callback `myMap` (34-40), tracking (42-94). |
| Google Maps | Embed iframe on the home; Maps JavaScript API with key and `callback=myMap` on all four pages | index.html:351, 461; about.html:269; contact.html:282; service.html:335 | LPS-S1, LPS-02. |
| Build, hosting | None in the repository: no package manager, build step, CI or hosting configuration | repository tree | How and where v1 was served is (unknown). |

### 1.3 Pages

| File | Content | Linked from the home |
|---|---|---|
| `index.html` | The only adapted page: one-page home, sections below. | — |
| `about.html` | Template "About Us", Lorem ipsum (about.html:119-131). | No |
| `service.html` | Template "Our Services": "Air Transport", "Cargo Transport", "Trucks Transport", "Train Transport", Lorem ipsum (service.html:117-200). | No |
| `contact.html` | Template "Contact Us": a form without handler and a JavaScript map centred on New York (contact.html:117-150; LPS-02). | No |
| `tracking_section.txt` | The parked tracking box (§3.6). | No |

The home links only to itself (`index.html`, index.html:71, 85, 416) and to its anchors.
The three template pages link to each other (about.html:82-93) but nothing links to them:
they are reachable only by typing their URL.

Sections of the home (index.html):

| # | Section | Lines | Content |
|---|---|---|---|
| 1 | Top bar | 46-65 | "Whats. : +39 329 239 6962" (WhatsApp, new tab), "Email : logisticasanchezmb@gmail.com" (mailto), "SEREGNO, VIA DEI MEIANI, 90 20831 (MB)" (Google Maps, new tab). |
| 2 | Menu | 70-106 | Logo `images/logistica-sanchez-logo.jpg`; "Home" · "Servizi" `#servizi` · "Chi siamo" `#chisiamo` · "Contattaci" `#contattaci`. The template's "Login" and search button are commented out (96-103). |
| 3 | Hero carousel `#customCarousel1` | 112-205 | Three slides, Bootstrap carousel (`data-ride="carousel"`, 116): advances every 5 s and pauses on hover (Bootstrap defaults, js/bootstrap.js:567-571), three indicators, no arrows. Copy in §4.1. Buttons: "Portale Ordini" (138), "Tracking" (166), WhatsApp quote link (185), WhatsApp and Instagram icons (188-189). |
| 4 | Services `#servizi` | 212-260 | "Servizi Logistici Online": card "Tracking" → "Vai al Tracking" (233); card "Portale Ordini" → "Vai al Portale Ordini" (251). |
| 5 | About `#chisiamo` | 267-304 | "Chi Siamo" text, "Portale Ordini" link (291), photo `about-img.jpg`. |
| 6 | Tracking | 308-310 | Empty comment pair: the box was removed in `8201716`. |
| 7 | Contacts `#contattaci` | 313-357 | "Contattaci e visita i nostri profili social": Instagram, WhatsApp and e-mail tiles (331-346); Google Maps iframe of Via dei Meiani 90 (351). |
| 8 | Info band | 362-433 | "Info" text with the links "Tracking" and "Portale Ordini" (372); "Address" [sic, English] block with address, WhatsApp, e-mail (381-403); WhatsApp and Instagram icons (405-408); "Indice" links (412-428). |
| 9 | Footer | 438-445 | "© {anno} Powered by Sanchez Logistica, il pallet espresso." (year filled in by js/custom.js:2-8). |

### 1.4 How production relates to it

* The live site at `https://logisticasanchez.com` is a different codebase: a rewrite of this
  template with another UI library, not in these repositories (owner). Treat v1 as a proxy
  for the live site's structure, anchors and copy, not as a copy of it.
* The message of ls-home@3bc229a calls branch v1 "the production site". Per the owner that
  is loose wording: production was rewritten from v1.
* The new static site replaced v1 in the main line of `ls-home` (ls-home@722cead, "remove
  legacy template"); branches `v1` and `dev` still hold the old site.

## 2. URL inventory

What must still answer after the cut-over. The new static site already implements most of
it (paths: ls-home@fe4b056:public/_redirects:1-11; anchors:
ls-home@fe4b056:public/js/main.js:218-224; chapter 06 §5.2 and §6.3), and requirement PUB-04
makes it M1 scope. The live site's own URL list is (unknown) (LPS-U1); the v1 list below is
the best proxy available.

### 2.1 Paths

| Legacy path | What it is in v1 | New app (M1) | Notes |
|---|---|---|---|
| `/` | Home | Home, 200 | |
| `/index.html` | Home; the logo, the menu's "Home" and the footer's "Home" link here (index.html:71, 85, 416) | 301 → `/` | **Not** in the new site's `_redirects`: add it. |
| `/about.html` | Template page | 301 → `/chi-siamo/` | ls-home@fe4b056:public/_redirects:2 |
| `/service.html` | Template page | 301 → `/servizi/` | ls-home@fe4b056:public/_redirects:3 |
| `/contact.html` | Template page | 301 → `/contatti/` | ls-home@fe4b056:public/_redirects:4 |
| `/about`, `/service`, `/contact` | Not files in v1; they would exist only on a host that serves `.html` files without the extension (inferred) | 301 as the three above | ls-home@fe4b056:public/_redirects:5-7 |
| `/servizi.html`, `/contatti.html`, `/tracking.html`, `/preventivo.html` | Not in `v1`, `dev` or the template import (`git grep` on the three refs finds none, verified); added to the new site as guesses (ls-home@722cead: first two; ls-home@3bc229a: last two) | Keep the 301s: harmless | Whether the live site uses them is (unknown). |
| `/tracking_section.txt` | Developer snippet in the web root, public if the folder was uploaded as is (inferred) | 404 | Do not port. |
| `/README.md`, `/.gitignore`, `/css/style.scss`, `/css/style.css.map` | Repository files | 404 | |
| `/images/…`, `/css/…`, `/js/…`, `/fonts/…` | Template and brand assets | 404 | Whether anything outside the site hot-links them (for example the logo in e-mail signatures) is (unknown) (LPS-U5). |

Fragments survive the `/index.html` → `/` redirect: when the `Location` header has no
fragment, the browser re-applies the original one (RFC 7231 §7.1.2). The new site's anchor
mapping runs only when `location.pathname === '/'`
(ls-home@fe4b056:public/js/main.js:222), so without that 301 `/index.html#chisiamo` would
stay on the home.

### 2.2 Anchors

| Anchor | Target in v1 | Linked from (v1) | New app |
|---|---|---|---|
| `#servizi` | Services section (index.html:212) | Menu (88), "Indice" (422) | Stays on the home: the new home has `id="servizi"` (ls-home@fe4b056:public/index.html:243). Keep that id. |
| `#chisiamo` | About section (index.html:267) | Menu (91), "Indice" (419) | Client-side → `/chi-siamo/` (ls-home@fe4b056:public/js/main.js:221). |
| `#contattaci` | Contacts section (index.html:313) | Menu (94), "Indice" (425) | Client-side → `/contatti/`. |
| `#tracking` | In-page tracking box, only from `203c11a` to `e5b9298` (31 May – 4 Jun 2024) (ls-home@dev:index.html:310); in v1 only inside the parked snippet (tracking_section.txt:1) | At `dev`: slide 2 button, services card, info text (ls-home@dev:index.html:166, 233, 411) | Client-side → `/tracking/`. |
| `#customCarousel1`, `#navbarSupportedContent` | Carousel and menu-collapse targets | — | Not navigation; ignore. |

Fragments never reach the server, so they can only be handled in the page
(ls-home@fe4b056:public/js/main.js:218-219). The menu links are relative
(`href="#servizi"`), so a visitor who had opened `/index.html` bookmarked
`/index.html#servizi` (inferred): one more reason for the 301 above.

**Acceptance (PUB-04):** every path in §2.1 answers with the stated status and `Location`;
E2E: `/#chisiamo`, `/#contattaci`, `/#tracking` and `/index.html#chisiamo` land on their
target page; `/#servizi` stays on the home and shows the services section.

### 2.3 External links

| Target | Labels and behaviour | Where (index.html) | Notes |
|---|---|---|---|
| `http://sanchez-logistica-fe.s3-website.eu-north-1.amazonaws.com/index.html` | "Portale Ordini" (slide 1, new tab), "Vai al Portale Ordini" (new tab), "Portale Ordini" under "Chi Siamo" (same tab), "Portale Ordini" in the info band (new tab) | 138, 251, 291, 372 | The order portal's older address; today `https://ordini.logisticasanchez.com` (owner). Plain HTTP (LPS-S3). |
| `http://sanchez-logistica-tracking.s3-website.eu-north-1.amazonaws.com/index.html` | "Tracking" (slide 2), "Vai al Tracking", "Tracking" in the info band; all same tab | 166, 233, 372 | The old separate tracking app (owner); its source is in no repository (LPS-U4). |
| `https://api.whatsapp.com/send?phone=393292396962` | Top bar (new tab), slide 3 icon, contact tile, info band, info icon | 47, 188, 337, 391, 406 | Main number (§2.4). |
| `https://api.whatsapp.com/send?phone=393289299463` | Slide 3 text "Hai bisogno di un preventivo? Contattaci direttamente." (`id="whatsapp-contact"`) | 185 | Second number (§2.4, LPS-04). |
| `mailto:logisticasanchezmb@gmail.com` | Top bar, contact tile, info band | 53, 342, 397 | |
| `https://www.instagram.com/sanchez_logistica_mb` | Slide 3 icon, contact tile "@sanchez_logistica_mb", info icon | 189, 332, 407 | |
| Google Maps place page "Via dei Meiani, 90, 20831 Seregno MB" (`https://www.google.it/maps/place/…`, coordinates 45.6428451, 9.2365537) | Top-bar address, new tab | 59 | The info-band address has `href=""` instead (385). |
| Google Maps embed (`https://www.google.com/maps/embed?pb=…`, Via dei Meiani 90) | Map in the contacts section, `loading="lazy"` | 351 | Loaded without consent (LPS-S5). |
| `https://html.design/` | "Free Html Templates" footer credit | about.html:250; contact.html:263; service.html:316 | Template pages only. |

No `tel:` link exists anywhere in v1 (verified): the phone number is offered only as
WhatsApp.

### 2.4 Phone numbers and other contact points

| Number | Shown as text | Link | Where | Status |
|---|---|---|---|---|
| +39 329 239 6962 | "Whats. : +39 329 239 6962", "+39 329 239 6962" | WhatsApp `393292396962` | index.html:47-50, 188, 337-340, 391-394, 406 | Official phone and WhatsApp (owner). |
| +39 328 929 9463 | Never shown | WhatsApp `393289299463` | index.html:185 only (free-quote text, slide 3) | Not among the owner's contacts; still in use? (Q-15). Do not carry over unless the owner confirms. |
| +01 123455678990 | "Call : +01 123455678990" | none (`href=""`) | about.html:49; contact.html:49; service.html:49 | Template placeholder. |
| +01 1234567890 | "Call +01 1234567890" | none (`href=""`) | about.html:166; contact.html:179; service.html:232 | Template placeholder. |

E-mail: logisticasanchezmb@gmail.com (index.html:56, 345, 400; owner); template
placeholder "demo@gmail.com" (about.html:55, 172; contact.html:55, 185; service.html:55,
238). Address: Via dei Meiani 90, 20831 Seregno (MB) (index.html:62, 388; owner).
Instagram: sanchez_logistica_mb (index.html:335; owner).

### 2.5 Hosts outside the site

Not served by the new app, but linked from v1 (and from the public repository for good):

| Host | Today | Proposal (owner decision: it touches production) |
|---|---|---|
| `sanchez-logistica-fe.s3-website.eu-north-1.amazonaws.com` | Older order-portal address (owner); still serving? (unknown) | Keep the bucket and turn it into a redirect to the customer area (chapter 10 §7 sends `ordini.logisticasanchez.com` to `/area-clienti/`). Never delete it (LPS-S4). |
| `sanchez-logistica-tracking.s3-website.eu-north-1.amazonaws.com` | Old tracking app (owner); still serving? (unknown) | Same: redirect to `https://logisticasanchez.com/tracking/`; never delete. |
| `ordini.logisticasanchez.com`, `app.logisticasanchez.com` | Order portal; backend (owner) | Chapter 10 §7 and API-03. |

Implementation note (inferred, verify against the AWS documentation when configuring): an
S3 website `RedirectAllRequestsTo` keeps the requested path, so sending every request to one
page needs a routing rule with `ReplaceKeyWith`.

## 3. Tracking

### 3.1 Where the tracking lived

| Period | Tracking on the old site |
|---|---|
| 31 May – 4 Jun 2024 (`203c11a` to `e5b9298`) | In-page box `#tracking` on the home, calling the backend directly (ls-home@dev:index.html:310-347; ls-home@dev:js/custom.js:42-94). |
| From 4 Jun 2024 (`8201716` = v1) | Box removed; the links open the separate tracking app `http://sanchez-logistica-tracking.s3-website.eu-north-1.amazonaws.com/index.html` (index.html:166, 233, 372). The JavaScript stayed in `custom.js` and now fails at load (LPS-01). The markup is parked in `tracking_section.txt`, with `hidden` on the section. |
| Today (production) | The live site makes the same call to `https://app.logisticasanchez.com` (owner). How it presents the result is (unknown) (LPS-U2). |

`js/custom.js` is identical in `dev` and `v1`: after `203c11a` only `index.html`, the
images and `tracking_section.txt` changed (verified).

### 3.2 The call

As written in v1, against the UAT host (js/custom.js:67-92):

```http
POST http://uat-sanchez-logistica.eu-north-1.elasticbeanstalk.com/mono/api/order/trace/state
Content-Type: application/json
```

As made by the live site (owner, from the owner's curl):

```http
POST https://app.logisticasanchez.com/mono/api/order/trace/state
Origin: https://logisticasanchez.com
Content-Type: application/json
```

Request body (the code value is illustrative: the real code format is unknown, Q-06):

```json
{ "codice": "LS-2024-0118" }
```

Successful answer as v1 reads it: only `status` is used (js/custom.js:72-73). Whether
`status` holds the CMS label ("Presa in carico") or the enumeration name ("PRESA") is
unknown (Q-03):

```json
{ "status": "Presa in carico" }
```

| Item | Value | Source |
|---|---|---|
| Method, path | `POST /mono/api/order/trace/state` | js/custom.js:68; owner |
| Auth | None: no token; no cookies either (fetch's default `credentials: "same-origin"` sends none cross-origin) | js/custom.js:76-83 |
| Headers sent | `Content-Type: application/json` only; no `Accept` | js/custom.js:79-81 |
| Body | `{"codice": <input value>}`, exactly as typed: no trim, no case change, no length or character check | js/custom.js:49, 69 |
| Success | Any 2xx: body parsed as JSON, `status` returned | js/custom.js:72-73, 85-91 |
| Failure | Any non-2xx throws `Error("Rest call failed")`; the error body is never read | js/custom.js:85-89 |
| Status code for an unknown code | From the backend source: `200 {"status": "Non gestito"}`, so v1 shows "Non gestito" as a state; `400` for an empty code (12a §1.4 D1) | LPS-U3 |
| Other response fields | (unknown); ignored by v1 | |
| CORS | The JSON content type makes the browser send a preflight `OPTIONS` before the POST whenever page and API origins differ (inferred, Fetch standard); the backend must allow the page origin, `POST` and `Content-Type`. The live call comes from `https://logisticasanchez.com` (owner). | |

The value stands for one of the six backend tracking states (enumeration `statoTracking`),
which the CMS hard-codes as `NON_GESTITO` "Non gestito", `PRESA` "Presa in carico",
`TRANSITO` "Transito", `TERMINAL_DEST` "Terminal destinatario", `ASSEGNATO` "Assegnato",
`CONSEGNATO` "Consegnato" (ls-cms@dev:js/components/data/user-data.js:22-53; chapter 04).
The CMS sets them by `id` (ls-cms@dev:js/rest/dto/order-request.js:3-8; chapter 04). The
box's default text "Non Gestito" (tracking_section.txt:32) hints that the author expected a
readable label (inferred, weak: the CMS label is "Non gestito").

### 3.3 Client logic, step by step

1. Clicking "Track" (`#tracking-btn-id`) runs `getTrackingState` (js/custom.js:94). Input and
   button are not inside a `<form>`, so Enter in the field does nothing (inferred: a submit
   button without a form submits nothing).
2. `codice` = the input's value (js/custom.js:49). Empty → the result column is hidden and
   nothing else happens, no message (js/custom.js:50-51).
3. `fetch` POST with the JSON body (js/custom.js:67-83). No timeout, retry, loading
   indicator or button disabling: repeated clicks send parallel requests and the last answer
   to arrive wins (inferred).
4. Non-2xx → throws (js/custom.js:85-89). 2xx → `response.json()`, then `status`
   (js/custom.js:72-73).
5. Success → heading "Stato spedizione N. {codice}", state text = `status`, column shown
   (js/custom.js:56-58), both written with `innerHTML` (LPS-S2).
6. Any exception (non-2xx, network or CORS failure, invalid JSON) → `console.log(error)`,
   state text "Codice non valido", column shown (js/custom.js:59-62). The heading is not
   touched.

### 3.4 UI states and messages

| State | When | Left column | Right column (`#tracking-state-div-id`) |
|---|---|---|---|
| Parked | Always, in v1 | Box in no page | — |
| Idle | Page load (`dev`) | "Tracking spedizione" · "Inserisci il codice di riferimento della spedizione per effettuare il tracking." · input, placeholder "Inserisci il codice di riferimento" · button "Track" | Hidden (pre-filled with "Non Gestito") |
| Empty code | Click with an empty field | unchanged | Hidden |
| Found | 2xx | unchanged | "Stato spedizione N. {codice}" · "La tua spedizione attualmente è in stato di:" · `{status}` as received |
| Found, no `status` | 2xx without `status` | unchanged | Third line reads "undefined" (inferred: `innerHTML = undefined`); empty if `status` is `null` |
| Error | Non-2xx, network, CORS, invalid JSON | unchanged | Heading empty, or still the previous code's "Stato spedizione N. …" · "La tua spedizione attualmente è in stato di:" · "Codice non valido" |

Sources: ls-home@dev:index.html:310-347 (the same markup as tracking_section.txt:1-38,
without `hidden` on the section); js/custom.js:43-65.

### 3.5 Error handling: what is wrong

* Every failure reads "Codice non valido", network errors, CORS refusals and server errors
  included: the customer is told the code is wrong when the service is down
  (js/custom.js:59-62).
* After a successful search, a failed one keeps the old heading: the page shows "Stato
  spedizione N. {previous code}" above "Codice non valido" (js/custom.js:56, 61).
* A 2xx answer without `status` prints "undefined" (inferred).
* No trim: a code pasted with a trailing space is sent with it (js/custom.js:49).
* Errors only go to the browser console; nothing is reported anywhere.

### 3.6 `tracking_section.txt`

The parked markup, verbatim (tracking_section.txt:1-38). It is the `dev` box
(ls-home@dev:index.html:310-347) plus `hidden` on the section; the ids are the ones
`custom.js` looks up (js/custom.js:44-47, 94).

```html
  <section class="track_section layout_padding" id="tracking" hidden>
    <div class="track_bg_box">
<!--      <img src="images/track-bg.jpg" alt="">-->
    </div>
    <div class="container" id="tracking-div-id">
      <div class="row">
        <div class="col-md-6">
          <div class="heading_container">
            <h2>
              Tracking spedizione
            </h2>
          </div>
          <p>
            Inserisci il codice di riferimento della spedizione per effettuare il tracking.
          </p>
            <input id="tracking-code-input-id" type="text" placeholder="Inserisci il codice di riferimento" />
            <button type="submit" id="tracking-btn-id">
              Track
            </button>

        </div>
        <div class="col-md-6" id="tracking-state-div-id" hidden>
          <div class="heading_container">
            <h2 id="tracking-h2-id">
            </h2>
          </div>
          <p>
            La tua spedizione attualmente è in stato di:
          </p>
          <div class="heading_container">
            <h2 id="tracking-state-txt-id">
              Non Gestito
            </h2>
          </div>
        </div>
      </div>
    </div>
  </section>
```

### 3.7 What the new app keeps and changes

* **Keep the contract (API-01).** `POST /mono/api/order/trace/state` on
  `app.logisticasanchez.com`, JSON `{"codice": …}`; 2xx with a non-empty string `status`
  (the label) for a known code; `200 {"status": "Non gestito"}` for an unknown or temporary
  code, as the backend answers (chapter 02 §10.2); CORS, preflight included, for
  `https://logisticasanchez.com`. Extra response fields are safe (v1 ignores them). Never
  answer 2xx with a missing or `null` `status`: a v1-style client would print "undefined"
  or an empty state (inferred).
* **Do not port the v1 box.** The public tracking page comes from the new site (chapter 06
  §5.7, TRK-01): it trims the code, tells "not found" apart from "service unavailable" and
  writes text safely. The v1 strings ("Codice non valido", "Stato spedizione N. …",
  "Track") are reference only; what the live site shows is (unknown).
* **Fixes:** output escaping (LPS-S2), HTTPS only (LPS-S3), the code-matching rules of TRK-02,
  no personal data in the answer (TRK-03), rate limit (TRK-04).

## 4. Content and claims

### 4.1 Copy worth keeping

Verbatim from index.html; line breaks inside paragraphs (`<br>`, source newlines) are joined
with spaces.

**Slogan.** "Il pallet espresso." (index.html:124-125, 287); title " Sanchez Logistica, il
pallet espresso." (16); footer "Powered by Sanchez Logistica, il pallet espresso."
(441-442). The owner's form is Logistica Sanchez S.r.l. – "Il pallet espresso" (owner).

**Slide 1** (index.html:123-140)

> Il Pallet Espresso.
>
> Logistica Sanchez è specializzata nella distribuzione di merce. Copriamo tutto il
> territorio nazionale con tempo di resa da 24h a 72h e abbiamo una distribuzione diretta
> su tutto il territorio della Lombardia Torino e Verona. Con i nostri Partner copriamo il
> resto del suolo Italiano.
>
> Effettua subito un ordine presso il nostro portale: [Portale Ordini]

**Slide 2** (index.html:153-168)

> Spedizioni In tutta italia
>
> Facciamo parte di una rete (network) di partner e professionisti dislocati in tutta
> Italia per poter offrire un servizio di consegna che soddisfi tutte le esigenze della
> nostra clientela.
>
> Trova la mia spedizione: [Tracking]

**Slide 3** (index.html:181-189)

> Contattaci Per un preventivo Gratutito [sic]
>
> Hai bisogno di un preventivo? Contattaci direttamente.

**Servizi Logistici Online** (index.html:217-253)

> **Tracking** — Puoi tracciare la tua spedizione in qualsiasi momento. [Vai al Tracking]
>
> **Portale Ordini** — Effettua subito un ordine sul nostro portale dedicato. Basta solo la
> tua email. [Vai al Portale Ordini]

"Basta solo la tua email." describes the login-less portal (chapter 03, OP-S1). It stays
roughly true with the sign-in link of CUS-01; reword it if that flow changes.

**Chi Siamo** (index.html:274-293)

> Attraverso la rete di cui facciamo parte copriamo direttamente in modo molto più
> flessibile il territorio e con superiore efficienza qualitativa. Ogni imprenditore
> (Partner) è direttamente coinvolto sia in distribuzione che in raccolta, questo porta a
> dare il massimo per quanto riguarda le performance di resa e assistenza. Controlliamo
> attivamente l’efficienza dei nostri Partner per mantenere sempre elevati gli standard ed
> è grazie al lavoro consolidato in questi anni che possiamo garantire un servizio di
> qualità.
>
> Il pallet espresso.
>
> Effettua subito un ordine presso il nostro portale: [Portale Ordini]

**Contattaci** (index.html:322): "Contattaci e visita i nostri profili social"

**Info** (index.html:371-375)

> Il nostro centro logistico si pone l'obiettivo di essere flessibile, facile ed espresso.
>
> Per questo motivo il Tracking e il Portale Ordini sono servizi Online usufruibili da
> tutti in qualsiasi momento.
>
> Per ulteriori informazioni e chiarimenti rimaniamo a disposizione sui principali canali
> di comunicazione (Posta elettronica, telefono, social...)

**Labels.** Menu: "Home", "Servizi", "Chi siamo", "Contattaci" (index.html:85-94). Footer
"Indice": "Home", "Chi Siamo", "Servizi", "Contattaci" (index.html:413-427). Address format:
"SEREGNO, VIA DEI MEIANI, 90 20831 (MB)" (index.html:62, 388).

### 4.2 Business claims

Claims made by the old site. Confirm them with the owner before reusing them (chapter 01,
principle 6).

| Claim | Wording | Source | Compare |
|---|---|---|---|
| Delivery across Italy in 24–72 h | "tempo di resa da 24h a 72h" | index.html:129 | Chapter 01: 24–72 h, up to 96 h for remote areas. |
| Own direct distribution in Lombardy, Turin and Verona | "distribuzione diretta su tutto il territorio della Lombardia Torino e Verona" | index.html:130-131 | Not stated by the owner; relevant to the coverage pages (chapter 06). |
| Rest of Italy through partners | "Con i nostri Partner copriamo il resto del suolo Italiano." | index.html:132 | |
| Partners do both collection and delivery; their efficiency is monitored | "Ogni imprenditore (Partner) è direttamente coinvolto sia in distribuzione che in raccolta…" | index.html:280-285 | |
| Free quote | "Per un preventivo Gratutito" [sic] | index.html:181-183 | Offered through WhatsApp, second number (LPS-04). |
| Order with just an e-mail | "Basta solo la tua email." | index.html:249 | The legacy portal has no login (chapter 03). |
| Tracking and order portal open to everyone, at any time | "servizi Online usufruibili da tutti in qualsiasi momento" | index.html:372 | |

v1 contains **no statistics**: no shipment counts, years of activity or customer numbers.
The figures on the new site come from another source (§6.2).

### 4.3 Brand assets

| Asset | File | Keep? |
|---|---|---|
| Logo | `images/logistica-sanchez-logo.jpg`, 1080×400 JPEG on white: black brush-stroke "S" ending in an arrow, lime square outline, "LOGISTICA" | The only real brand asset; the new site already has it traced to SVG (ls-home@57c7c6e commit message; chapter 06). |
| Favicon | `images/fevicon.png` [sic], 512×512, navy delivery truck | Template icon (present since the template import). Drop. |
| Colours | Teal `#0a97b0`, navy `#04233b` (css/style.scss:3-5) | Template colours; the new palette keeps navy and teal (chapter 06). |
| Hero photo | `images/slider-bg.jpg`, 1920×1080, American trucks on a forest road | Template stock photo. Drop. |
| About photo | `images/about-img.jpg`, 1872×2048, 736,543 bytes: forklift lifting stacks of wooden pallets | Stock-looking, not the Seregno hub (inferred). Never present it as "our hub". |
| Icons | `images/insta.png` (actually WebP, 622,302 bytes), `images/whats.webp`, `images/email.webp`; `images/email.jpg` unused | Drop. |

### 4.4 Template leftovers to drop

* The three template pages, entirely: title and brand "Carint", placeholder contacts ("Call :
  +01 123455678990", "Email : demo@gmail.com", "Location"), menu "Services / About / Contact
  Us / Login" with a search button, Lorem ipsum, "Air / Cargo / Trucks / Train Transport",
  the contact form without handler, the "Subscribe" form, Facebook, Twitter, LinkedIn and
  Instagram icons with empty links, "All Rights Reserved By Free Html Templates" (for
  example about.html:16, 46-63, 94-101, 177-190, 228-238, 249-250).
* On the home: the empty `keywords`, `description` and `author` meta tags
  (index.html:11-13); the English heading "Address" (382); a malformed comment
  `<! -- SEGNALAZIONI -->` inside the brand link (74); the commented-out Login and search
  (96-103); Owl Carousel (26, 456); the second Font Awesome (30); the Google Maps JavaScript
  API and its `myMap` callback (461; js/custom.js:34-40); the empty tracking section
  (308-310).
* Unused files: `images/track-bg.jpg` (only in a commented-out tag), `images/email.jpg`,
  `images/s2.png` and `images/s4.png` (template pages only), `css/style.css.map`,
  `tracking_section.txt`.
* A missing file the template pages reference: `images/nav-bullet.png` (about.html:209;
  contact.html:222; service.html:275), never in the repository (verified).

## 5. Quirks and security issues

Decision: **Fix** = do it right in the new app; **Keep** = preserve; **Drop** = do not carry
over.

| ID | Kind | Issue | Evidence | Decision |
|---|---|---|---|---|
| LPS-S1 | security | Google Maps JavaScript API key hard-coded in all four pages, the same everywhere, present since the template import (ls-home@0732746), in a public repository: treat it as leaked. It shipped with the free template, so it is probably the template author's key (inferred). To locate it without printing it: `git -C ls-home grep -c "maps/api/js?key=" origin/v1`. | index.html:461; about.html:269; contact.html:282; service.html:335 | Drop: the new app does not need it (consent-gated iframe, chapter 06). If the company owns this key, delete or rotate it in Google Cloud and restrict any new key by HTTP referrer and API. Never copy it. |
| LPS-S2 | security | DOM XSS: the typed code and the backend's `status` are written with `innerHTML`. A code like `<img src=x onerror=alert(1)>` runs if the backend answers 2xx for it (the heading is set only on success); a `status` containing markup renders as markup. Self-XSS only: no URL parameter fills the field. | js/custom.js:56-57 | Fix: render text only, escape server-side. |
| LPS-S3 | security | Plain HTTP: the tracking call targets the UAT host over `http://`, which an HTTPS page cannot call (mixed content, inferred); the portal and tracking links point to S3 website endpoints, which serve HTTP only (AWS S3 documentation). The public page also exposes the UAT host name. | js/custom.js:68; index.html:138, 166, 233, 251, 291, 372 | Fix: HTTPS only, per-environment configuration, no UAT hosts in public pages. |
| LPS-S4 | security | The two S3 bucket names are written into public links forever. If a bucket is deleted its name becomes available to any AWS account (bucket names are global; AWS S3 documentation), and the old links would serve someone else's content (inferred risk). | index.html:138, 166 | Fix: never delete the buckets; turn them into redirects (§2.5). Owner decision. |
| LPS-S5 | privacy | Google Fonts, the Maps iframe and the Maps JavaScript API load before any consent; no privacy or cookie page; no legal name or VAT number anywhere on the site. | index.html:23, 351, 438-445, 461; css/style.css:1 | Fix: as the new site (self-hosted fonts, consent-gated map, privacy and cookie pages, "P.IVA 13113080967" in the footer; chapter 06). |
| LPS-01 | bug | `document.getElementById('tracking-btn-id').onclick = …` runs on every page, but no page contains that element since `8201716`: a TypeError on every load (inferred). It is the last statement, so nothing visible breaks, but the tracking code is dead. | js/custom.js:94 | Drop. |
| LPS-02 | bug | The Maps callback centres the map on 40.712775, -74.005973 (New York) at zoom 18. Only `contact.html` has `#googleMap`, so the contact template page shows New York; on the other pages the callback finds no container and the Maps API logs an error (inferred). | js/custom.js:34-40; contact.html:150 | Drop. |
| LPS-03 | bug | Tracking error handling conflates "unknown code" with "service down", keeps a stale heading, can print "undefined", does not trim, has no loading state and no Enter key (§3.5). | js/custom.js:43-65 | Fix: the new site's widget (chapter 06 §5.7). |
| LPS-04 | inconsistency | Two WhatsApp numbers: the free-quote text on slide 3 opens a chat with +39 328 929 9463, every other link uses +39 329 239 6962. The second number is never displayed and is not among the owner's contacts. | index.html:185 vs 47, 188, 337, 391, 406 | Fix: one number from configuration (PUB-02); ask the owner (Q-15). |
| LPS-05 | accessibility, SEO | No `lang` on `<html>`; empty meta description; title starting with a space; icon-only links without accessible names; Instagram and e-mail images with alt "Sanchez-Logistica-Whatsapp"; the info-band address link `href=""` reloads the page; footer link `href="#"`. | index.html:2, 12, 16, 188-189, 332, 342, 385, 406-407, 442 | Fix (the new site already does). |
| LPS-06 | content | Template pages with Lorem ipsum, fake contacts and dead forms belong to the site (orphaned but reachable). Their contact form has no action and its inputs have no `name`, so it sends nothing. | contact.html:126-144; about.html; service.html | Drop; 301 their URLs (§2.1). |
| LPS-07 | content | Typos and naming: "Gratutito", "In tutta italia", "Lombardia Torino e Verona" (no comma); the brand appears as "Sanchez Logistica" (title, footer) and as "Logistica Sanchez" (hero). | index.html:16, 128, 130-131, 154, 183, 442 | Fix: legal name "Logistica Sanchez S.r.l." and slogan "Il pallet espresso" (owner). |
| LPS-08 | performance | Unused Owl Carousel; Font Awesome loaded twice; three font families for one used; a 622 KB WebP named `.png` shown at 20 % width; a 736 KB 1872×2048 photo in a half-width column; the Maps JavaScript API on every page. | index.html:23, 26, 29-30, 332, 456, 461; css/style.css:1 | Drop. |
| LPS-09 | bug | The tracking box's CSS targets `form input` and `form button`, but the markup has no form: the controls render unstyled (inferred). | css/style.css:578-637; tracking_section.txt:16-19 | Drop. |
| LPS-10 | repository | `origin/dev` (`203c11a`) references images added only in `8d6fa0a` and `nav-bullet.png`, which never existed: broken images if that state were deployed. `README.md` is UTF-16 (re-saved as UTF-8 in the new site, ls-home@3bc229a). | ls-home@dev:index.html:72, 371, 376, 381, 456-468 | Use `origin/v1` as the reference. |

## 6. Production delta

### 6.1 Known (owner)

| Fact | Source |
|---|---|
| The live public site is `https://logisticasanchez.com`. | owner |
| It is a separate codebase, not in these repositories: a rewrite of the ls-home v1 template with another UI library. | owner |
| Its only known backend call: `POST https://app.logisticasanchez.com/mono/api/order/trace/state` with JSON body `{"codice":"<code>"}`, sent from `Origin: https://logisticasanchez.com` (the owner's curl). | owner |
| The production backend lives at `https://app.logisticasanchez.com/mono/...`. The repositories still point to the UAT host `http://uat-sanchez-logistica.eu-north-1.elasticbeanstalk.com`, and a comment mentions `https://api.logisticasanchez.com`. | owner; js/custom.js:68; ls-cms@dev:js/constant/rest-constant.js:2-3 |
| Order portal today: `https://ordini.logisticasanchez.com`; its older address is the S3 "fe" endpoint; the old separate tracking app is the S3 "tracking" endpoint (§2.3). | owner |
| Company data the site must show: legal name and slogan, P.IVA 13113080967, address, phone and WhatsApp +39 329 239 6962, e-mail, hours Mon–Fri 8–18 and Sat 8–12, Instagram sanchez_logistica_mb. | owner (chapter 01) |

### 6.2 Inferred

* Since its only known backend call is tracking, the live site has a tracking box or page of
  its own (inferred), and its contact or quote paths, if any, do not use the backend
  (inferred).
* The backend accepts browser calls from `https://logisticasanchez.com` (inferred from the
  owner's curl). The new site's README presents it as the only allowed origin
  (ls-home@fe4b056:README.md:87-92): an assumption, not a verified fact (LPS-U9).
* **Live-site copy.** Commit ls-home@610d2e9, titled "Use the real copy and business
  details from logisticasanchez.com", put the texts below into the new site. That message is
  the only evidence that they come from the live site; treat them as (inferred), and have
  the owner confirm the numbers before they are published.

| Item | Text (verbatim) | Where it landed |
|---|---|---|
| Tagline | "Il tuo partner affidabile per spedizioni express in tutta Italia. Soluzioni logistiche flessibili e personalizzate." | ls-home@610d2e9:partials/footer.html:5 |
| Hours, legal line | "Lun–Ven 8–18 · Sab 8–12"; "Logistica Sanchez S.r.l. – Il pallet espresso · P.IVA 13113080967" | ls-home@610d2e9:partials/footer.html:50, 57 (match the owner's data) |
| Hero | "Specializzati nella distribuzione di merce su tutto il territorio nazionale, con una rete capillare di partner professionisti. Consegne garantite da 24 a 72 ore, con tracking in tempo reale." | ls-home@610d2e9:public/index.html:151 |
| Badges | "Copertura nazionale", "Partner certificati", "Tracking in tempo reale"; "100%" "Italia coperta" | ls-home@610d2e9:public/index.html:157-159, 175 |
| Stats | "2000+" "spedizioni effettuate", "100+" "città servite", "100+" "clienti soddisfatti", "10+" "anni di esperienza" | ls-home@610d2e9:public/index.html:235-238 |
| About | "Logistica Sanchez S.r.l. offre distribuzione e trasporto di merci su tutto il territorio nazionale, con una specializzazione nel servizio “pallet espresso” per piccole e medie imprese." / "Attraverso una rete capillare di partner certificati garantiamo copertura diretta e flessibile del territorio. Ogni partner è direttamente coinvolto nella distribuzione e nella raccolta, per performance eccellenti e un’assistenza dedicata." | ls-home@610d2e9:public/chi-siamo/index.html:148-149. A rewrite of v1's "Chi Siamo" (index.html:278-285), consistent with "rewrite of v1". |
| FAQ | "I tempi standard sono di 24–72 ore per le principali città italiane; per le zone più remote possono arrivare fino a 96 ore. È disponibile anche un servizio express con consegna garantita in 24 ore." / "Sì, tutte le spedizioni sono coperte da un’assicurazione base." | ls-home@610d2e9:public/faq/index.html:157, 180 |
| Links | "Portale ordini" → `https://ordini.logisticasanchez.com`; Instagram | ls-home@610d2e9:partials/header.html:11, 88; ls-home@610d2e9:partials/footer.html:21, 51 |

### 6.3 Unknown

| ID | Unknown | Why it matters | How to find out |
|---|---|---|---|
| LPS-U1 | The live site's URL inventory: pages, anchors, query strings, sitemap, `www` or apex. | Every live URL needs an answer after the switch (PUB-04). | The owner, or a crawl of the live site, Search Console, hosting logs; before the cut-over. |
| LPS-U2 | How the live site shows tracking: in-page box or own page, wording, trimming and case of the code, display of the six states. | Customers' expectations; the new UI replaces it anyway. | Look at the live site. |
| LPS-U3 | Exact answers of `trace/state`: `status` as label or name (Q-03); HTTP status and body for an unknown, empty or whitespace code; other fields; case sensitivity (TRK-02). | Compatibility endpoint (API-01). | One real call per case before the cut-over, or the backend source (Q-01). |
| LPS-U4 | The separate tracking app on S3: source, behaviour, whether it is still online and who links to it. | Redirect of its host (§2.5). | Owner, AWS console. |
| LPS-U5 | Whether the S3 buckets `sanchez-logistica-fe` and `sanchez-logistica-tracking` still serve, in which AWS account; whether printed material, e-mails, signatures or the Google Business Profile link old hosts or assets. | Redirects, LPS-S4. | Owner, AWS console. |
| LPS-U6 | Contacts and forms on the live site: which phone numbers (the second WhatsApp number? Q-15), whether it has contact or quote forms and where they go. | Parity of contact paths. | Look at the live site. |
| LPS-U7 | Analytics, cookies and consent on the live site; whether it still loads the Maps key; its hosting, CDN and TLS; who manages DNS (Q-16). | Privacy pages, cut-over. | Owner. |
| LPS-U8 | Whether ls-home v1 was ever published, and whether the Maps key belongs to the company. | LPS-S1 remediation. | Owner, Google Cloud console. |
| LPS-U9 | The backend's CORS allow-list (`www`? the S3 origins? others?) and whether it checks `Origin` at all. | Compatibility endpoint, staging tests. | One preflight per origin, or the backend source. |

### 6.4 Questions for the owner

Merged into chapter 11: the live site's URL list and the old S3 buckets → Q-29; the Google
Maps key → Q-23; the backend's CORS origins → Q-39. Other questions that touch this
chapter: Q-03 (`status` label or name), Q-06 (code format), Q-15 (second WhatsApp number),
Q-16 (hosting, DNS), Q-20 (retire the Netlify site).
