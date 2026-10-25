/**
 * Google Maps facade: nothing is requested from Google (and no cookies are
 * set) until the visitor asks for the interactive map. The choice can be
 * remembered locally and revoked from the cookie policy page.
 */
const KEY = 'ls_maps_ok';

export default function init(nodes) {
  let remembered = false;
  try {
    remembered = localStorage.getItem(KEY) === '1';
  } catch {
    /* storage unavailable */
  }
  for (const el of nodes) {
    const load = () => {
      if (el.querySelector('iframe')) return;
      const query = el.dataset.map;
      const iframe = document.createElement('iframe');
      iframe.src = `https://www.google.com/maps?q=${encodeURIComponent(query)}&z=15&hl=it&output=embed`;
      iframe.title = `Mappa: ${query}`;
      iframe.loading = 'lazy';
      iframe.referrerPolicy = 'no-referrer-when-downgrade';
      iframe.allowFullscreen = true;
      el.append(iframe);
      el.classList.add('is-loaded');
    };
    el.querySelector('[data-map-load]')?.addEventListener('click', () => {
      if (el.querySelector('[data-map-remember]')?.checked) {
        try {
          localStorage.setItem(KEY, '1');
        } catch {
          /* storage unavailable */
        }
      }
      load();
    });
    if (remembered) load();
  }
}
