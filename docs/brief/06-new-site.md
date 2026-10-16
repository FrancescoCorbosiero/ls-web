# 06 · New static site (ls-home) — porting guide for the public site

This chapter documents the new public website in repository `ls-home`, branch
`claude/determined-lovelace-ggeitw`, head commit `fe4b056` (16 Oct 2026). Its pages,
design system and widgets are the base of the monolith's public site (chapter 01,
principle 2; requirements PUB-01…10 and TRK-01 in chapter 09). It is **not** the site
that is live today: production `https://logisticasanchez.com` runs another codebase
(owner; chapter 05), and the README's "Before going live" checklist is still unticked
(ls-home@fe4b056:README.md:212-225), so (inferred) this site has never been published.

Two later commits on the branch change two details; **port from the branch head `9b78785`**:
`ff58d4f` shows a neutral message for the state `Non gestito`, which the backend also
returns for unknown codes (§5.7), and `9b78785` passes `/mono/*` on to the legacy backend
for the links in its e-mails (§6.3). Citations stay at `fe4b056`; only
`public/js/tracking.js`, `public/tracking/index.html`, `public/_redirects` and `README.md`
differ.

Citations: `ls-home@fe4b056:<path>:<line>` is the commit above. Other refs used:
`ls-home@v1` (old template site), `ls-cms@dev` (CMS), `owner` (stated by the owner).
In quoted Italian strings, `{…}` marks a value filled in at run time (e.g. "Spedizione
{code}"); `…` marks a shortened quote.

## 1. At a glance

| | |
|---|---|
| Kind | Plain static site: HTML, one CSS file, 8 small ES modules; no framework, no build step (ls-home@fe4b056:README.md:6-9) |
| Pages | 26 HTML files: 24 indexable + `/grazie/` + `404.html` (sync check below) |
| Hosting | Netlify: publishes `public/`, functions from `netlify/functions` (ls-home@fe4b056:netlify.toml:4-11) |
| Server code | One Netlify Function, `POST /api/form`, e-mails the quote/contact forms with Amazon SES (ls-home@fe4b056:netlify/functions/form.mjs:19) |
| Backend calls | One: tracking, `POST https://app.logisticasanchez.com/mono/api/order/trace/state` (ls-home@fe4b056:public/js/config.js:31) |
| Third parties | Google Maps iframe only after the visitor's consent (a click, optionally remembered on the device) (ls-home@fe4b056:public/js/map.js:8-39); `wa.me`, `tel:`, `mailto:` links |
| Cookies | None set by the site; two `localStorage` keys (ls-home@fe4b056:public/cookie/index.html:163-164) |
| Weight | `main.css` 87,455 B (16.5 KB gzip); the 8 JS files 47,112 B together (15.5 KB gzip); home HTML 53,206 B (11.4 KB gzip) — measured on `fe4b056` |
| Health | `node tools/sync.mjs --check`, run on a copy on 2026-10-16: `26 pages, 24 in sitemap, 0 out of sync, 0 errors, 0 warnings` |
| Secrets | None in the repository: SES credentials are environment variables (ls-home@fe4b056:netlify/functions/form.mjs:10-15) |

## 2. Repository layout

| Path | Role | In the monolith |
|---|---|---|
| `public/` | The website, published as is (ls-home@fe4b056:README.md:13-32) | Static resources + one template per page |
| `partials/head.html`, `header.html`, `cta.html`, `footer.html` | Shared fragments, copied into every page by the sync tool | Template fragments (§4.10, §8.2) |
| `tools/sync.mjs` | Injects partials, marks the active menu item, rebuilds `sitemap.xml`, checks SEO basics, switches domain (ls-home@fe4b056:tools/sync.mjs:8-21) | Replaced by fragments, a sitemap route and CI checks |
| `tools/pallets.py` | Generates the four isometric pallet SVGs to scale, 2 px per cm (ls-home@fe4b056:tools/pallets.py:2-12) | Optional dev tool; its SVG outputs are static assets |
| `site.config.json` | Production URL `https://logisticasanchez.com` and partial list (ls-home@fe4b056:site.config.json:2-3) | `SITE_URL` setting |
| `netlify.toml` | Publish dir and functions dir | Not needed |
| `netlify/functions/form.mjs` | Quote/contact handler, Amazon SES | Ported to the `site` module (§5.6) |
| `package.json` | Only `@aws-sdk/client-sesv2` `3.1145.0`, Node ≥ 20 (ls-home@fe4b056:package.json:5-10) | Not needed |
| `public/_headers`, `public/_redirects` | Security/caching headers, 301s | App configuration (§6) |
| `README.md` | Operations guide, tracking contract, SES setup, go-live checklist | Reference |

## 3. Page inventory

### 3.1 URLs and metadata

Every indexable page has `<meta name="robots" content="index, follow, max-image-preview:large">`,
`<link rel="canonical">` = `https://logisticasanchez.com` + URL, `og:url` = canonical,
`og:image` = `https://logisticasanchez.com/img/og/<file>` (1200 × 630), `og:image:alt`
= the title (home: "Logistica Sanchez, il pallet espresso"), `og:type` `website`
(`article` on blog posts) and one `<h1>` (checked by ls-home@fe4b056:tools/sync.mjs:150-161).
`/grazie/` and `404.html` have `noindex, follow`, no canonical and no Open Graph tags
(ls-home@fe4b056:public/grazie/index.html:25, ls-home@fe4b056:public/404.html:25).
"CTA" = the page includes the `cta` partial (the CTA band above the footer).

| URL | `<title>` | `<h1>` | `data-page` | CTA | OG image |
|---|---|---|---|---|---|
| `/` | Spedizione pallet in Italia in 24–72h \| Logistica Sanchez | Il pallet espresso. Spedizioni di pallet in tutta Italia in 24–72 ore. | `home` | yes | `og-default.png` |
| `/servizi/` | Servizi di spedizione pallet e logistica \| Logistica Sanchez | Un servizio per ogni spedizione, dal pallet al camion | `servizi` | yes | `servizi.png` |
| `/servizi/spedizione-pallet/` | Spedizione pallet espresso in tutta Italia \| Logistica Sanchez | Spedizione pallet espresso in tutta Italia | `servizi/spedizione-pallet` | yes | `servizi-spedizione-pallet.png` |
| `/servizi/groupage/` | Groupage e collettame in tutta Italia \| Logistica Sanchez | Groupage e collettame: paghi solo lo spazio che occupi | `servizi/groupage` | yes | `servizi-groupage.png` |
| `/servizi/carico-completo/` | Carico completo e mezzi dedicati (FTL) \| Logistica Sanchez | Carico completo: un mezzo tutto per la tua merce | `servizi/carico-completo` | yes | `servizi-carico-completo.png` |
| `/servizi/magazzino/` | Magazzino e cross-docking a Seregno (MB) \| Logistica Sanchez | Magazzino e cross-docking in Brianza | `servizi/magazzino` | yes | `servizi-magazzino.png` |
| `/zone/` | Zone servite e tempi di consegna \| Logistica Sanchez | Zone servite e tempi di consegna | `zone` | yes | `zone.png` |
| `/zone/monza-brianza/` | Spedizione pallet Monza e Brianza \| Logistica Sanchez | Spedizione pallet a Monza e in Brianza | `zone/monza-brianza` | yes | `zone-monza-brianza.png` |
| `/zone/milano/` | Spedizione pallet Milano e provincia \| Logistica Sanchez | Spedizione pallet a Milano e provincia | `zone/milano` | yes | `zone-milano.png` |
| `/zone/como/` | Spedizione pallet Como e provincia \| Logistica Sanchez | Spedizione pallet a Como e provincia | `zone/como` | yes | `zone-como.png` |
| `/zone/lecco/` | Spedizione pallet Lecco e provincia \| Logistica Sanchez | Spedizione pallet a Lecco e provincia | `zone/lecco` | yes | `zone-lecco.png` |
| `/zone/bergamo/` | Spedizione pallet Bergamo e provincia \| Logistica Sanchez | Spedizione pallet a Bergamo e provincia | `zone/bergamo` | yes | `zone-bergamo.png` |
| `/tracking/` | Traccia la tua spedizione \| Logistica Sanchez | Traccia la tua spedizione | `tracking` | yes | `tracking.png` |
| `/preventivo/` | Richiedi un preventivo gratuito \| Logistica Sanchez | Richiedi un preventivo per la tua spedizione | `preventivo` | no | `preventivo.png` |
| `/contatti/` | Contatti e dove siamo \| Logistica Sanchez | Parliamo della tua spedizione | `contatti` | no | `contatti.png` |
| `/chi-siamo/` | Chi siamo \| Logistica Sanchez, il pallet espresso | Il pallet espresso, dalla Brianza a tutta Italia | `chi-siamo` | yes | `chi-siamo.png` |
| `/faq/` | FAQ sulle spedizioni di pallet \| Logistica Sanchez | Domande frequenti | `faq` | yes | `faq.png` |
| `/blog/` | Blog: guide per spedire pallet \| Logistica Sanchez | Spedire meglio, spendere meno | `blog` | yes | `blog.png` |
| `/blog/come-preparare-un-pallet-per-la-spedizione/` | Come preparare un pallet per la spedizione \| Logistica Sanchez | Come preparare un pallet per la spedizione: la guida pratica | `blog` | yes | `blog-come-preparare-un-pallet-per-la-spedizione.png` |
| `/blog/misure-pallet-epal-industriale/` | Misure pallet EPAL e industriale \| Logistica Sanchez | Misure dei pallet: EPAL, industriale, mezzo e quarto a confronto | `blog` | yes | `blog-misure-pallet-epal-industriale.png` |
| `/blog/quanto-costa-spedire-un-pallet/` | Quanto costa spedire un pallet? \| Logistica Sanchez | Quanto costa spedire un pallet? I fattori che fanno il prezzo | `blog` | yes | `blog-quanto-costa-spedire-un-pallet.png` |
| `/blog/groupage-o-carico-completo/` | Groupage o carico completo: come scegliere \| Logistica Sanchez | Groupage o carico completo: come scegliere | `blog` | yes | `blog-groupage-o-carico-completo.png` |
| `/privacy/` | Privacy policy \| Logistica Sanchez | Privacy policy | `privacy` | no | `privacy.png` |
| `/cookie/` | Cookie policy \| Logistica Sanchez | Cookie policy | `cookie` | no | `cookie.png` |
| `/grazie/` (noindex) | Grazie, richiesta ricevuta \| Logistica Sanchez | Grazie, richiesta ricevuta! | `grazie` | no | — |
| `/404.html` (noindex) | Pagina non trovata \| Logistica Sanchez | Questo pallet si è perso per strada. | `404` | no | — |

Meta descriptions are unique per page (warned by ls-home@fe4b056:tools/sync.mjs:232-233);
copy them verbatim from each page's `<head>` (around line 24 of every page). Two of them
contain the entity `&#x27;` (apostrophe), which browsers decode (`/chi-siamo/`, `/zone/milano/`).

### 3.2 Content, widgets and structured data per page

Widget names refer to §5. "Breadcrumb" = visible `nav.breadcrumbs` plus a
`BreadcrumbList` node. The site-wide `LocalBusiness` + `WebSite` graph (§3.3) is on
every page, including `/grazie/` and 404.

| URL | Purpose and main blocks | Widgets | Page JSON-LD |
|---|---|---|---|
| `/` | Hero (kicker, h1, lede, "Richiedi un preventivo" → `/preventivo/`, "Effettua un ordine" → order portal, trust list, responsive photo, badges "24–72h" and "100%"); quick tools `#strumenti` (3 tabs: tracking, delivery time, quick quote); stats; 4 service cards + 6 accessory chips; 4 steps; dark coverage section (`#copertura`) with legend, CAP box and map; 6 "Perché sceglierci" features; brand band; pallet guide (`#guida-pallet`, 4 formats with specs and prefill links); 8 sectors; 3 blog teasers; 6 FAQ (ls-home@fe4b056:public/index.html:142-603) | tabs, eta (×2), coverage, reveal | `WebPage` |
| `/servizi/` | "Quale servizio fa per te?" chooser; 4 service blocks with anchors `#spedizione-pallet`, `#groupage`, `#carico-completo`, `#magazzino`; 6 accessory services (`#accessori`); comparison table (ls-home@fe4b056:public/servizi/index.html:154-290) | reveal | `CollectionPage`, Breadcrumb, `ItemList` (4) |
| `/servizi/spedizione-pallet/` | "In breve"; 4 steps; delivery-time table + CAP box; formats and "Il pallet perfetto, in breve"; 4 FAQ; related services | eta, reveal | `WebPage`, Breadcrumb, `Service`, `FAQPage` (4) |
| `/servizi/groupage/` | 4 steps; pros/cons panels; "Peso reale o peso tassabile?" with a worked example; 4 FAQ; related | reveal | `WebPage`, Breadcrumb, `Service`, `FAQPage` (4) |
| `/servizi/carico-completo/` | Hero with a responsive photo (`.hero-photo-card`) instead of the aside; 4 features; vehicles (Furgone, Motrice, Bilico); when it pays off; 3 FAQ; related | reveal | `WebPage`, Breadcrumb, `Service`, `FAQPage` (3) |
| `/servizi/magazzino/` | 6 features; location panel with "Chiedi una proposta" → `/preventivo/?servizio=magazzino` (ls-home@fe4b056:public/servizi/magazzino/index.html:197); 3 FAQ; related | reveal | `WebPage`, Breadcrumb, `Service`, `FAQPage` (3) |
| `/zone/` | Hero CAP box; 6 zone cards (5 local pages + "Altre zone" → `/contatti/`); dark coverage section with legend and map; 20-region table; 4 special cases; 3 FAQ (ls-home@fe4b056:public/zone/index.html:141-255) | eta, coverage, reveal | `WebPage`, Breadcrumb, `FAQPage` (3) |
| `/zone/<provincia>/` (5) | Local landing page, one template (§3.4) | eta, reveal | `WebPage`, Breadcrumb, `Service` (`AdministrativeArea`), `FAQPage` (3) |
| `/tracking/` | Hero with the tracking form, recent codes, `<noscript>` note and result box; legend of the 6 states; "Dove trovo il codice?"; 3 FAQ (ls-home@fe4b056:public/tracking/index.html:141-193) | tracking, reveal | `WebPage`, Breadcrumb, `FAQPage` (3) |
| `/preventivo/` | 4-step quote wizard + aside ("Cosa succede dopo", "Preferisci parlarne?", 3 ticks) (ls-home@fe4b056:public/preventivo/index.html:150-357) | quote (+ forms, eta) | `WebPage`, Breadcrumb |
| `/contatti/` | Hero with WhatsApp/phone buttons and office hours; 4 contact cards; contact form; `#dove-siamo` with map facade and directions links (ls-home@fe4b056:public/contatti/index.html:141-252) | forms, map, reveal | `ContactPage`, Breadcrumb |
| `/chi-siamo/` | Hero text + logo card; "Perché «il pallet espresso»"; 4 values; 4 method steps; hub section with map facade (ls-home@fe4b056:public/chi-siamo/index.html:141-225) | map, reveal | `AboutPage`, Breadcrumb |
| `/faq/` | 5 groups, 18 questions (`<details name="faq-g0…g4">`), WhatsApp button (ls-home@fe4b056:public/faq/index.html:141-185) | reveal | `FAQPage` (18, is the page node), Breadcrumb |
| `/blog/` | Featured post + 3 cards (ls-home@fe4b056:public/blog/index.html:150-189) | — | `CollectionPage`, Breadcrumb, `Blog` (4 posts) |
| `/blog/<slug>/` (4) | Article template (§3.4) | share, toc, reveal | `WebPage`, Breadcrumb, `BlogPosting` |
| `/privacy/` | Legal prose, "Ultimo aggiornamento: 16 ottobre 2026" (ls-home@fe4b056:public/privacy/index.html:152) | — | `WebPage`, Breadcrumb |
| `/cookie/` | Legal prose, storage table, "Revoca il consenso alle mappe" button (ls-home@fe4b056:public/cookie/index.html:152-177) | maps-revoke | `WebPage`, Breadcrumb |
| `/grazie/` | Landing after a form post without JavaScript; WhatsApp button; 3 cards (guide, delivery times, tracking) (ls-home@fe4b056:public/grazie/index.html:130-154) | — | — |
| `/404.html` | "404" + h1; 4 cards (tracking, quote, services, blog) (ls-home@fe4b056:public/404.html:130-154) | — | — |

FAQ answers in JSON-LD repeat the visible answers as plain text (links removed). The 6
home-page FAQ have no `FAQPage` node.

### 3.3 Site-wide structured data (head partial)

`partials/head.html` carries one JSON-LD graph with `{{site}}` placeholders
(ls-home@fe4b056:partials/head.html:16):

* `LocalBusiness` `@id` `{{site}}/#business`: `name` "Logistica Sanchez", `legalName`
  "Logistica Sanchez S.r.l.", `vatID` "IT13113080967", `alternateName` ["Sanchez
  Logistica", "Logistica Sanchez – Il pallet espresso"], `slogan` "Il pallet espresso",
  `description`, `url`, `logo` (`/img/brand/logo-square.png`, 512 × 512), `image`
  (`/img/og/og-default.png`), `telephone` "+393292396962", `email`, `address`
  (`PostalAddress`: "Via dei Meiani, 90", "20831", "Seregno", "MB", "IT"), `hasMap`
  (Google Maps search URL), `areaServed` Country "Italia",
  `openingHoursSpecification` Mon–Fri 08:00–18:00 and Sat 08:00–12:00, `contactPoint`
  (customer service, Italian), `sameAs` [Instagram], `knowsAbout` (7 topics),
  `hasOfferCatalog` with four `Offer` → `Service` references to
  `{{site}}/servizi/<slug>/#service`.
* `WebSite` `@id` `{{site}}/#website`, `inLanguage` "it-IT", `publisher` → `#business`.

Page graphs reuse fixed `@id`s: `<url>#webpage`, `<url>#breadcrumb`, `<url>#service`,
`<url>#faq`, `<url>#article`, `/blog/#blog`, `/servizi/#list`. Every page node has
`isPartOf` → `#website`, `about` → `#business` and `primaryImageOfPage` = its OG image.

### 3.4 Page families (shared templates)

**Service pages** (4): page hero with "In breve" aside (carico-completo shows the photo
instead, ls-home@fe4b056:public/servizi/carico-completo/index.html:154-160), sections
listed in §3.2, `FAQPage`, "Altri servizi" grid of the other three. `Service` node:
`serviceType`, `provider` → `#business`, `areaServed` Country "Italia",
`availableChannel` (`servicePhone` "+393292396962", `serviceUrl` `/preventivo/`).

**Local zone pages** (5): identical structure and line positions
(ls-home@fe4b056:public/zone/monza-brianza/index.html:140-226; the other four have the
same line numbers): breadcrumb Home › Zone servite › zone; kicker "Zone servite · {zone}";
hero buttons "Richiedi un preventivo" and WhatsApp with a zone text; "In breve" aside
(4 ticks, line 156); two intro paragraphs; CAP box (line 173) + 3-row delivery-time
table; 3 features (line 196); "Comuni serviti" list (line 207); 3 FAQ (line 217); chips
to the other zones and "Tutta Italia" (line 222). `Service` node with `areaServed` =
[province, "Lombardia"] as `AdministrativeArea`.

| Page | `areaServed` province | Features (icon) | FAQ questions | Towns |
|---|---|---|---|---|
| `monza-brianza` | Provincia di Monza e della Brianza | A pochi minuti da te (`map-pin`) · Arredo e design (`sofa`) · Ritiri programmati (`repeat`) | Fate ritiri in giornata in Brianza? · Posso portare la merce direttamente al vostro hub di Seregno? · Spedite mobili e arredi? | 28 |
| `milano` | Città metropolitana di Milano | Consegne su appuntamento (`calendar-clock`) · Hinterland e poli produttivi (`factory`) · Scarico senza muletto (`arrow-down-to-line`) | Consegnate nel centro di Milano? · Quanto ci mettete a ritirare a Milano? · Fate consegne a privati a Milano? | 24 |
| `como` | Provincia di Como | Tessile e moda (`shirt`) · Legno e arredo (`sofa`) · Lago e valli (`route`) | Ritirate anche nei paesi sul lago di Como? · Spedite tessuti e rotoli? · Effettuate spedizioni verso la Svizzera? | 20 |
| `lecco` | Provincia di Lecco | Metalmeccanica (`factory`) · SS36, la via diretta (`route`) · Scarico in sicurezza (`arrow-down-to-line`) | Spedite pallet molto pesanti? · Ritirate anche in Valsassina? · Posso avere ritiri fissi ogni settimana? | 19 |
| `bergamo` | Provincia di Bergamo | Distretti produttivi (`factory`) · Dal groupage al completo (`truck`) · Spedizioni tracciate (`package-search`) | Ritirate anche nelle valli bergamasche? · Conviene il groupage o il carico completo? · Quanto tempo serve da Bergamo a Roma? | 20 |

WhatsApp text of the zone hero: "Ciao Logistica Sanchez, vorrei spedire dei pallet dalla
zona di {Monza e Brianza|Milano|Como|Lecco|Bergamo}." The town lists are in the HTML at
line 207 of each page; README flags them for owner review (§7.3).

**Blog posts** (4): `<article>` with a page-hero header (breadcrumb, kicker = section,
h1, lede, `.article-meta`: date, "N minuti di lettura", "Redazione Logistica Sanchez"),
table of contents (`nav.toc`, sticky from 1100px: ls-home@fe4b056:public/css/main.css:3986-3989), `.prose` body with callouts, an inline CTA, a
table, share buttons (WhatsApp, LinkedIn, "Copia il link"), then "Altre guide utili"
with the other three posts. Extra head tags: `article:published_time`,
`article:modified_time`, `article:section`
(ls-home@fe4b056:public/blog/come-preparare-un-pallet-per-la-spedizione/index.html:35-37).

| Slug | Section | Published = modified | Words | Reading time | TOC anchors |
|---|---|---|---|---|---|
| `come-preparare-un-pallet-per-la-spedizione` | Guide | 2026-10-06 | 740 | 4 min | `#bancale #disposizione #peso-altezza #fissaggio #etichette #checklist` |
| `misure-pallet-epal-industriale` | Guide | 2026-09-29 | 613 | 4 min | `#perche #tabella #epal #industriale #ridotti #camion #scegliere` |
| `quanto-costa-spedire-un-pallet` | Costi | 2026-09-22 | 567 | 3 min | `#prezzo-unico #peso #formato #destinazione #servizi #tempi #volumi #preventivo` |
| `groupage-o-carico-completo` | Logistica | 2026-09-08 | 454 | 3 min | `#definizioni #groupage #completo #confronto #quando #esempi` |

`BlogPosting` fields: `headline`, `description`, `datePublished`, `dateModified`,
`inLanguage`, `url`, `mainEntityOfPage`, `image` (OG image), `author` (Organization →
`#business`), `publisher`, `articleSection`, `wordCount`, `keywords`, `isPartOf` →
`/blog/#blog`.

**Utility pages**: `/grazie/` and `404.html` use `.notfound` inside a page hero, no
breadcrumb, no CTA band. `/grazie/` lede: "Ti ricontattiamo al più presto, negli orari
d'ufficio (lunedì–venerdì 8–18, sabato 8–12). Se la spedizione è urgente, scrivici anche
su WhatsApp." (ls-home@fe4b056:public/grazie/index.html:136).

## 4. Design system

### 4.1 Foundations

* One file, `public/css/main.css` (4,579 lines), vanilla CSS, mobile-first, ordered
  fonts → tokens → base → layout → components → sections → pages → utilities →
  motion/print; palette "brand navy + teal (legacy site) + lime from the logo square"
  (ls-home@fe4b056:public/css/main.css:1-6).
* Light theme only (`color-scheme: light`, ls-home@fe4b056:public/css/main.css:116); no
  dark mode. No inline styles or scripts anywhere in the HTML (required by the CSP, §6.4).
* Modern CSS used: `:has()` (checked choice cards, `html:has(dialog[open])`),
  `text-wrap: balance/pretty`, `backdrop-filter` under `@supports`, logical properties.

### 4.2 Fonts

Self-hosted WOFF2, Latin subset, `font-display: swap`, SIL OFL licence file in
`public/fonts/OFL-barlow.txt` (ls-home@fe4b056:public/css/main.css:8-57).

| Family | Weights (files) | Use | Fallback stack |
|---|---|---|---|
| Barlow Condensed | 600, 700, 800 (`barlow-condensed-<w>.woff2`) | `--font-display`: h1–h4, hero, stats, legends; CSS asks for 750–900 in places, rendered with the nearest file | "Arial Narrow", Arial, sans-serif |
| Barlow | 400, 500, 600, 700 (`barlow-<w>.woff2`) | `--font-body` | system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif |

Preloaded on every page: `barlow-condensed-700` and `barlow-400`
(ls-home@fe4b056:partials/head.html:3-4). Each file is about 21 KB.

### 4.3 Colour tokens (`:root`, ls-home@fe4b056:public/css/main.css:60-92)

| Token | Value | Token | Value |
|---|---|---|---|
| `--navy-950` | `#021a2c` | `--ink` | `#0c1d2c` |
| `--navy-900` (brand, `theme-color`) | `#04233b` | `--ink-2` | `#33475b` |
| `--navy-800` | `#0a3352` | `--mute` | `#586c80` |
| `--navy-700` | `#13466d` | `--line` | `#d8e1e9` |
| `--navy-600` | `#1f5b87` | `--line-2` | `#e7edf2` |
| `--teal-700` | `#066a7b` | `--paper` | `#ffffff` |
| `--teal-600` (links) | `#097489` | `--paper-2` | `#f4f7fa` |
| `--teal-500` (focus ring) | `#0a97b0` | `--paper-3` | `#e9eff4` |
| `--teal-300` | `#86cbd8` | `--wa` | `#25d366` |
| `--teal-100` | `#d7eff3` | `--wa-ink` | `#128c4b` |
| `--teal-50` | `#edf8fa` | `--ok` | `#1d7f47` |
| `--lime-400` | `#dbe878` | `--warn` | `#a35f00` |
| `--lime-300` (accent) | `#e8f197` | `--err` | `#b8322a` |
| `--lime-200` | `#f1f7c3` | `--zone-1` (24 h) | `#e8f197` |
| `--lime-100` | `#f8fbe4` | `--zone-2` (48 h) | `#86cbd8` |
| | | `--zone-3` (72 h) | `#3d7fa3` |

The e-mail sent by the form handler uses its own inline palette: `#0b2239` text and
buttons, `#5b6b7b` muted text, `#f3f5f7` background
(ls-home@fe4b056:netlify/functions/form.mjs:179-205).

### 4.4 Layout, spacing and shape tokens

| Token / rule | Value | Source |
|---|---|---|
| `--container` | 1200px (+ 2 gutters); `.container-narrow` 820px | ls-home@fe4b056:public/css/main.css:108, 291-299 |
| `--gutter` | `clamp(16px, 4.5vw, 32px)` | :109 |
| `--section-y` | `clamp(64px, 9vw, 116px)`; `.section-tight` = 0.6 × | :110, 300-306 |
| `--header-h` | 64px, 76px from 1024px (used by `scroll-padding-top`) | :111, 118-122, 133 |
| Radii | `--r-xs` 6px, `--r-sm` 10px, `--r` 14px, `--r-lg` 20px, `--r-xl` 28px | :97-101 |
| Shadows | `--shadow-xs`, `--shadow-sm`, `--shadow`, `--shadow-lg` (navy-tinted) | :103-106 |
| Easing | `--ease` `cubic-bezier(0.2, 0.7, 0.2, 1)`, `--ease-out` `cubic-bezier(0.16, 1, 0.3, 1)` | :113-114 |
| Spacing utilities | `.mt-s` 16px, `.mt-m` 32px, `.mt-l` 56px, `.mt-0`, `.mb-0` | :4435-4449 |

### 4.5 Typography scale

| Element | Size | Weight / line-height | Source |
|---|---|---|---|
| `body` | 1rem; 1.0625rem from 768px | 400 / 1.65, colour `--ink` | ls-home@fe4b056:public/css/main.css:143-159 |
| `h1` | `clamp(2.2rem, 1.35rem + 3.6vw, 4rem)`; in `.page-hero` `clamp(2rem, 1.3rem + 3vw, 3.5rem)` | 800 / 1.08, display font, navy | :190-204, 3090-3094 |
| `h2` | `clamp(1.75rem, 1.25rem + 2.1vw, 2.75rem)` | 800 / 1.08 | :205-207 |
| `h3` | `clamp(1.12rem, 1.03rem + 0.4vw, 1.3rem)` | 800 / 1.2 | :208-212 |
| `h4`, `.h4` | 1.05rem | 800 / 1.3 | :213-216, 4366-4370 |
| Home hero title | main `clamp(2.7rem, 1.25rem + 6.6vw, 5.5rem)`; sub `clamp(1.15rem, 0.98rem + 0.85vw, 1.6rem)` | 860 / 0.98; sub 700 teal-300 | :1142-1156 |
| `.lede` | `clamp(1.05rem, 1rem + 0.3vw, 1.22rem)` | 1.6, `--ink-2` | :344-348 |
| `.kicker` | 0.8rem uppercase, letter-spacing .12em, lime square before | 750, teal-600 | :383-412 |
| `.note`, `.hint`, `.field-hint` | 0.85rem | `--mute` | :2179-2183, 1506-1512 |

### 4.6 Breakpoints

Mobile-first `min-width` queries at **480, 640, 700, 768, 900, 1024, 1100, 1200, 1280 px**
and `max-width` refinements at 359, 479, 599, 767, 1023 px; plus `(hover: hover)`,
`prefers-reduced-motion` and `print` (ls-home@fe4b056:public/css/main.css, 19 distinct
media queries). The key switch is **1024px**: desktop nav and topbar appear, burger and
drawer go away, the mobile action bar is hidden and the WhatsApp floating button appears
(ls-home@fe4b056:public/css/main.css:548-552, 665-672, 895-920, 2951-3004, 3005-3034).

### 4.7 Component classes

| Group | Classes |
|---|---|
| Layout | `container`, `container-narrow`, `section`, `section-tight`, `section-alt`, `section-dark`, `section-head` (`.center`), `section-head-row`, `grid-2` (`.wide-left`), `split` (`.is-center`), `layout-aside`, `aside-stack`, `article-layout`, `coverage-grid`, `why-grid` |
| Text | `kicker`, `lede`, `h4`, `note`, `hint`, `muted`, `prose`, `legal`, `legal-meta`, `text-center`, `nowrap`, `visually-hidden` |
| Buttons, links | `btn` + `btn-accent`, `btn-secondary`, `btn-outline-light`, `btn-ghost`, `btn-wa`, sizes `btn-sm`, `btn-lg`, `btn-block`; `link-arrow`, `linklike`. **`btn-primary` (91 occurrences in the published pages) has no CSS rule**: it renders as the base `.btn` (navy background, lime text) |
| Header, navigation | `skip-link`, `topbar*`, `site-header` (`.is-scrolled`), `brand*`, `main-nav`, `nav-list`, `nav-item`, `nav-link`, `nav-caret`, `has-mega` (`.is-open`, `.is-closing`), `mega*`, `header-actions`, `header-track`, `header-cta`, `burger`, `drawer*` (native `<dialog>`) |
| Footer, sticky UI | `site-footer`, `footer-*`, `cta-band`, `cta-*`, `action-bar` (`.is-hidden`), `fab-wa`, `to-top` |
| Heroes | `hero*` (home), `page-hero`, `page-hero-grid`, `page-hero-aside`, `page-hero-actions`, `breadcrumbs`, `notfound`, `notfound-code`, `logo-card` |
| Content | `card`, `card-link`, `card-ico`, `card-more`, `services-grid` (`.cols-3`), `features` (`.features-3`, `.features-4`), `feature`, `feature-ico`, `values`, `value-num`, `steps`, `step`, `step-num`, `stats`, `stat*`, `chips`, `chip`, `ticks`, `badge`, `badge-soft`, `panel` (`.panel-alt`, `.panel-dark`, `.is-pro`, `.is-con`), `callout` (`.callout-info`), `inline-cta`, `table`, `table-wrap`, `specs`, `chooser`, `towns`, `zone-cards`, `zone-card`, `contact-cards`, `contact-card`, `hours` (`.hours-dark`), `sectors`, `band*`, `posts`, `post-card` (`.post-card-featured`), `post-cover` (`.is-light`, `.is-teal`, `.is-lime`), `post-body`, `post-meta`, `article-meta`, `toc`, `share`, `faq`, `faq-group`, `faq-q`, `faq-a` |
| Widgets | `quick*`, `eta-box` (`.eta-box-light`), `eta-result`, `eta-pill[data-zone]`, `zones-legend`, `swatch`, `coverage-map` (`.is-filter`), `map-tip`, `itm-*` (inline map), `map-facade*`, `map-pin`, `map-links`, `pallet-guide`, `pallet-tabs`, `pallet-tab`, `pallet-panel`, `pallet-figure`, `pallet-specs`, `track-*`, `progress`, `events`, `state-list`, `wizard*`, `summary`, `wa-alt`, `mini-steps` |
| Forms | `form-grid` (`.cols-2`, `.cols-3`), `field`, `span-2`, `label`, `opt`, `input`, `input-code`, `input-group`, `select`, `textarea`, `field-hint`, `field-error`, `form-status` (`.is-ok`, `.is-error`), `choice-grid` (`.choice-grid-3`), `choice` (`.choice-sm`), `choice-ico`, `check`, `check-light`, `check-card`, `qty`, `hp-field`, `contact-form` |
| Utilities, state | `actions` (`.center`), `mt-*`, `mb-0`, `hide-xs`, `hide-mobile`, `tab-long`/`tab-short`, `spin`, `dot-live`; states `is-active`, `is-current`, `is-done`, `is-in`, `is-loaded`, `is-hl`; `data-ready` set by JS on enhanced widgets |

### 4.8 Icons

* Sprite `public/img/icons.svg`: 60 `<symbol>`s, Lucide icons (ISC) plus custom
  `i-whatsapp` and `i-pallet` (ls-home@fe4b056:public/img/icons.svg:1-2).
* Usage, always decorative:
  `<svg class="icon" aria-hidden="true"><use href="/img/icons.svg#i-truck"/></svg>`;
  in JS the helper `icon(name, cls = 'icon')` (ls-home@fe4b056:public/js/config.js:44-45).
  `.icon` = 1.15em, `stroke: currentColor`, no fill (ls-home@fe4b056:public/css/main.css:253-262).
  The sprite must stay same-origin (external `<use>` does not load cross-origin, inferred
  from browser rules).
* The sync tool fails on unknown icon ids and lists unused ones
  (ls-home@fe4b056:tools/sync.mjs:113-114, 186-192, 285-291). Ids:

```text
i-truck i-boxes i-package-check i-warehouse i-map-pin i-map i-phone i-mail i-clock i-search
i-arrow-right i-arrow-left i-arrow-up i-circle-check i-shield-check i-route i-calendar-check
i-calendar-clock i-file-check i-timer i-ruler i-building-2 i-factory i-shopping-cart i-sofa
i-hard-hat i-chevron-down i-x i-navigation i-triangle-alert i-headset i-refresh-cw i-copy
i-share-2 i-plus i-minus i-globe i-calculator i-clipboard-list i-user i-send i-loader-circle
i-waypoints i-box i-repeat i-arrow-down-to-line i-shirt i-wine i-cpu i-receipt-text i-book-open
i-lightbulb i-phone-call i-thumbs-up i-car i-pallet i-whatsapp i-package-search i-compass i-instagram
```

### 4.9 Images

| Folder / file | Content | Format | Used by |
|---|---|---|---|
| `img/brand/` | `logo.svg` (856 × 400), `logo-white.svg`, `mark.svg`, `mark-white.svg`, `logo-square.png` (512 × 512) | SVG, PNG | header/drawer mark, footer logo, about logo card, JSON-LD logo. `mark-white.svg` is not referenced |
| `img/photos/` | `hero-trucks-{520,800,1200}`, `road-convoy-{768,1280,1920}` | AVIF + WebP + JPG each | home hero `<picture>` with `fetchpriority="high"` (ls-home@fe4b056:public/index.html:164-168); carico-completo (ls-home@fe4b056:public/servizi/carico-completo/index.html:156-158). `road-convoy-1920.*` is not referenced. README: stock photo, to replace with real ones (ls-home@fe4b056:README.md:224-225) |
| `img/pallets/` | `eur`, `industriale`, `mezzo`, `quarto` (405 × 447, same viewBox, to scale) | SVG, generated by `tools/pallets.py` | home pallet guide, blog cover |
| `img/italia-copertura.svg` | Italy by region with zone classes and `data-zone`, `data-name`, `data-eta`; city labels; Seregno hub; internal `<style>` | SVG (440 × 565, 27 KB) | coverage map (§5.4); boundaries ISTAT via openpolis (ls-home@fe4b056:public/img/italia-copertura.svg:2) |
| `img/map-pattern.svg` | Background of the map facade | SVG | ls-home@fe4b056:public/css/main.css:3385 |
| `img/og/` | 24 Open Graph images, one per indexable page | PNG 1200 × 630, ~59 KB | §6.5 |
| root | `favicon.ico` (48), `favicon.svg`, `apple-touch-icon.png` (180), `icon-192.png`, `icon-512.png`, `icon-maskable-512.png`, `favicon-32.png` | ICO/SVG/PNG | head partial and manifest; `favicon-32.png` is not referenced |

Every `<img>` has `alt`, `width` and `height` (sync check, ls-home@fe4b056:tools/sync.mjs:163-166);
below-the-fold images use `loading="lazy"`.

### 4.10 Partials and the sync tool

* Pages contain the partials between markers, e.g.
  `<!-- partial:header -->` … `<!-- /partial:header -->`; the head partial sits at the top
  of `<head>`, followed by the page's own `<title>`, description, canonical, OG tags and
  JSON-LD; `header` follows `<body data-page="…">`; `cta` (optional) and `footer` close
  the page (ls-home@fe4b056:tools/sync.mjs:14-18, 102-110).
* Tokens in partials: `{{year}}` and `{{site}}`; comments starting with `TODO` are dropped
  (ls-home@fe4b056:tools/sync.mjs:87-91).
* Active menu: links with `data-nav="<key>"` get `aria-current="page"` when the key equals
  the page's `data-page`, and `aria-current="true"` when the key is an ancestor
  (`servizi` on `servizi/groupage`) (ls-home@fe4b056:tools/sync.mjs:92-98). Nav keys in
  the header: `home`, `servizi`, `servizi/spedizione-pallet`, `servizi/groupage`,
  `servizi/carico-completo`, `servizi/magazzino`, `zone`, `tracking`, `chi-siamo`,
  `blog`, `faq`, `contatti`, `preventivo` (ls-home@fe4b056:partials/header.html:25-88).
* Checks: `<html lang="it">`, title (≤ 65 chars warning), description (70–165 warning),
  canonical equals the file's URL, OG tags present and `og:url` = canonical, exactly one
  `<h1>`, `<img>` alt/width/height, `target=_blank` with `rel=noopener`, valid JSON-LD,
  every local link/asset/icon exists, heading levels do not skip, duplicate
  titles/descriptions (ls-home@fe4b056:tools/sync.mjs:132-204, 232-233).
* Sitemap and `--domain`: §6.2, §6.1. `--check` validates without writing (CI mode).

Fragment contents (what the templates must render):

| Fragment | Content (Italian labels verbatim) | Source |
|---|---|---|
| `head` | charset, viewport (`viewport-fit=cover`), 2 font preloads, `/css/main.css`, `<script type="module" src="/js/main.js">`, icons, manifest, `theme-color` `#04233b`, `format-detection telephone=no`, `og:site_name` "Logistica Sanchez", `og:locale` `it_IT`, `twitter:card` `summary_large_image`, site JSON-LD, speculation rules | ls-home@fe4b056:partials/head.html:1-17 |
| `header` | skip link "Vai al contenuto"; topbar (≥ 1024px): phone, e-mail, address → `/contatti/#dove-siamo`, "Lun–Ven 8–18 · Sab 8–12", "Portale ordini", "Traccia la spedizione"; sticky header: brand ("Logistica Sanchez" / "Il pallet espresso"), nav "Servizi" (mega menu: 4 services with subtitles + "Servizi accessori": "Sponda idraulica", "Consegna su appuntamento", "Ritiri programmati", "Prova di consegna digitale", "Tutti i servizi"), "Zone servite", "Chi siamo", "Blog", "Contatti"; buttons "Traccia" and "Preventivo gratuito"; burger; `<dialog id="drawer">` mobile menu (Home, Servizi group, "Zone servite e tempi", "Traccia la spedizione", "Chi siamo", "Blog e guide", "Domande frequenti", "Contatti", "Portale ordini", "Richiedi un preventivo gratuito", WhatsApp/Chiama/Email, hours + address) | ls-home@fe4b056:partials/header.html:1-100 |
| `cta` | kicker "Preventivo gratuito e senza impegno", h2 "Hai un pallet da spedire? Parliamone oggi.", text "Compila il modulo in due minuti o scrivici su WhatsApp: ricevi un prezzo chiaro, con tempi di ritiro e consegna già definiti.", buttons "Richiedi un preventivo" and "Scrivici su WhatsApp" | ls-home@fe4b056:partials/cta.html:1-14 |
| `footer` | logo, "Il tuo partner affidabile per spedizioni express in tutta Italia. Soluzioni logistiche flessibili e personalizzate.", address block; columns "Servizi", "Zone servite", "Azienda", "Contatti" (phone, WhatsApp, e-mail, hours, Instagram); bottom line "© {year} Logistica Sanchez S.r.l. – Il pallet espresso · P.IVA 13113080967 · Via dei Meiani 90, 20831 Seregno (MB)", "Privacy", "Cookie", "Sito realizzato da Alpacode"; mobile action bar ("Chiama", "WhatsApp", "Traccia", "Preventivo"); WhatsApp floating button; "Torna su" button | ls-home@fe4b056:partials/footer.html:1-73 |

### 4.11 Accessibility features

* `lang="it"`; skip link to `<main id="main">` on every page
  (ls-home@fe4b056:partials/header.html:1); focus ring `3px solid --teal-500` on
  `:focus-visible` (ls-home@fe4b056:public/css/main.css:245-248).
* Landmarks: `nav` with Italian `aria-label`s ("Principale", "Menu mobile",
  "Percorso di navigazione", "Azioni rapide", footer columns), `aria-current` on menus and
  breadcrumbs.
* Mega menu: CSS hover on pointer devices plus a caret button with `aria-expanded`,
  `aria-controls`; Esc closes and returns focus (ls-home@fe4b056:public/js/main.js:37-62).
* Drawer: native modal `<dialog>` (focus trap, Esc, inert page), page scroll locked by
  `html:has(dialog[open])` (ls-home@fe4b056:public/js/main.js:65-84,
  ls-home@fe4b056:public/css/main.css:140-142).
* Tabs: WAI-ARIA tabs with automatic activation, arrows/Home/End, roving `tabindex`
  (ls-home@fe4b056:public/js/main.js:87-116).
* Forms: visible labels, `aria-describedby` to hint and error (`<id>-err`), `aria-invalid`,
  radio groups with `role="radiogroup"` + `aria-labelledby`, errors in Italian, live
  regions (`aria-live="polite"` on CAP results, tracking result and wizard counter;
  `role="status"` on form status), wizard legends focused on step change.
* FAQ with native `<details>/<summary>` (exclusive groups through `name`), tables with
  hidden `<caption>` and `scope`, decorative icons `aria-hidden`, buttons at least 48px
  high (40px for `.btn-sm`), 52px action-bar targets
  (ls-home@fe4b056:public/css/main.css:426, 507, 2972).

### 4.12 Motion, reduced motion and print

* `main.js` reads `prefers-reduced-motion` once: no reveal animation, `scrollTo` and
  `scrollIntoView` use `auto` instead of `smooth`
  (ls-home@fe4b056:public/js/main.js:8, 32, 127; ls-home@fe4b056:public/js/quote.js:16;
  ls-home@fe4b056:public/js/tracking.js:237).
* CSS: smooth scrolling, drawer animation, map routes/ping, band route, hero rise and
  wizard step animation only inside `(prefers-reduced-motion: no-preference)`; under
  `reduce` every animation/transition is cut to 0.01 ms and revealed elements are
  visible (ls-home@fe4b056:public/css/main.css:135-139, 2247-2254, 4481-4550).
* Reveal on scroll: elements are hidden only after JS adds `html.reveal-on`; elements
  already on screen are shown at once (ls-home@fe4b056:public/js/main.js:119-145).
* Print: header, drawer, action bar, FAB, to-top, CTA band, footer and quick tabs hidden;
  dark heroes printed black on white; external links followed by their URL
  (ls-home@fe4b056:public/css/main.css:4553-4579).

### 4.13 Behaviour without JavaScript

| Feature | With JS | Without JS |
|---|---|---|
| Quote wizard | 4 steps, custom validation, summary, fetch | All steps stacked, one "Invia la richiesta" button (`.wizard-submit-nojs`), browser validation, native POST → 303 `/grazie/` (ls-home@fe4b056:public/css/main.css:3745-3816) |
| Contact form | fetch + inline status | Native POST → 303 `/grazie/` or HTML error page |
| Tabs (quick tools, pallet guide) | Tabs | All panels stacked with their titles (`[data-ready]` rules, ls-home@fe4b056:public/css/main.css:1346-1408, 2425, 2468) |
| CAP boxes | Inline result | GET `/zone/?cap=…` → static tables on `/zone/` |
| Tracking | Search, result, recent codes | GET `/tracking/?code=…` shows only "Per il tracciamento online attiva JavaScript, oppure scrivici su WhatsApp indicando il codice." (ls-home@fe4b056:public/tracking/index.html:162) |
| Coverage map | Inline SVG, tooltips | Static `<img>` of the SVG |
| Google Maps | Iframe after consent | Address + Google Maps / Apple Mappe / Waze links |
| Mobile menu (< 1024px) | `<dialog>` drawer | **Not available**: burger does nothing; footer links and action bar remain |
| Reveal, to-top, share, year | Enhanced | Content visible; to-top hidden; footer year as rendered |

## 5. JavaScript widgets

### 5.1 Loading model and `config.js`

`/js/main.js` is the only JavaScript file a page loads (a module in `<head>`; the other
`<script>` tags are JSON-LD data and speculation rules). It runs the core UI and imports
a widget only when its selector is on the page
(ls-home@fe4b056:public/js/main.js:239-253):

| Selector | Module | Pages |
|---|---|---|
| `[data-tracking]` | `tracking.js` | `/tracking/` |
| `[data-quote]` | `quote.js` (imports `eta.js`, `forms.js`) | `/preventivo/` |
| `[data-eta-form]` | `eta.js` | home (2), `/zone/`, 5 zone pages, spedizione-pallet |
| `[data-coverage]` | `coverage.js` | home, `/zone/` |
| `[data-map]` | `map.js` | `/contatti/`, `/chi-siamo/` |
| `[data-ajax-form]` | `forms.js` | `/contatti/` |

`config.js` (ls-home@fe4b056:public/js/config.js:5-45):

| Export | Value / purpose |
|---|---|
| `BUSINESS` | `name` "Logistica Sanchez", `phone` "+393292396962", `phoneDisplay` "+39 329 239 6962" (non-breaking spaces), `whatsapp` "393292396962", `email` "logisticasanchezmb@gmail.com", `hours` "Lun–Ven 8:00–18:00, Sab 8:00–12:00" |
| `TRACKING` | `endpoint` `https://app.logisticasanchez.com/mono/api/order/trace/state`, `portalUrl` "" (alternative: redirect to a portal page with `{code}`), `demoCode` "" (must stay empty in production), `timeoutMs` 8000 |
| `waLink(text)` | `https://wa.me/393292396962?text=<encoded>` |
| `mailLink(subject, body)` | `mailto:` with encoded subject/body |
| `escapeHtml`, `icon` | output escaping; sprite helper |

### 5.2 `main.js` — core UI

| Behaviour | Detail | Source (ls-home@fe4b056:public/js/main.js) |
|---|---|---|
| Sticky header shadow | `.is-scrolled` when `scrollY > 8` | 11-33 |
| Back to top | visible when `scrollY ≥ 900`, smooth unless reduced motion | 18, 32 |
| Mega menu | caret toggles `.is-open`; Esc closes; click outside / focus out closes | 37-62 |
| Drawer | `showModal()`, closes on backdrop click, link click, resize to ≥ 1024px; opens the "Servizi" group when it contains the current page | 65-84 |
| Tabs | `[data-tabs]` roots; preselect by `location.hash` = panel id (e.g. `/#qp-eta`, `/#pallet-mezzo`); sets `data-ready`; dispatches an unused `tabchange` event | 87-116 |
| Reveal | `[data-stagger]` children get `data-reveal` and `--rd` = index × 0.07s (max 7) | 119-145 |
| Action bar | hidden while a text field has focus (keyboard space) | 148-154 |
| Table of contents | highlights the link of the section in view | 157-175 |
| Share | `navigator.share`, else copies the URL and shows "Link copiato" for 2 s | 178-196 |
| Maps consent revoke | `[data-maps-revoke]` removes `ls_maps_ok`, label "Consenso revocato" | 199-211 |
| Year | fills `[data-year]` | 213-216 |
| Legacy anchors | on `/` only: `#chisiamo` → `/chi-siamo/`, `#contattaci` → `/contatti/`, `#tracking` → `/tracking/` (`location.replace`), unless an element with that id exists; fragments never reach the server | 218-224 |

**In the monolith:** serve unchanged. The header fragment must keep the markup hooks
(`data-header`, `data-mega`, `.nav-caret`, `#drawer`, `data-drawer-open`,
`data-drawer-close`, `data-drawer-group`, `data-action-bar`, `data-to-top`). Keep the
legacy-anchor redirect (PUB-04).

### 5.3 `eta.js` — CAP → region → delivery time

Pure client-side; no backend. "Zones and hours are indicative and live here only (plus the
legend/map in the HTML)" (ls-home@fe4b056:public/js/eta.js:1-5).

**Zones** (ls-home@fe4b056:public/js/eta.js:8-12):

| Zone | `label` | `note` |
|---|---|---|
| 1 | 24 ore | Nord Italia |
| 2 | 48 ore | Centro e parte del Sud |
| 3 | 72 ore | Sud e isole maggiori |

**Region → zone and CAP prefix → region** (first two digits of the CAP;
ls-home@fe4b056:public/js/eta.js:14-43):

| Region | Zone | Time | CAP prefixes |
|---|---|---|---|
| Lombardia | 1 | 24 ore | 20, 21, 22, 23, 24, 25, 26, 27, 46 |
| Piemonte | 1 | 24 ore | 10, 12, 13, 14, 15, 28 |
| Valle d'Aosta | 1 | 24 ore | 11 |
| Liguria | 1 | 24 ore | 16, 17, 18, 19 |
| Veneto | 1 | 24 ore | 30, 31, 32, 35, 36, 37, 45 |
| Trentino-Alto Adige | 1 | 24 ore | 38, 39 |
| Friuli-Venezia Giulia | 1 | 24 ore | 33, 34 |
| Emilia-Romagna | 1 | 24 ore | 29, 40, 41, 42, 43, 44, 47, 48 |
| Toscana | 2 | 48 ore | 50, 51, 52, 53, 54, 55, 56, 57, 58, 59 |
| Umbria | 2 | 48 ore | 05, 06 |
| Marche | 2 | 48 ore | 60, 61, 62, 63 |
| Lazio | 2 | 48 ore | 00, 01, 02, 03, 04 |
| Abruzzo | 2 | 48 ore | 64, 65, 66, 67 |
| Molise | 2 | 48 ore | 86 |
| Campania | 2 | 48 ore | 80, 81, 82, 83, 84 |
| Puglia | 2 | 48 ore | 70, 71, 72, 73, 74, 76 |
| Basilicata | 3 | 72 ore | 75, 85 |
| Calabria | 3 | 72 ore | 87, 88, 89 |
| Sicilia | 3 | 72 ore | 90, 91, 92, 93, 94, 95, 96, 97, 98 |
| Sardegna | 3 | 72 ore | 07, 08, 09 |

Prefixes with no region (→ "CAP non trovato"): 49, 68, 69, 77, 78, 79, 99.

**Special places** ("tempi e costi li concordiamo insieme"):

* Minor islands, 23 CAPs (ls-home@fe4b056:public/js/eta.js:46-50): 80071, 80073,
  80074, 80075, 80076, 80077, 80079 (Capri, Ischia, Procida); 57030, 57031, 57032,
  57033, 57034, 57036, 57037, 57038, 57039 (Elba, Capraia); 58012 (Giglio), 91017
  (Pantelleria), 91023 (Favignana), 98055 (Lipari), 07024 (La Maddalena), 09014
  (Carloforte), 04027 (Ponza).
* Venice lagoon: regex `^301(2[1-6]|3[1-5]|4[12])$`, i.e. 30121–30126, 30131–30135,
  30141–30142 (ls-home@fe4b056:public/js/eta.js:51).

**Algorithm** (`lookup`, ls-home@fe4b056:public/js/eta.js:53-61): strip non-digits; not 5
digits → `null`; unknown prefix → `{cap, valid: false}`; else `{cap, valid: true, region,
zone, label, special}` where `special` is "isola minore", "laguna di Venezia" or `null`.

**Output** (`describe`, ls-home@fe4b056:public/js/eta.js:63-70), HTML with a pill coloured
by `data-zone`:

| Case | Rendered text |
|---|---|
| valid | `[24 ore]` "Lombardia · consegna indicativa dal ritiro, in giorni lavorativi." |
| special | `[Su richiesta]` "Campania, isola minore: tempi e costi li concordiamo insieme." |
| invalid prefix | `[CAP non trovato]` "Controlla le cinque cifre del codice postale." |
| fewer than 5 digits, on submit | "Inserisci un CAP di 5 cifre, ad esempio 40121." (on typing: empty) |

**Markup contract** (ls-home@fe4b056:public/js/eta.js:72-98): a `form[data-eta-form]`
with `input[name="cap"]` and `[data-eta-result]` (`aria-live="polite"`); the input is
cleaned to 5 digits while typing; submit is prevented; `aria-invalid` set on an invalid
prefix; a valid result dispatches `document` event `eta:region` (detail = result), used
by the coverage map; `?cap=` in the URL pre-fills empty inputs and runs. The quote
wizard reuses `lookup`/`describe` for the delivery CAP hint (§5.5).

**Test cases** (computed by running `eta.js` on 2026-10-16; PUB-08):

| Input | Result |
|---|---|
| `20831` | Lombardia, zone 1, "24 ore" |
| `40121` | Emilia-Romagna, zone 1, "24 ore" |
| `11100` | Valle d'Aosta, zone 1 |
| `00118` | Lazio, zone 2, "48 ore" |
| `70121` | Puglia, zone 2 |
| `80133` | Campania, zone 2 |
| `75100` | Basilicata, zone 3, "72 ore" |
| `90133` / `09124` | Sicilia / Sardegna, zone 3 |
| `80073`, `57037`, `91017`, `07024` | special "isola minore" → "Su richiesta" |
| `30124`, `30126`, `30141`, `30142` | special "laguna di Venezia" → "Su richiesta" |
| `30171` (Mestre) | Veneto, zone 1, not special |
| `47890` (San Marino) / `00120` (Vatican) | Emilia-Romagna zone 1 / Lazio zone 2 (consequence of the prefix table) |
| `49000`, `68000`, `99999` | `valid: false` → "CAP non trovato" |
| `2083`, `2O831` (letter O), empty | `null` (no result) |
| `" 20 831 "` | spaces stripped → Lombardia |

The same zones are repeated by hand in HTML: legends on `/` and `/zone/`
(ls-home@fe4b056:public/index.html:347-351, ls-home@fe4b056:public/zone/index.html:206-210),
the 20-row table on `/zone/` (line 229), 3-row tables on the 5 zone pages and
spedizione-pallet, the `data-zone`/`data-eta` attributes of `italia-copertura.svg`
(the README asks to update them together: ls-home@fe4b056:README.md:75-77), and the
services copy "24 ore al Nord, 48 al Centro, 72 al Sud e nelle isole"
(ls-home@fe4b056:public/servizi/index.html:179,
ls-home@fe4b056:public/servizi/spedizione-pallet/index.html:156).

**In the monolith:** M1 keeps `eta.js` and its data unchanged (PUB-08). Render the legends
and tables from one server-side zone dataset identical to `eta.js` so they cannot drift;
the unified source with the price-list transit times is M2 (ENH-10; mismatch in chapter
07, Q-14). No endpoint is needed; optionally render the result server-side for
`/zone/?cap=` so it also works without JavaScript.

### 5.4 `coverage.js` and `map.js` — coverage map and Google Maps consent

**`coverage.js`** (ls-home@fe4b056:public/js/coverage.js:8-101): the page ships
`<figure class="coverage-map" data-coverage><img src="/img/italia-copertura.svg" …></figure>`.
The script fetches the same SVG (same origin, `connect-src 'self'`), inlines it with
`role="img"` and the image's `alt` as `aria-label`, removes `width`/`height`, the
per-path `<title>`s and the SVG's internal `<style>` (styles are mirrored in
ls-home@fe4b056:public/css/main.css:2188-2302 because the CSP blocks inline `<style>`).
Then:

