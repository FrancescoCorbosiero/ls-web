/**
 * Forms: Italian validation messages + submission via fetch to /api/form, the Netlify
 * Function that emails the request with Amazon SES (netlify/functions/form.mjs).
 * Without JavaScript the forms post natively to the same URL and land on /grazie/.
 */
import { BUSINESS, escapeHtml } from './config.js';

export function messageFor(el) {
  const v = el.validity;
  if (v.valid) return '';
  if (v.valueMissing) {
    if (el.type === 'checkbox') return 'Spunta la casella per continuare.';
    if (el.type === 'radio') return 'Seleziona una delle opzioni.';
    return 'Questo campo è obbligatorio.';
  }
  if (v.typeMismatch && el.type === 'email') return 'Inserisci un indirizzo email valido, ad esempio nome@azienda.it.';
  if (v.patternMismatch) return el.dataset.msg || 'Controlla il formato inserito.';
  if (v.rangeUnderflow) return `Il valore minimo è ${el.min}.`;
  if (v.rangeOverflow) return `Il valore massimo è ${el.max}.`;
  if (v.tooShort) return `Inserisci almeno ${el.minLength} caratteri.`;
  if (v.badInput) return 'Valore non valido.';
  return 'Controlla questo campo.';
}

function errorEl(el) {
  if (el.type === 'radio') return el.closest('[data-group]')?.querySelector('.field-error') ?? null;
  return el.id ? document.getElementById(`${el.id}-err`) : null;
}

function show(el, msg) {
  const out = errorEl(el);
  if (out) out.textContent = msg;
  const targets = el.type === 'radio' ? el.form?.querySelectorAll(`input[name="${el.name}"]`) ?? [el] : [el];
  for (const t of targets) t.setAttribute('aria-invalid', msg ? 'true' : 'false');
}

/** Validates every field inside `container`; returns the first invalid field or null. */
export function validate(container) {
  let first = null;
  const radios = new Set();
  for (const el of container.querySelectorAll('input, select, textarea')) {
    if (el.type === 'hidden' || el.disabled || el.closest('.hp-field')) continue;
    if (el.type === 'radio') {
      if (radios.has(el.name)) continue;
      radios.add(el.name);
    }
    const msg = messageFor(el);
    show(el, msg);
    if (msg && !first) first = el;
  }
  return first;
}

export function liveValidation(form) {
  const recheck = (e) => {
    const el = e.target;
    if (!el.matches?.('input, select, textarea')) return;
    const group = el.type === 'radio' ? form.querySelector(`input[name="${el.name}"][aria-invalid="true"]`) : null;
    if (el.getAttribute('aria-invalid') === 'true' || group) show(el, messageFor(el));
  };
  form.addEventListener('input', recheck);
  form.addEventListener('change', recheck);
  form.addEventListener('focusout', (e) => {
    const el = e.target;
    if (el.matches?.('input:not([type="radio"]):not([type="checkbox"]), textarea') && el.value) show(el, messageFor(el));
  });
}

/** POSTs the form to its action (/api/form), url-encoded like a native submission. */
export async function submitForm(form) {
  const res = await fetch(form.action, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: new URLSearchParams(new FormData(form)).toString(),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.ok) throw new Error(data.error || `HTTP ${res.status}`);
}

export const failureMessage = () =>
  `Invio non riuscito. Riprova tra poco oppure scrivici a <a href="mailto:${BUSINESS.email}">${escapeHtml(BUSINESS.email)}</a> o su WhatsApp al ${escapeHtml(BUSINESS.phoneDisplay)}.`;

export default function init(forms) {
  for (const form of forms) {
    form.noValidate = true;
    liveValidation(form);
    const status = form.querySelector('[data-form-status]');
    const button = form.querySelector('[type="submit"]');
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const invalid = validate(form);
      if (invalid) {
        invalid.focus();
        return;
      }
      const label = button?.innerHTML;
      if (button) {
        button.disabled = true;
        button.textContent = 'Invio in corso…';
      }
      status.className = 'form-status';
      status.textContent = '';
      try {
        await submitForm(form);
        form.reset();
        status.className = 'form-status is-ok';
        status.textContent = form.dataset.success || 'Grazie! Messaggio ricevuto: ti rispondiamo al più presto.';
      } catch {
        status.className = 'form-status is-error';
        status.innerHTML = failureMessage();
      } finally {
        if (button) {
          button.disabled = false;
          button.innerHTML = label;
        }
        status.focus?.();
      }
    });
  }
}
