import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end smoke of the Logistica Sanchez web app. The app is started by run.sh (local
 * profile, fresh database, mail sink on disk) and reached at E2E_BASE_URL. Titles carry the
 * requirement ID they cover ("[CUS-04] …"), which the parity-matrix generator reads from
 * test-results/results.json.
 */
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:8089';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  forbidOnly: !!process.env.CI,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    ['json', { outputFile: 'test-results/results.json' }],
  ],
  use: {
    baseURL,
    locale: 'it-IT',
    timezoneId: 'Europe/Rome',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      // NFR-01: the public pages and the two entry pages of the private areas at phone width.
      name: 'phone',
      testMatch: /public\.spec\.ts/,
      use: { ...devices['Pixel 7'] },
    },
  ],
});
