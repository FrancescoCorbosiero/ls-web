import { readFileSync } from 'node:fs';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { linkIn, waitForMail } from '../support/mail';
import { PageProblems, expectNoHorizontalScroll, offline } from '../support/browser';

/**
 * M2 end to end (ADR 0025, 0033, 0034, 0035, 0036, 0037, 0038): the seeded ADMIN turns each feature on from «Impostazioni»,
 * the seeded ACTIVE customer and the staff use it, and every flag is turned off again at the end
 * so that the specs after this one see M1. Serial; the accounts are dev-seed's (run.sh).
 */
test.describe.configure({ mode: 'serial' });

const stamp = Date.now().toString(36);
const seedCustomer = {
  email: 'cliente.attivo@cliente.example',
  label: 'Ditta Attiva S.r.l. – Seregno (cliente.attivo@cliente.example)',
};
const staff = {
  username: process.env.E2E_ADMIN_USER ?? 'admin.seed',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
};
const saved = {
  name: `Cantiere Rubrica ${stamp}`,
  addressLine: 'Via dei Mille 4',
  city: 'Lissone',
  province: 'MB',
  postalCode: '20851',
};
const flagsTurnedOn: string[] = [];

let customerContext: BrowserContext;
let staffContext: BrowserContext;
let customerPage: Page;
let staffPage: Page;
let customerProblems: PageProblems;
let staffProblems: PageProblems;
let bookOrderCode = '';

async function newPage(browser: Browser, baseURL: string): Promise<[BrowserContext, Page, PageProblems]> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await offline(page, baseURL);
  const problems = await PageProblems.watch(page);
  return [context, page, problems];
}

/** Turns a flag on or off from «Impostazioni» (ADMIN), as the owner does. */
async function setFlag(key: string, on: boolean): Promise<void> {
  await staffPage.goto('/gestione/impostazioni/');
  const row = staffPage.locator(`tr[data-flag="${key}"]`);
  const toggle = row.getByRole('button', { name: on ? 'Attiva' : 'Spegni', exact: true });
  if (await toggle.count()) {
    await toggle.click();
    await expect(staffPage.locator('[data-flash]')).toContainText(on ? 'attivata' : 'disattivata');
  }
  await expect(staffPage.locator(`tr[data-flag="${key}"]`).getByRole('button')).toHaveText(on ? 'Spegni' : 'Attiva');
  if (on && !flagsTurnedOn.includes(key)) flagsTurnedOn.push(key);
}

/** NFR-01: no serious or critical axe finding (ADR 0029). */
async function expectAccessible(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'best-practice']).analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
  expect(blocking, `axe on ${page.url()}`).toEqual([]);
}

test.beforeAll(async ({ browser, baseURL }) => {
  expect(staff.password, 'E2E_ADMIN_PASSWORD must hold the dev-seed password of admin.seed (run.sh sets it)').not.toBe('');
  [customerContext, customerPage, customerProblems] = await newPage(browser, baseURL!);
  [staffContext, staffPage, staffProblems] = await newPage(browser, baseURL!);
  await staffPage.goto('/gestione/login');
  await staffPage.getByLabel('Username').fill(staff.username);
  await staffPage.getByLabel('Password').fill(staff.password);
  await staffPage.getByRole('button', { name: 'Accedi' }).click();
  await expect(staffPage).toHaveURL(/\/gestione\/$/);

  await customerPage.goto('/area-clienti/');
  await customerPage.locator('#email').fill(seedCustomer.email);
  await customerPage.getByRole('button', { name: 'Ricevi il link' }).click();
  const mail = await waitForMail({ to: seedCustomer.email, subject: /link per accedere/ });
  await customerPage.goto(linkIn(mail, '/area-clienti/accesso'));
  await customerPage.getByRole('button', { name: 'Accedi' }).click();
  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/$/);
});