* hover/move on a region (`.itm-r`) → tooltip "{data-name}" + "Consegna in {data-eta}";
  click/tap shows it centred on the region;
* hover on a legend item `.zones-legend [data-zone]` in the same `<section>` → highlights
  the regions of that zone (`.is-hl`, others dimmed via `.is-filter`);
* `eta:region` event → highlights that region and shows its tooltip for 4 s.
If the fetch fails, the static image stays.

SVG data contract: `<path class="itm-r itm-z{1|2|3}" data-zone="1" data-name="Lombardia"
data-eta="24 ore">` for 20 regions; groups `itm-regions`, `itm-routes`, `itm-cities`,
`itm-hubg`; 11 city labels and the "Seregno" hub label
(ls-home@fe4b056:public/img/italia-copertura.svg).

**`map.js`** (ls-home@fe4b056:public/js/map.js:6-40): `[data-map="Via dei Meiani 90, 20831 Seregno MB"]`
facade with address, button "Mostra la mappa interattiva" (`[data-map-load]`), checkbox
"Ricorda la scelta su questo dispositivo" (`[data-map-remember]`) and note "La mappa è
fornita da Google Maps: caricandola accetti i suoi cookie. Cookie policy"
(ls-home@fe4b056:public/contatti/index.html:237-245). Nothing is requested from Google
until the click; then an iframe
`https://www.google.com/maps?q=<query>&z=15&hl=it&output=embed` (title "Mappa: {query}",
lazy, `referrerpolicy=no-referrer-when-downgrade`, fullscreen) is appended and
`.is-loaded` set. If "Ricorda" was ticked, `localStorage.ls_maps_ok = "1"` and later visits
load the map at once; `/cookie/` revokes it (§5.2). Directions links (always visible):
Google Maps, Apple Mappe, Waze (ls-home@fe4b056:public/contatti/index.html:246-250).

