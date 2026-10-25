/**
 * Home motion — the site's half of the core (ADR 0030; alpacode-motion D5, D14): which
 * elements of the home each effect takes. The home's markup is the ls-home port and carries
 * no motion hook (PUB-01), so every hook is mapped here onto the page's own classes and ids.
 *
 * The casting, one move per section and never the same move twice in a row:
 *   stats («2000+ spedizioni») ...... count: the numbers count up to the value they always show
 *   «Soluzioni logistiche» .......... kicker + split-lines + reveal: the heading rises out of its lines
 *   «Come funziona» ................. rail: the four steps light up in order, the connectors draw
 *   «Perché sceglierci» ............. kicker + split-lines + reveal
 *   the band «Spedire un pallet…» ... scrub: the statement is read word by word as it scrolls by
 *   «Che pallet devi spedire?» ...... kicker + split-lines + reveal
 *   «Spedire meglio» (blog) ......... kicker + split-lines + reveal
 * The hero stays CSS (first paint never waits on JavaScript); the other blocks keep main.js's
 * own fade (data-reveal, data-stagger).
 */
const SPLIT_SECTIONS = [
  '#servizi .section-head',
  '[aria-labelledby="perche-title"] .why-head',
  '#guida-pallet .section-head',
  '[aria-labelledby="blog-title"] .section-head',
];

export const config = {
  selectors: {
    '[data-count]': ['.stats-grid .stat-value'],
    '[data-kicker]': SPLIT_SECTIONS.map((head) => `${head} > .kicker`),
    '[data-split="lines"]': ['#servizi-title', '#perche-title', '#pallet-title', '#blog-title'],
    '[data-reveal]': [...SPLIT_SECTIONS.map((head) => `${head} > .lede`), '[aria-labelledby="perche-title"] .why-head > .actions'],
    '[data-rail]': ['.steps'],
    '[data-rail-step]': ['.steps > .step'],
    '[data-rail-tile]': ['.steps .step-num'],
    '[data-scrub]': ['.band-quote'],
  },
  /** The page's infinite CSS loops (the live dot, the map's route flows and pings, the band's
   *  route): each pauses while it is off screen. */
  loops: ['.dot-live', '.coverage-map', '.band-route'],
};
