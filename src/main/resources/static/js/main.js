/**
 * Logistica Sanchez — core UI (ES module, no dependencies).
 * Every component enhances markup that already works without JavaScript.
 * Page widgets are fetched on demand with dynamic import().
 */
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;

/* Sticky header shadow + back-to-top button */
function initScroll() {
  const header = $('[data-header]');
  const toTop = $('[data-to-top]');
  let ticking = false;
  const update = () => {
    const y = window.scrollY;
    header?.classList.toggle('is-scrolled', y > 8);
    if (toTop) toTop.hidden = y < 900;
    ticking = false;
  };
  addEventListener(
    'scroll',
    () => {
      if (!ticking) {
        ticking = true;
        requestAnimationFrame(update);
      }
    },
    { passive: true },
  );
  update();
  toTop?.addEventListener('click', () => scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' }));
}

/* Services mega menu: hover is pure CSS; the caret button toggles it for
   keyboard and touch users (disclosure pattern), Esc closes it. */
function initMega() {
  for (const item of $$('[data-mega]')) {
    const btn = $('.nav-caret', item);
    if (!btn) continue;
    const set = (open) => {
      item.classList.toggle('is-open', open);
      btn.setAttribute('aria-expanded', String(open));
    };
    btn.addEventListener('click', () => set(!item.classList.contains('is-open')));
    item.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape') return;
      if (item.classList.contains('is-open')) {
        set(false);
        btn.focus();
      }
      if (item.matches(':hover')) item.classList.add('is-closing');
    });
    item.addEventListener('mouseleave', () => item.classList.remove('is-closing'));
    item.addEventListener('focusout', (e) => {
      if (!item.contains(e.relatedTarget)) set(false);
    });
    document.addEventListener('click', (e) => {
      if (!item.contains(e.target)) set(false);
    });
  }
}

/* Mobile drawer: native modal <dialog> (focus trap, Esc, inert page for free). */
function initDrawer() {
  const dialog = $('#drawer');
  const openBtn = $('[data-drawer-open]');
  if (!dialog || !openBtn || typeof dialog.showModal !== 'function') return;
  openBtn.addEventListener('click', () => {
    if (!dialog.open) dialog.showModal();
    openBtn.setAttribute('aria-expanded', 'true');
  });
  $('[data-drawer-close]', dialog)?.addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => openBtn.setAttribute('aria-expanded', 'false'));
  dialog.addEventListener('click', (e) => {
    if (e.target === dialog) dialog.close();
  });
  for (const a of $$('a', dialog)) a.addEventListener('click', () => dialog.close());
  const group = $('[data-drawer-group]', dialog);
  if (group && $('[aria-current]', group)) group.open = true;
  matchMedia('(min-width: 1024px)').addEventListener('change', (e) => {
    if (e.matches && dialog.open) dialog.close();
  });
}

/* Accessible tabs (WAI-ARIA tabs pattern, automatic activation). */
export function initTabs(root) {
  const tabs = $$('[role="tab"]', root);
  const panels = tabs.map((t) => document.getElementById(t.getAttribute('aria-controls')));
  if (!tabs.length || panels.some((p) => !p)) return;
  const select = (i, focus = false) => {
    tabs.forEach((t, j) => {
      const on = i === j;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
      panels[j].hidden = !on;
    });
    if (focus) tabs[i].focus();
    root.dispatchEvent(new CustomEvent('tabchange', { detail: { index: i, tab: tabs[i], panel: panels[i] } }));
  };
  tabs.forEach((tab, i) => {
    tab.addEventListener('click', () => select(i));
    tab.addEventListener('keydown', (e) => {
      const last = tabs.length - 1;
      const next = { ArrowRight: i === last ? 0 : i + 1, ArrowLeft: i === 0 ? last : i - 1, Home: 0, End: last }[e.key];
      if (next === undefined) return;
      e.preventDefault();
      select(next, true);
    });
  });
  // Deep link: #id of a panel or ?formato=… preselects a tab.
  const wanted = tabs.findIndex((t) => location.hash && `#${t.getAttribute('aria-controls')}` === location.hash);
  const initial = wanted >= 0 ? wanted : Math.max(0, tabs.findIndex((t) => t.getAttribute('aria-selected') === 'true'));
  select(initial);
  root.setAttribute('data-ready', '');
}

