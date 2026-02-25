export function resolveTemplateName(name: Record<string, string>, locale: string): string {
  return name[locale] || name['de'] || Object.values(name)[0] || '';
}
