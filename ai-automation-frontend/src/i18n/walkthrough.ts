import de from '../data/walkthrough.de.json';
import en from '../data/walkthrough.en.json';
import { useI18nStore } from './index';

/** Title and value points of one walkthrough panel. */
export interface WalkthroughText {
  title: string;
  points: string[];
}

/**
 * Panel copy lives in `src/data/walkthrough.<lang>.json`, one file per language — same shape as
 * the module texts, so a further language is a new file plus one line here.
 *
 * Unlike the rest of the app, **English is the source here and German the translation**: the
 * public presence these texts also feed is English, and the short panel lines carry better in it.
 * That is the opposite direction from `README.de.md`, deliberately.
 */
const WALKTHROUGH_TEXTS: Record<string, Record<string, WalkthroughText>> = { de, en };

/** Language used when the requested one has no file. */
export const WALKTHROUGH_TEXT_FALLBACK = 'en';

/** Languages the panel copy exists in. */
export const WALKTHROUGH_LANGUAGES: readonly string[] = Object.keys(WALKTHROUGH_TEXTS);

/** Copy of one panel in `lang`. Unknown language ⇒ English, unknown panel ⇒ empty. */
export function walkthroughText(id: string, lang: string): WalkthroughText {
  const fallback = WALKTHROUGH_TEXTS[WALKTHROUGH_TEXT_FALLBACK] ?? {};
  const catalog = WALKTHROUGH_TEXTS[lang] ?? fallback;
  return catalog[id] ?? fallback[id] ?? { title: id, points: [] };
}

/** Panel copy in the locale the user selected. */
export function useWalkthroughText(): (id: string) => WalkthroughText {
  const locale = useI18nStore((s) => s.locale);
  return (id: string) => walkthroughText(id, locale);
}
