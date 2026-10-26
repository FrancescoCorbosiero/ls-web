import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { linkIn, waitForMail } from '../support/mail';
import { PageProblems, expectNoHorizontalScroll, offline } from '../support/browser';

/**
 * M2.1 end to end (ADR 0025, 0033): the seeded ADMIN turns each feature on from «Impostazioni»,
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
  await expect(staffPage.locator('tr[data-flag="leads.inbox"]')).toContainText('Non ancora sviluppata');
  await expect(staffPage.locator('tr[data-flag="leads.inbox"]').getByRole('button')).toBeDisabled();

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

test('[NFR-01] the address book screens pass axe and fit a phone', async () => {
  const screens: Array<[Page, string]> = [
    [customerPage, '/area-clienti/profilo/'],
    [customerPage, '/area-clienti/profilo/rubrica/nuovo'],
    [customerPage, '/area-clienti/ordini/nuovo/'],
    [staffPage, '/gestione/ordini/nuovo'],
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
