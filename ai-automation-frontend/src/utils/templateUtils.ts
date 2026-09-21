/** One language of a localized map ({de, en}); same fallback as the backend: locale, then English, then German. */
export function resolveTemplateName(name: Record<string, string>, locale: string): string {
  return name[locale] || name['en'] || name['de'] || Object.values(name)[0] || '';
}
