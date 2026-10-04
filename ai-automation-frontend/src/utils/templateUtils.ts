import type { TranslationKeys } from '../i18n';
import type { BlockDefinition } from '../types';

/** One language of a localized map ({de, en}); same fallback as the backend: locale, then English, then German. */
export function resolveTemplateName(name: Record<string, string>, locale: string): string {
  return name[locale] || name['en'] || name['de'] || Object.values(name)[0] || '';
}

/** A template or pool category in the UI language; a category this build does not know shows as is. */
export function categoryLabel(t: TranslationKeys, category: string): string {
  return t.templates.categories[category as keyof TranslationKeys['templates']['categories']] ?? category;
}

/** Prefix of a blueprint id — a `workflow_id` with it names a blueprint, not a workflow. */
export const BLUEPRINT_PREFIX = 'blueprint-';

/**
 * The blueprints a template's top-level sub-workflow blocks still name (TODO 093d2f11): a blueprint
 * package. The server installs those together with the template and wires the ids
 * (`POST /workflow-templates/{id}/install`) — a plain create would keep the name as `workflow_id`.
 */
export function blueprintPartIds(template: { blocks?: BlockDefinition[] | null }): string[] {
  const ids: string[] = [];
  for (const block of template.blocks ?? []) {
    const id = block.type === 'sub-workflow' ? (block.config as Record<string, unknown> | undefined)?.workflow_id : undefined;
    if (typeof id === 'string' && id.startsWith(BLUEPRINT_PREFIX) && !ids.includes(id)) ids.push(id);
  }
  return ids;
}
