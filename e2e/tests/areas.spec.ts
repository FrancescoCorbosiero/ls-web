import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { linkIn, waitForMail } from '../support/mail';
import { PageProblems, offline } from '../support/browser';

/**
 * The two private areas side by side (ADR 0045). Both share the site's one session cookie, so a
 * browser holds one sign-in at a time: the admin and a customer in two tabs of the same browser
 * take turns, and the customer's «Resta connesso» comes back once the admin leaves; two browsers
 * — the office and the customers, each on their own devices as in production — stay signed in
 * side by side. And a back-office page never looks like the customer area. The accounts are
 * dev-seed's (run.sh); the customer is the pending one, which the other specs leave alone.
 */
const customerEmail = 'cliente.in-attesa@cliente.example';
const staff = {
  username: process.env.E2E_ADMIN_USER ?? 'admin.seed',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
};

test.beforeAll(() => {
  expect(staff.password, 'E2E_ADMIN_PASSWORD must hold the dev-seed password of admin.seed (run.sh sets it)').not.toBe('');
});

async function newTab(context: BrowserContext, baseURL: string): Promise<Page> {
  const page = await context.newPage();
  await offline(page, baseURL);
  return page;
}

async function staffSignsIn(page: Page): Promise<void> {
  await page.goto('/gestione/');
  await page.getByLabel('Username').fill(staff.username);
  await page.getByLabel('Password').fill(staff.password);
  await page.getByRole('button', { name: 'Accedi' }).click();
  await expect(page).toHaveURL(/\/gestione\/$/);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Ciao');
}

/** The mailed link, with «Resta connesso» left ticked as the page offers it (ADR 0031). */
async function customerSignsIn(page: Page): Promise<void> {
  await page.goto('/area-clienti/');
  await page.locator('#email').fill(customerEmail);
  await page.getByRole('button', { name: 'Ricevi il link' }).click();
  await expect(page.getByText("Ti abbiamo inviato un'email")).toBeVisible();
  const mail = await waitForMail({ to: customerEmail, subject: /link per accedere/ });
  await page.goto(linkIn(mail, '/area-clienti/accesso'));
  await expect(page.getByLabel(/Resta connesso/)).toBeChecked();
  await page.getByRole('button', { name: 'Accedi' }).click();
  await expect(page).toHaveURL(/\/area-clienti\/ordini\/$/);
}

/** NFR-01: no serious or critical axe finding (ADR 0029), colour contrast on the tinted page included. */
async function expectAccessible(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'best-practice']).analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
  expect(blocking, `axe on ${page.url()}`).toEqual([]);
}

/** The page background and the line drawn on top of the header. */
async function look(page: Page): Promise<{ background: string; line: string }> {
  return page.evaluate(() => ({
    background: getComputedStyle(document.body).backgroundColor,
    line: getComputedStyle(document.querySelector('.site-header')!, '::before').content,
  }));
}

test('[ADM-01] [CUS-01] one browser holds one sign-in: the admin and a customer in two tabs take turns', async ({ browser, baseURL }) => {
  const context = await browser.newContext();
  const adminTab = await newTab(context, baseURL!);
  const customerTab = await newTab(context, baseURL!);

  await staffSignsIn(adminTab);
  await customerSignsIn(customerTab);

  // back to the admin's tab: the customer's sign-in ended the staff session
  expect((await adminTab.goto('/gestione/ordini/'))?.status()).toBe(403);
  await adminTab.goto('/gestione/');
  await expect(adminTab.getByRole('heading', { level: 1 })).toHaveText('Login');

  // the admin signs in again: now the customer's tab is the one refused…
  await staffSignsIn(adminTab);
  expect((await customerTab.goto('/area-clienti/ordini/'))?.status()).toBe(403);

  // …until «Esci» from the back office: the remembered device opens the customer area again
  await adminTab.getByRole('button', { name: 'Esci' }).click();
  await expect(adminTab.getByRole('heading', { level: 1 })).toHaveText('Login');
  expect((await customerTab.goto('/area-clienti/ordini/'))?.status()).toBe(200);
  await expect(customerTab.getByRole('heading', { level: 1 })).toHaveText('I miei ordini');
  await context.close();
});

test('[ADM-01] [CUS-01] two browsers stay signed in side by side, as the office and the customers on their own devices', async ({ browser, baseURL }) => {
  const office = await browser.newContext();
  const customerDevice = await browser.newContext();
  const officePage = await newTab(office, baseURL!);
  const customerPage = await newTab(customerDevice, baseURL!);

  await staffSignsIn(officePage);
  await customerSignsIn(customerPage);
  expect((await officePage.goto('/gestione/ordini/'))?.status()).toBe(200);
  expect((await customerPage.goto('/area-clienti/ordini/'))?.status()).toBe(200);

  // «Esci» on the office computer leaves the customer signed in
  await officePage.getByRole('button', { name: 'Esci' }).click();
  await expect(officePage.getByRole('heading', { level: 1 })).toHaveText('Login');
  expect((await customerPage.goto('/area-clienti/ordini/'))?.status()).toBe(200);
  await expect(customerPage.getByRole('heading', { level: 1 })).toHaveText('I miei ordini');
  await office.close();
  await customerDevice.close();
});

test('[ADM-01] the back office does not look like the customer area', async ({ browser, baseURL }) => {
  const context = await browser.newContext();
  const page = await newTab(context, baseURL!);
  const problems = await PageProblems.watch(page);

  await page.goto('/area-clienti/');
  const customerArea = await look(page);
  expect(customerArea.line).toBe('none');

  await page.goto('/gestione/');
  const backOfficeLogin = await look(page);
  expect(backOfficeLogin.background).not.toBe(customerArea.background);
  expect(backOfficeLogin.line).not.toBe('none');
  await expectAccessible(page);

  await staffSignsIn(page);
  expect(await look(page)).toEqual(backOfficeLogin);
  await expectAccessible(page);
  await page.goto('/gestione/ordini/');
  expect(await look(page)).toEqual(backOfficeLogin);
  await expectAccessible(page);
  problems.expectNone();
  await context.close();
});
