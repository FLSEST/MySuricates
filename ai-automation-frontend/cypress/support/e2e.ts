import './commands';

// Force English locale before each test
beforeEach(() => {
  localStorage.setItem('i18n-locale', JSON.stringify({ state: { locale: 'en' }, version: 0 }));
});
