import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { en, type TranslationKeys } from './en';
import { de } from './de';

export type Locale = 'en' | 'de';

const translations: Record<Locale, TranslationKeys> = { en, de };

interface I18nState {
  locale: Locale;
  setLocale: (locale: Locale) => void;
}

export const useI18nStore = create<I18nState>()(
  persist(
    (set) => ({
      locale: 'en',
      setLocale: (locale) => set({ locale }),
    }),
    { name: 'i18n-locale' }
  )
);

/**
 * Keeps `<html lang>` on the app language (screen readers, hyphenation, spell checking) — index.html
 * only carries the default until this runs (e86f6544). Returns the unsubscribe function.
 */
export function syncDocumentLanguage(): () => void {
  const apply = (locale: Locale) => {
    document.documentElement.lang = locale;
  };
  apply(useI18nStore.getState().locale);
  return useI18nStore.subscribe((state) => apply(state.locale));
}

/**
 * Hook to access translations.
 *
 * Usage:
 *   const t = useT();
 *   <h1>{t.dashboard.title}</h1>
 */
export function useT(): TranslationKeys {
  const locale = useI18nStore((s) => s.locale);
  return translations[locale];
}

/**
 * Non-hook accessor for the current locale's translations.
 * For use in class components (e.g. ErrorBoundary) and other non-React contexts
 * where hooks are unavailable. Reads the persisted locale synchronously.
 */
export function getTranslations(): TranslationKeys {
  return translations[useI18nStore.getState().locale];
}

export { type TranslationKeys };
