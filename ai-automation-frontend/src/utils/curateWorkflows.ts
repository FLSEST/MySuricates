import type { Workflow } from '../types';

export interface CuratedWorkflows {
  curated: Workflow[];
  rest: Workflow[];
}

/**
 * "Masse und Ausnahme separieren": curated = all pinned ∪ top-by-runCount up to `limit`.
 * Pure function. `rest` (sorted by name) holds everything not in curated.
 */
export function curateWorkflows(
  workflows: Workflow[],
  pinnedIds: string[],
  limit = 6,
): CuratedWorkflows {
  const pinnedSet = new Set(pinnedIds);
  const pinned = workflows.filter((w) => pinnedSet.has(w.id));
  const unpinnedByRuns = workflows
    .filter((w) => !pinnedSet.has(w.id))
    .sort((a, b) => b.runCount - a.runCount);

  const fillCount = Math.max(0, limit - pinned.length);
  const curated = [...pinned, ...unpinnedByRuns.slice(0, fillCount)];
  const rest = unpinnedByRuns
    .slice(fillCount)
    .sort((a, b) => a.name.localeCompare(b.name));

  return { curated, rest };
}