test.afterAll(async () => {
  // every flag back off: the specs after this one see M1
  for (const key of flagsTurnedOn) await setFlag(key, false);
  await customerContext?.close();
  await staffContext?.close();
});

test.afterEach(() => {
  customerProblems?.expectNone();
  staffProblems?.expectNone();
});

test('[ENH-07] the admin finds the address book and «Ordina di nuovo» available and turns them on', async () => {
  await staffPage.goto('/gestione/impostazioni/');
  const rows = staffPage.locator('tr[data-flag]');
  for (const key of ['customers.addressBook', 'orders.repeat']) {
    const row = staffPage.locator(`tr[data-flag="${key}"]`);
    await expect(row).toContainText('Disponibile');
    await expect(row.getByRole('button')).toBeEnabled();
  }
  // built flags first; one not built yet keeps its toggle disabled
  await expect(rows.first()).toContainText('Disponibile');
  await expect(staffPage.locator('tr[data-flag="pricing.lists"]')).toContainText('Non ancora sviluppata');
  await expect(staffPage.locator('tr[data-flag="pricing.lists"]').getByRole('button')).toBeDisabled();

  await setFlag('customers.addressBook', true);
  await setFlag('orders.repeat', true);
});

test('[ENH-07] the customer keeps a recipient in the address book of the profile', async () => {
  await customerPage.goto('/area-clienti/profilo/');
  await expect(customerPage.getByRole('heading', { name: 'Rubrica dei destinatari' })).toBeVisible();
  await customerPage.getByRole('link', { name: 'Aggiungi destinatario' }).click();
  await expect(customerPage.getByRole('heading', { name: 'Nuovo destinatario' })).toBeVisible();
  await customerPage.locator('#name').fill(saved.name);
  await customerPage.locator('#addressLine').fill(saved.addressLine);
  await customerPage.locator('#city').fill(saved.city);
  await customerPage.locator('#province').fill(saved.province);
  await customerPage.locator('#postalCode').fill(saved.postalCode);
  await customerPage.getByRole('button', { name: 'Salva il destinatario' }).click();

  await expect(customerPage).toHaveURL(/\/area-clienti\/profilo\/#rubrica$/);
  await expect(customerPage.locator('[data-flash]')).toContainText('salvato nella rubrica');
  await expect(customerPage.locator('[data-table="address-book"]')).toContainText(saved.name);
});

test('[ENH-07] the order form fills the recipient from the address book', async () => {
  await customerPage.goto('/area-clienti/ordini/nuovo/');
  await customerPage.locator('#addressId').selectOption({ label: `${saved.name} – ${saved.city} (${saved.province})` });
  // with JavaScript the fields follow the choice at once
  await expect(customerPage.locator('#name')).toHaveValue(saved.name);
  await expect(customerPage.locator('#postalCode')).toHaveValue(saved.postalCode);
  const line = (field: string) => customerPage.locator(`[name="line.0.${field}"]`);
  await line('kind').selectOption('EPAL');
  await line('heightCm').fill('160');
  await line('weightKg').fill('380');
  await line('quantity').fill('1');
  await customerPage.locator('#transportDocumentCode').fill(`DDT-RUBRICA-${stamp}`);
  await customerPage.getByRole('button', { name: 'Continua al riepilogo' }).click();
  await expect(customerPage.getByRole('heading', { name: 'Controlla i dati inseriti' })).toBeVisible();
  await expect(customerPage.locator('[data-recipient]')).toContainText(saved.name);
  await customerPage.getByRole('button', { name: "Conferma l'ordine" }).click();
  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/nuovo\/inviato\/$/);
  bookOrderCode = (await customerPage.locator('main').innerText()).match(/TEMP-[A-Za-z0-9]+/)?.[0] ?? '';
  expect(bookOrderCode).toMatch(/^TEMP-/);
});

