import de from '../data/modules.de.json';
import en from '../data/modules.en.json';
import { useI18nStore } from './index';

/** Display text for one module or hardening switch. */
export interface ModuleText {
  name: string;
  description: string;
}

/**
 * Module texts live in `src/data/modules.<lang>.json`, one file per language — so a further
 * language is a new file plus one line here, no code change anywhere else. The backend's
 * `ModuleCatalogTextsTest` guards that every `ModuleCatalog` key is described in every language
 * and generates `scripts/lib/module-catalog.generated.sh` for the setup script from the same files.
 */
const MODULE_TEXTS: Record<string, Record<string, ModuleText>> = { de, en };

/** Language used when the requested one has no file. */
export const MODULE_TEXT_FALLBACK = 'en';

/** Languages the module texts exist in. */
export const MODULE_TEXT_LANGUAGES: readonly string[] = Object.keys(MODULE_TEXTS);

/**
 * Name and description of a module in `lang`. Unknown language ⇒ English,
 * unknown module ⇒ the key itself (a module the backend knows and this build does not).
 */
export function moduleText(key: string, lang: string): ModuleText {
  const fallback = MODULE_TEXTS[MODULE_TEXT_FALLBACK] ?? {};
  const catalog = MODULE_TEXTS[lang] ?? fallback;
  return catalog[key] ?? fallback[key] ?? { name: key, description: '' };
}

/** Just the display name — the common case in the UI. */
export function moduleName(key: string, lang: string): string {
  return moduleText(key, lang).name;
}

/** Module texts in the locale the user selected. */
export function useModuleText(): (key: string) => ModuleText {
  const locale = useI18nStore((s) => s.locale);
  return (key: string) => moduleText(key, locale);
}

/** Module names in the locale the user selected. */
export function useModuleName(): (key: string) => string {
  const locale = useI18nStore((s) => s.locale);
  return (key: string) => moduleName(key, locale);
}
