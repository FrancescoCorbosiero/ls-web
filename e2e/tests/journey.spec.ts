import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { linkIn, waitForMail } from '../support/mail';
import { PageProblems, expectNoHorizontalScroll, offline } from '../support/browser';

/**
 * The M1 journey end to end, in order: a new customer registers from the mailed link, the
 * staff approves it, the customer signs in, places an order and changes it while it is pending,
 * the staff approves the order with a code and an amount, the public tracking answers the label,
 * the staff sets CONSEGNATO and the delivery e-mail reaches the customer's mailbox file; then
 * the record editing of ADR 0028: the staff enter an order for the customer, who withdraws it,
 * and create a customer who phoned and correct its data. Every step depends on the previous
 * one (serial), and the staff account is the one dev-seed created (run.sh).
 */
test.describe.configure({ mode: 'serial' });

const stamp = Date.now().toString(36);
const customer = {
  email: `e2e-${stamp}@cliente.example`,
  companyName: `E2E Trasporti ${stamp} S.r.l.`,
  addressLine: 'Via di Prova 10',
  province: 'MB',
  city: 'Seregno',
  postalCode: '20831',
  phone: '0362 123456',
};
const order = {
  transportDocument: `DDT-E2E-${stamp}`,
  code: `E2E-${stamp.toUpperCase()}`,
  amount: '120,50',
  recipient: {
    name: 'Destinatario E2E S.r.l.',
    addressLine: 'Via Milano 1',
    province: 'CO',
    city: 'Como',
    postalCode: '22100',
  },
};
const staff = {
  username: process.env.E2E_ADMIN_USER ?? 'admin.seed',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
};

let customerContext: BrowserContext;
let staffContext: BrowserContext;
let customerPage: Page;
let staffPage: Page;
let customerProblems: PageProblems;
let staffProblems: PageProblems;
let tempCode = '';
let orderDetailPath = '';
let officeEmail = '';
let phoneTempCode = '';
let phoneOrderPath = '';

async function newPage(browser: Browser, baseURL: string): Promise<[BrowserContext, Page, PageProblems]> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await offline(page, baseURL);
  const problems = await PageProblems.watch(page);
  return [context, page, problems];
}

test.beforeAll(async ({ browser, baseURL }) => {
  expect(staff.password, 'E2E_ADMIN_PASSWORD must hold the dev-seed password of admin.seed (run.sh sets it)').not.toBe('');
  [customerContext, customerPage, customerProblems] = await newPage(browser, baseURL!);
  [staffContext, staffPage, staffProblems] = await newPage(browser, baseURL!);
});

test.afterAll(async () => {
  await customerContext?.close();
  await staffContext?.close();
});

test.afterEach(() => {
  customerProblems?.expectNone();
  staffProblems?.expectNone();
});

/** NFR-01: no serious or critical axe finding on a private screen (ADR 0029). */
async function expectAccessible(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'best-practice']).analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
  expect(blocking, `axe on ${page.url()}`).toEqual([]);
}

test('[CUS-02] a new address asks for access and completes the registration from the mailed link', async () => {
  await customerPage.goto('/area-clienti/');
  await customerPage.getByLabel('Email').fill(customer.email);
  await customerPage.getByRole('button', { name: 'Avanti' }).click();
  await expect(customerPage.getByText("Ti abbiamo inviato un'email")).toBeVisible();

  const mail = await waitForMail({ to: customer.email, subject: /Completa la registrazione/ });
  await customerPage.goto(linkIn(mail, '/area-clienti/registrazione/'));
  await expect(customerPage.getByRole('heading', { name: "Inserisci i dati dell'azienda" })).toBeVisible();
  await customerPage.locator('#email').fill(customer.email);
  await customerPage.locator('#companyName').fill(customer.companyName);
  await customerPage.locator('#addressLine').fill(customer.addressLine);
  await customerPage.locator('#province').fill(customer.province);
  await customerPage.locator('#city').fill(customer.city);
  await customerPage.locator('#postalCode').fill(customer.postalCode);
  await customerPage.locator('#phone').fill(customer.phone);
  await customerPage.getByRole('button', { name: 'Avanti' }).click();

  await expect(customerPage.getByRole('heading', { name: 'Controlla i dati inseriti' })).toBeVisible();
  await expect(customerPage.getByText(customer.companyName)).toBeVisible();
  await customerPage.getByRole('button', { name: 'Conferma', exact: true }).click();

  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/$/);
  await expect(customerPage.locator('[data-customer-status="pending"]')).toBeVisible();
});

