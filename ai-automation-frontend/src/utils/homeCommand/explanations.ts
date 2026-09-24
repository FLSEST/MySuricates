import { countHits } from './query';
import type { ParsedQuery } from './types';
import type { HintId } from '../../data/hints';
import type { FeatureModuleName } from '../../types';

/** Hints that explain a module's page — they never appear while the module is off. */
export const HINT_MODULES: Partial<Record<HintId, FeatureModuleName>> = {
  'wishes-loop': 'wishes',
  'factory-create': 'factory',
  'chains-sequence': 'chains',
  'skills-packages': 'skills',
  'webhooks-trigger': 'webhooks',
  'integrations-connect': 'integrations',
  'debug-failing': 'debug',
  'audit-findings': 'audit',
  'insights-numbers': 'learning',
  'onboarding-profiles': 'onboarding-profiles',
};

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
