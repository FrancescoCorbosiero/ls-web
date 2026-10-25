/**
 * Delivery-time estimator: Italian CAP -> region -> delivery zone.
 * Zones and hours are indicative and live here only (plus the legend/map
 * in the HTML): change them in one place if the service changes.
 */
import { escapeHtml } from './config.js';

export const ZONES = {
  1: { label: '24 ore', note: 'Nord Italia' },
  2: { label: '48 ore', note: 'Centro e parte del Sud' },
  3: { label: '72 ore', note: 'Sud e isole maggiori' },
};

export const REGION_ZONE = {
  Lombardia: 1, Piemonte: 1, "Valle d'Aosta": 1, Liguria: 1, Veneto: 1,
  'Trentino-Alto Adige': 1, 'Friuli-Venezia Giulia': 1, 'Emilia-Romagna': 1,
  Toscana: 2, Umbria: 2, Marche: 2, Lazio: 2, Abruzzo: 2, Molise: 2, Campania: 2, Puglia: 2,
  Basilicata: 3, Calabria: 3, Sicilia: 3, Sardegna: 3,
};

// First two digits of the CAP identify the province area.
const PREFIX_REGION = {
  '00': 'Lazio', '01': 'Lazio', '02': 'Lazio', '03': 'Lazio', '04': 'Lazio',
  '05': 'Umbria', '06': 'Umbria',
  '07': 'Sardegna', '08': 'Sardegna', '09': 'Sardegna',
  '10': 'Piemonte', '11': "Valle d'Aosta", '12': 'Piemonte', '13': 'Piemonte', '14': 'Piemonte', '15': 'Piemonte',
  '16': 'Liguria', '17': 'Liguria', '18': 'Liguria', '19': 'Liguria',
  '20': 'Lombardia', '21': 'Lombardia', '22': 'Lombardia', '23': 'Lombardia', '24': 'Lombardia',
  '25': 'Lombardia', '26': 'Lombardia', '27': 'Lombardia', '28': 'Piemonte', '29': 'Emilia-Romagna',
  '30': 'Veneto', '31': 'Veneto', '32': 'Veneto', '33': 'Friuli-Venezia Giulia', '34': 'Friuli-Venezia Giulia',
  '35': 'Veneto', '36': 'Veneto', '37': 'Veneto', '38': 'Trentino-Alto Adige', '39': 'Trentino-Alto Adige',
  '40': 'Emilia-Romagna', '41': 'Emilia-Romagna', '42': 'Emilia-Romagna', '43': 'Emilia-Romagna', '44': 'Emilia-Romagna',
  '45': 'Veneto', '46': 'Lombardia', '47': 'Emilia-Romagna', '48': 'Emilia-Romagna',
  '50': 'Toscana', '51': 'Toscana', '52': 'Toscana', '53': 'Toscana', '54': 'Toscana',
  '55': 'Toscana', '56': 'Toscana', '57': 'Toscana', '58': 'Toscana', '59': 'Toscana',
  '60': 'Marche', '61': 'Marche', '62': 'Marche', '63': 'Marche',
  '64': 'Abruzzo', '65': 'Abruzzo', '66': 'Abruzzo', '67': 'Abruzzo',
  '70': 'Puglia', '71': 'Puglia', '72': 'Puglia', '73': 'Puglia', '74': 'Puglia', '75': 'Basilicata', '76': 'Puglia',
  '80': 'Campania', '81': 'Campania', '82': 'Campania', '83': 'Campania', '84': 'Campania',
  '85': 'Basilicata', '86': 'Molise', '87': 'Calabria', '88': 'Calabria', '89': 'Calabria',
  '90': 'Sicilia', '91': 'Sicilia', '92': 'Sicilia', '93': 'Sicilia', '94': 'Sicilia',
  '95': 'Sicilia', '96': 'Sicilia', '97': 'Sicilia', '98': 'Sicilia',
};

// Places reached by ferry or boat: times and costs are agreed case by case.
const MINOR_ISLANDS = new Set([
  '80071', '80073', '80074', '80075', '80076', '80077', '80079', // Capri, Ischia, Procida
  '57030', '57031', '57032', '57033', '57034', '57036', '57037', '57038', '57039', // Elba, Capraia
  '58012', '91017', '91023', '98055', '07024', '09014', '04027', // Giglio, Pantelleria, Favignana, Lipari, La Maddalena, Carloforte, Ponza
]);
const isLagoon = (cap) => /^301(2[1-6]|3[1-5]|4[12])$/.test(cap); // Venezia centro storico, Lido, Murano, Burano

export function lookup(value) {
  const cap = String(value ?? '').replace(/\D/g, '');
  if (cap.length !== 5) return null;
  const region = PREFIX_REGION[cap.slice(0, 2)];
  if (!region) return { cap, valid: false };
  const zone = REGION_ZONE[region];
  const special = MINOR_ISLANDS.has(cap) ? 'isola minore' : isLagoon(cap) ? 'laguna di Venezia' : null;
  return { cap, valid: true, region, zone, label: ZONES[zone].label, special };
}

export function describe(result) {
  if (!result) return '';
  if (!result.valid) return `<span class="eta-pill" data-zone="0">CAP non trovato</span> Controlla le cinque cifre del codice postale.`;
  if (result.special) {
    return `<span class="eta-pill" data-zone="0">Su richiesta</span> ${escapeHtml(result.region)}, ${escapeHtml(result.special)}: tempi e costi li concordiamo insieme.`;
  }
  return `<span class="eta-pill" data-zone="${result.zone}">${escapeHtml(result.label)}</span> ${escapeHtml(result.region)} · consegna indicativa dal ritiro, in giorni lavorativi.`;
}

export default function init(forms) {
  for (const form of forms) {
    const input = form.querySelector('input[name="cap"]');
    const out = form.querySelector('[data-eta-result]');
    if (!input || !out) continue;
    const update = (announce) => {
      const value = input.value.replace(/\D/g, '').slice(0, 5);
      if (value !== input.value) input.value = value;
      const result = lookup(value);
      out.innerHTML = result ? describe(result) : announce ? 'Inserisci un CAP di 5 cifre, ad esempio 40121.' : '';
      input.setAttribute('aria-invalid', String(Boolean(result && !result.valid)));
      if (result?.valid) {
        document.dispatchEvent(new CustomEvent('eta:region', { detail: result }));
      }
    };
    input.addEventListener('input', () => update(false));
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      update(true);
    });
    const preset = new URLSearchParams(location.search).get('cap');
    if (preset && !input.value) {
      input.value = preset;
      update(true);
    }
  }
}
