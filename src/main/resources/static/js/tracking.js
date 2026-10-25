/**
 * Shipment tracking widget.
 *  - /tracking/?code=XXX runs a search on load (shareable links)
 *  - TRACKING.endpoint set   -> ask the backend (POST {codice}) and show the status
 *  - TRACKING.portalUrl set  -> redirect to the portal page
 *  - neither                 -> one-tap request of the status via WhatsApp / email / phone
 * Recent codes are remembered on this device only (localStorage).
 */
import { BUSINESS, TRACKING, waLink, mailLink, escapeHtml as h, icon } from './config.js';

/* Tracking states of the backend: enumeration "statoTracking" (GET /mono/api/enumeration/statoTracking),
   the same ids, names and labels the CMS uses to set them. trace/state answers one of them in "status".
   title: what the customer reads; step: label in the progress bar (NON_GESTITO comes before the bar). */
const STATES = [
  { id: 0, name: 'NON_GESTITO', label: 'Non gestito', title: 'In attesa di presa in carico', icon: 'clipboard-list' },
  { id: 1, name: 'PRESA', label: 'Presa in carico', title: 'Presa in carico', step: 'Presa in carico', icon: 'package-check' },
  { id: 2, name: 'TRANSITO', label: 'Transito', title: 'In transito', step: 'In transito', icon: 'truck' },
  { id: 3, name: 'TERMINAL_DEST', label: 'Terminal destinatario', title: 'Arrivata al terminal di destinazione', step: 'Terminal di destinazione', icon: 'warehouse' },
  { id: 4, name: 'ASSEGNATO', label: 'Assegnato', title: 'Affidata al mezzo per la consegna', step: 'In consegna', icon: 'navigation' },
  { id: 5, name: 'CONSEGNATO', label: 'Consegnato', title: 'Consegnata', step: 'Consegnata', icon: 'circle-check' },
];
const STEPS = STATES.filter((st) => st.step);
const keyOf = (v) =>
  String(v).normalize('NFD').replace(/[\u0300-\u036f]/g, '').trim().toUpperCase().replace(/[\s-]+/g, '_');
