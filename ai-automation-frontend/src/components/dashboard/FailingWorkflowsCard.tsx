import { AlertTriangle } from 'lucide-react';
import { useMemo } from 'react';
import { Card, CardHeader } from '../ui';
import { useT } from '../../i18n';
import type { WorkflowRun } from '../../types';

interface FailingWorkflowsCardProps {
  recentRuns?: WorkflowRun[];
}

interface WorkflowFailStats {
  name: string;
  total: number;
  failed: number;
  rate: number;
}

export function FailingWorkflowsCard({ recentRuns }: FailingWorkflowsCardProps) {
  const t = useT();

  const failingWorkflows = useMemo<WorkflowFailStats[]>(() => {
    if (!recentRuns?.length) return [];

    // Group runs by workflow name
    const groups = new Map<string, { total: number; failed: number }>();
    for (const run of recentRuns) {
      const name = run.workflowName;
      const entry = groups.get(name) ?? { total: 0, failed: 0 };
      entry.total++;
      if (run.status === 'FAILED') entry.failed++;
      groups.set(name, entry);
    }

    return Array.from(groups.entries())
      .map(([name, { total, failed }]) => ({
        name,
        total,
        failed,
        rate: total > 0 ? failed / total : 0,
      }))
      .filter((wf) => wf.failed > 0)
      .sort((a, b) => b.rate - a.rate || b.failed - a.failed)
      .slice(0, 5);
  }, [recentRuns]);

  return (
    <Card>
      <CardHeader title={t.dashboard.failingWorkflows} />
      {failingWorkflows.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 text-text-tertiary">
          <AlertTriangle className="w-7 h-7 mb-2 opacity-40" />
          <p className="text-xs">{t.dashboard.noFailingWorkflows}</p>
        </div>
      ) : (
        <div className="space-y-2.5">
          {failingWorkflows.map((wf) => (
            <div key={wf.name}>
              <div className="flex items-center justify-between gap-2 mb-1">
                <span className="text-xs text-text truncate">{wf.name}</span>
                <span className="text-[10px] text-danger shrink-0 font-medium">
                  {Math.round(wf.rate * 100)}%
                </span>
              </div>
              {/* Mini progress bar */}
              <div className="h-1 rounded-full bg-surface overflow-hidden">
                <div
                  className="h-full rounded-full bg-danger transition-all duration-500"
                  style={{ width: `${wf.rate * 100}%` }}
                />
              </div>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
