/**
 * reveal — blocks fade up in staggered batches as they enter.
 * Port of alpacode-motion src/engine/effects/reveal.ts (7b1a203). Here it only takes the
 * blocks motion hands over from main.js (the ledes and actions of the split headings, see
 * config.js); every other block keeps main.js's own CSS reveal.
 * Blocks below the fold are hidden at init and marked data-motion-pending (the core shows
 * one at once when keyboard focus lands inside it). One ScrollTrigger.batch hands them over
 * as they enter: a jump shows the skipped ones at once and keeps the stagger short for the
 * rest. When a block has landed, control goes back to the stylesheet (clearProps).
 */
export const hook = '[data-reveal]';

/* ---------- Settings: the site's voice ---------- */
const RISE = 28; // px: how far a block rises (alpacode 44; shorter for a lede under its heading)
const DURATION = 1.0; // s: how long a block takes to land
const STAGGER = 0.09; // s between the blocks of a small batch
const SPREAD = 0.45; // s a batch of more than five is spread over
const START = 'top 90%'; // where a block counts as entering
const DELAY = 0.25; // s: a lede lands just after its heading's lines

export function init(kit) {
  const { gsap, ScrollTrigger } = kit;
  const blocks = kit.query(hook).filter((el) => !kit.inFirstView(el));
  if (!blocks.length) return;
  blocks.forEach(kit.handOver);
  gsap.set(blocks, { opacity: 0, y: RISE, transition: 'none' });
  blocks.forEach((el) => el.setAttribute('data-motion-pending', ''));
  kit.cleanup(() => blocks.forEach((el) => el.removeAttribute('data-motion-pending')));
  ScrollTrigger.batch(blocks, {
    start: START,
    once: true,
    onEnter: (batch) => {
      // a block keyboard focus has already shown is left to that
      const els = batch.filter((el) => el.hasAttribute('data-motion-pending'));
      els.forEach((el) => el.removeAttribute('data-motion-pending'));
      const passed = els.filter((el) => el.getBoundingClientRect().bottom < 0);
      const entering = els.filter((el) => !passed.includes(el));
      if (passed.length) gsap.set(passed, { overwrite: true, clearProps: 'opacity,transform,transition' });
      if (!entering.length) return;
      gsap.to(entering, {
        opacity: 1,
        y: 0,
        duration: DURATION,
        delay: DELAY,
        ease: 'power3.out',
        stagger: entering.length > 5 ? { amount: SPREAD } : STAGGER,
        overwrite: true,
        clearProps: 'opacity,transform,transition',
      });
    },
  });
}
