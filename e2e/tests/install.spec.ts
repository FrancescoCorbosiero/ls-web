import { devices, expect, test, type Page } from '@playwright/test';
import { linkIn, waitForMail } from '../support/mail';
import { PageProblems, offline } from '../support/browser';

/**
 * The customer area installs on a phone as an app (ADR 0047). Chromium's own manifest parser
 * (DevTools Protocol) finds the customer manifest from the customer pages only, with the values
 * that make the area an installable standalone app; on a phone the orders page explains how to
 * add it to the home screen, on a desktop it does not. The customer is dev-seed's pending one,
 * which the other specs leave alone.
 */
const customerEmail = 'cliente.in-attesa@cliente.example';

async function customerSignsIn(page: Page): Promise<void> {
  await page.goto('/area-clienti/');
  await page.locator('#email').fill(customerEmail);
  await page.getByRole('button', { name: 'Ricevi il link' }).click();
  await expect(page.getByText("Ti abbiamo inviato un'email")).toBeVisible();
  const mail = await waitForMail({ to: customerEmail, subject: /link per accedere/ });
  await page.goto(linkIn(mail, '/area-clienti/accesso'));
  await page.getByRole('button', { name: 'Accedi' }).click();
  await expect(page).toHaveURL(/\/area-clienti\/ordini\/$/);
}

test('[NFR-01] Chromium reads the customer area, and only it, as an installable standalone app', async ({ browser, baseURL }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await offline(page, baseURL!);
  const problems = await PageProblems.watch(page);
  const devtools = await context.newCDPSession(page);

  await page.goto('/area-clienti/');
  const read = await devtools.send('Page.getAppManifest');
  expect(read.url).toBe(new URL('/area-clienti.webmanifest', baseURL).href);
  expect(read.errors).toEqual([]);
  const manifest = JSON.parse(read.data ?? '{}');
  expect(manifest).toMatchObject({
    id: '/area-clienti/',
    start_url: '/area-clienti/',
    scope: '/area-clienti/',
    display: 'standalone',
  });
  expect(manifest.short_name.length).toBeLessThanOrEqual(12);
  const sizes = manifest.icons.map((icon: { sizes: string }) => icon.sizes);
  expect(sizes).toEqual(expect.arrayContaining(['192x192', '512x512']));
  for (const icon of manifest.icons) {
    const answer = await page.request.get(icon.src);
    expect(answer.status(), icon.src).toBe(200);
    expect(answer.headers()['content-type']).toBe('image/png');
  }

  await page.goto('/gestione/');
  expect((await devtools.send('Page.getAppManifest')).url, 'the back office is not an app').toBe('');
  await page.goto('/');
  expect((await devtools.send('Page.getAppManifest')).url).toBe(new URL('/site.webmanifest', baseURL).href);
  problems.expectNone();
  await context.close();
});

test('[NFR-01] on a phone the orders page explains how to add the app to the home screen, on a desktop it does not', async ({ browser, baseURL }) => {
  const phone = await browser.newContext({ ...devices['Pixel 7'] });
  const phonePage = await phone.newPage();
  await offline(phonePage, baseURL!);
  const problems = await PageProblems.watch(phonePage);
  await customerSignsIn(phonePage);

  const hint = phonePage.locator('[data-install-hint]');
  await expect(hint).toBeVisible();
  await hint.getByText("Usa l'area clienti come un'app").click();
  await expect(hint).toContainText('Aggiungi alla schermata Home');
  await expect(hint).toContainText('Installa app');
  await expect(hint.getByRole('link', { name: 'Imposta la password' })).toBeVisible();
  problems.expectNone();

  // the same signed-in customer on a desktop browser: no hint
  const desktop = await browser.newContext({ storageState: await phone.storageState() });
  const desktopPage = await desktop.newPage();
  await offline(desktopPage, baseURL!);
  await desktopPage.goto('/area-clienti/ordini/');
  await expect(desktopPage.getByRole('heading', { level: 1 })).toHaveText('I miei ordini');
  await expect(desktopPage.locator('[data-install-hint]')).toBeHidden();
  await phone.close();
  await desktop.close();
});
