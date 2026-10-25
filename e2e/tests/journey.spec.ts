import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { linkIn, waitForMail } from '../support/mail';
import { PageProblems, offline } from '../support/browser';

/**
 * The M1 journey end to end, in order: a new customer registers from the mailed link, the
 * staff approves it, the customer signs in and places an order, the staff approves the order
 * with a code and an amount, the public tracking answers the label, the staff sets CONSEGNATO
 * and the delivery e-mail reaches the customer's mailbox file. Every step depends on the
 * previous one (serial), and the staff account is the one dev-seed created (run.sh).
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
  const officeEmail = (await office.text()).match(/email:\s*['"]([^'"]+)['"]/)?.[1] ?? '';
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
  await customerPage.getByRole('button', { name: 'Accedi' }).click();

  await expect(customerPage).toHaveURL(/\/area-clienti\/ordini\/$/);
  await expect(customerPage.getByRole('heading', { name: 'I miei ordini' })).toBeVisible();
  await expect(customerPage.locator('[data-customer-status]')).toHaveCount(0);
});

test('[CUS-04] the customer places an order through the three-page wizard', async () => {
  await customerPage.goto('/area-clienti/ordini/nuovo/');
  await customerPage.locator('#kind').selectOption('EPAL');
  await customerPage.locator('#heightCm').fill('180');
  await customerPage.locator('#lengthCm').fill('120');
  await customerPage.locator('#widthCm').fill('80');
  await customerPage.locator('#weightKg').fill('450');
  await customerPage.locator('#quantity').fill('2');
  await customerPage.getByRole('button', { name: 'Conferma', exact: true }).click();
  await expect(customerPage.locator('main')).toContainText('900,00');

  await customerPage.locator('#transportDocumentCode').fill(order.transportDocument);
  await customerPage.locator('#packageCount').fill('3');
  await customerPage.getByRole('button', { name: 'Avanti' }).click();

  await expect(customerPage.getByRole('heading', { name: 'Inserisci i dati del destinatario' })).toBeVisible();
  await customerPage.locator('#name').fill(order.recipient.name);
  await customerPage.locator('#addressLine').fill(order.recipient.addressLine);
  await customerPage.locator('#province').fill(order.recipient.province);
  await customerPage.locator('#city').fill(order.recipient.city);
  await customerPage.locator('#postalCode').fill(order.recipient.postalCode);
  await customerPage.getByRole('button', { name: 'Avanti' }).click();

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
