# 0047 · The customer area installs on a phone as an app

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** NFR-01 (customer area usable on a phone), CUS-01, CUS-05 · chapter 09
  «Later»: «PWA/offline» · **Questions:** Q-57 (answered for customers), Q-58 (push, open)
  · **Owner:** request of 2026-10-26 («Rendi l'area clienti installabile sul telefono»), after
  ADR 0046

## Context

ADR 0046 plans an API for a future native client; its first step for customers (Q-57, default)
is cheaper: make the web customer area installable on the phone, so that it opens from its own
icon, full screen, like an app, with every screen and rule reused. Browsers install a site as an
app when the page links a web app manifest that says what the app is (name, icons, start page,
scope, display mode). The public site already has one (`/site.webmanifest`, `display: browser`,
copied from ls-home); the private areas had none.

## Decision

* **A manifest of its own for the customer area**, `/area-clienti.webmanifest`, served by
  `WebManifestController` as `application/manifest+json`, public and cookie-free (browsers fetch
  manifests without credentials): `id`, `start_url` and `scope` `/area-clienti/`, `display:
  standalone`, name «Logistica Sanchez – Area clienti», short name «LS Clienti», a description,
  `lang: it`, theme colour `#04233b` (the private pages' `theme-color`), background `#f4f7fa`
  (the area's page, for Android's splash screen), and the site's icons: 192 and 512 pixels and a
  maskable 512. Its path is outside `/area-clienti/`, so it stays public without widening the
  public entries of the private areas (ADR 0002); the route matrix lists it.
* **Linked only by customer-area pages.** `PlatformModelAttributes` sets `customerArea` for
  paths under `/area-clienti/`; `layout/private :: htmlHead` then adds the manifest link and
  the metas that older iPhones read instead of the manifest (`apple-mobile-web-app-capable`,
  `apple-mobile-web-app-title` «LS Clienti», `apple-mobile-web-app-status-bar-style: default`,
  with `mobile-web-app-capable`); the `apple-touch-icon` was already there. The back office gets
  no manifest (desk work), the public site keeps its own.
* **No service worker now.** It is not needed to install: Chrome's criteria are HTTPS and a
  manifest with a name, 192 and 512 pixel icons, a start URL and a standalone display, and iOS
  never asked for one. A worker caching a private area would need care for the customers' data;
  it is the step for an offline page and, with Q-58, for push notifications.
* **«Usa l'area clienti come un'app»** at the bottom of «I miei ordini»: a closed card (the FAQ
  card of the site) shown by CSS only on touch screens (`pointer: coarse`) and never inside the
  installed app (`display-mode: standalone`), without JavaScript (ADR 0029). It says how to add
  the area on an iPhone (Safari, Condividi, «Aggiungi alla schermata Home») and on Android
  (Chrome, menu, «Installa app»), and «Nell'app accedi con la tua e-mail e la password», with
  «Imposta la password» for a customer who has none (ADR 0032).
* **Signing in from the app.** On Android the installed app runs on Chrome's engine and shares
  its cookies, so a customer kept signed in by «Resta connesso» (ADR 0031) opens it signed in.
  On an iPhone a Home Screen app keeps its storage apart from Safari and e-mailed links open in
  Safari: the customer may sign in once inside the app with e-mail and password, and «Resta
  connesso» keeps them there for 60 days. Hence the password line of the card.
* Nothing else changes: same sessions, CSP (`default-src 'self'` covers the manifest), routes
  and pages; the app is the site, so every deploy reaches it at once.

## Consequences

* Customers can put the area on their home screen with its icon and name: it opens full screen
  and has its own entry among the open apps. No store, account or cost.
* Not yet: an offline page (without network the app shows the browser's error), push
  notifications (Q-58), a store presence (Q-57 for a store app). On an iPhone a standalone app
  has no back button: every customer page already has the section navigation. Pages outside the
  scope (public tracking, privacy) open with the browser's bar.
* New Italian texts for the owner's review: «LS Clienti», «Logistica Sanchez – Area clienti», the
  description and the card's texts.

## References

Chapter 09 «Later»; ADR 0002 (routes), 0018 (CSP), 0029 (private-area interface), 0031
(remembered device), 0032 (password), 0046 (API v1 plan). Code: `static/area-clienti.webmanifest`,
`platform.web.WebManifestController`, `PlatformModelAttributes`, `PlatformRouteContributor`,
`templates/layout/private.html` (`htmlHead`, `installHint`), `orders/list.html`, `app.css`,
`app-icons.svg` (`i-smartphone`). Tests: `InstallableCustomerAreaIT`, `LayoutIT`, the route
matrix, `e2e/tests/install.spec.ts` (Chromium's manifest parser, phone and desktop).
