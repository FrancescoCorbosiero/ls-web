/**
 * scrub — a statement's words light up as the scroll reads them.
 * Port of alpacode-motion src/engine/effects/scrub.ts (7b1a203).
 * The words start dim and come to full strength one after another, scrubbed by the scroll
 * from when the statement enters until its foot passes the middle of the screen; scrolling
 * back dims them again. SplitText splits it into words (nested <em> kept) and splits again
 * when widths or fonts change. A statement in the first view is left alone: dimming painted
 * words is hiding them.
 */
export const hook = '[data-scrub]';

/* ---------- Settings: the site's voice ---------- */
const DIM = 0.16; // opacity of a word not read yet
const START = 'top 82%'; // the statement's top here: the first word starts
const END = 'bottom 48%'; // its foot here: the last word is lit
const SMOOTH = 0.6; // s the words take to catch up with the scroll

export function init(kit) {
  const { gsap, SplitText } = kit;
  kit.query(hook).forEach((el) => {
    if (kit.inFirstView(el)) return;
    kit.handOver(el);
    SplitText.create(el, {
      type: 'words',
      wordsClass: 'scrub-word',
      autoSplit: true,
      onSplit: (self) =>
        gsap.fromTo(
          self.words,
          { opacity: DIM },
          {
            opacity: 1,
            ease: 'none',
            stagger: 0.1,
            scrollTrigger: { trigger: el, start: START, end: END, scrub: SMOOTH },
          },
        ),
    });
  });
}
