import { countHits, matchesKeyword } from './query';
import type { ParsedQuery } from './types';

/** The fixed actions and settings the command field offers. */
export type ActionId = 'locale' | 'theme' | 'create-workflow' | 'create-project' | 'settings' | 'hints' | 'pro';

/**
 * Keywords per action (de + en, normalized, `*` = prefix — see `matchesKeyword`). The label in the
 * UI language is searched as well, so the list only needs the words a label does not contain.
 */
export const ACTION_KEYWORDS: Record<ActionId, readonly string[]> = {
  locale: ['sprache*', 'language*', 'englisch', 'english', 'deutsch', 'german', 'locale'],
  theme: ['dunkel*', 'hell', 'heller', 'dark*', 'light', 'lighter', 'theme*', 'farb*', 'nachtmodus', 'design'],
  'create-workflow': ['vorlage*', 'template*', 'workflow*', 'neu', 'neue*', 'anlegen', 'erstell*', 'create', 'new', 'pool'],
  'create-project': ['projekt*', 'project*', 'neu', 'neue*', 'anlegen', 'erstell*', 'create', 'new', 'ordner', 'folder'],
  settings: ['einstellung*', 'setting*', 'optionen', 'option*', 'konfig*', 'config*', 'preferences'],
  hints: ['hinweis*', 'hint*', 'tipp*', 'tips', 'hilfe', 'help', 'erklarung*'],
  pro: ['pro', 'profi*', 'expert*', 'modus', 'mode', 'bauen', 'build*', 'editor', 'andern', 'bearbeit*', 'edit*'],
};

/**
 * Actions whose keywords (weight 2) or label (weight 1) contain the query's words, best first.
 * `labels` are the action labels in the UI language. Pure function.
 */
export function matchActions(query: ParsedQuery, labels: Record<ActionId, string>): ActionId[] {
  if (query.tokens.length === 0) return [];
  const scored = (Object.keys(ACTION_KEYWORDS) as ActionId[]).map((id) => {
    const keywordHits = query.tokens.filter((tok) => ACTION_KEYWORDS[id].some((spec) => matchesKeyword(tok, spec))).length;
    return { id, score: keywordHits * 2 + countHits(query.tokens, labels[id]) };
  });
  return scored.filter((s) => s.score > 0).sort((a, b) => b.score - a.score).map((s) => s.id);
}
