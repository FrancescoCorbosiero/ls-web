/**
 * Quote request wizard (4 steps) on top of a plain form that posts to /api/form.
 * Without JavaScript every step is visible and the form posts normally.
 * Prefill via URL: /preventivo/?servizio=groupage&da=20831&a=40121&pallet=2&tipo=eur
 */
import { lookup, describe } from './eta.js';
import { validate, liveValidation, submitForm, failureMessage } from './forms.js';
import { BUSINESS, waLink, escapeHtml as h, icon } from './config.js';

const LABELS = {
  servizio: { pallet: 'Spedizione pallet espresso', groupage: 'Groupage / collettame', completo: 'Carico completo', magazzino: 'Magazzino e logistica' },
  tipo_pallet: { eur: 'EPAL 120×80 cm', industriale: 'Industriale 120×100 cm', mezzo: 'Mezzo pallet 80×60 cm', quarto: 'Quarto di pallet 60×40 cm', altro: 'Altro / fuori misura' },
  destinatario: { azienda: 'Azienda', privato: 'Privato' },
  contatto: { email: 'Email', telefono: 'Telefono', whatsapp: 'WhatsApp' },
};
const reduce = () => matchMedia('(prefers-reduced-motion: reduce)').matches;

function values(form) {
  const fd = new FormData(form);
  const get = (k) => String(fd.get(k) ?? '').trim();
  const date = get('data_ritiro');
  const extras = fd.getAll('servizi_extra[]').map(String);
  return {
    servizio: LABELS.servizio[get('servizio')] || '',
    data: date ? new Date(`${date}T12:00:00`).toLocaleDateString('it-IT', { weekday: 'short', day: 'numeric', month: 'long' }) : 'Da concordare',
    ritiro: [get('ritiro_cap'), get('ritiro_localita')].filter(Boolean).join(' '),
    consegna: [get('consegna_cap'), get('consegna_localita')].filter(Boolean).join(' '),
    destinatario: LABELS.destinatario[get('destinatario')] || '',
    merce: [
      get('numero') && `${get('numero')} × ${LABELS.tipo_pallet[get('tipo_pallet')] || 'pallet'}`,
      get('peso') && `${get('peso')} kg totali`,
      get('altezza') && `h ${get('altezza')} cm`,
    ].filter(Boolean).join(', '),
    descrizione: get('descrizione_merce'),
    extra: extras.join(', '),
    nome: [get('nome'), get('azienda') && `(${get('azienda')})`].filter(Boolean).join(' '),
    recapiti: [get('email'), get('telefono')].filter(Boolean).join(' · '),
    contatto: LABELS.contatto[get('contatto_preferito')] || '',
    note: get('note'),
  };
}

function summaryRows(v) {
  return [
    ['Servizio', v.servizio],
    ['Ritiro', v.ritiro],
    ['Consegna', v.consegna && `${v.consegna}${v.destinatario ? ` (${v.destinatario.toLowerCase()})` : ''}`],
    ['Data di ritiro', v.data],
    ['Merce', v.merce],
    ['Tipo di merce', v.descrizione],
    ['Servizi extra', v.extra],
  ].filter(([, val]) => val);
}

function whatsappText(v) {
  const lines = [`Ciao ${BUSINESS.name}, vorrei un preventivo:`];
  for (const [k, val] of summaryRows(v)) lines.push(`• ${k}: ${val}`);
  if (v.note) lines.push(`• Note: ${v.note}`);
  if (v.nome) lines.push('', `${v.nome}${v.recapiti ? ` – ${v.recapiti}` : ''}`);
  return lines.join('\n');
}

function prefill(form) {
  const q = new URLSearchParams(location.search);
  const setRadio = (name, value) => {
    const el = value && form.querySelector(`input[name="${name}"][value="${CSS.escape(value)}"]`);
    if (el) el.checked = true;
  };
  setRadio('servizio', q.get('servizio'));
  setRadio('tipo_pallet', q.get('tipo'));
  const set = (name, value, re) => {
    const el = form.elements[name];
    if (el && value && (!re || re.test(value))) el.value = value;
  };
  set('ritiro_cap', q.get('da'), /^\d{5}$/);
  set('consegna_cap', q.get('a'), /^\d{5}$/);
  set('numero', q.get('pallet'), /^\d{1,2}$/);
}

function wireQty(form) {
  for (const box of form.querySelectorAll('[data-qty]')) {
    const input = box.querySelector('input');
    const step = (d) => {
      const min = Number(input.min || 1);
      const max = Number(input.max || 99);
      const v = Math.min(max, Math.max(min, (Number(input.value) || min) + d));
      input.value = String(v);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    };
    box.querySelector('[data-qty-dec]')?.addEventListener('click', () => step(-1));
    box.querySelector('[data-qty-inc]')?.addEventListener('click', () => step(1));
  }
}

