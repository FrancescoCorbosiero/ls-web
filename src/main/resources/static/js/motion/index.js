/**
 * Home motion — the entry point (ADR 0030; alpacode-motion D14 index.ts). main.js imports it
 * only on the home (body[data-page="home"]). Under prefers-reduced-motion nothing else is
 * fetched until the visitor switches motion back on; otherwise the core and the effects are
 * imported, and the core fetches GSAP once the page has loaded and the browser is idle.
 * Reference: alpacode-motion at 7b1a203 (src/engine/, catalog.md); the site's selector map
 * is config.js, its effects registry.js.
 */
export default function init() {
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const go = () =>
    Promise.all([import('./core.js'), import('./registry.js')])
      .then(([core, registry]) => core.start(registry.effects))
      .catch((err) => console.error('motion: not started', err));
  if (reduced.matches) reduced.addEventListener('change', go, { once: true });
  else go();
}
