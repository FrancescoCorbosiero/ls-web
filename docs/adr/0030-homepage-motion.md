# 0030 · Home page motion: a small GSAP layer after alpacode-motion

* **Status:** Accepted
* **Date:** 2026-10-25
* **Requirement IDs:** PUB-01, PUB-05, PUB-10 (chapter 09) · **Questions:** none (owner request of 2026-10-25: «you can use the repo "alpacode-motion" as reference for (lightweight) structured GSAP animations in the homepage») · **Reference:** `FrancescoCorbosiero/alpacode-motion` at `7b1a203` (CLAUDE.md "The rules of motion", `src/engine/`, `catalog.md`, `docs/decisions.md` D5, D7, D8, D13, D14)

## Context

The home is the ls-home port (ADR 0012): its `<main>` must stay equal to ls-home@9b78785
(`SitePagesIT`, `tools/port-ls-home-pages.py --check`), the public CSP allows scripts from
`/js/` only and no inline style (`SecurityHeadersFilter.PUBLIC_CSP`), and PUB-10 keeps the
Lighthouse and axe budgets. It already moves in three ways, all CSS-driven: the hero's intro
keyframes (`rise`, the route under «espresso»), main.js's reveal (`data-reveal`,
`data-stagger`, `html.reveal-on`, `.is-in`: blocks fade up as they enter) and four infinite
loops (the live dot, the map's route flows and pings, the band's route).

The owner asked for structured, lightweight GSAP motion on the home with alpacode-motion as
the reference. alpacode-motion is context, not a dependency (its D1): an effect's code is
copied into a site and adapted, and the site records which entries it took from which commit.
This ADR is that record.

## Decision

