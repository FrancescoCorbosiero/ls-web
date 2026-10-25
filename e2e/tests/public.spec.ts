import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { PageProblems, expectNoHorizontalScroll, offline } from '../support/browser';

/** Every indexable page of the public site plus /grazie/ (SitePages), as the sitemap lists them. */
const PUBLIC_PAGES = [
  '/',
  '/servizi/',
  '/servizi/spedizione-pallet/',
  '/servizi/groupage/',
  '/servizi/carico-completo/',
  '/servizi/magazzino/',
  '/zone/',
  '/zone/milano/',
  '/zone/monza-brianza/',
  '/zone/como/',
  '/zone/lecco/',
  '/zone/bergamo/',
  '/tracking/',
  '/preventivo/',
  '/contatti/',
  '/chi-siamo/',
  '/faq/',
  '/blog/',
  '/blog/quanto-costa-spedire-un-pallet/',
  '/blog/misure-pallet-epal-industriale/',
  '/blog/come-preparare-un-pallet-per-la-spedizione/',
  '/blog/groupage-o-carico-completo/',
  '/privacy/',
  '/cookie/',
  '/grazie/',
];

/** The entry pages of the two private areas: nonce CSP, no scripts beyond the allowed ones. */
const PRIVATE_ENTRY_PAGES = ['/area-clienti/', '/gestione/login'];

test.describe('public site smoke', () => {
  for (const url of PUBLIC_PAGES) {
    test(`[PUB-01] ${url} answers 200 without console errors or CSP violations`, async ({ page, baseURL }) => {
      await offline(page, baseURL!);
      const problems = await PageProblems.watch(page);
      const response = await page.goto(url);
      expect(response?.status()).toBe(200);
      expect(response?.headers()['content-security-policy']).toContain("default-src 'self'");
      await expect(page).toHaveTitle(/\S/);
      await expect(page.locator('main, [role=main], body > header').first()).toBeVisible();
      await page.waitForLoadState('networkidle');
      await expectNoHorizontalScroll(page);
      problems.expectNone();
    });
  }

  for (const url of PRIVATE_ENTRY_PAGES) {
    test(`[NFR-02] ${url} is served with the nonce CSP and raises no violation`, async ({ page, baseURL }) => {
      await offline(page, baseURL!);
      const problems = await PageProblems.watch(page);
      const response = await page.goto(url);
      expect(response?.status()).toBe(200);
      const csp = response?.headers()['content-security-policy'] ?? '';
      expect(csp).toMatch(/script-src 'self' 'nonce-[A-Za-z0-9+/=_-]+'/);
      expect(csp).not.toContain('unsafe-');
      expect(response?.headers()['cache-control']).toContain('no-store');
      await expect(page.getByRole('button', { name: /Avanti|Accedi/ })).toBeVisible();
      await page.waitForLoadState('networkidle');
      await expectNoHorizontalScroll(page);
      problems.expectNone();
    });
  }

  test('[PUB-04] the legacy URLs redirect once with 301 to their new address', async ({ request }) => {
    const cases: Array<[string, string]> = [
      ['/index.html', '/'],
      ['/zone?cap=20831', '/zone/?cap=20831'],
      ['/servizi/spedizione-pallet', '/servizi/spedizione-pallet/'],
    ];
    for (const [from, to] of cases) {
      const response = await request.get(from, { maxRedirects: 0 });
      expect(response.status(), from).toBe(301);
      expect(new URL(response.headers()['location'], 'http://localhost').pathname + new URL(response.headers()['location'], 'http://localhost').search, from).toBe(to);
    }
    const missing = await request.get('/pagina-che-non-esiste/');
    expect(missing.status()).toBe(404);
  });

  test('[PUB-09] sitemap.xml lists every indexable page and robots.txt points at it', async ({ request }) => {
    const sitemap = await request.get('/sitemap.xml');
    expect(sitemap.status()).toBe(200);
    const xml = await sitemap.text();
    const locations = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => new URL(m[1]).pathname);
    const indexable = PUBLIC_PAGES.filter((u) => u !== '/grazie/');
    expect(locations.sort()).toEqual([...indexable].sort());
    const robots = await request.get('/robots.txt');
    expect(robots.status()).toBe(200);
    expect(await robots.text()).toMatch(/Sitemap: https?:\/\/\S+\/sitemap\.xml/);
  });
});

test.describe('accessibility (axe)', () => {
  const AXE_PAGES = ['/', '/servizi/spedizione-pallet/', '/preventivo/', '/tracking/', '/contatti/'];

  /**
   * Findings inherited from the verbatim legacy site (ls-home 9b78785, PUB-01: pages and CSS are
   * served unchanged). Listed one by one with rule id and selector so that anything else fails
   * the run; each line is an open point for the owner (changing the colour is a PUB-01 allow-list
   * change, docs/phase1/log.md step C).
   */
  const KNOWN_LEGACY_VIOLATIONS: Array<{ id: string; target: string; why: string }> = [
    {
      id: 'color-contrast',
      target: 'span[data-zone="3"]',
      why: 'the «72 ore» pill of the delivery-times table: white text on the legacy zone-3 colour (.eta-pill[data-zone="3"], main.css) measures below 4.5:1',
    },
  ];

  function blockingViolations(violations: Awaited<ReturnType<AxeBuilder['analyze']>>['violations']): string[] {
    const out: string[] = [];
    for (const v of violations) {
      if (v.impact !== 'serious' && v.impact !== 'critical') continue;
      const nodes = v.nodes.filter(
        (n) => !KNOWN_LEGACY_VIOLATIONS.some((k) => k.id === v.id && n.target.join(' ') === k.target),
      );
      if (nodes.length > 0) out.push(`${v.id} (${v.impact}): ${nodes.map((n) => n.target.join(' ')).join(', ')}`);
    }
    return out;
  }

  for (const url of AXE_PAGES) {
    test(`[PUB-10] axe finds no serious or critical violation on ${url}`, async ({ page, baseURL }) => {
      await offline(page, baseURL!);
      // The settled page is what matters: the reveal animation (opacity 0 → 1) is skipped as the
      // site's own prefers-reduced-motion rule does, so axe never samples a half-faded element.
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.goto(url);
      await page.waitForLoadState('networkidle');
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'best-practice']).analyze();
      expect(blockingViolations(results.violations)).toEqual([]);
    });
  }

  for (const url of PRIVATE_ENTRY_PAGES) {
    test(`[NFR-01] axe finds no serious or critical violation on ${url}`, async ({ page, baseURL }) => {
      await offline(page, baseURL!);
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.goto(url);
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'best-practice']).analyze();
      expect(blockingViolations(results.violations)).toEqual([]);
    });
  }
});