**In the monolith:** unchanged. Keep `frame-src https://www.google.com https://maps.google.com`
in the CSP, serve `italia-copertura.svg` same-origin as `image/svg+xml`, and keep the
cookie page's storage table in step with any new storage key.

### 5.5 `quote.js` — 4-step quote wizard

A plain `<form class="wizard" id="modulo" data-quote name="preventivo" method="POST"
action="/api/form">` enhanced into a wizard (ls-home@fe4b056:public/preventivo/index.html:152-329,
ls-home@fe4b056:public/js/quote.js:1-212). It contains `form-name=preventivo` (hidden) and
the honeypot `bot-field` (§5.6).

**Steps** (`fieldset[data-step]`, progress labels "1. Servizio", "2. Percorso",
"3. Merce", "4. Contatti"):

| # | Legend | Intro text |
|---|---|---|
| 1 | Di cosa hai bisogno? | Scegli il servizio. Se non sei sicuro lascia «Spedizione pallet»: ti consigliamo noi la soluzione migliore. |
| 2 | Da dove a dove? | Ci bastano CAP e località: l'indirizzo completo lo concordiamo dopo. |
| 3 | Cosa spedisci? | Peso e misure servono a scegliere il mezzo e a calcolare un prezzo corretto. Vanno bene anche valori indicativi. |
| 4 | A chi inviamo il preventivo? | Controlla il riepilogo e lasciaci i tuoi contatti: ti rispondiamo al più presto, negli orari d'ufficio. |