test('[ENH-07] «Ordina di nuovo» opens the form filled from an order, with a new transport document to type', async () => {
  await customerPage.goto(`/area-clienti/ordini/${bookOrderCode}/`);
  await customerPage.getByRole('button', { name: 'Ordina di nuovo' }).click();
  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/nuovo\/$/);
  await expect(customerPage.locator('[data-flash]')).toContainText(bookOrderCode);
  await expect(customerPage.locator('[name="line.0.heightCm"]')).toHaveValue('160');
  await expect(customerPage.locator('#name')).toHaveValue(saved.name);
  await expect(customerPage.locator('#transportDocumentCode')).toHaveValue('');
  await customerPage.locator('#transportDocumentCode').fill(`DDT-DI-NUOVO-${stamp}`);
  await customerPage.getByRole('button', { name: 'Continua al riepilogo' }).click();
  await customerPage.getByRole('button', { name: "Conferma l'ordine" }).click();
  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/nuovo\/inviato\/$/);
  const second = (await customerPage.locator('main').innerText()).match(/TEMP-[A-Za-z0-9]+/)?.[0] ?? '';
  expect(second).toMatch(/^TEMP-/);
  expect(second).not.toBe(bookOrderCode);
});

test("[ENH-07] the staff use the customer's address book for an order by phone", async () => {
  await staffPage.goto('/gestione/ordini/nuovo');
  await staffPage.locator('#cliente').selectOption({ label: seedCustomer.label });
  await staffPage.getByRole('button', { name: 'Mostra la rubrica del cliente' }).click();
  // the page comes back with the customer's book; its script runs before «load»
  await expect(staffPage.locator('#addressId')).toBeVisible();
  await staffPage.waitForLoadState('load');
  await staffPage.locator('#addressId').selectOption({ label: `${saved.name} – ${saved.city} (${saved.province})` });
  await expect(staffPage.locator('#name')).toHaveValue(saved.name);
  await expect(staffPage.locator('#addressLine')).toHaveValue(saved.addressLine);
  await expect(staffPage.getByLabel('Salva il destinatario nella rubrica del cliente')).toBeVisible();
});

test('[ENH-05] the admin turns on the tracking history and the e-mails at every change', async () => {
  await staffPage.goto('/gestione/impostazioni/');
  for (const key of ['tracking.history', 'tracking.notifyCustomer']) {
    await expect(staffPage.locator(`tr[data-flag="${key}"]`)).toContainText('Disponibile');
    await setFlag(key, true);
  }
});

test('[ENH-05] a tracking change e-mails the customer and shows in the order timeline', async () => {
  await staffPage.goto('/gestione/ordini/?codice=TEST-0002');
  const row = staffPage.locator('tr', { hasText: 'TEST-0002' });
  await row.getByRole('link', { name: 'Dettaglio' }).click();
  await staffPage.locator('#stato').selectOption('2');
  await staffPage.getByRole('button', { name: 'Imposta stato' }).click();
  await expect(staffPage.locator('#tracking')).toContainText('Transito');

  const mail = await waitForMail({ to: seedCustomer.email, subject: /Aggiornamento spedizione N\. TEST-0002: In transito/ });
  expect(mail.body).toContain('Consegna prevista (indicativa)');

  await customerPage.goto('/area-clienti/ordini/TEST-0002/');
  const timeline = customerPage.locator('[data-tracking-timeline]');
  await expect(timeline).toContainText('Presa in carico');
  await expect(timeline).toContainText('In transito');
  await expect(customerPage.locator('[data-delivery-day]')).toContainText('Consegna prevista');
});

