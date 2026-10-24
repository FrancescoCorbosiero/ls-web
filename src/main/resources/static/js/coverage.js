/**
 * Interactive coverage map. The page ships a plain <img> of the SVG (works
 * without JS); here it is swapped for the inline SVG to add region tooltips,
 * legend highlighting and the link with the CAP estimator.
 */
import { escapeHtml } from './config.js';

export default function init(figures) {
  for (const fig of figures) enhance(fig);
}

async function enhance(fig) {
  const img = fig.querySelector('img');
  if (!img) return;
  let svg;
  try {
    const res = await fetch(img.currentSrc || img.src);
    if (!res.ok) return;
    const tpl = document.createElement('template');
    tpl.innerHTML = (await res.text()).trim();
    svg = tpl.content.querySelector('svg');
  } catch {
    return; // keep the static image
  }
  if (!svg) return;
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', img.alt);
  svg.removeAttribute('width');
  svg.removeAttribute('height');
  for (const t of svg.querySelectorAll('path > title')) t.remove();
  svg.querySelector('style')?.remove(); // styles live in main.css (a strict CSP blocks inline <style>)
  img.replaceWith(svg);

  const tip = document.createElement('div');
  tip.className = 'map-tip';
  tip.hidden = true;
  fig.append(tip);
  const regions = [...svg.querySelectorAll('.itm-r')];
  const legend = fig.closest('section')?.querySelectorAll('.zones-legend [data-zone]') ?? [];

  const showTip = (region, x, y) => {
    tip.innerHTML = `${escapeHtml(region.dataset.name)}<span>Consegna in ${escapeHtml(region.dataset.eta)}</span>`;
    tip.style.left = `${x}px`;
    tip.style.top = `${y}px`;
    tip.hidden = false;
  };
  const tipAtRegion = (region) => {
    const box = region.getBoundingClientRect();
    const host = fig.getBoundingClientRect();
    showTip(region, box.left - host.left + box.width / 2, box.top - host.top + box.height / 2);
  };
  const highlight = (match) => {
    let any = false;
    for (const r of regions) {
      const on = Boolean(match && match(r));
      r.classList.toggle('is-hl', on);
      any ||= on;
    }
    fig.classList.toggle('is-filter', any);
  };

  svg.addEventListener('pointermove', (e) => {
    const region = e.target.closest?.('.itm-r');
    if (!region) {
      tip.hidden = true;
      return;
    }
    const host = fig.getBoundingClientRect();
    showTip(region, e.clientX - host.left, e.clientY - host.top);
  });
  svg.addEventListener('pointerleave', () => (tip.hidden = true));
  svg.addEventListener('click', (e) => {
    const region = e.target.closest?.('.itm-r');
    if (region) tipAtRegion(region);
  });

  for (const item of legend) {
    const zone = item.dataset.zone;
    item.addEventListener('pointerenter', () => {
      item.classList.add('is-active');
      highlight((r) => r.dataset.zone === zone);
    });
    item.addEventListener('pointerleave', () => {
      item.classList.remove('is-active');
      highlight(null);
    });
  }

  document.addEventListener('eta:region', (e) => {
    const { region: name } = e.detail;
    const region = regions.find((r) => r.dataset.name === name);
    if (!region) return;
    highlight((r) => r === region);
    tipAtRegion(region);
    clearTimeout(fig._etaTimer);
    fig._etaTimer = setTimeout(() => {
      highlight(null);
      tip.hidden = true;
    }, 4000);
  });
}