test('[EML-02] the customer receives the registration confirmation and the office its notice', async ({ baseURL }) => {
  await waitForMail({ to: customer.email, subject: /Registrazione ricevuta/ });
  const office = await staffPage.request.get(new URL('/js/config.js', baseURL).href);
  officeEmail = (await office.text()).match(/email:\s*['"]([^'"]+)['"]/)?.[1] ?? '';
  expect(officeEmail).toMatch(/@/);
  await waitForMail({ to: officeEmail, subject: /./, body: new RegExp(customer.email) });
});

test('[ADM-02] the staff approves the customer from «In Approvazione»', async () => {
  await staffPage.goto('/gestione/login');
  await staffPage.getByLabel('Username').fill(staff.username);
  await staffPage.getByLabel('Password').fill(staff.password);
  await staffPage.getByRole('button', { name: 'Accedi' }).click();
  await expect(staffPage).toHaveURL(/\/gestione\/$/);

  await staffPage.goto('/gestione/clienti/in-approvazione/');
  const row = staffPage.locator('tr', { hasText: customer.email });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText('SI');
  await row.getByRole('link', { name: 'Approva', exact: true }).click();
  await expect(staffPage.getByText('Sei sicuro di voler procedere?')).toBeVisible();
  await staffPage.getByRole('button', { name: 'Conferma' }).click();

  await staffPage.goto('/gestione/clienti/in-approvazione/');
  await expect(staffPage.locator('tr', { hasText: customer.email })).toHaveCount(0);
  await staffPage.goto(`/gestione/clienti/?q=${encodeURIComponent(customer.email)}`);
  await expect(staffPage.locator('tr', { hasText: customer.email })).toContainText('Attivo');
});

test('[EML-03] the approval e-mail reaches the customer', async () => {
  const mail = await waitForMail({ to: customer.email, subject: /Conferma nuovo cliente/ });
  expect(mail.body).toContain('Benvenuto in');
});

test('[CUS-01] the approved customer signs in from the mailed link', async () => {
  await customerContext.clearCookies();
  await customerPage.goto('/area-clienti/');
  await customerPage.getByLabel('Email').fill(customer.email);
  await customerPage.getByRole('button', { name: 'Avanti' }).click();
  await expect(customerPage.getByText("Ti abbiamo inviato un'email")).toBeVisible();

  const mail = await waitForMail({ to: customer.email, subject: /link per accedere/ });
  await customerPage.goto(linkIn(mail, '/area-clienti/accesso'));
  await expect(customerPage.getByRole('heading', { name: "Accedi all'area clienti" })).toBeVisible();
  await expect(customerPage.getByLabel('Resta connesso su questo dispositivo per 60 giorni')).toBeChecked();
  await customerPage.getByRole('button', { name: 'Accedi' }).click();

  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/$/);
  await expect(customerPage.getByRole('heading', { name: 'I miei ordini' })).toBeVisible();
  await expect(customerPage.locator('[data-customer-status]')).toHaveCount(0);
});

test('[CUS-01] «Resta connesso» opens the area again once the session has ended', async () => {
  // ADR 0031: only the remember cookie is left, as after the idle timeout or a restart
  await customerContext.clearCookies({ name: 'LS_SESSION' });
  await customerPage.goto('/area-clienti/');
  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/$/);
  await expect(customerPage.getByRole('heading', { name: 'I miei ordini' })).toBeVisible();
});