function wireEta(form) {
  const input = form.elements.consegna_cap;
  const hint = form.querySelector('[data-eta-hint]');
  if (!input || !hint) return;
  const update = () => {
    const r = lookup(input.value);
    hint.innerHTML = r && r.valid ? describe(r) : '';
  };
  input.addEventListener('input', update);
  update();
}

function setup(form) {
  const steps = [...form.querySelectorAll('[data-step]')];
  const bar = [...form.querySelectorAll('.wizard-progress li')];
  const prev = form.querySelector('[data-prev]');
  const next = form.querySelector('[data-next]');
  const submit = form.querySelector('[data-submit]');
  const count = form.querySelector('[data-count]');
  const status = form.querySelector('[data-form-status]');
  const summary = form.querySelector('[data-summary]');
  const wa = form.querySelector('[data-wa]');
  if (!steps.length || !prev || !next || !submit) return;

  form.noValidate = true;
  liveValidation(form);
  prefill(form);
  wireQty(form);
  wireEta(form);
  const date = form.elements.data_ritiro;
  if (date) date.min = new Date().toISOString().slice(0, 10);

  let current = 0;
  const go = (i, focus = true) => {
    current = Math.max(0, Math.min(steps.length - 1, i));
    steps.forEach((s, j) => s.classList.toggle('is-active', j === current));
    bar.forEach((li, j) => {
      li.classList.toggle('is-done', j < current);
      li.classList.toggle('is-current', j === current);
      if (j === current) li.setAttribute('aria-current', 'step');
      else li.removeAttribute('aria-current');
    });
    prev.hidden = current === 0;
    next.hidden = current === steps.length - 1;
    submit.hidden = current !== steps.length - 1;
    if (count) count.textContent = `Passo ${current + 1} di ${steps.length}`;
    if (current === steps.length - 1) renderSummary();
    if (focus) {
      steps[current].querySelector('legend')?.focus({ preventScroll: true });
      if (form.getBoundingClientRect().top < 0) form.scrollIntoView({ behavior: reduce() ? 'auto' : 'smooth', block: 'start' });
    }
  };

  const renderSummary = () => {
    const v = values(form);
    if (summary) summary.innerHTML = summaryRows(v).map(([k, val]) => `<div><dt>${h(k)}</dt><dd>${h(val)}</dd></div>`).join('');
    if (wa) wa.href = waLink(whatsappText(v));
  };

  next.addEventListener('click', () => {
    const invalid = validate(steps[current]);
    if (invalid) {
      invalid.focus();
      return;
    }
    go(current + 1);
  });
  prev.addEventListener('click', () => go(current - 1));
  form.addEventListener('input', () => current === steps.length - 1 && renderSummary());
  wa?.addEventListener('click', renderSummary);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (current < steps.length - 1) {
      next.click(); // Enter inside a field moves forward instead of submitting
      return;
    }
    const invalid = validate(form);
    if (invalid) {
      go(steps.findIndex((s) => s.contains(invalid)), false);
      invalid.focus();
      return;
    }
    const label = submit.innerHTML;
    submit.disabled = true;
    submit.textContent = 'Invio in corso…';
    status.className = 'form-status';
    status.textContent = '';
    try {
      await submitForm(form);
      const v = values(form);
      form.innerHTML = `
        <div class="wizard-done" tabindex="-1">
          <div class="card-ico">${icon('circle-check')}</div>
          <h2>Richiesta inviata, grazie${v.nome ? ` ${h(v.nome.split(' ')[0])}` : ''}!</h2>
          <p class="lede">Abbiamo ricevuto la tua richiesta: ti ricontattiamo al più presto con prezzo e tempi di ritiro e consegna.</p>
          <div class="actions center mt-m">
            <a class="btn btn-wa" href="${h(waLink(whatsappText(v)))}" target="_blank" rel="noopener">${icon('whatsapp')}Hai fretta? Scrivici su WhatsApp</a>
            <a class="btn btn-secondary" href="/">Torna alla home</a>
          </div>
        </div>`;
      form.querySelector('.wizard-done').focus();
      form.scrollIntoView({ behavior: reduce() ? 'auto' : 'smooth', block: 'start' });
    } catch {
      status.className = 'form-status is-error';
      status.innerHTML = failureMessage();
      submit.disabled = false;
      submit.innerHTML = label;
    }
  });

  form.setAttribute('data-ready', '');
  go(0, false);
}

export default function init(forms) {
  forms.forEach(setup);
}