**Fields** (client rules from the HTML; server rules from
ls-home@fe4b056:netlify/functions/form.mjs:52-129; "1-line" = whitespace collapsed).
Server side, every field is trimmed and then **cut to its maximum length before it is
checked** (form.mjs:39; default maximum 200). Values that are too long are accepted
truncated, never rejected: `consegna_cap=401234` is read as `40123` and accepted, a
150-character `nome` is cut to 100. Read every length in the "Server rule" column as "cut
to N, then checked":

| Name | Step | Control and label | Required | Allowed values / client rule | Server rule | E-mail row |
|---|---|---|---|---|---|---|
| `servizio` | 1 | radio "Servizio" | yes, default `pallet` | `pallet` "Spedizione pallet", `groupage` "Groupage / collettame", `completo` "Carico completo", `magazzino` "Magazzino" | one of the 4 keys | Servizio |
| `data_ritiro` | 1 | date "Data di ritiro desiderata (facoltativo)" | no | `min` = today (set by JS) | shown if `YYYY-MM-DD`, else "Da concordare"; past dates accepted | Data di ritiro (long Italian date) |
| `ritiro_cap` | 2 | text "CAP di ritiro", numeric, maxlength 5 | yes | `[0-9]{5}`, message "Inserisci un CAP di 5 cifre." | `^\d{5}$` | Ritiro = CAP + località |
| `ritiro_localita` | 2 | text "Località di ritiro" | yes | — | 1-line, non-empty, ≤ 80 | (in Ritiro) |
| `consegna_cap` | 2 | text "CAP di consegna" + delivery-time hint | yes | as `ritiro_cap` | `^\d{5}$` | Consegna |
| `consegna_localita` | 2 | text "Località di consegna" | yes | — | 1-line, non-empty, ≤ 80 | (in Consegna) |
| `destinatario` | 2 | radio "Il destinatario è" | yes, default `azienda` | `azienda` "Un'azienda", `privato` "Un privato" | one of | Destinatario (Azienda / Privato) |
| `tipo_pallet` | 3 | radio "Formato" | yes, default `eur` | `eur` "EPAL / EUR" 120 × 80, `industriale` 120 × 100, `mezzo` 80 × 60, `quarto` 60 × 40, `altro` "Colli sfusi o fuori misura" | one of | (in Quantità) |
| `numero` | 3 | number "Quantità" with −/+ buttons | yes, default 1 | 1–99 | number 1–99, comma accepted as decimal separator | Quantità = "{n} × {formato}" |
| `peso` | 3 | number "Peso totale (kg)" | yes | 1–40000 | number 1–40000 | Peso totale = "{n} kg" |
| `altezza` | 3 | number "Altezza massima (cm) (facoltativo)" | no | 10–300 | empty, or 10–300 | Altezza = "{n} cm" |
| `descrizione_merce` | 3 | text "Che merce spedisci? (facoltativo)" | no | — | 1-line ≤ 200 | Descrizione |
| `servizi_extra[]` | 3 | checkboxes "Servizi accessori (facoltativi)" | no | values "Sponda idraulica", "Consegna su appuntamento", "Preavviso al destinatario", "Merce fragile o non sovrapponibile" | unknown values dropped silently | Servizi extra (comma-separated) |
| `nome` | 4 | text "Nome e cognome" | yes | — | 1-line, non-empty, ≤ 100 | Nome |
| `azienda` | 4 | text "Azienda (facoltativo)" | no | — | 1-line ≤ 120 | Azienda |
| `email` | 4 | email "Email" | yes | browser e-mail check | regex, ≤ 254 | Email; also Reply-To |
| `telefono` | 4 | tel "Telefono" | yes | `[0-9+\s\(\)\.\/\-]{6,20}`, message "Inserisci un numero di telefono valido." | regex `^[0-9+\s().\/-]{6,20}$`, required for this form | Telefono (+ call/WhatsApp buttons) |
| `contatto_preferito` | 4 | radio "Come preferisci essere ricontattato?" | yes, default `email` | `email`, `telefono`, `whatsapp` | one of | Preferisce |
| `note` | 4 | textarea "Note (facoltative)" | no | — | ≤ 3000, line breaks kept | Note |
| `privacy` | 4 | checkbox "Ho letto l'informativa privacy e acconsento al trattamento dei dati per ricevere una risposta." | yes | value `accettata` | must equal `accettata` | — |

