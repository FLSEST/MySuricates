import type { ModelStats } from '../../types';

/** The detailed API has multiple rows per reported model; Simple combines their tokens again. */
export function aggregateModelTokens(data: ModelStats[]) {
  const totals = new Map<string | null, number>();
  for (const entry of data) {
    const model = entry.model?.trim() || null;
    totals.set(model, (totals.get(model) ?? 0) + entry.totalTokens);
  }
  return Array.from(totals, ([model, tokens]) => ({ model, tokens }))
    .sort((a, b) => b.tokens - a.tokens);
}
