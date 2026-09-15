import { useNavigate } from 'react-router-dom';
import { Clock } from 'lucide-react';
import { Card, CardHeader } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { useDashboard } from '../../hooks/useLearning';
import { useT, useI18nStore } from '../../i18n';
import { formatDuration, formatRelativeTime, formatDateTime } from '../../utils/formatTime';
import type { WorkflowRun } from '../../types';
import { WidgetSkeleton } from './WidgetSkeleton';

export function RecentRunsCard() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const { data: stats, isLoading } = useDashboard();
  if (isLoading) return <WidgetSkeleton size="twoThirds" />;

  return (
    <Card padding={false}>
      <div className="p-5 pb-0">
        <CardHeader title={t.dashboard.recentRuns} />
      </div>
      {!stats?.recentRuns?.length ? (
        <div className="px-5 py-12 text-center text-sm text-text-tertiary">
          {t.dashboard.noRecentRuns}
        </div>
      ) : (
        <div className="divide-y divide-border">
          {stats.recentRuns.slice(0, 8).map((run: WorkflowRun) => (
            <div
              key={run.id}
              className="flex items-center justify-between px-5 py-3 hover:bg-surface transition-colors cursor-pointer"
              onClick={() => navigate(`/workflows/${run.workflowId}/run/${run.id}`)}
            >
              <div className="flex items-center gap-3 min-w-0">
                <StatusBadge status={run.status} />
                <div className="min-w-0">
                  <span className="text-sm font-medium text-text truncate block">
                    {run.workflowName}
                  </span>
                  {run.projectName && (
                    <span className="text-xs text-accent/70 truncate block">{run.projectName}</span>
                  )}
                </div>
              </div>
              <div className="flex items-center gap-4 shrink-0 text-xs text-text-tertiary">
                <span className="flex items-center gap-1">
                  <Clock className="w-3 h-3" />
                  {formatDuration(run.durationMs)}
                </span>
                <span>{run.startedAt ? `${formatDateTime(run.startedAt, locale)} · ${formatRelativeTime(run.startedAt, locale)}` : '-'}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