Labels used in summaries and e-mail (`servizio`: "Spedizione pallet espresso",
"Groupage / collettame", "Carico completo", "Magazzino e logistica"; `tipo_pallet`:
"EPAL 120×80 cm", "Industriale 120×100 cm", "Mezzo pallet 80×60 cm", "Quarto di pallet
60×40 cm", "Altro / fuori misura") are duplicated in
ls-home@fe4b056:public/js/quote.js:10-15 and ls-home@fe4b056:netlify/functions/form.mjs:24-30.

**URL prefill** (ls-home@fe4b056:public/js/quote.js:63-78):

| Parameter | Fills | Accepted | Linked from |
|---|---|---|---|
| `servizio` | radio `servizio` | an existing value | `/servizi/magazzino/` (`?servizio=magazzino`) |
| `tipo` | radio `tipo_pallet` | an existing value | home pallet guide (`?tipo=` `eur`, `industriale`, `mezzo`, `quarto`; ls-home@fe4b056:public/index.html:457, 471, 485, 499) |
| `da` | `ritiro_cap` | `^\d{5}$` | home quick quote form (GET `/preventivo/`, ls-home@fe4b056:public/index.html:212-226) |
| `a` | `consegna_cap` | `^\d{5}$` | home quick quote form |
| `pallet` | `numero` | `^\d{1,2}$` | home quick quote form |

**Behaviour** (ls-home@fe4b056:public/js/quote.js:107-208): `noValidate` + Italian
messages; "Continua" validates only the current step and focuses the first invalid
field; "Indietro" goes back; Enter inside a field moves forward instead of submitting;
counter "Passo N di 4"; the delivery CAP shows the `eta.js` result as a hint; on the last
step the summary (`dl[data-summary]`) and the WhatsApp link are rebuilt on every input.

Summary rows (only non-empty): Servizio, Ritiro, Consegna ("{CAP} {località}
({azienda|privato})"), Data di ritiro (short Italian date or "Da concordare"), Merce
("{n} × {formato}, {peso} kg totali, h {altezza} cm"), Tipo di merce, Servizi extra
(ls-home@fe4b056:public/js/quote.js:43-53).

**WhatsApp hand-off**: link "Invia il riepilogo su WhatsApp" (`[data-wa]`) to
`wa.me/393292396962` with this text (ls-home@fe4b056:public/js/quote.js:55-61); output
below produced by running the code on sample values:

```text
Ciao Logistica Sanchez, vorrei un preventivo:
• Servizio: Spedizione pallet espresso
• Ritiro: 20831 Seregno
• Consegna: 40121 Bologna (azienda)
• Data di ritiro: gio 22 ottobre
• Merce: 2 × EPAL 120×80 cm, 650 kg totali, h 140 cm
• Tipo di merce: Ricambi meccanici
• Servizi extra: Sponda idraulica, Preavviso al destinatario
• Note: Ritiro preferibilmente al mattino

Mario Rossi (Rossi Meccanica S.r.l.) – mario.rossi@rossimeccanica.example · 333 000 0000
```

**Submit**: validates the whole form (jumps to the step of the first error), button text
"Invio in corso…", `submitForm()` (§5.6). On success the form is replaced by a
confirmation (no navigation): "Richiesta inviata, grazie {first name}!", "Abbiamo
ricevuto la tua richiesta: ti ricontattiamo al più presto con prezzo e tempi di ritiro e
consegna.", buttons "Hai fretta? Scrivici su WhatsApp" (same summary text) and "Torna alla
home". On failure: the shared failure message (§5.6) and the button is restored
(ls-home@fe4b056:public/js/quote.js:166-204).

**In the monolith:** same template and script; the form keeps posting to `/api/form`
(now a monolith route). Keep every field name (including the brackets in
`servizi_extra[]`) and value. Single source for the labels used by the summary and the
e-mail. Optional later: pre-fill contact fields for a signed-in customer; indicative
public price range only behind `pricing.publicEstimate` (ENH-04).

### 5.6 `forms.js` + `netlify/functions/form.mjs` — validation, submission, e-mail

**Contact form** (ls-home@fe4b056:public/contatti/index.html:179-221):
`<form class="panel contact-form" name="contatti" method="POST" action="/api/form" data-ajax-form data-success="Grazie! Abbiamo ricevuto il tuo messaggio: ti rispondiamo al più presto.">`
with hidden `form-name=contatti`, honeypot `bot-field`, and:

| Name | Control and label | Required | Client rule | Server rule | E-mail row |
|---|---|---|---|---|---|
| `nome` | text "Nome e cognome" | yes | — | 1-line, non-empty, ≤ 100 | Nome |
| `email` | email "Email" | yes | browser e-mail check | regex, ≤ 254 | Email; Reply-To |
| `telefono` | tel "Telefono (facoltativo)" | no | phone pattern | empty or phone regex | Telefono |
| `oggetto` | select "Argomento": "Richiesta di preventivo", "Informazioni su una spedizione", "Magazzino e logistica", "Collaborazioni", "Altro" (no `value` attributes: the text is sent) | default first | — | any 1-line text ≤ 100 (not checked against the list) | Oggetto; subject |
| `messaggio` | textarea "Messaggio" | yes | `minlength=10` | ≥ 10 chars after trim, ≤ 5000 | Testo |
| `privacy` | checkbox (same text as the quote form) | yes | — | equals `accettata` | — |

**Client validation** (ls-home@fe4b056:public/js/forms.js:8-67): `noValidate` and
messages from the Constraint Validation API, written into `#<id>-err` (radios: the
`.field-error` of the `[data-group]`), `aria-invalid` toggled; fields in `.hp-field`,
hidden or disabled are skipped; live re-check on input/change once a field is invalid,
and on blur for filled text fields.

| Validity state | Message |
|---|---|
| missing, checkbox | Spunta la casella per continuare. |
| missing, radio | Seleziona una delle opzioni. |
| missing, other | Questo campo è obbligatorio. |
| e-mail type mismatch | Inserisci un indirizzo email valido, ad esempio nome@azienda.it. |
| pattern mismatch | the field's `data-msg`, else "Controlla il formato inserito." |
| range under / over | Il valore minimo è {min}. / Il valore massimo è {max}. |
| too short | Inserisci almeno {minLength} caratteri. |
| bad input / other | Valore non valido. / Controlla questo campo. |

**Submission** (`submitForm`, ls-home@fe4b056:public/js/forms.js:70-78): `fetch(form.action)`,
`POST`, headers `Content-Type: application/x-www-form-urlencoded`,
`Accept: application/json`, body = all form fields url-encoded (same encoding as a native
post). Success requires HTTP 2xx **and** `{"ok": true}`. Contact form on success:
`form.reset()` and the `data-success` text in `[data-form-status]` (class `is-ok`); on
failure (class `is-error`): "Invio non riuscito. Riprova tra poco oppure scrivici a
logisticasanchezmb@gmail.com o su WhatsApp al +39 329 239 6962." (e-mail as a `mailto:`
link) (ls-home@fe4b056:public/js/forms.js:80-118).

Example request fields (sent url-encoded) and responses:

```json
{
  "form-name": "contatti",
  "bot-field": "",
  "nome": "Giulia Bianchi",
  "email": "giulia.bianchi@example.it",
  "telefono": "",
  "oggetto": "Informazioni su una spedizione",
  "messaggio": "Buongiorno, vorrei sapere quando passate a ritirare i due pallet ordinati ieri.",
  "privacy": "accettata"
}
```

```json
{ "ok": true }
```

```json
{ "ok": false, "error": "invalid:messaggio" }
```

**Server pipeline** (`handleForm`, ls-home@fe4b056:netlify/functions/form.mjs:244-288),
route `POST /api/form` (line 19):

1. "JSON mode" if the `Accept` header contains `application/json`; otherwise "native mode".
2. `Content-Length` > 20,000 → 413 `too_large` (also when the read body exceeds 20,000
   characters). Body parsed as `multipart/form-data` or url-encoded; parse error → 400
   `bad_request`.
3. Honeypot: non-empty `bot-field` → pretend success (200 `{ok:true}` / 303), nothing sent.
4. `form-name` must be `preventivo` or `contatti`, else 400 `unknown_form`.
5. Field checks in this order; the first failure → 422 `invalid:<field>`.
   Common (`contact()`): `nome`, `email`, `telefono` (format, if present), `privacy`.
   `preventivo` then: `servizio`, `ritiro_cap`, `consegna_cap`, `ritiro_localita`,
   `consegna_localita`, `destinatario`, `tipo_pallet`, `numero`, `peso`, `altezza`,
   `telefono` (required), `contatto_preferito`. `contatti` then: `messaggio`.
6. Send one e-mail; any exception (including missing configuration) → logged, 502
   `send_failed`.
7. Success: JSON mode 200 `{"ok": true}`; native mode 303 `Location: /grazie/`.

In native mode every failure is an HTML page (same status code) titled "Invio non
riuscito – Logistica Sanchez" with h1 "Invio non riuscito", the message, the line "Puoi
anche scriverci a logisticasanchezmb@gmail.com, su WhatsApp o chiamarci al +39 329 239 6962."
and a button "Torna al modulo" → `/<form-name>/` (`/` for an unknown form)
(ls-home@fe4b056:netlify/functions/form.mjs:234-241). Messages: 413 "Il messaggio è troppo
lungo.", 400 "Il modulo non è stato ricevuto correttamente." / "Il modulo non è stato
riconosciuto.", 422 "Alcuni campi non sono compilati correttamente: torna al modulo e
controllali.", 502 "Non siamo riusciti a inoltrare la richiesta. Riprova tra poco."

Server test cases (results of running the handler with a fake sender, 2026-10-16):

| Request | Status | Body / header |
|---|---|---|
| valid quote, JSON mode | 200 | `{"ok":true}` |
| valid quote, native mode | 303 | `Location: /grazie/` |
| honeypot filled | 200 | `{"ok":true}`, no e-mail |
| `consegna_cap=4012` | 422 | `{"ok":false,"error":"invalid:consegna_cap"}` (native: HTML page, 422) |
| quote without `telefono` | 422 | `invalid:telefono` |
| `peso=0` | 422 | `invalid:peso` |
| `numero=2,5` | 200 | accepted as 2.5 (quirk) |
| `consegna_cap=401234` | 200 | accepted as 40123 (quirk: truncation) |
| `form-name=altro` | 400 | `unknown_form` |
| SES error | 502 | `send_failed` |
| contact with `messaggio=corto` | 422 | `invalid:messaggio` |
| `Content-Length: 20001` | 413 | `too_large` |

**E-mail** (`compose`, ls-home@fe4b056:netlify/functions/form.mjs:164-210): one message to
the office, text + HTML, UTF-8.

* Subject — quote: "Richiesta di preventivo: {servizio}, {ritiro} → {consegna}";
  contact: "Messaggio dal sito: {oggetto or 'contatto'} ({nome})"; whitespace collapsed,
  max 200 chars.
* Reply-To = the customer's e-mail; From = `MAIL_FROM`; To = `MAIL_TO` split on commas.
* Body: title ("Nuova richiesta di preventivo" / "Nuovo messaggio dal sito"), sections
  (quote: SPEDIZIONE, MERCE, CLIENTE; contact: MESSAGGIO, CLIENTE) of label/value rows,
  empty rows and sections omitted; footer "Inviato dal modulo «Preventivo|Contatti» di
  logisticasanchez.com il {dd/mm/yy, hh:mm Europe/Rome}. Rispondi a questa email per
  scrivere direttamente al cliente."
* HTML: grey page, white 640px card, kicker "Logistica Sanchez · sito web", h1 title,
  buttons "Rispondi via email" (`mailto:`), "Chiama {telefono}" (`tel:` digits and +),
  "Scrivi su WhatsApp" (only for Italian mobiles `3…` → `wa.me/39…`, or when the customer
  prefers WhatsApp and the number has ≥ 8 digits), then one table per section; all values
  HTML-escaped (ls-home@fe4b056:netlify/functions/form.mjs:155-162, 176-207).

Plain-text part produced by the handler for the sample quote (sent at 09:29):

```text
Nuova richiesta di preventivo

SPEDIZIONE
Servizio: Spedizione pallet espresso
Data di ritiro: giovedì 22 ottobre 2026
Ritiro: 20831 Seregno
Consegna: 40121 Bologna
Destinatario: Azienda

MERCE
Quantità: 2 × EPAL 120×80 cm
Peso totale: 650 kg
Altezza: 140 cm
Descrizione: Ricambi meccanici
Servizi extra: Sponda idraulica, Preavviso al destinatario

CLIENTE
Nome: Mario Rossi
Azienda: Rossi Meccanica S.r.l.
Email: mario.rossi@rossimeccanica.example
Telefono: 333 000 0000
Preferisce: WhatsApp
Note: Ritiro preferibilmente al mattino

Inviato dal modulo «Preventivo» di logisticasanchez.com il 16/10/26, 09:29. Rispondi a questa email per scrivere direttamente al cliente.
```

**Configuration** (Netlify, scope Functions; ls-home@fe4b056:README.md:161-170,
ls-home@fe4b056:netlify/functions/form.mjs:10-15, 214-231): `SES_REGION` (e.g.
`eu-north-1`), `SES_ACCESS_KEY_ID`, `SES_SECRET_ACCESS_KEY` (an IAM user used only for
this), `MAIL_FROM` (e.g. `Sito Logistica Sanchez <sito@logisticasanchez.com>`, a verified
domain address, not Gmail, to pass DMARC), `MAIL_TO` (e.g. the office Gmail; several
addresses comma-separated). SESv2 `SendEmail`, simple content. The README's IAM policy
restricts the key to that sender and that recipient (ls-home@fe4b056:README.md:180-196):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "ses:SendEmail",
    "Resource": "*",
    "Condition": {
      "StringEquals": { "ses:FromAddress": "sito@logisticasanchez.com" },
      "ForAllValues:StringEquals": { "ses:Recipients": ["logisticasanchezmb@gmail.com"] }
    }
  }]
}
```

**In the monolith:** `POST /api/form` in the `site` module with the same field names,
check order, error codes, JSON/303 behaviour, honeypot and 20,000-character limit
(PUB-06/07). The e-mail goes through the notifications outbox (EML-08); with an outbox a
502 `send_failed` can only mean "could not be queued" (inferred adaptation). Store each
submission as a lead (PUB-11), add a rate limit (chapter 10 §4), keep the e-mail layout and
Reply-To. Rejecting over-long values with 422 (and any CAP that is not exactly 5 digits)
instead of truncating them is a deliberate change from the legacy behaviour: record it in an
ADR. The app's SES identity will also write to customers, so the
"office only" IAM condition above cannot be reused as is (inferred). See CSRF in §8.5.

### 5.7 `tracking.js` — shipment tracking

**Markup** (ls-home@fe4b056:public/tracking/index.html:141-168): `div[data-tracking]`
containing `<form action="/tracking/" method="get">` with `input[name="code"]`
(label "Codice di spedizione", placeholder "Codice di riferimento", hint "Copialo per
intero, così come è scritto sul documento."), `[data-track-error]`, `[data-track-recent]`,
`<noscript>` note and `[data-track-result]` (`tabindex=-1`, `aria-live="polite"`). The home
quick-tools form also GETs `/tracking/?code=…` (ls-home@fe4b056:public/index.html:189-196).

**Modes** (ls-home@fe4b056:public/js/tracking.js:1-8, 216-234): `TRACKING.demoCode` set and
typed → demo; `TRACKING.endpoint` set → backend (production setting); else `portalUrl` →
redirect; else "ask us" (WhatsApp/e-mail/phone with the code).

**Flow** (`run`, ls-home@fe4b056:public/js/tracking.js:203-238):

1. Code = input with all whitespace removed; must match `` ^[^\s<>"'`]{1,64}$ `` (1–64
   characters, no spaces, `<`, `>`, quotes or backticks). Errors:
   empty → "Inserisci il codice della spedizione."; invalid characters → "Il codice
   contiene caratteri non validi: controllalo sul documento di trasporto."