test('[ENH-05] public tracking shows the states with their dates and the delivery day, nothing personal', async ({ request }) => {
  const api = await request.post('/mono/api/order/trace/state', { data: { codice: 'TEST-0002' } });
  const answer = await api.json();
  expect(Object.keys(answer).sort()).toEqual(['eta', 'events', 'status', 'updatedAt']);
  expect(answer.status).toBe('Transito');
  expect(answer.events[0]).toMatchObject({ status: 'Transito' });
  expect(JSON.stringify(answer)).not.toContain('Destinatario Due');

  await customerPage.goto('/tracking/?code=TEST-0002');
  const result = customerPage.locator('[data-track-result]');
  await expect(result).toContainText('In transito');
  await expect(result).toContainText('Consegna prevista');
  await expect(result.locator('ol.events li')).toHaveCount(2);
});

test('[ENH-15] the admin turns on the office tools', async () => {
  await staffPage.goto('/gestione/impostazioni/');
  for (const key of ['orders.bulkTracking', 'admin.dashboard', 'admin.exports']) {
    await expect(staffPage.locator(`tr[data-flag="${key}"]`)).toContainText('Disponibile');
    await setFlag(key, true);
  }
});

test('[ENH-15] one tracking state on a pasted list of codes, checked before it is set', async () => {
  const unknown = `NON-ESISTE-${stamp}`;
  await staffPage.goto('/gestione/tracking/');
  const bulk = staffPage.locator('[data-bulk]');
  await bulk.locator('#codici').fill(`test-0002\n${unknown}, TEST-0002`);
  await bulk.locator('#bulk-stato').selectOption('4');
  await bulk.getByRole('button', { name: "Controlla l'elenco" }).click();

  const preview = staffPage.locator('[data-bulk-preview]');
  await expect(preview).toContainText('Spedizioni trovate: 1');
  await expect(preview.locator('[data-bulk-not-found]')).toContainText(unknown);
  await expect(preview.locator('tr[data-order="TEST-0002"]')).toContainText('Transito');
  await expectAccessible(staffPage);
  await preview.getByRole('button', { name: 'Imposta «Assegnato» su 1 spedizione' }).click();

  await expect(staffPage).toHaveURL(/\/gestione\/tracking\/$/);
  await expect(staffPage.locator('[data-flash]')).toContainText('Tracking «Assegnato» impostato su 1 spedizione.');
  await expect(staffPage.locator('[data-flash]')).toContainText(unknown);
  // the rules of a single change, e-mail included (tracking.notifyCustomer is on)
  await waitForMail({ to: seedCustomer.email, subject: /Aggiornamento spedizione N\. TEST-0002: Affidata al mezzo per la consegna/ });
});

test('[ENH-08] «Oggi» on the Panoramica: to approve, to pick up, on the way for too long', async () => {
  await staffPage.goto('/gestione/');
  const today = staffPage.locator('[data-today]');
  await expect(today.getByRole('heading', { name: 'Oggi', exact: true })).toBeVisible();
  // dev-seed's DDT-SEED-0001 and the orders of this spec wait for approval
  await expect(today.locator('[data-today-group="toApprove"] li').first()).toBeVisible();
  await expect(today.locator('[data-today-group="toPickUp"]')).toContainText('Da ritirare');
  await expect(today.locator('[data-today-group="stuck"]')).toContainText('In viaggio da più di 3 giorni');
});

test('[ENH-08] the order search finds a shipment by recipient, city or customer', async () => {
  await staffPage.goto('/gestione/ordini/');
  await expect(staffPage.locator('#codice')).toHaveAttribute('placeholder', 'Codice, destinatario, città, DDT o cliente');
  for (const text of ['Destinatario Due', 'como', 'Ditta Attiva']) {
    await staffPage.locator('#codice').fill(text);
    await staffPage.getByRole('button', { name: 'Filtra' }).click();
    await expect(staffPage.locator('tr[data-order="TEST-0002"]'), `search «${text}»`).toBeVisible();
  }
});

