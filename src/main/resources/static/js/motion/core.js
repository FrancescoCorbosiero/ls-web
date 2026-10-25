/**
 * Home motion — the core (ADR 0030). A plain-JS port of alpacode-motion's engine core
 * (src/engine/core/ at 7b1a203: lifecycle, guards, focus, refresh) for a page without a
 * client router and without a bundler:
 *   - GSAP, ScrollTrigger and SplitText (vendored under /js/vendor/gsap/) are fetched only
 *     when motion is welcome, after the page has loaded and the browser is idle;
 *   - every effect is built in a gsap.context() of its own inside the page's, so one that
 *     throws is undone alone and the page keeps the rest (and stays complete);
 *   - under prefers-reduced-motion nothing is fetched or built; switching it on mid-visit
 *     reverts everything, switching it off builds the page;
 *   - nothing is hidden by CSS: effects hide their elements at init, and only below the fold;
 *   - the site's own reveal (main.js: data-reveal, .is-in) keeps every element motion does
 *     not take; an element motion takes is handed over first (its block gets .is-in);
 *   - keyboard focus never waits for a reveal; triggers are refreshed once the fonts are in
 *     and when the page height changes (tabs, FAQ, the inlined coverage map);
 *   - the CSS loops of the page (route flows, pings, the live dot) pause off screen.
 */
import { config } from './config.js';

const VENDOR = '/js/vendor/gsap/';
const LIBRARIES = ['gsap.min.js', 'ScrollTrigger.min.js', 'SplitText.min.js'];
const FIRST_VIEW = 0.92; // of the first screen: what starts above this line is never hidden

const reducedQuery = matchMedia('(prefers-reduced-motion: reduce)');
const fineQuery = matchMedia('(hover: hover) and (pointer: fine)');

/** True when the element starts inside (or above) the screen: those are left alone. */
export const inFirstView = (el) => el.getBoundingClientRect().top < innerHeight * FIRST_VIEW;

let library = null;
let ctx = null;
let mm = null;
let cleanups = [];
let started = false;

/** The three vendored files, in order, once per visit (classic scripts: they set window.gsap…). */
function loadLibrary() {
  if (!library) {
    library = Promise.all(
      LIBRARIES.map(
        (name) =>
          new Promise((resolve, reject) => {
            const script = document.createElement('script');
            script.src = VENDOR + name;
            script.async = false; // fetched together, run in this order
            script.onload = resolve;
            script.onerror = () => reject(new Error(`motion: ${name} could not be loaded`));
            document.head.append(script);
          }),
      ),
    ).then(() => {
      const { gsap, ScrollTrigger, SplitText } = window;
      gsap.registerPlugin(ScrollTrigger, SplitText);
      ScrollTrigger.config({ ignoreMobileResize: true });
      return { gsap, ScrollTrigger, SplitText };
    });
  }
  return library;
}

/** After the load event, when the browser is idle: first paint and the page's own work come first. */
function whenIdle() {
  return new Promise((resolve) => {
    const idle = () =>
      'requestIdleCallback' in window ? requestIdleCallback(() => resolve(), { timeout: 1500 }) : setTimeout(resolve, 200);
    if (document.readyState === 'complete') idle();
    else addEventListener('load', idle, { once: true });
  });
}

/** The elements of a hook: the site's selectors for it (config.js). The markup never carries a
 *  motion hook (PUB-01 keeps <main> equal to ls-home), so the map is the only source. */
function query(hook) {
  const selectors = config.selectors[hook];
  return selectors?.length ? [...document.querySelectorAll(selectors.join(','))] : [];
}

/** A listener removed with the page. */
function on(el, type, fn, opts) {
  el.addEventListener(type, fn, opts);
  cleanups.push(() => el.removeEventListener(type, fn, opts));
}

/** main.js reveals [data-reveal] blocks with a CSS transition; the block an effect takes is
 *  marked shown (.is-in, main.js's own end state) so that only one move plays on it. */