2. URL replaced with `/tracking/?code=<code>` (shareable); `?code=` on load runs a search.
3. Shows "Ricerca in corso…" with a spinning icon, then calls the backend.
4. Renders the result, refreshes recent codes, focuses the result box and scrolls it into
   view.

**Request** (`fetchShipment`, ls-home@fe4b056:public/js/tracking.js:168-184), sent from the
site's origin (cross-origin today, so the backend must answer the CORS preflight caused
by the JSON content type; inferred from the headers):

```http
POST https://app.logisticasanchez.com/mono/api/order/trace/state
Content-Type: application/json
Accept: application/json
```

```json
{ "codice": "ABC123" }
```

(The code format is unknown, Q-06; the code is sent exactly as typed minus spaces, case
preserved — ls-home@fe4b056:public/js/tracking.js:31.) The old template site made the same
call and displayed `responseJson.status` as received (ls-home@v1:js/custom.js:55-58, 67-74).

**Response handling:**

| Backend answer | Page shows |
|---|---|
| 2xx with a recognised `status` | Shipment card (below); code saved in recent codes |
| 2xx with `status` `Non gestito` (from `ff58d4f`) | the "ask us" block with "Non ci sono ancora aggiornamenti per la spedizione {code}. Se l'hai prenotata da poco è normale; altrimenti controlla il codice sul documento di trasporto oppure chiedi direttamente a noi."; code not saved. The backend answers `Non gestito` for unknown codes and pending orders' temporary codes too (12a §1.4 D1), so the page cannot show it as a state. |
| 2xx with an unrecognised non-empty `status` | Shipment card with the raw status, no progress bar |
| 2xx without `status` (or empty) | "not found" block |
| any non-2xx | "not found" block: "Non troviamo la spedizione {code} nel sistema: controlla il codice sul documento di trasporto oppure chiedi direttamente a noi." |
| network error, timeout (8 s abort), non-JSON 2xx | "error" block: "Il servizio di tracciamento non risponde in questo momento. Puoi chiederci lo stato della spedizione {code} con un tocco." |

Minimal answer (what production returns: `status` is the label, and `StatusRepr` has no
other field; 12a §1.4 D1):

```json
{ "status": "Presa in carico" }
```

Richer answer the page already renders (all fields except `status` optional; none exists
in the legacy backend) (ls-home@fe4b056:README.md:127-143):

```json
{
  "status": "TRANSITO",
  "origin": "Seregno (MB)",
  "destination": "Bologna (BO)",
  "pallets": 2,
  "eta": "2026-10-14",
  "updatedAt": "2026-10-13T09:40:00+02:00",
  "pod": "https://… (optional, link to the signed proof of delivery)",
  "events": [
    { "time": "2026-10-13T09:40:00+02:00", "status": "TRANSITO", "location": "Hub di Seregno (MB)", "note": "Partita verso la destinazione" }
  ]
}
```

**The six states** (`STATES`, ls-home@fe4b056:public/js/tracking.js:14-21) mirror the
backend enumeration `statoTracking` as the CMS knows it (ids, names, labels:
ls-cms@dev:js/components/data/user-data.js:22-53; fetched from
`GET /mono/api/enumeration/statoTracking`, ls-cms@dev:js/constant/rest-constant.js:35;
the CMS sets a state by posting `{codice, tracking: <id>}` to `/mono/cms/order/trace`,
ls-cms@dev:js/rest/dto/order-request.js:3-7 — chapter 04):

| id | name | CMS label | Status line on the site | Progress-bar step | Icon | Legend text on `/tracking/` |
|---|---|---|---|---|---|---|
| 0 | `NON_GESTITO` | Non gestito | not shown as a state from `ff58d4f` (neutral message above); at `fe4b056`: In attesa di presa in carico | — | `clipboard-list` | removed from the legend in `ff58d4f`; at `fe4b056`: La spedizione è registrata: stiamo organizzando il ritiro della merce. |
| 1 | `PRESA` | Presa in carico | Presa in carico | 1 "Presa in carico" | `package-check` | Abbiamo ritirato la merce: da qui in poi viaggia sotto la nostra responsabilità. |
| 2 | `TRANSITO` | Transito | In transito | 2 "In transito" | `truck` | Il pallet è in viaggio verso il terminal della zona di destinazione. |
| 3 | `TERMINAL_DEST` | Terminal destinatario | Arrivata al terminal di destinazione | 3 "Terminal di destinazione" | `warehouse` | È arrivato al terminal più vicino al destinatario ed è pronto per la consegna. |
| 4 | `ASSEGNATO` | Assegnato | Affidata al mezzo per la consegna | 4 "In consegna" | `navigation` | È stato affidato al mezzo che effettua la consegna. |
| 5 | `CONSEGNATO` | Consegnato | Consegnata | 5 "Consegnata" | `circle-check` | Il destinatario ha ricevuto la merce. |

