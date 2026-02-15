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

export { type TranslationKeys };
