/**
 * split-lines — each line of a heading rises out of its mask.
 * Port of alpacode-motion src/engine/effects/split-lines.ts (7b1a203).
 * Below the fold only, once, as the heading enters (_headings.js). The lines are measured
 * where they wrap, so the move follows the layout at every width and splits again when the
 * fonts arrive. Masks: .split-line-mask in main.css (ADR 0030 block).
 */
import { splitHeading } from './_headings.js';

export const hook = '[data-split="lines"]';

/* ---------- Settings: the site's voice ---------- */
const RISE = 115; // %: how far each line rises, of its own height
const DURATION = 1.15; // s: how long each line takes
const STAGGER = 0.085; // s between lines
const EASE = 'power4.out'; // the long, soft landing

export function init(kit) {
  const move = {
    split: { type: 'lines', mask: 'lines' },
    play: (split, scrollTrigger) =>
      kit.gsap.from(split.lines, { yPercent: RISE, duration: DURATION, ease: EASE, stagger: STAGGER, scrollTrigger }),
  };
  kit.query(hook).forEach((el) => splitHeading(kit, el, move));
}