Legend headings on `/tracking/` use the step labels ("Terminal di destinazione", "In
consegna") (ls-home@fe4b056:public/tracking/index.html:176).

**Matching** (ls-home@fe4b056:public/js/tracking.js:23-30, 81-91): the answer may be an
object with `status`, or a bare string/number. If `status` is an object, its `name`,
then `label`, then `id` is used. Key = NFD-normalised, accents stripped, trimmed,
upper-cased, spaces and hyphens → `_`. A state matches if key = `String(id)`, key =
`name`, or key = key of `label`. Verified on 2026-10-16 by running the code: `"PRESA"`,
`"Presa in carico"`, `"presa in carico"` → PRESA; `2` → TRANSITO; `"3"`,
`"Terminal destinatario"`, `"terminal-dest"` → TERMINAL_DEST; `{id:4,name:"ASSEGNATO",…}`
→ ASSEGNATO; `0`, `"Non gestito"` → NON_GESTITO. Unknown values are shown as written,
except all-caps values, which become sentence case with spaces (`"IN_GIACENZA"` →
"In giacenza").

**Shipment card** (`renderShipment`, ls-home@fe4b056:public/js/tracking.js:93-138):
"Spedizione {code}" (+ badge "Esempio dimostrativo" in demo); status line with icon
(class `is-delivered` for CONSEGNATO); "Ultimo aggiornamento:" + `updatedAt` (format
"mar 13 ott, 09:40"); progress list `aria-label="Avanzamento della spedizione"` with
`is-done` / `is-current` + `aria-current="step"`; meta list "Partenza", "Destinazione",
"Colli" ("{n} pallet"), "Consegna prevista" or "Consegnata" (`eta`, format "mercoledì 14
ottobre"); "Cronologia" (events in the order received, each: time, `note` or the state's
title, `location`); actions "Scarica la prova di consegna" (if `pod`), "Copia il link"
(copies `<origin>/tracking/?code=<code>`, label "Link copiato" for 2 s) and "Hai bisogno
di aiuto?" (WhatsApp "Ciao Logistica Sanchez, ho una domanda sulla spedizione {code}.").

**"Ask us" block** (`renderAsk`, ls-home@fe4b056:public/js/tracking.js:140-166):
"Spedizione {code}", "Ricevi lo stato aggiornato", the intro of the case (not found /
error / no endpoint: "Invia il codice con un tocco: ti rispondiamo con lo stato
aggiornato e la finestra di consegna prevista."), buttons "Chiedi su WhatsApp" (text
"Ciao Logistica Sanchez, vorrei lo stato della spedizione {code}. Grazie!"), "Scrivi una
email" (subject "Stato spedizione {code}"), phone, and the note "{BUSINESS.hours}. Fuori
orario ti rispondiamo appena possibile." (+ a "Prova il codice {demo}" button when a demo
code is set).

**Demo mode** (ls-home@fe4b056:public/js/tracking.js:58-78, 216-217): only when
`TRACKING.demoCode` is set (compared with the upper-cased input, so the setting must be
upper case): TRANSITO, Seregno (MB) → Bologna (BO), 2 pallets, delivery next working day,
updated 1 h ago, three events. Empty in production (ls-home@fe4b056:public/js/config.js:33).

**Recent codes** (ls-home@fe4b056:public/js/tracking.js:33, 42-56, 195-201):
`localStorage.ls_recent_codes` = JSON array, newest first, deduplicated, max 4, filtered
by the code regex on read; saved only when the backend found the code (always in the
portal/ask modes); shown as buttons after "Ricerche recenti:" that re-run the search.

**In the monolith:** set `TRACKING.endpoint` to the relative path
`/mono/api/order/trace/state` (chapter 10), drop the backend origin from `connect-src`, and
answer from the database with the same contract (API-01, TRK-01/02), including
`200 {"status":"Non gestito"}` for unknown and temporary codes (chapter 02 FX-03, FX-06).
Any non-2xx is shown
as "not found", so a 429 from the rate limit (TRK-04) or a 5xx would tell the visitor the
code does not exist: either accept it (parity) or let `tracking.js` treat 429/5xx like a
network error (small change; record it). Extra fields may be returned only from real data
and without personal data (TRK-03); never send `pod` or any document link from the public
endpoint (documents are downloaded only in the customer area, ENH-12). Optional: render the result server-side for
`/tracking/?code=` so it works without JavaScript.

## 6. SEO and platform

### 6.1 Domain and canonical URLs

Canonical domain is the apex `https://logisticasanchez.com` (no `www`), used in
canonicals, `og:url`, JSON-LD, sitemap, robots and security.txt; `www` must redirect to
it (Netlify primary domain). Reason given: the backend accepts browser calls (CORS) only
from `https://logisticasanchez.com` (ls-home@fe4b056:README.md:86-92). From the backend
source: it allows the origins of its `ALLOWED_ORIGINS` setting, which the repository sets
to the apex, `www` and `app.` (12a §2.10); a `*.netlify.app` preview is not among them. The domain is written
into the files; `node tools/sync.mjs --domain https://…` rewrites every
`.html/.xml/.txt/.webmanifest/.json/.js` file and `site.config.json`
(ls-home@fe4b056:tools/sync.mjs:63-80). The e-mail footer has its own constant
`SITE = 'logisticasanchez.com'` (ls-home@fe4b056:netlify/functions/form.mjs:21).

### 6.2 Sitemap and robots

* `public/sitemap.xml`: 24 URLs (every page except noindex pages and 404), home first,
  then alphabetical; `<lastmod>` = date of the file's last git commit (today's date if
  modified or outside git); `<priority>` 1.0 home, 0.3 `/privacy/` and `/cookie/`, 0.8
  first-level pages, 0.6 deeper pages (ls-home@fe4b056:tools/sync.mjs:236-282). All
  `lastmod` values are currently 2026-10-16.
* `robots.txt`: `User-agent: *`, `Allow: /`, `Sitemap: https://logisticasanchez.com/sitemap.xml`
  (ls-home@fe4b056:public/robots.txt:1-4).

### 6.3 Redirects (`public/_redirects`, 301 unless stated)

| From | To |
|---|---|
| `/about.html`, `/about` | `/chi-siamo/` |
| `/service.html`, `/service` | `/servizi/` |
| `/contact.html`, `/contact` | `/contatti/` |
| `/servizi.html` | `/servizi/` |
| `/contatti.html` | `/contatti/` |
| `/tracking.html` | `/tracking/` |
| `/preventivo.html` | `/preventivo/` |
| `/mono/*` (from `9b78785`) | `https://app.logisticasanchez.com/mono/:splat`, status 200: a Netlify proxy, so the links and the logo in the backend's e-mails, built on the apex by default (`EMAIL_ORIGIN`, chapter 02 §10.3), keep working after the apex moves to this site |

(ls-home@fe4b056:public/_redirects:1-11.) In the monolith the `/mono/*` proxy disappears:
the app answers those paths itself (API-01…API-03). `about/service/contact.html` are the pages of
the old template (ls-home@v1); the other `.html` names are (inferred) URLs of earlier
sites, see chapter 05. Fragment redirects (`/#chisiamo`, `/#contattaci`, `/#tracking`)
are client-side (§5.2). `www` → apex is a Netlify domain setting, not in the file.

### 6.4 Security headers, CSP and caching (`public/_headers`)

For `/*` (ls-home@fe4b056:public/_headers:1-7):

| Header | Value |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `X-Frame-Options` | `SAMEORIGIN` |
| `Strict-Transport-Security` | `max-age=31536000` |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=(), browsing-topics=()` |
| `Content-Security-Policy` | `default-src 'self'; script-src 'self' 'inline-speculation-rules'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self' https://app.logisticasanchez.com; frame-src https://www.google.com https://maps.google.com; form-action 'self'; base-uri 'self'; frame-ancestors 'self'; object-src 'none'; upgrade-insecure-requests` |

Notes: JSON-LD blocks are data, not scripts, so they pass `script-src`; the only inline
script is the speculation-rules block, allowed by `'inline-speculation-rules'`; JS sets
styles only through the CSSOM (`element.style`, custom properties), which `style-src`
does not block.

Caching (ls-home@fe4b056:public/_headers:9-16): `/fonts/*` `public, max-age=31536000,
immutable`; `/img/*` and `/*.png` `public, max-age=604800`. CSS, JS and HTML have no rule
(Netlify default revalidation, inferred) and their file names carry no version.

### 6.5 Open Graph images

24 PNGs in `public/img/og/`, 1200 × 630, about 59 KB each, one per indexable page; file
name = page path with `/` → `-` (`servizi-carico-completo.png`, `blog-<slug>.png`), home
uses `og-default.png` (also the `LocalBusiness.image`). No generator exists in the
repository (unknown how they were produced), so new pages need a new image made by hand
or a generator added in the monolith.

### 6.6 Speculation rules

Every page prerenders same-origin links on hover/pointer-down (`eagerness: moderate`),
except links with `target=_blank`, `download` or `rel~=nofollow`
(ls-home@fe4b056:partials/head.html:17; Chromium only).

### 6.7 Manifest, icons, security.txt

* `site.webmanifest`: name "Logistica Sanchez – Il pallet espresso", short_name
  "Logistica Sanchez", `lang` it, `start_url`/`scope` `/`, `display` `browser`,
  `background_color` `#ffffff`, `theme_color` `#04233b`, icons 192, 512 and maskable 512
  (ls-home@fe4b056:public/site.webmanifest:1-16).
* Head icons: `favicon.ico` (48 × 48), `favicon.svg`, `apple-touch-icon.png`
  (ls-home@fe4b056:partials/head.html:7-10).
* `/.well-known/security.txt` (RFC 9116): `Contact: mailto:logisticasanchezmb@gmail.com`,
  `Expires: 2027-10-01T00:00:00.000Z` (renew before it lapses), `Preferred-Languages: it, en`,
  `Canonical` URL (ls-home@fe4b056:public/.well-known/security.txt:1-5).

### 6.8 Netlify behaviours the monolith must reproduce

* Directory URLs with trailing slash (`/zone/` ← `zone/index.html`); (inferred) Netlify
  redirects `/zone` → `/zone/`, and every canonical ends with `/`.
* Unknown paths answer `404.html` with status 404 (inferred Netlify convention; PUB-09).
* `POST /api/form` only (ls-home@fe4b056:netlify/functions/form.mjs:19).
* Headers and redirects of §6.3–6.4; `www` → apex.

## 7. Business data and content

### 7.1 Business data (to keep in one place)

Today these values are repeated by hand. In `public/` alone (partials copied into every
page) the phone/WhatsApp number `393292396962` appears 312 times in 27 files, the e-mail
195 times in 28 files, the address 143 times and the VAT number 53 times; there are 142
`wa.me/393292396962` links (counted on `fe4b056`).

| Datum | Value (verbatim) | Where it appears |
|---|---|---|
| Brand / legal name | Logistica Sanchez / Logistica Sanchez S.r.l. | header brand, footer, JSON-LD, privacy |
| Slogan | Il pallet espresso | header brand tag, footer bottom line, JSON-LD |
| VAT | P.IVA 13113080967 (JSON-LD `vatID` "IT13113080967") | footer, JSON-LD, privacy |
| Address | Via dei Meiani, 90 – 20831 Seregno (MB) | topbar, drawer, footer, JSON-LD, contatti, chi-siamo, map facades (`data-map` "Via dei Meiani 90, 20831 Seregno MB") |
| Phone | +39 329 239 6962 (`tel:+393292396962`) | topbar, drawer, footer, action bar, contatti, `config.js`, form error page, JSON-LD |
| WhatsApp | `https://wa.me/393292396962` (+ prefilled texts, §7.2) | 142 links in the pages, `config.js`, form error page |
| E-mail | logisticasanchezmb@gmail.com | topbar, drawer, footer, contatti, privacy, cookie, `config.js`, form error page, security.txt |
| Hours | "Lun–Ven 8–18 · Sab 8–12" (partials); "Lun–Ven 8:00–18:00, Sab 8:00–12:00" (`config.js`); table "Lunedì – Venerdì" "8:00 – 18:00", "Sabato" "8:00 – 12:00", "Domenica" "Chiuso" (contatti); JSON-LD opening hours | ls-home@fe4b056:partials/header.html:10, ls-home@fe4b056:public/js/config.js:11, ls-home@fe4b056:public/contatti/index.html:156-160 |
| Instagram | https://www.instagram.com/sanchez_logistica_mb | footer, JSON-LD `sameAs` |
| Order portal | https://ordini.logisticasanchez.com ("Portale ordini", "Effettua un ordine") | topbar, drawer, footer, home hero (ls-home@fe4b056:public/index.html:154) |
| Site URL | https://logisticasanchez.com | canonicals, OG, JSON-LD, sitemap, robots, security.txt, `site.config.json`, e-mail footer |
| Credit | "Sito realizzato da Alpacode" → https://alpacode.it | footer (ls-home@fe4b056:partials/footer.html:61) |

These match the owner's data (chapter 01). In the monolith: one business configuration
feeding fragments, JSON-LD, `config.js`, e-mail templates, error pages and security.txt
(PUB-02). The order-portal links become "Area clienti" → `/area-clienti/` (chapter 10).

### 7.2 WhatsApp prefilled messages (static HTML)

| Text | Used |
|---|---|
| Ciao Logistica Sanchez, vorrei informazioni per una spedizione. | 80 links (action bar, FAB and drawer on every page; contatti hero and card) |
| Ciao Logistica Sanchez, vorrei un preventivo per una spedizione. | 21 links (CTA band on 20 pages, preventivo aside) |
| Ciao Logistica Sanchez, vorrei un preventivo. | quote wizard link before JS rebuilds it |
| … vorrei un preventivo per spedire dei pallet. / … per una spedizione in groupage. / … per un trasporto a carico completo. | service pages |
| … vorrei informazioni sul servizio di magazzino. | magazzino |
| … vorrei un consiglio su quale servizio scegliere per una spedizione. | servizi |
| … vorrei spedire dei pallet dalla zona di {zona}. | 5 zone pages |
| … vorrei sapere lo stato di una spedizione. / … non trovo la mia spedizione nel tracking. | tracking |
| … ho una domanda sulle spedizioni. | faq |
| … ho appena inviato una richiesta dal sito. | grazie |

Blog share links use `https://wa.me/?text=<title> – Logistica Sanchez <url>` (no number).
Dynamic texts built by JS are in §5.5 and §5.7.

### 7.3 Copy to review with the owner