test('[CUS-04] the customer places an order with the one-page form and the summary', async () => {
  await customerPage.goto('/area-clienti/ordini/nuovo/');
  const line = (row: number, field: string) => customerPage.locator(`[name="line.${row}.${field}"]`);
  await line(0, 'kind').selectOption('EPAL');
  await expect(line(0, 'lengthCm')).toHaveValue('120');
  await expect(line(0, 'widthCm')).toHaveValue('80');
  await line(0, 'heightCm').fill('180');
  await line(0, 'weightKg').fill('450');
  await line(0, 'quantity').fill('2');
  await expect(customerPage.locator('[data-total-weight]')).toHaveText('900,00');
  await expect(customerPage.locator('[data-total-volume]')).toHaveText('3,46');

  await customerPage.getByRole('button', { name: 'Aggiungi riga' }).click();
  await expect(customerPage.locator('[data-line-row]')).toHaveCount(2);
  await line(1, 'kind').selectOption('GENERIC');
  await line(1, 'heightCm').fill('100');
  await line(1, 'lengthCm').fill('120');
  await line(1, 'widthCm').fill('80');
  await line(1, 'weightKg').fill('300');
  await line(1, 'quantity').fill('1');
  await expect(customerPage.locator('[data-total-weight]')).toHaveText('1.200,00');
  await expect(customerPage.locator('[data-total-units]')).toHaveText('3');

  await customerPage.locator('#transportDocumentCode').fill(order.transportDocument);
  await customerPage.locator('#packageCount').fill('3');
  await customerPage.locator('#name').fill(order.recipient.name);
  await customerPage.locator('#addressLine').fill(order.recipient.addressLine);
  await customerPage.locator('#province').fill(order.recipient.province);
  await customerPage.locator('#city').fill(order.recipient.city);
  await customerPage.locator('#postalCode').fill(order.recipient.postalCode);
  await customerPage.getByRole('button', { name: 'Continua al riepilogo' }).click();

  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/nuovo\/riepilogo\/$/);
  await expect(customerPage.getByRole('heading', { name: 'Controlla i dati inseriti' })).toBeVisible();
  await expect(customerPage.locator('[data-review-totals]')).toHaveText('1.200,00 kg · 4,42 m³');
  await expect(customerPage.locator('main')).toContainText(order.transportDocument);
  await expect(customerPage.locator('main')).toContainText(order.recipient.name);
  await customerPage.getByRole('button', { name: "Conferma l'ordine" }).click();

  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/nuovo\/inviato\/$/);
  await expect(customerPage.locator('[data-order-done]')).toContainText('Grazie per aver scelto');
  tempCode = (await customerPage.locator('main').innerText()).match(/TEMP-[A-Za-z0-9]+/)?.[0] ?? '';
  expect(tempCode).toMatch(/^TEMP-/);
});

test('[CUS-05] the new order is listed in «I miei ordini» with its detail', async () => {
  await customerPage.goto('/area-clienti/ordini/');
  const row = customerPage.locator('tr', { hasText: tempCode });
  await expect(row).toHaveCount(1);
  await customerPage.goto(`/area-clienti/ordini/${tempCode}/`);
  await expect(customerPage.locator('main')).toContainText(order.transportDocument);
  await expect(customerPage.locator('main')).toContainText(order.recipient.name);
  await waitForMail({ to: customer.email, subject: /Richiesta di spedizione ricevuta/, body: new RegExp(tempCode) });
});

test('[CUS-07] the customer changes the pending order before the office confirms it', async () => {
  await customerPage.goto(`/area-clienti/ordini/${tempCode}/`);
  await customerPage.getByRole('link', { name: 'Modifica', exact: true }).click();
  await expect(customerPage).toHaveURL(new RegExp(`/area-clienti/ordini/${tempCode}/modifica$`));
  await expect(customerPage.locator('[data-line-row]')).toHaveCount(2);
  await customerPage.getByRole('button', { name: 'Elimina la riga 2' }).click();
  await expect(customerPage.locator('[data-line-row]')).toHaveCount(1);
  await expect(customerPage.locator('[data-total-weight]')).toHaveText('900,00');
  await customerPage.locator('#notes').fill('Consegna al mattino');
  await customerPage.getByRole('button', { name: 'Salva le modifiche' }).click();

  await expect(customerPage).toHaveURL(new RegExp(`/area-clienti/ordini/${tempCode}/$`));
  await expect(customerPage.locator('main')).toContainText('Modifiche salvate');
  await expect(customerPage.locator('main')).toContainText('Consegna al mattino');
  await expect(customerPage.locator('main')).toContainText('Ultima modifica fatta da te');
  await waitForMail({ to: officeEmail, subject: /modificata dal cliente/, body: new RegExp(tempCode) });
});

