import './commands';
import { apiKey, setApiKey } from './target';

/**
 * The API key is sensitive: Cypress 16 hands it out only through the command cy.env(). Read it once
 * per spec, before the spec's own hooks create test data or visit a page.
 */
before(() => {
  cy.env(['API_KEY']).then(({ API_KEY }) => setApiKey(API_KEY));
});

/**
 * Inject API key, locale and the seen welcome tour into localStorage before each page load.
 *
 * The 'window:before:load' event fires before the app's JavaScript runs,
 * ensuring Zustand rehydrates from localStorage with the API key already set.
 * Without this, the ApiKeyGuard would show the login screen, and the welcome tour
 * (fresh browser storage in every test) would cover the page.
 */
Cypress.on('window:before:load', (win) => {
  const key = apiKey();
  // Settings a spec stored itself before the visit (e.g. advancedMode) are kept.
  let stored: { state?: Record<string, unknown> } = {};
  try {
    stored = JSON.parse(win.localStorage.getItem('app-settings') ?? '{}');
  } catch {
    stored = {};
  }
  win.localStorage.setItem(
    'app-settings',
    JSON.stringify({
      state: {
        advancedMode: false,
        ...stored.state,
        ...(key ? { apiKey: key } : {}),
        walkthroughSeen: true,
        simpleOnboardingSeen: true,
      },
      version: 0,
    }),
  );
  win.localStorage.setItem(
    'i18n-locale',
    JSON.stringify({ state: { locale: 'en' }, version: 0 }),
  );
});