| Item | Where | Why | Source |
|---|---|---|---|
| Service copy: delivery zones, warehousing, accessory services (sponda idraulica, consegna su appuntamento, copertura assicurativa integrativa) and town lists | services pages, zone pages, mega menu, home chips | README: written for "a typical pallet hub — keep only what Logistica Sanchez actually offers" | ls-home@fe4b056:README.md:214-216 |
| Stock truck photo | home hero, carico-completo | to replace with real photos (same file names) | ls-home@fe4b056:README.md:224-225 |
| Copy taken from the production site: stats "2000+ spedizioni effettuate", "100+ città servite", "100+ clienti soddisfatti", "10+ anni di esperienza"; hero lede; about text; FAQ answers | home, chi-siamo, faq | imported, not verified | commit ls-home@610d2e9 message; ls-home@fe4b056:public/index.html:151, 235-238 |
| Delivery promise is contradictory: "Consegne garantite da 24 a 72 ore" (hero), "fino a 96 ore… servizio express con consegna garantita in 24 ore" (FAQ), "Sono tempi indicativi" (zone FAQ), zone table 24/48/72 | home, faq, zone | one rule needed (also vs price-list times, chapter 07, Q-14) | ls-home@fe4b056:public/index.html:151, 582; ls-home@fe4b056:public/faq/index.html:157; ls-home@fe4b056:public/zone/index.html:251 |
| Insurance: "tutte le spedizioni sono coperte da un'assicurazione base" vs "copertura… oltre i limiti di responsabilità previsti per legge" | faq, servizi | confirm | ls-home@fe4b056:public/faq/index.html:180; ls-home@fe4b056:public/servizi/index.html:262-263 |
| Hours without Saturday: "Lun–Ven 8:00–18:00" | preventivo aside, contatti phone card | inconsistent with "Sab 8–12" elsewhere | ls-home@fe4b056:public/preventivo/index.html:346; ls-home@fe4b056:public/contatti/index.html:174 |
| "Partner certificati", hub "Su appuntamento", "Altre zone: Varese, Lodi, Pavia, Cremona" | home, contatti, zone | factual claims | ls-home@fe4b056:public/index.html:158; ls-home@fe4b056:public/contatti/index.html:176; ls-home@fe4b056:public/zone/index.html:194 |
| Vehicle and pallet figures (bilico 13,6 m, "fino a 33 posti pallet EUR"; 33/26/66/132 posti a terra) | carico-completo, home pallet guide, blog | generic industry figures (inferred) | ls-home@fe4b056:public/servizi/carico-completo/index.html:190, 213; ls-home@fe4b056:public/index.html:455-497 |
| Pricing examples (1 m³ = 250 kg, peso tassabile) | groupage, blog cost article | illustrative only; not the company's rule — real rules in chapter 07 | ls-home@fe4b056:public/servizi/groupage/index.html:205-211 |
| 4 blog articles, dated 8 Sep–6 Oct 2026, "Redazione Logistica Sanchez" | blog | written with the site (inferred) | §3.4 |
| Privacy names Netlify and Amazon SES as processors; cookie page says the site sets no cookies | privacy, cookie | must change for the monolith (hosting, session cookies), NFR-03 | ls-home@fe4b056:public/privacy/index.html:175-176; ls-home@fe4b056:public/cookie/index.html:155 |
| Footer credit "Sito realizzato da Alpacode" | footer | keep or not | ls-home@fe4b056:partials/footer.html:61 |

## 8. Porting guide for the monolith

### 8.1 Serve unchanged

Copy with the same paths (HTML, CSS and JS use root-relative URLs such as `/css/main.css`,
`/img/icons.svg#…`, `/fonts/…`, `/img/map-pattern.svg` inside the CSS, `/tracking/?code=`
inside `tracking.js`):

* `public/css/main.css`;
* `public/js/*.js` — unchanged except `config.js` (`TRACKING.endpoint` relative, and
  `BUSINESS` from the business configuration, either generated at startup or kept in
  step by a test);
* `public/fonts/*` (with `OFL-barlow.txt`), `public/img/**` (brand, photos, pallets,
  `italia-copertura.svg`, `map-pattern.svg`, `icons.svg`, `og/`);
* favicons, `apple-touch-icon.png`, `icon-*.png`, `site.webmanifest`,
  `.well-known/security.txt` (or generate it, renewing `Expires`);
* unreferenced files (`mark-white.svg`, `road-convoy-1920.*`, `favicon-32.png`) may be
  dropped.

### 8.2 Pages and fragments

| Today | Monolith (Thymeleaf in the reference stack, chapter 10) |
|---|---|
| `partials/head.html` | layout `<head>` fragment + a per-page block (title, description, canonical, robots, OG/article tags, page JSON-LD) |
| `partials/header.html` | header fragment; the `data-nav`/`aria-current` logic of ls-home@fe4b056:tools/sync.mjs:92-98 becomes a helper fed by a per-route page key (the old `data-page`); keep `<body data-page>` |
| `partials/cta.html` | optional fragment, included on the pages marked "CTA" in §3.1 |
| `partials/footer.html` | footer fragment (year rendered server-side, `data-year` kept) |
| `{{site}}`, `{{year}}` | `SITE_URL` setting, current year |
| 26 HTML pages | one template per page with the `<main>` content copied verbatim (simplest way to satisfy PUB-01); zone pages may be data-driven only if the HTML stays the same |
| `tools/sync.mjs` checks | CI tests: one `<h1>`, canonical = URL, unique titles/descriptions, links/assets/icons exist, JSON-LD parses |

### 8.3 Endpoints: today → monolith

| Widget call | Today | Monolith |
|---|---|---|
| Tracking lookup | `POST https://app.logisticasanchez.com/mono/api/order/trace/state` (CORS) | `POST /mono/api/order/trace/state`, same origin, same contract (API-01); the old host keeps answering during the transition |
| Quote and contact forms | `POST /api/form` (Netlify Function) | `POST /api/form` in the `site` module, identical behaviour |
| Coverage SVG | `GET /img/italia-copertura.svg` (fetch) | static file, same path |
| Google Maps | iframe `https://www.google.com/maps?…&output=embed` | unchanged (third party) |
| Prefill pages | `GET /tracking/?code=`, `/zone/?cap=`, `/preventivo/?servizio&tipo&da&a&pallet` | same URLs; optional server-side rendering of results |

### 8.4 What moves server-side

* Form handling (`form.mjs`) → `site` module + notifications outbox (EML-08), with storage
  of submissions (leads) and rate limits.
* Tracking data → read from the database by the `tracking` module (TRK-02); state
  changes recorded as events (ADM-06), which can later feed the timeline the page already
  draws (ENH-05).
* Sitemap and robots → generated from the route registry with the rules of §6.2 (exclude
  noindex pages and errors); `lastmod` from a per-page content date instead of git.
* Partials, active menu, domain and year → templates and settings.
* Business data and zone data → single sources (§7.1, §5.3).
* Redirects (§6.3), headers and CSP (§6.4), trailing-slash and 404 handling (§6.8).

### 8.5 Pitfalls

* **CSP.** Keep it at least as strict (PUB-05): no inline `<script>` or `style=""` in any
  public template (framework error pages, CSRF meta scripts, analytics snippets, import
  maps all break it); keep `'inline-speculation-rules'` and the Google `frame-src`;
  `connect-src 'self'` once tracking is same-origin. The customer area and back office get a policy at least as
  strict: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
  connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none';
  object-src 'none'` — never `'unsafe-inline'` or `'unsafe-eval'`. Configure HTMX through its
  `htmx-config` meta tag with `includeIndicatorStyles=false`, `allowEval=false`,
  `allowScriptTags=false` and `selfRequestsOnly=true`; put the indicator CSS in `main.css`;
  do not use `hx-on`, `hx-vars` or `js:` expressions.
* **Apex domain and CORS.** Canonical stays `https://logisticasanchez.com`; `www` and
  `http` 301 to it. Use a relative tracking endpoint so the page works on any host
  (staging, `www` before the redirect). The compatibility endpoint on
  `app.logisticasanchez.com` must keep CORS (including the preflight) for
  `https://logisticasanchez.com` while the old public site still calls it (owner; API-01).
* **CSRF.** A framework-wide CSRF filter (on by default in Spring Security, the reference
  stack of chapter 10) rejects both the fetch and the native post to `/api/form`. Decision (chapter 10 §4): exempt `POST /api/form` from the
  CSRF filter — it is unauthenticated and has no session — and protect it with the honeypot,
  a rate limit and an `Origin`/`Referer` check against the site's origin, so public HTML
  stays cacheable and cookie-free.
* **Field names.** `servizi_extra[]` contains brackets (bind it explicitly);
  `form-name` and `bot-field` contain a hyphen.
* **Speculation rules.** They prerender any same-origin link on hover. Exclude
  `/area-clienti/*`, `/gestione/*`, logout links, `/api/*` and any GET with side effects,
  or keep the rule only on public pages.
* **No-JS paths** (§4.13) must keep working: native posts → 303 `/grazie/` or the HTML
  error page; GET forms to `/tracking/`, `/zone/`, `/preventivo/`. E2E with JS off
  (PUB-06).
* **Performance budget** (PUB-10, Lighthouse ≥ 95): no client framework or global bundle
  on public pages; widgets stay lazy-loaded per selector; keep the two font preloads, the
  hero image `fetchpriority="high"` and responsive `<picture>`s, `width`/`height` on
  images; today ≈ 16.5 KB CSS + only the needed JS modules (all 8 = 15.5 KB gzip).
* **Caching and versioning.** CSS/JS names have no hash and modules import each other by
  relative path (`./config.js`, `./tracking.js`…). Fingerprinting would require rewriting
  those imports (an import map is an inline script, blocked by the CSP). Simplest:
  `Cache-Control: no-cache` + `ETag` for CSS/JS/HTML, keep the font/image rules.
* **Accessibility.** Keep `<main id="main">` (skip link target), exactly one `<h1>`,
  `aria-current`, the `<dialog>` drawer, tabs and live regions; run axe (PUB-10).
* **`btn-primary`** has no CSS: do not add a rule that changes the base button look.
* **Coverage map styles** live twice (inside the SVG and in `main.css`); change both.
* **Staging** must not be indexed (`X-Robots-Tag: noindex`) and must not e-mail the office
  (mail catcher, chapter 10).
* **Privacy pages** must name the new processors and list the session/CSRF cookies before
  launch (NFR-03).

### 8.6 Parity checks

| Requirement | Check against this chapter |
|---|---|
| PUB-01 | every URL of §3.1 answers 200 with that title, canonical and `<h1>`; JSON-LD types of §3.2 |
| PUB-02 | changing the phone in the business configuration changes every place in §7.1 |
| PUB-03 | sitemap = the 24 indexable URLs, priorities of §6.2 |
| PUB-04 | redirects of §6.3, legacy anchors of §5.2, `www` → apex |
| PUB-05 | headers of §6.4 on every public response |
| PUB-06/07 | field table of §5.5/§5.6, server cases of §5.6, e-mail text sample, JS on and off |
| PUB-08 | CAP cases of §5.3; legend hover and `eta:region` highlight on `/zone/` |
| PUB-09 | unknown URL → 404 page with status 404; `/grazie/` and 404 `noindex` |
| TRK-01 | the six states of §5.7 (status line, step, icon), unknown status, not found, error |

## 9. Quirks

1. NON_GESTITO draws an empty progress bar (no step reached), while the README table says
   "—" (ls-home@fe4b056:public/js/tracking.js:98-130; ls-home@fe4b056:README.md:113).
2. Any non-2xx from the backend reads as "code not found", including server errors
   (ls-home@fe4b056:public/js/tracking.js:178-179).
3. `.track-state.is-exception` is styled but never applied; there is no exception state
   (ls-home@fe4b056:public/css/main.css:3513; Q-04).
4. `pod` is used as a link without checking its scheme
   (ls-home@fe4b056:public/js/tracking.js:134).
5. The server accepts decimal quantities (`numero=2,5` → "2.5 ×"), although the browser
   field is an integer (ls-home@fe4b056:netlify/functions/form.mjs:45-50, 92).
6. `data_ritiro`: the JS minimum uses the UTC date (`toISOString`), so between midnight
   and 01:00/02:00 Italian time the minimum is yesterday; the server accepts past dates
   (ls-home@fe4b056:public/js/quote.js:125; ls-home@fe4b056:netlify/functions/form.mjs:65-69).
7. `oggetto` is not checked against its five options server-side
   (ls-home@fe4b056:netlify/functions/form.mjs:135).
8. Labels and the extras list are duplicated between `quote.js` and `form.mjs`; unknown
   extras are dropped silently (ls-home@fe4b056:netlify/functions/form.mjs:30, 97).
9. Zone data is duplicated in JS, HTML tables/legends and the SVG (§5.3).
10. Main menu on phones needs JavaScript (§4.13).
11. On `/zone/?cap=…` the `eta:region` event can fire before `coverage.js` has inlined the
    SVG, so the region may not be highlighted on load (inferred race:
    ls-home@fe4b056:public/js/eta.js:92-96, ls-home@fe4b056:public/js/coverage.js:12-32, 89).
12. Blog posts use `data-page="blog"`, so the "Blog" menu item gets
    `aria-current="page"` (not `"true"`) on articles.
13. `main.js` comments a `?formato=` deep link that is not implemented
    (ls-home@fe4b056:public/js/main.js:111).
14. `form.mjs` says `ses` "is only passed by tests", but the repository has no tests
    (ls-home@fe4b056:netlify/functions/form.mjs:213).
15. Minor-island list is partial (23 CAPs); other ferry-only places (e.g. Lampedusa) get
    their region's zone (ls-home@fe4b056:public/js/eta.js:46-50).
16. Duplicate nested `<div class="faq">` on `/tracking/`
    (ls-home@fe4b056:public/tracking/index.html:188).
17. `forms.js` assumes `[data-form-status]` exists (TypeError otherwise;
    ls-home@fe4b056:public/js/forms.js:87, 101).

## 10. Unknowns

* What `status` contains in production (label, name or id) — Q-03; the page accepts all.
* Which HTTP status the backend returns for an unknown code (only "an error status",
  ls-home@fe4b056:public/js/tracking.js:178), its case sensitivity for codes and the code
  format (Q-06).
* Whether the backend can provide origin, destination, pallets, ETA, events or a POD link.
* How the Open Graph images were produced.
* Whether browsers apply the strict CSP sent with `italia-copertura.svg` (rule `/*`) to its
  internal `<style>` when the SVG is shown as an `<img>` (the no-JS map fallback); verify
  in Chrome, Firefox and Safari.
* Whether the copy of §7.3 matches the real offer, and whether the stats are current.
* The current SES setup (sandbox, verified identities) for the site sender.
