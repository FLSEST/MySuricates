import './commands';

/**
 * Inject API key and locale into localStorage before each page load.
 *
 * The 'window:before:load' event fires before the app's JavaScript runs,
 * ensuring Zustand rehydrates from localStorage with the API key already set.
 * Without this, the ApiKeyGuard would show the login screen.
 */
Cypress.on('window:before:load', (win) => {
  const apiKey = Cypress.env('API_KEY');
  if (apiKey) {
    win.localStorage.setItem(
      'app-settings',
      JSON.stringify({ state: { apiKey, advancedMode: false }, version: 0 }),
    );
  }
  win.localStorage.setItem(
    'i18n-locale',
    JSON.stringify({ state: { locale: 'en' }, version: 0 }),
  );
});
