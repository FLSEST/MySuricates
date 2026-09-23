import type { TranslationKeys } from '../i18n';

/** One language of a localized map ({de, en}); same fallback as the backend: locale, then English, then German. */
export function resolveTemplateName(name: Record<string, string>, locale: string): string {
  return name[locale] || name['en'] || name['de'] || Object.values(name)[0] || '';
}

/** A template or pool category in the UI language; a category this build does not know shows as is. */
export function categoryLabel(t: TranslationKeys, category: string): string {
  return t.templates.categories[category as keyof TranslationKeys['templates']['categories']] ?? category;
}
