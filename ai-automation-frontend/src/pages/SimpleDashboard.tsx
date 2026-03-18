import { useNavigate } from 'react-router-dom';
import { GitBranch, TrendingUp, Zap, Clock } from 'lucide-react';
import { Card, CardHeader, Button, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import { useDashboard } from '../hooks/useLearning';
import { useT, useI18nStore } from '../i18n';
import { formatDuration, formatRelativeTime } from '../utils/formatTime';
import type { WorkflowRun } from '../types';

export function SimpleDashboard() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const { data: dashboard, isLoading, error, refetch } = useDashboard();

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  const stats = dashboard;

  return (
    <div className="space-y-6 animate-fade-in max-w-2xl">
      <div>
        <h1 className="text-2xl font-bold text-text">{t.dashboard.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.dashboard.subtitle}</p>
      </div>

      {/* Key stats */}
      <div className="grid grid-cols-3 gap-3">
        <Card className="flex items-center gap-3 py-3! px-4!">
          <div className="p-2 rounded-lg bg-success-subtle">
            <GitBranch className="w-4 h-4 text-success" />
          </div>
          <div className="min-w-0">
            <p className="text-xs text-text-secondary truncate">{t.dashboard.totalWorkflows}</p>
            <p className="text-lg font-bold text-text">{stats?.totalWorkflows ?? 0}</p>
          </div>
        </Card>
        <Card className="flex items-center gap-3 py-3! px-4!">
          <div className="p-2 rounded-lg bg-warning-subtle">
            <Zap className="w-4 h-4 text-warning" />
          </div>
          <div className="min-w-0">
            <p className="text-xs text-text-secondary truncate">{t.dashboard.runsToday}</p>
            <p className="text-lg font-bold text-text">{stats?.runsToday ?? 0}</p>
          </div>
        </Card>
        <Card className="flex items-center gap-3 py-3! px-4!">
          <div className="p-2 rounded-lg bg-accent/10">
            <TrendingUp className="w-4 h-4 text-accent" />
          </div>
          <div className="min-w-0">
            <p className="text-xs text-text-secondary truncate">{t.dashboard.successRate}</p>
            <p className="text-lg font-bold text-text">
              {stats?.successRate != null ? `${(stats.successRate * 100).toFixed(0)}%` : '-'}
            </p>
          </div>
        </Card>
      </div>

      {/* Quick action */}
      <Button
        icon={<GitBranch className="w-4 h-4" />}
        onClick={() => navigate('/workflows')}
        className="w-full"
      >
        {t.workflows.create}
      </Button>

      {/* Recent runs */}
      <Card padding={false}>
        <div className="p-5 pb-0">
          <CardHeader title={t.dashboard.recentRuns} />
        </div>
        {!stats?.recentRuns?.length ? (
          <div className="px-5 py-10 text-center text-sm text-text-tertiary">
            {t.dashboard.noRecentRuns}
          </div>
        ) : (
          <div className="divide-y divide-border">
            {stats.recentRuns.slice(0, 6).map((run: WorkflowRun) => (
              <div
                key={run.id}
                className="flex items-center justify-between px-5 py-3 hover:bg-surface transition-colors cursor-pointer"
                onClick={() => navigate(`/workflows/${run.workflowId}/run/${run.id}`)}
              >
                <div className="flex items-center gap-3 min-w-0">
                  <StatusBadge status={run.status} />
                  <span className="text-sm font-medium text-text truncate">{run.workflowName}</span>
                </div>
                <div className="flex items-center gap-2 shrink-0 text-xs text-text-tertiary">
                  <Clock className="w-3 h-3" />
                  <span>{formatDuration(run.durationMs)}</span>
                  <span>{run.startedAt ? formatRelativeTime(run.startedAt, locale) : '-'}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