test('[ENH-08] the order and customer lists download as CSV files', async () => {
  await staffPage.goto('/gestione/ordini/?stato=&codice=TEST-0002');
  const [orders] = await Promise.all([
    staffPage.waitForEvent('download'),
    staffPage.getByRole('link', { name: 'Esporta CSV' }).click(),
  ]);
  expect(orders.suggestedFilename()).toMatch(/^ordini-\d{4}-\d{2}-\d{2}\.csv$/);
  const ordersCsv = readFileSync(await orders.path(), 'utf8');
  expect(ordersCsv.startsWith('\uFEFF"ID";"Codice";"Codice provvisorio";')).toBe(true);
  expect(ordersCsv).toContain('"TEST-0002"');
  expect(ordersCsv).toContain('"Destinatario Due S.r.l."');

  await staffPage.goto('/gestione/clienti/?q=cliente.attivo');
  const [customers] = await Promise.all([
    staffPage.waitForEvent('download'),
    staffPage.getByRole('link', { name: 'Esporta CSV' }).click(),
  ]);
  expect(customers.suggestedFilename()).toMatch(/^clienti-\d{4}-\d{2}-\d{2}\.csv$/);
  const customersCsv = readFileSync(await customers.path(), 'utf8');
  expect(customersCsv.trim().split('\r\n')).toHaveLength(2);
  expect(customersCsv).toContain('"Ditta Attiva S.r.l."');
});

test('[ENH-06] the admin turns on the website requests', async () => {
  await staffPage.goto('/gestione/impostazioni/');
  await expect(staffPage.locator('tr[data-flag="leads.inbox"]')).toContainText('Disponibile');
  await setFlag('leads.inbox', true);
});

test('[ENH-06] a quote request from the website becomes a customer and then an order', async () => {
  await staffPage.goto('/gestione/richieste/');
  await expect(staffPage.locator('[data-count="leads"]')).toBeVisible();
  await staffPage.locator('tr[data-lead]', { hasText: 'Mario Prova' }).getByRole('link', { name: 'Mario Prova' }).click();
  await expect(staffPage.getByRole('heading', { level: 1, name: 'Mario Prova' })).toBeVisible();
  await expect(staffPage.locator('[data-section="goods"]')).toContainText('2 × EPAL 120×80 cm');
  await expectAccessible(staffPage);

  await staffPage.getByRole('button', { name: 'Segna come contattata' }).click();
  await expect(staffPage.locator('[data-flash]')).toContainText('Contattata');

  await staffPage.getByRole('link', { name: 'Crea cliente' }).click();
  await expect(staffPage.locator('[data-from-lead]')).toBeVisible();
  await expect(staffPage.locator('#companyName')).toHaveValue('Officina Prova S.r.l.');
  await staffPage.locator('#addressLine').fill('Via delle Prove 1');
  await staffPage.locator('#province').fill('MB');
  await staffPage.getByRole('button', { name: 'Crea il cliente' }).click();
  await expect(staffPage.locator('[data-flash]')).toContainText('La richiesta è collegata al nuovo cliente');

  await staffPage.getByRole('link', { name: 'Crea ordine' }).click();
  await expect(staffPage.locator('[name="line.0.heightCm"]')).toHaveValue('150');
  await expect(staffPage.locator('[name="line.0.weightKg"]')).toHaveValue('300');
  await expect(staffPage.locator('#city')).toHaveValue('Torino');
  await staffPage.locator('#name').fill('Ricevente Torino S.r.l.');
  await staffPage.locator('#addressLine').fill('Corso Francia 10');
  await staffPage.locator('#province').fill('TO');
  await staffPage.locator('#transportDocumentCode').fill(`DDT-RICHIESTA-${stamp}`);
  await staffPage.getByRole('button', { name: "Inserisci l'ordine" }).click();
  await expect(staffPage.locator('[data-flash]')).toContainText('La richiesta del sito è segnata come vinta');
});

test('[ENH-20] the admin turns on the saved quotes', async () => {
  await staffPage.goto('/gestione/impostazioni/');
  await expect(staffPage.locator('tr[data-flag="quotes.saved"]')).toContainText('Disponibile');
  await setFlag('quotes.saved', true);
});