* **The engine, copied and adapted.** `static/js/motion/` is a plain-JS port (no bundler, no
  TypeScript) of alpacode-motion's engine at 7b1a203, in its layout (D14): `core.js` (the
  lifecycle, the guards, `core-focus-reveal`, `core-refresh`), `config.js` (the site's
  selector map), `registry.js` (the site's effects), `index.js` (the entry), `effects/<id>.js`
  with the effect contract of D13 (`hook` + `init(kit)`, settings as constants at the top).
  The kit carries `gsap`, `ScrollTrigger`, `SplitText`, `media` (the page's
  `gsap.matchMedia()`), `on`, `cleanup`, `query`, `inFirstView` and `handOver`.
* **Loading.** One line in main.js's lazy widget list imports `motion/index.js` on the home
  only (`body[data-page="home"]`). Under `prefers-reduced-motion: reduce` nothing else is
  fetched (until the visitor switches motion back on); otherwise the core and the effects are
  imported, and the core loads GSAP **3.15.0** (alpacode-motion's pin) once the `load` event
  has fired and the browser is idle (`requestIdleCallback`, 1.5 s timeout): `gsap.min.js`,
  `ScrollTrigger.min.js`, `SplitText.min.js`, vendored unmodified under
  `static/js/vendor/gsap/` from the npm package and inserted as ordered classic scripts. No
  Lenis, no smooth scrolling, no other page affected.
* **The story, one move per section** (alpacode CLAUDE.md "Using the catalog on a site"; never
  the same move in two neighbouring sections):

  | Section | Move | Entry (alpacode-motion 7b1a203) |
  |---|---|---|
  | Hero | unchanged: CSS keyframes (first paint never waits on JavaScript) | — |
  | Stats «2000+ spedizioni…» | the figures count up to the value they always show; the text is the truth and is put back verbatim | `count` (status `src`: written from its notes) |
  | «Soluzioni logistiche complete» | the kicker's lime square stamps in, the heading's lines rise out of their masks, the lede lands after them | `kicker` (from its notes), `split-lines` + `_headings` (port), `reveal` (port) |
  | «Come funziona» | the four steps light up in order as the scroll passes and the dashed connectors draw (scrubbed: it runs back when the reader scrolls back) | `rail` (from its notes; step i lights at i/n, alpacode's threshold) |
  | «Copertura nazionale» | unchanged: main.js reveal and the map's own CSS loops | — |
  | «Perché sceglierci» | kicker, split heading, lede and actions | as the services head |
  | Band «Spedire un pallet…» | the statement's words light up as the scroll reads them | `scrub` (port) |
  | «Che pallet devi spedire?», «Spedire meglio, spendere meno» | kicker, split heading, lede | as the services head |
  | Settori, FAQ, the other blocks | unchanged: main.js reveal | — |

* **Selector map instead of attributes (D5, adapted).** The markup carries no motion hook:
  `<main>` stays the ls-home port (PUB-01), so `query(hook)` reads `config.js` only — the
  canonical hooks (`[data-split="lines"]`, `[data-scrub]`, `[data-count]`, `[data-kicker]`,
  `[data-rail]` with `-step` and `-tile`, `[data-reveal]`) are the map's keys. The raw hook is
  never queried, also because `data-reveal` is main.js's own attribute on this site.
* **Coexistence with main.js.** main.js keeps every block motion does not take. An element
  motion takes is handed over first: its `[data-reveal]` block gets `.is-in` (main.js's own
  end state), so only one move ever plays on it.
* **The rules of motion, kept.** Nothing is hidden by CSS: every effect hides its elements in
  JavaScript at init, and only when they start below 92 % of the screen (`inFirstView`),
  so a page whose script fails is complete. Reveals hand control back to the stylesheet
  (`clearProps`); the scrubbed timelines own their start (`fromTo`); triggers are sorted,
  refreshed once the fonts are in and whenever the page height changes (a `ResizeObserver`
  covers the tabs' `tabchange`, the FAQ and the inlined coverage map); a block waiting to be
  revealed shows at once when keyboard focus lands inside it (`data-motion-pending`); each
  effect is built in a `gsap.context()` of its own, so one that throws is undone alone. Loops
  run only on screen: the page's four infinite CSS loops pause while off screen
  (`.is-offscreen`, an `IntersectionObserver`).
* **Reduced motion, mid-visit too.** Switching reduced motion on reverts everything
  (SplitText restores the headings, the figures get their text back, the inline
  `scroll-behavior` ScrollTrigger leaves on `<html>` is removed, `has-motion` goes); switching
  it off builds the page again from where the reader is.
* **CSS.** One clearly marked block at the end of `main.css` ("ls-web addition: home motion
  (ADR 0030)"): the line masks of split headings, `--kick` (the kicker's square) and `--rail`
  (the connectors), tabular digits while a figure counts, loops paused off screen. Every rule
  waits for `html.has-motion` or for an element the script creates, and every variable
  defaults to its end state; nothing uses `display: none`.
* **CSP.** Only scripts from `/js/` run. GSAP and SplitText write styles through the CSSOM,
  which `style-src 'self'` allows; the E2E runs listen for `securitypolicyviolation` and found
  none (SplitText's line masks included). `SitePagesIT` keeps the motion modules free of
  `setAttribute('style'`, `cssText`, `innerHTML`, `insertAdjacentHTML` and `eval(`.
* **Licence.** GSAP 3.15.0 and its plugins are distributed under GreenSock's standard
  "no charge" licence (gsap.com/standard-license): free, including commercial use and every
  plugin (SplitText, ScrollTrigger) since 3.13. The vendored files keep their licence headers.

## Consequences

* **Weight** (gzip -9, measured on the committed files): GSAP 49,904 B (gsap 28,302 +
  ScrollTrigger 17,961 + SplitText 3,641; 125,234 B raw); the motion modules 10,808 B as
  served (22,758 B raw, readable source with comments, eleven files; 7,664 B if they were one
  file). All of it is fetched after `load`, on the home only. Under reduced motion only
  `motion/index.js` is fetched: 917 B raw, 548 B gzip.
* **Lighthouse** (local run, Lighthouse 12.8.2 in this session's Chromium, the same jar
  serving the home with and without the motion files, two runs each): mobile 88 without
  motion, 88 and 90 with it (TBT 0 → 40 ms; FCP, LCP, CLS unchanged); desktop 99–100 in both.
  The mobile score is under the PUB-10 budget of 95 **without** motion too in this environment
  (LCP 3.4 s under simulated slow 4G, a local server without HTTP/2): the Lighthouse CI job
  still to be added (plan §Status) measures it on the real stack.
* **Parity.** The home's HTML is unchanged (no script tag, no attribute): PUB-01 and the port
  script are untouched; the PUB-01 allow-list of ADR 0012 needs no entry. main.js changes by
  one marked line and main.css by one marked block at its end.
* **Proof.** `SitePagesIT.theHomeMotionIsAModuleOfMainJsAndLeavesTheMarkupAlone` (the hook in
  main.js, the files served with the public CSP, no hook in the markup, no CSP-unsafe DOM
  writes); `e2e/tests/public.spec.ts` «home motion (ADR 0030)» on desktop and phone: GSAP loads
  after the page, no console error or CSP violation, every heading and figure ends equal to the
  server's text and fully visible, axe clean inside `<main>` once the motion has settled, and
  under reduced motion nothing but `motion/index.js` is fetched and the page stays static.
  `e2e/playwright.config.ts` accepts `CHROMIUM_PATH` for sessions whose pre-installed Chromium
  is not the build Playwright 1.56.1 pins (CI installs its own).
* **Maintenance.** An effect is changed in its module (its settings are the constants at the
  top) and its CSS in the marked block; a new move starts from the section's story and the
  catalog, gets a line in `registry.js` and its selectors in `config.js`. Moving to a newer
  alpacode-motion commit or GSAP version is a new line in this ADR.

## References

alpacode-motion@7b1a203: `CLAUDE.md`, `src/engine/core/{lifecycle,guards,focus,refresh,kit}.ts`,
`src/engine/effects/{reveal,split-lines,_headings,scrub}.ts` and `.css`, `catalog.md` rows
`count`, `kicker`, `rail`, `docs/decisions.md` D1, D5, D7, D8, D13–D15. ADR 0012 (port and
allow-list), ADR 0018 (CSP), chapter 06 §8.5 (performance budgets), chapter 09 PUB-01,
PUB-05, PUB-10.
