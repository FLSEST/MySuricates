import type { Workflow } from '../types';

export interface WorkflowMatch {
  workflow: Workflow;
  score: number;
}

/**
 * Ranks workflows against a free-text intent. Pure function, no I/O.
 * Score = token hits in name/description/tags (+ extra weight for name hits),
 * with runCount as a small tie-breaker. Non-matches (score 0) are dropped.
 */
export function matchWorkflows(intent: string, workflows: Workflow[]): WorkflowMatch[] {
  const q = intent.trim().toLowerCase();
  if (!q) return [];
  const tokens = q.split(/\s+/).filter(Boolean);

  const scored = workflows.map((w) => {
    const name = w.name.toLowerCase();
    const haystack = [w.name, w.description ?? '', ...(w.tags ?? [])].join(' ').toLowerCase();
    let score = 0;
    for (const tok of tokens) {
      if (haystack.includes(tok)) score += 1;
      if (name.includes(tok)) score += 1; // name hits weigh double
    }
    if (score > 0) score += Math.min(w.runCount, 1000) / 10000; // tie-breaker
    return { workflow: w, score };
  });

  return scored
    .filter((m) => m.score > 0)
    .sort((a, b) => b.score - a.score);
}