test('[ENH-20] a quote prepared from a request reaches the customer, who orders it', async ({ request, baseURL }) => {
  // a quote request sent from the site with the seeded customer's e-mail
  const sent = await request.post('/api/form', {
    headers: { Origin: baseURL!, Accept: 'application/json' },
    form: {
      'form-name': 'preventivo',
      servizio: 'pallet',
      ritiro_cap: '20831',
      ritiro_localita: 'Seregno',
      consegna_cap: '10121',
      consegna_localita: 'Torino',
      destinatario: 'azienda',
      tipo_pallet: 'eur',
      numero: '1',
      peso: '400',
      altezza: '160',
      nome: 'Referente Ditta Attiva',
      azienda: 'Ditta Attiva S.r.l.',
      email: seedCustomer.email,
      telefono: '+39 333 000 0003',
      contatto_preferito: 'email',
      privacy: 'accettata',
    },
  });
  expect(sent.status()).toBe(200);

  await staffPage.goto('/gestione/richieste/?q=cliente.attivo');
  await staffPage.locator('tr[data-lead]', { hasText: 'Referente Ditta Attiva' }).first().getByRole('link').first().click();
  await staffPage.getByRole('button', { name: 'Collega la richiesta al cliente' }).click();
  await expect(staffPage.locator('[data-flash]')).toContainText('Richiesta collegata al cliente');
  await staffPage.getByRole('link', { name: 'Prepara preventivo' }).click();
  await expect(staffPage.locator('[name="line.0.heightCm"]')).toHaveValue('160');
  await staffPage.locator('#amount').fill('145,00');
  await staffPage.locator('#note').fill('IVA esclusa.');
  await expectAccessible(staffPage);
  await staffPage.getByRole('button', { name: 'Invia il preventivo' }).click();
  await expect(staffPage.locator('[data-flash]')).toContainText('Preventivo PRV-');
  await expect(staffPage.locator('[data-section="quotes"]')).toContainText('Inviato');

  const mail = await waitForMail({ to: seedCustomer.email, subject: /Il tuo preventivo PRV-/ });
  await customerPage.goto(linkIn(mail, '/preventivi/'));
  await expect(customerPage.locator('[data-quote-amount]')).toContainText('145,00');
  await expectAccessible(customerPage);
  await customerPage.getByRole('link', { name: 'Ordina il preventivo' }).click();
  await expect(customerPage.locator('#city')).toHaveValue('Torino');
  await expectAccessible(customerPage);
  await customerPage.locator('#transportDocumentCode').fill(`DDT-PREVENTIVO-${stamp}`);
  await customerPage.locator('#name').fill('Ricevente Torino S.r.l.');
  await customerPage.locator('#addressLine').fill('Corso Francia 10');
  await customerPage.locator('#province').fill('TO');
  await customerPage.getByRole('button', { name: "Invia l'ordine" }).click();
  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/TEMP-[A-Za-z0-9]+\/$/);
  await expect(customerPage.locator('[data-flash]')).toContainText('dal preventivo PRV-');
});

test('[ENH-16] the admin turns on the customers\' own data changes', async () => {
  await staffPage.goto('/gestione/impostazioni/');
  await expect(staffPage.locator('tr[data-flag="customers.profileEdit"]')).toContainText('Disponibile');
  await setFlag('customers.profileEdit', true);
});