/* Reveal-on-scroll. Elements already on screen are shown at once (no flash). */
function initReveal() {
  for (const group of $$('[data-stagger]')) {
    [...group.children].forEach((child, i) => {
      child.setAttribute('data-reveal', '');
      child.style.setProperty('--rd', `${Math.min(i, 7) * 0.07}s`);
    });
  }
  const els = $$('[data-reveal]');
  if (!els.length || reduceMotion || !('IntersectionObserver' in window)) return;
  const vh = innerHeight;
  for (const el of els) {
    const r = el.getBoundingClientRect();
    if (r.top < vh * 0.95 && r.bottom > 0) el.classList.add('is-in');
  }
  document.documentElement.classList.add('reveal-on');
  const io = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add('is-in');
        io.unobserve(entry.target);
      }
    },
    { rootMargin: '0px 0px -6% 0px', threshold: 0.06 },
  );
  for (const el of els) if (!el.classList.contains('is-in')) io.observe(el);
}

/* Hide the mobile action bar while typing (the keyboard needs the space). */
function initActionBar() {
  const bar = $('[data-action-bar]');
  if (!bar) return;
  const isField = (el) => el?.matches?.('input:not([type="checkbox"]):not([type="radio"]), textarea, select');
  document.addEventListener('focusin', (e) => isField(e.target) && bar.classList.add('is-hidden'));
  document.addEventListener('focusout', (e) => isField(e.target) && bar.classList.remove('is-hidden'));
}

/* Table of contents: highlight the section being read. */
function initToc() {
  const links = $$('.toc a[href^="#"]');
  if (!links.length || !('IntersectionObserver' in window)) return;
  const map = new Map(links.map((a) => [decodeURIComponent(a.hash.slice(1)), a]));
  const io = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        links.forEach((a) => a.classList.remove('is-active'));
        map.get(entry.target.id)?.classList.add('is-active');
      }
    },
    { rootMargin: '-20% 0px -70% 0px' },
  );
  for (const id of map.keys()) {
    const target = document.getElementById(id);
    if (target) io.observe(target);
  }
}

/* Share buttons: native share sheet when available, else copy the link. */
function initShare() {
  for (const btn of $$('[data-share]')) {
    btn.addEventListener('click', async () => {
      const data = { title: document.title, url: location.href };
      try {
        if (navigator.share) await navigator.share(data);
        else {
          await navigator.clipboard.writeText(location.href);
          const label = btn.querySelector('span') || btn;
          const prev = label.textContent;
          label.textContent = 'Link copiato';
          setTimeout(() => (label.textContent = prev), 2000);
        }
      } catch {
        /* share sheet dismissed */
      }
    });
  }
}

/* Revoke the "load Google Maps" consent (cookie policy page). */
function initConsentControls() {
  for (const btn of $$('[data-maps-revoke]')) {
    btn.addEventListener('click', () => {
      try {
        localStorage.removeItem('ls_maps_ok');
      } catch {
        /* storage unavailable */
      }
      btn.textContent = 'Consenso revocato';
      btn.disabled = true;
    });
  }
}

function initYear() {
  const y = String(new Date().getFullYear());
  for (const el of $$('[data-year]')) el.textContent = y;
}

/* Anchors of the previous site (logisticasanchez.com/#chisiamo…) now live on their own pages.
   Fragments never reach the server, so this cannot be a _redirects rule. */
function redirectLegacyAnchors() {
  const legacy = { '#chisiamo': '/chi-siamo/', '#contattaci': '/contatti/', '#tracking': '/tracking/' };
  const target = location.pathname === '/' && legacy[location.hash];
  if (target && !document.getElementById(location.hash.slice(1))) location.replace(target);
}

redirectLegacyAnchors();
initScroll();
initMega();
initDrawer();
for (const root of $$('[data-tabs]')) initTabs(root);
initReveal();
initActionBar();
initToc();
initShare();
initConsentControls();
initYear();

/* Page widgets, loaded only where they are used. */
const widgets = [
  ['[data-tracking]', () => import('./tracking.js')],
  ['[data-quote]', () => import('./quote.js')],
  ['[data-eta-form]', () => import('./eta.js')],
  ['[data-coverage]', () => import('./coverage.js')],
  ['[data-map]', () => import('./map.js')],
  ['[data-ajax-form]', () => import('./forms.js')],
];
for (const [selector, load] of widgets) {
  const nodes = $$(selector);
  if (!nodes.length) continue;
  load()
    .then((mod) => mod.default?.(nodes))
    .catch((err) => console.error(`[widget ${selector}]`, err));
}