function handOver(el) {
  el.closest('[data-reveal]')?.classList.add('is-in');
}

/** One effect in a context of its own: if it throws, whatever it had hidden is put back. */
function buildOne(effect, kit, gsap) {
  let error = null;
  const own = gsap.context(() => {
    try {
      effect.init(kit);
    } catch (err) {
      error = err;
    }
  });
  if (error) {
    own.revert();
    console.error(`motion: ${effect.hook} failed and was undone`, error);
  }
}

/** core-focus-reveal: a block still waiting to be revealed shows at once when focus lands inside. */
function focusReveal(gsap) {
  on(document.documentElement, 'focusin', (e) => {
    const pending = e.target instanceof Element && e.target.closest('[data-motion-pending]');
    if (!pending) return;
    pending.removeAttribute('data-motion-pending');
    gsap.to(pending, { opacity: 1, y: 0, scale: 1, duration: 0.3, overwrite: true, clearProps: 'opacity,transform,transition' });
  });
}

/** core-refresh: the page height changes after init (tabs, FAQ, the coverage map swapped in). */
function refreshOnResize(ScrollTrigger) {
  let timer = 0;
  let last = document.documentElement.scrollHeight;
  const observer = new ResizeObserver(() => {
    const h = document.documentElement.scrollHeight;
    if (Math.abs(h - last) < 2) return;
    last = h;
    clearTimeout(timer);
    timer = setTimeout(() => ScrollTrigger.refresh(), 200);
  });
  observer.observe(document.documentElement);
  cleanups.push(() => {
    observer.disconnect();
    clearTimeout(timer);
  });
}

/** Loops run only on screen: the page's CSS loops pause while their host is off screen. */
function loopsOnScreen() {
  const hosts = config.loops.flatMap((selector) => [...document.querySelectorAll(selector)]);
  if (!hosts.length) return;
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) entry.target.classList.toggle('is-offscreen', !entry.isIntersecting);
  });
  hosts.forEach((host) => observer.observe(host));
  cleanups.push(() => {
    observer.disconnect();
    hosts.forEach((host) => host.classList.remove('is-offscreen'));
  });
}

function build(effects, lib) {
  if (ctx || reducedQuery.matches) return;
  const { gsap, ScrollTrigger } = lib;
  document.documentElement.classList.add('has-motion');
  mm = gsap.matchMedia();
  const kit = {
    ...lib,
    media: mm,
    fine: fineQuery.matches,
    on,
    cleanup: (fn) => void cleanups.push(fn),
    query,
    inFirstView,
    handOver,
  };
  ctx = gsap.context(() => effects.forEach((effect) => buildOne(effect, kit, gsap)));
  focusReveal(gsap);
  refreshOnResize(ScrollTrigger);
  loopsOnScreen();
  // Triggers created out of document order refresh in page order; fonts change line breaks.
  ScrollTrigger.sort();
  document.fonts?.ready.then(() => ctx && ScrollTrigger.refresh());
}

function teardown() {
  cleanups.forEach((fn) => fn());
  cleanups = [];
  mm?.revert();
  mm = null;
  ctx?.revert();
  ctx = null;
  document.documentElement.classList.remove('has-motion');
  // ScrollTrigger's refresh writes the computed scroll-behavior back inline on <html>; under
  // reduced motion main.css must decide it again.
  document.documentElement.style.removeProperty('scroll-behavior');
}

/** Starts the page's motion once per visit with the site's effects (registry in index.js). */
export function start(effects) {
  if (started) return;
  started = true;
  const go = () =>
    whenIdle()
      .then(loadLibrary)
      .then((lib) => build(effects, lib))
      .catch((err) => console.error('motion: not started', err));
  reducedQuery.addEventListener('change', () => (reducedQuery.matches ? teardown() : go()));
  if (!reducedQuery.matches) go();
}
