import type { RunStatus, WorkflowRunListItem } from '../types';

/** Most-recent (by startedAt) run status per workflowId. Pure function. */
export function latestRunStatusMap(runs: WorkflowRunListItem[]): Map<string, RunStatus> {
  const latest = new Map<string, { status: RunStatus; startedAt: string }>();
  for (const r of runs) {
    const prev = latest.get(r.workflowId);
    if (!prev || r.startedAt > prev.startedAt) {
      latest.set(r.workflowId, { status: r.status, startedAt: r.startedAt });
    }
  }
  const out = new Map<string, RunStatus>();
  for (const [id, v] of latest) out.set(id, v.status);
  return out;
}