test('[ENH-16] a customer changes its phone at once and its company name once the office approves', async ({ browser, baseURL }) => {
  // a customer of its own, created by the office: the seeded ones stay as the other specs expect them
  const email = `dati.${stamp}@cliente.example`;
  const company = `Ditta Profilo ${stamp} S.r.l.`;
  const renamed = `Ditta Profilo ${stamp} Trasporti S.r.l.`;
  await staffPage.goto('/gestione/clienti/nuovo');
  await staffPage.locator('#email').fill(email);
  await staffPage.locator('#companyName').fill(company);
  await staffPage.locator('#addressLine').fill('Via del Profilo 3');
  await staffPage.locator('#city').fill('Desio');
  await staffPage.locator('#province').fill('MB');
  await staffPage.locator('#postalCode').fill('20832');
  await staffPage.locator('#phone').fill('0362 111222');
  await staffPage.getByRole('button', { name: 'Crea il cliente' }).click();
  await expect(staffPage.locator('[data-flash]')).toContainText('creato e attivo');

  const [context, page, problems] = await newPage(browser, baseURL!);
  await page.goto('/area-clienti/');
  await page.locator('#email').fill(email);
  await page.getByRole('button', { name: 'Ricevi il link' }).click();
  const signIn = await waitForMail({ to: email, subject: /link per accedere/ });
  await page.goto(linkIn(signIn, '/area-clienti/accesso'));
  await page.getByRole('button', { name: 'Accedi' }).click();
  await expect(page).toHaveURL(/\/area-clienti\/ordini\/$/);

  await page.goto('/area-clienti/profilo/');
  await expect(page.getByRole('link', { name: 'Chiedi una modifica' })).toHaveCount(0);
  await page.getByRole('link', { name: 'Modifica i dati' }).click();
  await expect(page.locator('#companyName')).toHaveValue(company);
  await expectAccessible(page);
  await page.locator('#phone').fill('0362 333444');
  await page.locator('#companyName').fill(renamed);
  await page.getByRole('button', { name: 'Salva i dati' }).click();
  await expect(page.locator('[data-flash]')).toContainText('Dati salvati');
  await expect(page.locator('[data-flash]')).toContainText('inviata al nostro ufficio');
  await expect(page.locator('[data-pending-change]')).toContainText(renamed);
  await expect(page.locator('main')).toContainText('+390362333444');
  await expectAccessible(page);

  await staffPage.goto('/gestione/clienti/in-approvazione/');
  await staffPage
    .locator('[data-section="customer-changes"] tr', { hasText: company })
    .getByRole('link', { name: 'Apri il cliente' })
    .click();
  await expect(staffPage.locator('[data-change-request]')).toContainText(renamed);
  await expectAccessible(staffPage);
  await staffPage.getByRole('button', { name: 'Approva la modifica' }).click();
  await expect(staffPage.locator('[data-flash]')).toContainText('Modifica approvata');
  await expect(staffPage.getByRole('heading', { level: 1 })).toHaveText(renamed);

  const outcome = await waitForMail({ to: email, subject: /Dati aggiornati/ });
  expect(outcome.body).toContain(renamed);
  await page.goto('/area-clienti/profilo/');
  await expect(page.locator('[data-pending-change]')).toHaveCount(0);
  await expect(page.locator('main')).toContainText(renamed);
  problems.expectNone();
  await context.close();
});

test('[NFR-01] the M2.1 screens pass axe and fit a phone', async () => {
  const screens: Array<[Page, string]> = [
    [customerPage, '/area-clienti/profilo/'],
    [customerPage, '/area-clienti/profilo/rubrica/nuovo'],
    [customerPage, '/area-clienti/profilo/dati'],
    [customerPage, '/area-clienti/ordini/nuovo/'],
    [customerPage, '/area-clienti/ordini/TEST-0002/'],
    [staffPage, '/gestione/ordini/nuovo'],
    [staffPage, '/gestione/'],
    [staffPage, '/gestione/tracking/'],
    [staffPage, '/gestione/ordini/'],
    [staffPage, '/gestione/clienti/'],
    [staffPage, '/gestione/richieste/'],
  ];
  for (const [page, path] of screens) {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    for (const size of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
      await page.setViewportSize(size);
      await page.goto(path);
      await expectAccessible(page);
      await expectNoHorizontalScroll(page);
    }
  }
});
