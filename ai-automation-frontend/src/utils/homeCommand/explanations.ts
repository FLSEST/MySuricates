import { countHits } from './query';
import type { ParsedQuery } from './types';
import type { HintId } from '../../data/hints';
import type { FeatureModuleName } from '../../types';
import { navItems } from '../navItems';

/**
 * Hints that explain a module's page — they never appear while the module is off. Taken from the
 * sidebar: each entry names its page's hint and module (TODO 1df94028).
 */
export const HINT_MODULES: Partial<Record<HintId, FeatureModuleName>> = Object.fromEntries(
  navItems.filter((item) => item.module).map((item) => [item.hint, item.module]),
);

export interface HintEntry {
  id: HintId;
  title: string;
  points: readonly string[];
}

/**
 * Hints whose copy (in the UI language) contains the query's words — title hits weigh three times
 * the bullet hits. Words shorter than three letters are ignored: explanations are prose and would
 * match nearly anything. `disabled` are the modules reported off. Pure function.
 */
export function matchHints(query: ParsedQuery, hints: readonly HintEntry[], disabled: ReadonlySet<string>): HintId[] {
  const tokens = query.tokens.filter((tok) => tok.length >= 3);
  if (tokens.length === 0) return [];
  const scored = hints
    .filter((h) => {
      const module = HINT_MODULES[h.id];
      return !module || !disabled.has(module);
    })
    .map((h) => ({ id: h.id, score: countHits(tokens, h.title) * 3 + countHits(tokens, h.points.join(' ')) }));
  return scored.filter((s) => s.score > 0).sort((a, b) => b.score - a.score).map((s) => s.id);
}