test('[ADM-04] the staff approves the order with a code and an amount', async () => {
  await staffPage.goto('/gestione/ordini/in-approvazione/');
  const row = staffPage.locator('tr', { hasText: order.transportDocument });
  await expect(row).toHaveCount(1);
  await row.getByRole('link', { name: 'Approva ordine' }).click();
  await staffPage.locator('#codice').fill(order.code);
  await staffPage.locator('#importo').fill(order.amount);
  await staffPage.getByRole('button', { name: 'Conferma' }).click();

  await staffPage.goto(`/gestione/ordini/?codice=${encodeURIComponent(order.code)}`);
  const approved = staffPage.locator('tr', { hasText: order.code });
  await expect(approved).toHaveCount(1);
  await expect(approved).toContainText('120,50');
  orderDetailPath = new URL((await approved.getByRole('link', { name: 'Dettaglio' }).getAttribute('href')) ?? '', staffPage.url()).pathname;
  expect(orderDetailPath).toMatch(/^\/gestione\/ordini\/\d+\/$/);
});

test('[EML-05] the order confirmation with the final code reaches the customer', async () => {
  const mail = await waitForMail({ to: customer.email, subject: new RegExp(`Conferma ordine N\\. ${order.code}`) });
  expect(mail.body).toContain(order.code);
});

test('[TRK-01] the public tracking answers «Presa in carico» for the approved code', async ({ request }) => {
  const api = await request.post('/mono/api/order/trace/state', { data: { codice: order.code } });
  expect(api.status()).toBe(200);
  expect(await api.json()).toMatchObject({ status: 'Presa in carico' });

  await customerPage.goto(`/tracking/?code=${encodeURIComponent(order.code)}`);
  await expect(customerPage.locator('.track-code')).toContainText(order.code);
  await expect(customerPage.locator('main')).toContainText('Presa in carico');

  const unknown = await request.post('/mono/api/order/trace/state', { data: { codice: 'NON-ESISTE-0000' } });
  expect(await unknown.json()).toMatchObject({ status: 'Non gestito' });
});

test('[ADM-06] the staff sets the tracking state to CONSEGNATO', async () => {
  await staffPage.goto(orderDetailPath);
  await staffPage.locator('#stato').selectOption('5');
  await staffPage.getByRole('button', { name: 'Imposta stato' }).click();
  await expect(staffPage.locator('#tracking')).toContainText('Consegnato');
});

test('[TRK-02] the public tracking follows the new state', async ({ request }) => {
  const api = await request.post('/mono/api/order/trace/state', { data: { codice: order.code } });
  expect(await api.json()).toMatchObject({ status: 'Consegnato' });
});

test('[EML-09] the delivery e-mail reaches the customer mailbox file', async () => {
  const mail = await waitForMail({ to: customer.email, subject: new RegExp(`Consegna effettuata per ordine N\\. ${order.code}`) });
  expect(mail.body).toContain(order.code);
});

test('[ADM-11] the staff enters an order for the customer who phoned', async () => {
  await staffPage.goto('/gestione/ordini/nuovo');
  await staffPage.locator('#cliente').selectOption({ label: `${customer.companyName} – ${customer.city} (${customer.email})` });
  await staffPage.locator('[name="line.0.kind"]').selectOption('EPAL');
  await staffPage.locator('[name="line.0.heightCm"]').fill('150');
  await staffPage.locator('[name="line.0.weightKg"]').fill('400');
  await staffPage.locator('[name="line.0.quantity"]').fill('1');
  await expect(staffPage.locator('[data-total-weight]')).toHaveText('400,00');
  await staffPage.locator('#transportDocumentCode').fill(`DDT-TEL-${stamp}`);
  await staffPage.locator('#name').fill('Ricevente Telefonico S.r.l.');
  await staffPage.locator('#addressLine').fill('Via Lecco 2');
  await staffPage.locator('#province').fill('MB');
  await staffPage.locator('#city').fill('Monza');
  await staffPage.locator('#postalCode').fill('20900');
  await staffPage.getByRole('button', { name: "Inserisci l'ordine" }).click();

  await expect(staffPage).toHaveURL(/\/gestione\/ordini\/\d+\/$/);
  await expect(staffPage.locator('main')).toContainText('Ordine inserito con il codice provvisorio');
  await expect(staffPage.locator('main')).toContainText("Inserito dall'ufficio");
  phoneOrderPath = new URL(staffPage.url()).pathname;
  phoneTempCode = (await staffPage.locator('main').innerText()).match(/TEMP-[A-Za-z0-9]+/)?.[0] ?? '';
  expect(phoneTempCode).toMatch(/^TEMP-/);
});

