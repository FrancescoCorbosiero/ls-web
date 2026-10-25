/**
 * The headings' shared move, used by every split-* entry. Port of alpacode-motion
 * src/engine/effects/_headings.ts (7b1a203): SplitText with autoSplit (it splits again when
 * the fonts or the width change, and hands the new split the tween's progress), one
 * ScrollTrigger at top 88%, once; skipped in the first view. SplitText keeps the heading's
 * text as its aria-label, so assistive technology reads it whole. Not an entry.
 */

/* ---------- Settings: the site's voice ---------- */
const START = 'top 88%'; // where a heading's trigger fires

/** Split a heading and play its move as it enters. move = { split, play(split, scrollTrigger) }. */
export function splitHeading(kit, el, move) {
  if (kit.inFirstView(el)) return;
  kit.handOver(el);
  kit.SplitText.create(el, {
    ...move.split,
    linesClass: 'split-line',
    wordsClass: 'split-word',
    autoSplit: true,
    onSplit: (self) => move.play(self, { trigger: el, start: START, once: true }),
  });
}
