import { expect, type Page } from '@playwright/test';

/** Everything that is not the app under test is answered with an empty 204 (no network in CI). */
export async function offline(page: Page, baseURL: string): Promise<void> {
  await page.route((url) => !url.href.startsWith(baseURL), (route) => route.fulfill({ status: 204, body: '' }));
}

/** Collects console errors, uncaught exceptions and CSP violations of every document of the page. */
export class PageProblems {
  readonly problems: string[] = [];

  private constructor(private readonly page: Page) {}

  static async watch(page: Page): Promise<PageProblems> {
    const watcher = new PageProblems(page);
    await page.addInitScript(() => {
      document.addEventListener('securitypolicyviolation', (e) => {
        console.error(`CSP violation: ${e.violatedDirective} blocked ${e.blockedURI || 'inline'} at ${e.sourceFile}:${e.lineNumber}`);
      });
    });
    page.on('console', (message) => {
      if (message.type() === 'error') {
        watcher.problems.push(message.text());
      }
    });
    page.on('pageerror', (error) => watcher.problems.push(`uncaught: ${error.message}`));
    return watcher;
  }

  expectNone(): void {
    expect(this.problems, `console errors or CSP violations on ${this.page.url()}`).toEqual([]);
  }
}

/** No horizontal page scroll at the current viewport (phone usability, NFR-01). */
export async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const widths = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    viewport: window.innerWidth,
  }));
  expect(widths.scroll, `page wider than the viewport on ${page.url()}`).toBeLessThanOrEqual(widths.viewport);
}
