/**
 * rail — a sequence of steps lights up in order as the scroll passes it.
 * After the catalog entry `rail` of alpacode-motion (7b1a203; status src there, written here
 * from its notes: "a rail fills with the scroll, the steps light up as it passes, each tile
 * gives a little jolt"; step i lights at i/n, alpacode's threshold). The steps' own dashed
 * connectors are the rail: a pseudo-element can't be tweened, so each draws through --rail
 * (main.css, ADR 0030 block: scaleX on the row, scaleY when the steps stack). Scrubbed, so it
 * runs backwards when the reader scrolls back; a timeline that owns its start (fromTo).
 * Markup contract (config.js): [data-rail] the list, [data-rail-step] its steps,
 * [data-rail-tile] the badge of each step.
 */
export const hook = '[data-rail]';

/* ---------- Settings: the site's voice ---------- */
const START = 'top 78%'; // the list's top here: the first step lights
const END = 'bottom 52%'; // its foot here: the last step is lit
const SMOOTH = 0.6; // s the rail takes to catch up with the scroll
const DIM = 0.3; // opacity of a tile not reached yet
const SHRINK = 0.7; // scale of a tile not reached yet

export function init(kit) {
  const { gsap } = kit;
  const allSteps = kit.query('[data-rail-step]');
  const allTiles = kit.query('[data-rail-tile]');
  kit.query(hook).forEach((list) => {
    if (kit.inFirstView(list)) return;
    const steps = allSteps.filter((step) => list.contains(step));
    if (steps.length < 2) return;
    steps.forEach(kit.handOver);
    const tl = gsap.timeline({ scrollTrigger: { trigger: list, start: START, end: END, scrub: SMOOTH } });
    steps.forEach((step, i) => {
      const tile = allTiles.find((t) => step.contains(t));
      if (tile) {
        tl.fromTo(tile, { scale: SHRINK, opacity: DIM }, { scale: 1, opacity: 1, duration: 0.5, ease: 'back.out(2.2)' }, i);
      }
      if (i < steps.length - 1) {
        tl.fromTo(step, { '--rail': 0 }, { '--rail': 1, duration: 0.5, ease: 'none' }, i + 0.5);
      }
    });
  });
}
