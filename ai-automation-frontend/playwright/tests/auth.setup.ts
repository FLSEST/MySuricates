import { test as setup } from '@playwright/test';
import { mkdirSync } from 'fs';
import { getApiKey } from '../fixtures/api-helpers';

setup('inject auth into localStorage', async ({ page }) => {
  const apiKey = getApiKey();

  await page.goto('/');

  // Inject API key and locale into localStorage (same pattern as Cypress e2e.ts)
  await page.evaluate(
    ({ key }) => {
      localStorage.setItem(
        'app-settings',
        JSON.stringify({ state: { apiKey: key, advancedMode: false }, version: 0 }),
      );
      localStorage.setItem(
        'i18n-locale',
        JSON.stringify({ state: { locale: 'en' }, version: 0 }),
      );
    },
    { key: apiKey },
  );

  // Ensure auth directory exists
  mkdirSync('playwright/.auth', { recursive: true });

  // Save storage state for other tests to reuse
  await page.context().storageState({ path: 'playwright/.auth/state.json' });
});
