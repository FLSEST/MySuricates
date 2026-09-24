import { afterEach, describe, expect, it } from 'vitest';
import { syncDocumentLanguage, useI18nStore } from '..';

// <html lang> stood fixed on "en" and ignored the language switch (e86f6544) — screen readers read
// the German UI with English pronunciation, hyphenation followed the wrong language.
describe('<html lang>', () => {
  let stop: (() => void) | undefined;
  afterEach(() => {
    stop?.();
    stop = undefined;
    useI18nStore.setState({ locale: 'en' });
  });

  it('starts with the persisted app language', () => {
    useI18nStore.setState({ locale: 'de' });
    document.documentElement.lang = 'en';

    stop = syncDocumentLanguage();

    expect(document.documentElement.lang).toBe('de');
  });

  it('follows every language switch', () => {
    stop = syncDocumentLanguage();

    useI18nStore.getState().setLocale('de');
    expect(document.documentElement.lang).toBe('de');
    useI18nStore.getState().setLocale('en');
    expect(document.documentElement.lang).toBe('en');
  });

  it('stops following once unsubscribed', () => {
    stop = syncDocumentLanguage();
    stop();
    stop = undefined;

    useI18nStore.getState().setLocale('de');
    expect(document.documentElement.lang).toBe('en');
  });
});
