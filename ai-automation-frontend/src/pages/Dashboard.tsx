import { useNavigate } from 'react-router-dom';
import {
  LayoutDashboard, FolderKanban, GitBranch, Zap,
  TrendingUp, Clock, CheckCircle2, XCircle,
} from 'lucide-react';
import { Card, CardHeader, Button, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import { useDashboard } from '../hooks/useLearning';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
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

function formatDuration(ms: number | null): string {
  if (!ms) return '-';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

function formatRelativeTime(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export function Dashboard() {
  const t = useT();
  const navigate = useNavigate();
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
      <div>
        <h1 className="text-2xl font-bold text-text">{t.dashboard.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.dashboard.subtitle}</p>
      </div>

      {/* Stat cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4 animate-stagger">
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
          value={stats?.totalRuns ?? 0}
          accent="bg-warning-subtle"
        />
        <StatCard
          icon={<TrendingUp className="w-5 h-5 text-accent" />}
          label={t.dashboard.successRate}
          value={stats?.successRate != null ? `${(stats.successRate * 100).toFixed(0)}%` : '-'}
          accent="bg-accent/10"
        />
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
                    <span>{run.startedAt ? formatRelativeTime(run.startedAt) : '-'}</span>
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