/** Finds a state by id (0–5), name ("TRANSITO") or backend label ("Terminal destinatario"). */
const findState = (value) => {
  if (value === null || value === undefined || value === '') return null;
  const k = keyOf(value);
  return STATES.find((st) => String(st.id) === k || st.name === k || keyOf(st.label) === k) ?? null;
};
// Codes are sent exactly as typed (minus spaces): the backend decides what is valid.
const CODE_RE = /^[^\s<>"'`]{1,64}$/;
const RECENT_KEY = 'ls_recent_codes';

const fmtDateTime = new Intl.DateTimeFormat('it-IT', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
const fmtDate = new Intl.DateTimeFormat('it-IT', { weekday: 'long', day: 'numeric', month: 'long' });
const asDate = (v) => {
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? null : d;
};

function readRecent() {
  try {
    return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]').filter((c) => CODE_RE.test(c)).slice(0, 4);
  } catch {
    return [];
  }
}
function saveRecent(code) {
  try {
    const list = [code, ...readRecent().filter((c) => c !== code)].slice(0, 4);
    localStorage.setItem(RECENT_KEY, JSON.stringify(list));
  } catch {
    /* storage unavailable */
  }
}

function demoShipment() {
  const now = Date.now();
  const ago = (hours) => new Date(now - hours * 3600e3).toISOString();
  const eta = new Date(now + 24 * 3600e3);
  if (eta.getDay() === 0) eta.setDate(eta.getDate() + 1);
  if (eta.getDay() === 6) eta.setDate(eta.getDate() + 2);
  return {
    code: TRACKING.demoCode,
    status: 'TRANSITO',
    origin: 'Seregno (MB)',
    destination: 'Bologna (BO)',
    pallets: 2,
    eta: eta.toISOString().slice(0, 10),
    updatedAt: ago(1),
    events: [
      { time: ago(1), status: 'TRANSITO', location: 'Hub di Seregno (MB)', note: 'Partita verso la destinazione' },
      { time: ago(5), status: 'PRESA', location: 'Monza (MB)', note: 'Ritirati 2 pallet EUR 120×80' },
      { time: ago(26), status: 'NON_GESTITO', location: 'Online', note: 'Spedizione registrata' },
    ],
  };
}

/** Backend answer -> shipment object. Accepts {status} alone and the richer README format. */
function normalize(code, data) {
  const obj = typeof data === 'string' || typeof data === 'number' ? { status: data } : data && typeof data === 'object' ? data : {};
  let value = obj.status;
  if (value && typeof value === 'object') value = value.name ?? value.label ?? value.id; // the whole enum entry
  const raw = String(value ?? '').trim();
  if (!raw) return null;
  const state = findState(raw);
  // A status we do not know yet is still shown, as the backend wrote it.
  const label = state ? null : raw === raw.toUpperCase() ? raw.charAt(0) + raw.slice(1).toLowerCase().replace(/_/g, ' ') : raw;
  return { ...obj, code, state, label };
}

function renderShipment(s, { demo = false } = {}) {
  const { state } = s;
  const title = state ? state.title : s.label;
  const ico = state ? state.icon : 'package-search';
  const delivered = state?.name === 'CONSEGNATO';
  const reached = state ? STEPS.indexOf(state) : -1;
  const progress = STEPS.map((st, i) => {
    const cls = i < reached ? 'is-done' : i === reached ? 'is-current' : '';
    return `<li class="${cls}"${i === reached ? ' aria-current="step"' : ''}>${h(st.step)}</li>`;
  }).join('');
  const eta = asDate(s.eta);
  const updated = asDate(s.updatedAt);
  const meta = [
    ['Partenza', s.origin],
    ['Destinazione', s.destination],
    ['Colli', s.pallets ? `${s.pallets} pallet` : null],
    [delivered ? 'Consegnata' : 'Consegna prevista', eta ? fmtDate.format(eta) : null],
  ]
    .filter(([, v]) => v)
    .map(([k, v]) => `<div><dt>${h(k)}</dt><dd>${h(v)}</dd></div>`)
    .join('');
  const events = (Array.isArray(s.events) ? s.events : [])
    .map((ev) => {
      const t = asDate(ev.time);
      const label = findState(ev.status)?.title ?? ev.status ?? '';
      return `<li><time${t ? ` datetime="${h(t.toISOString())}"` : ''}>${t ? h(fmtDateTime.format(t)) : ''}</time><strong>${h(ev.note || label)}</strong>${ev.location ? `<span>${h(ev.location)}</span>` : ''}</li>`;
    })
    .join('');
  const shareUrl = `${location.origin}/tracking/?code=${encodeURIComponent(s.code)}`;
  return `
    <div class="track-head">
      <div>
        <p class="track-code">Spedizione ${h(s.code)}${demo ? ' · <span class="badge">Esempio dimostrativo</span>' : ''}</p>
        <p class="track-state${delivered ? ' is-delivered' : ''}">${icon(ico)}${h(title)}</p>
      </div>
      ${updated ? `<p class="muted mb-0">Ultimo aggiornamento:<br><strong>${h(fmtDateTime.format(updated))}</strong></p>` : ''}
    </div>
    ${state ? `<ol class="progress" role="list" aria-label="Avanzamento della spedizione">${progress}</ol>` : ''}
    ${meta ? `<dl class="track-meta">${meta}</dl>` : ''}
    ${events ? `<h3 class="h4">Cronologia</h3><ol class="events" reversed>${events}</ol>` : ''}
    <div class="track-actions">
      ${s.pod ? `<a class="btn btn-primary btn-sm" href="${h(s.pod)}" target="_blank" rel="noopener">${icon('file-check')}Scarica la prova di consegna</a>` : ''}
      <button class="btn btn-secondary btn-sm" type="button" data-copy="${h(shareUrl)}">${icon('copy')}<span>Copia il link</span></button>
      <a class="btn btn-secondary btn-sm" href="${h(waLink(`Ciao ${BUSINESS.name}, ho una domanda sulla spedizione ${s.code}.`))}" target="_blank" rel="noopener">${icon('whatsapp', 'icon icon-wa')}Hai bisogno di aiuto?</a>
    </div>`;
}

function renderAsk(code, reason) {
  const intro =
    reason === 'notfound'
      ? `Non troviamo la spedizione <strong>${h(code)}</strong> nel sistema: controlla il codice sul documento di trasporto oppure chiedi direttamente a noi.`
      : reason === 'unhandled'
        ? `Non ci sono ancora aggiornamenti per la spedizione <strong>${h(code)}</strong>. Se l'hai prenotata da poco è normale; altrimenti controlla il codice sul documento di trasporto oppure chiedi direttamente a noi.`
      : reason === 'error'
        ? `Il servizio di tracciamento non risponde in questo momento. Puoi chiederci lo stato della spedizione <strong>${h(code)}</strong> con un tocco.`
        : `Invia il codice con un tocco: ti rispondiamo con lo <strong>stato aggiornato</strong> e la <strong>finestra di consegna prevista</strong>.`;
  const text = `Ciao ${BUSINESS.name}, vorrei lo stato della spedizione ${code}. Grazie!`;
  return `
    <div class="track-head">
      <div>
        <p class="track-code">Spedizione ${h(code)}</p>
        <p class="track-state">${icon('headset')}Ricevi lo stato aggiornato</p>
      </div>
    </div>
    <p class="mt-s">${intro}</p>
    <div class="track-actions">
      <a class="btn btn-wa" href="${h(waLink(text))}" target="_blank" rel="noopener">${icon('whatsapp')}Chiedi su WhatsApp</a>
      <a class="btn btn-secondary" href="${h(mailLink(`Stato spedizione ${code}`, text))}">${icon('mail')}Scrivi una email</a>
      <a class="btn btn-secondary" href="tel:${h(BUSINESS.phone)}">${icon('phone')}${h(BUSINESS.phoneDisplay)}</a>
    </div>
    <p class="note mt-s mb-0">${h(BUSINESS.hours)}. Fuori orario ti rispondiamo appena possibile.${
      TRACKING.demoCode
        ? ` Vuoi vedere come appare il tracciamento? Prova il codice <button class="linklike" type="button" data-try="${h(TRACKING.demoCode)}">${h(TRACKING.demoCode)}</button>.`
        : ''
    }</p>`;
}

async function fetchShipment(code) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TRACKING.timeoutMs);
  try {
    const res = await fetch(TRACKING.endpoint, {
      method: 'POST',
      signal: ctrl.signal,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ codice: code }),
    });
    // The backend answers an error status for unknown codes (it has no dedicated 404).
    if (!res.ok) return { notFound: true };
    return { data: normalize(code, await res.json()) };
  } finally {
    clearTimeout(timer);
  }
}

