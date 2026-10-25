/**
 * kicker — a section's kicker label slides in while its lime square (the logo's) stamps in.
 * After the catalog entry `kicker` of alpacode-motion (7b1a203: "a kicker label fades in
 * while its strut, a ::before, draws in"; status src there, written here from its notes):
 * a pseudo-element can't be tweened, so the square is driven through --kick (main.css, ADR
 * 0030 block: scale and a quarter turn). Below the fold only, once, as the kicker enters.
 */
export const hook = '[data-kicker]';

/* ---------- Settings: the site's voice ---------- */
const SLIDE = 14; // px: where the label comes from, to the left
const DURATION = 0.9; // s
const START = 'top 90%'; // the kicker sits above its heading, so it fires first

export function init(kit) {
  const { gsap } = kit;
  kit.query(hook).forEach((el) => {
    if (kit.inFirstView(el)) return;
    kit.handOver(el);
    gsap.fromTo(
      el,
      { opacity: 0, x: -SLIDE, '--kick': 0 },
      {
        opacity: 1,
        x: 0,
        '--kick': 1,
        duration: DURATION,
        ease: 'expo.out',
        scrollTrigger: { trigger: el, start: START, once: true },
        clearProps: 'opacity,transform,--kick',
      },
    );
  });
}
