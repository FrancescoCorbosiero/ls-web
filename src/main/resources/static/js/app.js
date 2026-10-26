/**
 * Logistica Sanchez — private areas (ES module, no dependencies; ADR 0029).
 * The order form (CUS-04, CUS-07, ADM-11) works without JavaScript: «Aggiungi riga» and
 * «Elimina» are submit buttons the server answers. Here they act in place, the row and order
 * totals follow every keystroke with the server's rules (OrderTotals: weight = peso × quantità;
 * volume = round½↑(h × l × w / 1 000 000 × quantità, 2) per line), and choosing «Epal» fills
 * the standard 120 × 80 cm base when length and width are still empty. With the address book
 * (ENH-07, ADR 0033) choosing a saved recipient fills the recipient fields at once; without
 * JavaScript «Usa questo destinatario» asks the server to do it.
 */
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const LINE_NAME = /^line\.\d+\./;
const DASH = '–';

/** Hundredths as the server writes them (ItalianFormats): «1.236,00». */
const fmt = (hundredths) => {
  const whole = String(Math.floor(hundredths / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `${whole},${String(hundredths % 100).padStart(2, '0')}`;
};

/** A positive integer typed by the customer, or null. */
function int(value) {
  const v = String(value ?? '').trim();
  if (!/^[0-9]{1,9}$/.test(v)) return null;
  const n = Number(v);
  return n >= 1 ? n : null;
}

/** A weight with at most two decimals (comma or point) as hundredths, or null. */
function hundredths(value) {
  const v = String(value ?? '').trim().replace(',', '.');
  if (!/^[0-9]{1,7}(\.[0-9]{1,2})?$/.test(v)) return null;
  const [whole, frac = ''] = v.split('.');
  const h = Number(whole) * 100 + Number((frac + '00').slice(0, 2));
  return h > 0 ? h : null;
}

/** The totals of one row in hundredths, or null when the row is incomplete or invalid. */
function rowTotals(row) {
  const get = (field) => $(`[name$=".${field}"]`, row)?.value;
  const kind = get('kind');
  const [h, l, w, q] = ['heightCm', 'lengthCm', 'widthCm', 'quantity'].map((f) => int(get(f)));
  const weight = hundredths(get('weightKg'));
  if (!kind || !h || !l || !w || !q || weight === null) return null;
  const cubicCm = h * l * w * q;
  if (!Number.isSafeInteger(cubicCm) || !Number.isSafeInteger(weight * q)) return null;
  return { weight: weight * q, volume: Math.floor((cubicCm + 5000) / 10000), units: q };
}

function initOrderForm(form) {
  const list = $('[data-lines]', form);
  const template = $('template[data-line-template]', form);
  if (!list || !template) return;

  const rows = () => $$('[data-line-row]', list);

  const renumber = () => {
    rows().forEach((row, i) => {
      for (const el of $$('[name], [id], label[for]', row)) {
        if (el.name && LINE_NAME.test(el.name)) el.name = el.name.replace(LINE_NAME, `line.${i}.`);
        if (el.id && LINE_NAME.test(el.id)) el.id = el.id.replace(LINE_NAME, `line.${i}.`);
        const target = el.getAttribute('for');
        if (target && LINE_NAME.test(target)) el.setAttribute('for', target.replace(LINE_NAME, `line.${i}.`));
      }
      const remove = $('[data-remove-line]', row);
      if (remove) {
        remove.value = `removeLine.${i}`;
        if (remove.dataset.label) remove.setAttribute('aria-label', remove.dataset.label.replace('{n}', String(i + 1)));
      }
      const no = $('[data-line-no]', row);
      if (no) no.textContent = no.dataset.lineNo.replace('{n}', String(i + 1));
    });
    const add = $('[data-add-line]', form);
    if (add) add.disabled = rows().length >= Number(add.dataset.max || 50);
  };

  const update = () => {
    let weight = 0;
    let volume = 0;
    let units = 0;
    for (const row of rows()) {
      const t = rowTotals(row);
      const cellWeight = $('[data-line-weight]', row);
      const cellVolume = $('[data-line-volume]', row);
      if (cellWeight) cellWeight.textContent = t ? fmt(t.weight) : DASH;
      if (cellVolume) cellVolume.textContent = t ? fmt(t.volume) : DASH;
      if (!t) continue;
      weight += t.weight;
      volume += t.volume;
      units += t.units;
    }
    for (const el of $$('[data-total-weight]', form)) el.textContent = fmt(weight);
    for (const el of $$('[data-total-volume]', form)) el.textContent = fmt(volume);
    for (const el of $$('[data-total-units]', form)) el.textContent = String(units);
  };

  const addRow = () => {
    const fragment = template.content.cloneNode(true);
    const row = $('[data-line-row]', fragment);
    list.append(fragment);
    renumber();
    update();
    $('select', row)?.focus();
  };

  form.addEventListener('click', (e) => {
    const add = e.target.closest('[data-add-line]');
    if (add) {
      e.preventDefault();
      if (!add.disabled) addRow();
      return;
    }
    const remove = e.target.closest('[data-remove-line]');
    if (remove) {
      e.preventDefault();
      const row = remove.closest('[data-line-row]');
      const next = row?.nextElementSibling ?? row?.previousElementSibling;
      row?.remove();
      if (!rows().length) addRow();
      renumber();
      update();
      ($('select', next ?? list) ?? $('[data-add-line]', form))?.focus();
    }
  });

  form.addEventListener('input', (e) => {
    if (e.target.closest('[data-line-row]')) update();
  });

  form.addEventListener('change', (e) => {
    const select = e.target.closest('select[name$=".kind"]');
    if (!select) return;
    const preset = select.selectedOptions[0];
    const row = select.closest('[data-line-row]');
    for (const field of ['lengthCm', 'widthCm']) {
      const value = preset?.dataset[field];
      const input = $(`[name$=".${field}"]`, row);
      if (value && input && !input.value.trim()) input.value = value;
    }
    update();
  });

  renumber();
  update();
}

/** ENH-07: the recipient fields follow the saved recipient chosen in the address book. */
function initAddressBook(form) {
  const select = $('[data-address-book] select[name="addressId"]', form);
  if (!select) return;
  select.addEventListener('change', () => {
    const option = select.selectedOptions[0];
    if (!option?.value) return;
    for (const field of ['name', 'addressLine', 'city', 'province', 'postalCode', 'phone', 'email']) {
      const input = form.elements.namedItem(field);
      if (input instanceof HTMLInputElement) input.value = option.dataset[field] ?? '';
    }
  });
}

for (const form of $$('form[data-order-form]')) {
  initOrderForm(form);
  initAddressBook(form);
}
