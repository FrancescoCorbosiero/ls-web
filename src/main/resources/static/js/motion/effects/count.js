/**
 * count — a number counts up to its value; the static text stays the truth.
 * After the catalog entry `count` of alpacode-motion (7b1a203; status src there, written here
 * from its notes): the element's own text («2000+», «10+») is the value and the template, so
 * the page without JavaScript (or under reduced motion) shows exactly the same figures, and
 * the text is put back verbatim when the count ends or the page is rebuilt. Below the fold
 * only (a painted figure is never reset), once, in batches as the figures enter.
 */
export const hook = '[data-count]';

/* ---------- Settings: the site's voice ---------- */
const DURATION = 1.6; // s
const STAGGER = 0.12; // s between the figures of a row
const EASE = 'power2.out'; // fast at first, settling on the value
const START = 'top 88%';
const FIGURE = /^(\D*)(\d{1,7})(\D*)$/; // prefix, the integer, suffix («2000+»)

export function init(kit) {
  const { gsap, ScrollTrigger } = kit;
  const items = kit
    .query(hook)
    .map((el) => ({ el, text: el.textContent, parts: el.textContent.trim().match(FIGURE) }))
    .filter(({ el, parts }) => parts && !kit.inFirstView(el));
  if (!items.length) return;
  const byElement = new Map(items.map((item) => [item.el, item]));
  const write = ({ el, parts }, value) => {
    el.textContent = parts[1] + value + parts[3];
  };
  kit.cleanup(() =>
    items.forEach(({ el, text }) => {
      el.textContent = text;
      el.classList.remove('is-counting');
    }),
  );
  items.forEach((item) => {
    kit.handOver(item.el);
    write(item, 0);
  });
  ScrollTrigger.batch(
    items.map(({ el }) => el),
    {
      start: START,
      once: true,
      onEnter: (batch) =>
        batch.forEach((el, i) => {
          const item = byElement.get(el);
          const state = { value: 0 };
          gsap.to(state, {
            value: Number(item.parts[2]),
            duration: DURATION,
            delay: i * STAGGER,
            ease: EASE,
            onStart: () => el.classList.add('is-counting'),
            onUpdate: () => write(item, Math.round(state.value)),
            onComplete: () => {
              el.textContent = item.text;
              el.classList.remove('is-counting');
            },
          });
        }),
    },
  );
}
