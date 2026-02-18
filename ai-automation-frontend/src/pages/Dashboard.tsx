import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  FolderKanban, GitBranch, Zap,
  TrendingUp, Clock, Coins,
} from 'lucide-react';
import { Card, CardHeader, Button, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import { RunsChart } from '../components/dashboard/RunsChart';
import { SuccessRateChart } from '../components/dashboard/SuccessRateChart';
import { useDashboard } from '../hooks/useLearning';
import { useProjects } from '../hooks/useProjects';
import { useT, useI18nStore } from '../i18n';
import { formatDuration, formatRelativeTime, formatDateTime } from '../utils/formatTime';
import type { WorkflowRun } from '../types';

function StatCard({ icon, label, value, accent }: {
  icon: React.ReactNode;
  label: string;
  value: string | number;
  accent?: string;
}) {
  return (
    <Card className="flex items-center gap-4">
      <div className={`p-3 rounded-xl ${accent ?? 'bg-accent/10'}`}>
        {icon}
      </div>
      <div>
        <p className="text-sm text-text-secondary">{label}</p>
        <p className="text-2xl font-bold text-text">{value}</p>
      </div>
    </Card>
  );
}


function useLiveClock() {
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
}

export function Dashboard() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const now = useLiveClock();
  const { data: dashboard, isLoading: dashLoading, error, refetch } = useDashboard();
  const { data: projects } = useProjects();

  const activeProjects = projects?.filter((p) => p.status === 'RUNNING').length ?? 0;

  if (dashLoading) {
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
    <div className="space-y-6 animate-fade-in">
      {/* Page header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.dashboard.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{t.dashboard.subtitle}</p>
        </div>
        <div className="text-right shrink-0">
          <p className="text-2xl font-bold text-text font-mono tabular-nums">
            {now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
          </p>
          <p className="text-sm text-text-secondary">
            {now.toLocaleDateString([], { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' })}
          </p>
        </div>
      </div>

      {/* Stat cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-5 gap-4 animate-stagger">
        <StatCard
          icon={<FolderKanban className="w-5 h-5 text-accent" />}
          label={t.dashboard.activeProjects}
          value={activeProjects}
          accent="bg-accent/10"
        />
        <StatCard
          icon={<GitBranch className="w-5 h-5 text-[var(--color-success)]" />}
          label={t.dashboard.totalWorkflows}
          value={stats?.totalWorkflows ?? 0}
          accent="bg-success-subtle"
        />
        <StatCard
          icon={<Zap className="w-5 h-5 text-[var(--color-warning)]" />}
          label={t.dashboard.runsToday}
          value={stats?.runsToday ?? 0}
          accent="bg-warning-subtle"
        />
        <StatCard
          icon={<TrendingUp className="w-5 h-5 text-accent" />}
          label={t.dashboard.successRate}
          value={stats?.successRate != null ? `${(stats.successRate * 100).toFixed(0)}%` : '-'}
          accent="bg-accent/10"
        />
        <StatCard
          icon={<Coins className="w-5 h-5 text-[var(--color-warning)]" />}
          label={t.dashboard.totalCost}
          value={stats?.totalCostUsd30d != null && stats.totalCostUsd30d > 0
            ? `$${stats.totalCostUsd30d < 0.01 ? stats.totalCostUsd30d.toFixed(4) : stats.totalCostUsd30d.toFixed(2)}`
            : '$0.00'}
          accent="bg-warning-subtle"
        />
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <RunsChart data={stats?.timeSeries} />
        <SuccessRateChart data={stats?.timeSeries} />
      </div>

      {/* Two-column layout: recent runs + quick actions */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Recent runs - 2/3 width */}
        <Card padding={false} className="lg:col-span-2">
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
                    <span className="text-sm font-medium text-text truncate">
                      {run.workflowName}
                    </span>
                  </div>
                  <div className="flex items-center gap-4 shrink-0 text-xs text-text-tertiary">
                    <span className="flex items-center gap-1">
                      <Clock className="w-3 h-3" />
                      {formatDuration(run.durationMs)}
                    </span>
                    <span>{run.startedAt ? `${formatDateTime(run.startedAt)} · ${formatRelativeTime(run.startedAt, locale)}` : '-'}</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>

        {/* Quick actions - 1/3 width */}
        <Card>
          <CardHeader title={t.dashboard.quickActions} />
          <div className="space-y-2">
            <Button
              variant="secondary"
              size="sm"
              className="w-full justify-start"
              icon={<FolderKanban className="w-4 h-4" />}
              onClick={() => navigate('/projects')}
            >
              {t.projects.create}
            </Button>
            <Button
              variant="secondary"
              size="sm"
              className="w-full justify-start"
              icon={<GitBranch className="w-4 h-4" />}
              onClick={() => navigate('/workflows')}
            >
              {t.workflows.create}
            </Button>
            <Button
              variant="secondary"
              size="sm"
              className="w-full justify-start"
              icon={<Zap className="w-4 h-4" />}
              onClick={() => navigate('/skills')}
            >
              {t.skills.title}
            </Button>
          </div>

          {/* Top workflows */}
          {stats?.topWorkflows && stats.topWorkflows.length > 0 && (
            <div className="mt-6">
              <h4 className="text-xs font-semibold uppercase tracking-wider text-text-tertiary mb-3">
                {t.dashboard.topWorkflows}
              </h4>
              <div className="space-y-2">
                {stats.topWorkflows.slice(0, 5).map((wf) => (
                  <div key={wf.workflowId} className="flex items-center justify-between text-sm">
                    <span className="text-text-secondary truncate">{wf.workflowName}</span>
                    <span className="text-text-tertiary shrink-0 ml-2">{wf.runCount} {t.common.runs}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