test('[CUS-07] the customer withdraws the order the office entered for it', async () => {
  await customerPage.goto('/area-clienti/ordini/');
  await expect(customerPage.locator('tr', { hasText: phoneTempCode })).toHaveCount(1);
  await customerPage.goto(`/area-clienti/ordini/${phoneTempCode}/`);
  await customerPage.getByRole('link', { name: "Annulla l'ordine" }).click();
  await expect(customerPage.getByText('Vuoi davvero annullare questo ordine?')).toBeVisible();
  await customerPage.getByRole('button', { name: "Sì, annulla l'ordine" }).click();

  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/$/);
  await expect(customerPage.locator('main')).toContainText(`Ordine ${phoneTempCode} annullato`);
  await waitForMail({ to: officeEmail, subject: /annullata dal cliente/, body: new RegExp(phoneTempCode) });
  await staffPage.goto(phoneOrderPath);
  await expect(staffPage.locator('[data-cancelled]')).toContainText("Il cliente ha annullato l'ordine");
});

test('[ADM-12] the staff creates a customer who phoned and corrects its data', async () => {
  await staffPage.goto('/gestione/clienti/');
  await staffPage.getByRole('link', { name: 'Nuovo cliente' }).click();
  await staffPage.locator('#email').fill(`e2e-tel-${stamp}@cliente.example`);
  await staffPage.locator('[name="benvenuto"]').uncheck();
  await staffPage.locator('#companyName').fill(`E2E Telefono ${stamp} S.r.l.`);
  await staffPage.locator('#addressLine').fill('Via Garibaldi 3');
  await staffPage.locator('#city').fill('Lissone');
  await staffPage.locator('#province').fill('MB');
  await staffPage.locator('#postalCode').fill('20851');
  await staffPage.locator('#phone').fill('039 7654321');
  await staffPage.getByRole('button', { name: 'Crea il cliente' }).click();

  await expect(staffPage).toHaveURL(/\/gestione\/clienti\/\d+\/$/);
  await expect(staffPage.locator('main')).toContainText('creato e attivo');
  await staffPage.getByRole('link', { name: 'Modifica dati' }).click();
  await staffPage.locator('#city').fill('Desio');
  await staffPage.locator('#postalCode').fill('20832');
  await staffPage.getByRole('button', { name: 'Salva le modifiche' }).click();

  await expect(staffPage).toHaveURL(/\/gestione\/clienti\/\d+\/$/);
  await expect(staffPage.locator('main')).toContainText('Dati del cliente aggiornati');
  await expect(staffPage.locator('main')).toContainText('Desio');
});

test('[NFR-01] the customer area and the back office pass axe and fit a phone', async () => {
  const screens: Array<[Page, string]> = [
    [customerPage, '/area-clienti/ordini/'],
    [customerPage, '/area-clienti/ordini/nuovo/'],
    [customerPage, `/area-clienti/ordini/${order.code}/`],
    [customerPage, `/area-clienti/ordini/${phoneTempCode}/`],
    [customerPage, '/area-clienti/profilo/'],
    [staffPage, '/gestione/'],
    [staffPage, '/gestione/ordini/in-approvazione/'],
    [staffPage, '/gestione/ordini/'],
    [staffPage, orderDetailPath],
    [staffPage, phoneOrderPath],
    [staffPage, '/gestione/ordini/nuovo'],
    [staffPage, '/gestione/clienti/'],
    [staffPage, '/gestione/clienti/in-approvazione/'],
    [staffPage, '/gestione/clienti/nuovo'],
    [staffPage, '/gestione/tracking/'],
    [staffPage, '/gestione/report/'],
    [staffPage, '/gestione/impostazioni/'],
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