function setup(root) {
  const form = root.querySelector('form');
  const input = form?.querySelector('input[name="code"]');
  const out = root.querySelector('[data-track-result]');
  const err = root.querySelector('[data-track-error]');
  const recentEl = root.querySelector('[data-track-recent]');
  if (!form || !input || !out) return;
  form.noValidate = true;

  const renderRecent = () => {
    if (!recentEl) return;
    const list = readRecent();
    recentEl.innerHTML = list.length
      ? `<span>Ricerche recenti:</span>${list.map((c) => `<button type="button" data-try="${h(c)}">${h(c)}</button>`).join('')}`
      : '';
  };

  const run = async (raw, { updateUrl = true } = {}) => {
    const code = String(raw || '').replace(/\s+/g, '');
    input.value = code;
    if (!CODE_RE.test(code)) {
      if (err) err.textContent = code ? 'Il codice contiene caratteri non validi: controllalo sul documento di trasporto.' : 'Inserisci il codice della spedizione.';
      input.setAttribute('aria-invalid', 'true');
      input.focus();
      return;
    }
    if (err) err.textContent = '';
    input.setAttribute('aria-invalid', 'false');
    if (updateUrl) history.replaceState(null, '', `${location.pathname}?code=${encodeURIComponent(code)}`);

    if (TRACKING.demoCode && code.toUpperCase() === TRACKING.demoCode) {
      out.innerHTML = renderShipment(demoShipment(), { demo: true });
    } else if (TRACKING.endpoint) {
      out.innerHTML = `<p class="track-state">${icon('loader-circle', 'icon spin')}Ricerca in corso…</p>`;
      try {
        const { data, notFound } = await fetchShipment(code);
        // The backend answers "Non gestito" both for unknown codes and for shipments not yet
        // handled (ls@migration OrderController.trackingState): show a neutral message, not a state.
        const unhandled = data?.state?.name === 'NON_GESTITO';
        out.innerHTML = notFound || !data ? renderAsk(code, 'notfound') : unhandled ? renderAsk(code, 'unhandled') : renderShipment(data);
        if (data && !unhandled) saveRecent(code);
      } catch {
        out.innerHTML = renderAsk(code, 'error');
      }
    } else if (TRACKING.portalUrl) {
      saveRecent(code);
      location.href = TRACKING.portalUrl.replace('{code}', encodeURIComponent(code));
      return;
    } else {
      saveRecent(code);
      out.innerHTML = renderAsk(code);
    }
    renderRecent();
    out.focus({ preventScroll: true });
    out.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'nearest' });
  };

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    run(input.value);
  });
  root.addEventListener('click', async (e) => {
    const tryBtn = e.target.closest('[data-try]');
    if (tryBtn) {
      run(tryBtn.dataset.try);
      return;
    }
    const copyBtn = e.target.closest('[data-copy]');
    if (copyBtn) {
      try {
        await navigator.clipboard.writeText(copyBtn.dataset.copy);
        const span = copyBtn.querySelector('span');
        if (span) {
          span.textContent = 'Link copiato';
          setTimeout(() => (span.textContent = 'Copia il link'), 2000);
        }
      } catch {
        /* clipboard unavailable */
      }
    }
  });

  renderRecent();
  const initial = new URLSearchParams(location.search).get('code');
  if (initial) run(initial, { updateUrl: false });
}

export default function init(nodes) {
  nodes.forEach(setup);
}
