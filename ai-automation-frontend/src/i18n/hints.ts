import de from '../data/hints.de.json';
import en from '../data/hints.en.json';
import { useI18nStore } from './index';

/** Title and points of one hint. */
export interface HintText {
  title: string;
  points: string[];
}

/**
 * Hint copy lives in `src/data/hints.<lang>.json`, one file per language — the same shape as the
 * walkthrough copy, so a further language is a new file plus one line here.
 */
const HINT_TEXTS: Record<string, Record<string, HintText>> = { de, en };

/** Language used when the requested one has no file. */
export const HINT_TEXT_FALLBACK = 'en';

/** Languages the hint copy exists in. */
export const HINT_LANGUAGES: readonly string[] = Object.keys(HINT_TEXTS);

/** Copy of one hint in `lang`. Unknown language ⇒ English, unknown hint ⇒ its id as title. */
export function hintText(id: string, lang: string): HintText {
  const fallback = HINT_TEXTS[HINT_TEXT_FALLBACK] ?? {};
  const catalog = HINT_TEXTS[lang] ?? fallback;
  return catalog[id] ?? fallback[id] ?? { title: id, points: [] };
}

/** Copy of one hint in the locale the user selected. */
export function useHintText(id: string): HintText {
  const locale = useI18nStore((s) => s.locale);
  return hintText(id, locale);
}
