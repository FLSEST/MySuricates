import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  FolderKanban, GitBranch, Zap,
  TrendingUp, Clock, Coins, CalendarClock, CalendarRange, Info,
} from 'lucide-react';
import { Card, CardHeader, Button, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import { RunsChart } from '../components/dashboard/RunsChart';
import { SuccessRateChart } from '../components/dashboard/SuccessRateChart';
import { TodosDonutChart } from '../components/dashboard/TodosDonutChart';
import { UpcomingSchedulesCard } from '../components/dashboard/UpcomingSchedulesCard';
import { FailingWorkflowsCard } from '../components/dashboard/FailingWorkflowsCard';
import { ProviderStatsCard } from '../components/dashboard/ProviderStatsCard';
import { ModelStatsCard } from '../components/dashboard/ModelStatsCard';
import { TodosWidget } from '../components/dashboard/TodosWidget';
import { useDashboard } from '../hooks/useLearning';
import { useProjects } from '../hooks/useProjects';
import { useRemainingToday, useRemainingThisWeek } from '../hooks/useSchedules';
import { useSystemInfo } from '../hooks/useSystem';
import { useT, useI18nStore } from '../i18n';
import { formatDuration, formatRelativeTime, formatDateTime } from '../utils/formatTime';
import type { WorkflowRun, SystemInfo } from '../types';

function StatCard({ icon, label, value, accent, badge }: {
  icon: React.ReactNode;
  label: string;
  value: string | number;
  accent?: string;
  badge?: string;
}) {
  return (
    <Card className="flex items-center gap-3 py-3! px-4!">
      <div className={`p-2 rounded-lg ${accent ?? 'bg-accent/10'}`}>
        {icon}
      </div>
      <div className="min-w-0">
        <p className="text-xs text-text-secondary truncate">{label}</p>
        <div className="flex items-center gap-1.5">
          <p className="text-lg font-bold text-text">{value}</p>
          {badge && (
            <span className={`text-[9px] font-semibold px-1 py-0.5 rounded-full uppercase tracking-wide ${
              badge === 'RELEASE'
                ? 'bg-success/15 text-success'
                : 'bg-warning/15 text-warning'
            }`}>
              {badge}
            </span>
          )}
        </div>
      </div>
    </Card>
  );
}

function formatBuildTime(iso: string | undefined): string {
  if (!iso || iso === 'unknown') return '?';
  try {
    const d = new Date(iso);
    return d.toLocaleString([], { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  } catch {
    return iso;
  }
}

function BuildInfoCard({ systemInfo }: { systemInfo: SystemInfo | undefined }) {
  const t = useT();
  const feTimestamp = __BUILD_TIMESTAMP__;

  return (
    <Card className="flex items-center gap-3 py-3! px-4!">
      <div className="p-2 rounded-lg bg-teal-500/10">
        <Info className="w-4 h-4 text-teal-500" />
      </div>
      <div className="min-w-0">
        <p className="text-xs text-text-secondary">{t.dashboard.version}</p>
        <div className="flex items-center gap-1.5">
          <p className="text-lg font-bold text-text">
            {systemInfo ? `v${systemInfo.version}` : '-'}
          </p>
          {systemInfo?.buildType && (
            <span className={`text-[9px] font-semibold px-1 py-0.5 rounded-full uppercase tracking-wide ${
              systemInfo.buildType === 'RELEASE'
                ? 'bg-success/15 text-success'
                : 'bg-warning/15 text-warning'
            }`}>
              {systemInfo.buildType}
            </span>
          )}
        </div>
        <div className="flex gap-2 text-[9px] text-text-tertiary">
          <span title="Backend Build">BE {formatBuildTime(systemInfo?.buildTimestamp)}</span>
          <span title="Frontend Build">FE {formatBuildTime(feTimestamp)}</span>
        </div>
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
  const { data: remainingToday } = useRemainingToday();
  const { data: remainingThisWeek } = useRemainingThisWeek();
  const { data: systemInfo } = useSystemInfo();

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
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold text-text">{t.dashboard.title}</h1>
          <p className="text-sm text-text-secondary mt-1 truncate">{t.dashboard.subtitle}</p>
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
      <div className="grid grid-cols-2 sm:grid-cols-4 xl:grid-cols-8 gap-3 animate-stagger">
        <StatCard
          icon={<FolderKanban className="w-4 h-4 text-accent" />}
          label={t.dashboard.activeProjects}
          value={activeProjects}
          accent="bg-accent/10"
        />
        <StatCard
          icon={<GitBranch className="w-4 h-4 text-success" />}
          label={t.dashboard.totalWorkflows}
          value={stats?.totalWorkflows ?? 0}
          accent="bg-success-subtle"
        />
        <StatCard
          icon={<Zap className="w-4 h-4 text-warning" />}
          label={t.dashboard.runsToday}
          value={stats?.runsToday ?? 0}
          accent="bg-warning-subtle"
        />
        <StatCard
          icon={<TrendingUp className="w-4 h-4 text-accent" />}
          label={t.dashboard.successRate}
          value={stats?.successRate != null ? `${(stats.successRate * 100).toFixed(0)}%` : '-'}
          accent="bg-accent/10"
        />
        <StatCard
          icon={<Coins className="w-4 h-4 text-warning" />}
          label={t.dashboard.totalCost}
          value={stats?.totalCostUsd30d != null && stats.totalCostUsd30d > 0
            ? `$${stats.totalCostUsd30d < 0.01 ? stats.totalCostUsd30d.toFixed(4) : stats.totalCostUsd30d.toFixed(2)}`
            : '$0.00'}
          accent="bg-warning-subtle"
        />
        <StatCard
          icon={<CalendarClock className="w-4 h-4 text-blue-500" />}
          label={t.dashboard.plannedToday}
          value={remainingToday?.count ?? 0}
          accent="bg-blue-500/10"
        />
        <StatCard
          icon={<CalendarRange className="w-4 h-4 text-violet-500" />}
          label={t.dashboard.plannedThisWeek}
          value={remainingThisWeek?.count ?? 0}
          accent="bg-violet-500/10"
        />
        <BuildInfoCard systemInfo={systemInfo} />
      </div>

      {/* Charts row */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-5 gap-4">
        <RunsChart data={stats?.timeSeries} />
        <SuccessRateChart data={stats?.timeSeries} />
        <TodosDonutChart />
        <UpcomingSchedulesCard />
        <FailingWorkflowsCard recentRuns={stats?.recentRuns} />
      </div>

      {/* Provider / model stats row */}
      {((stats?.providerStats?.length ?? 0) > 0 || (stats?.modelStats?.length ?? 0) > 0) && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <ProviderStatsCard data={stats?.providerStats} />
          <ModelStatsCard data={stats?.modelStats} />
        </div>
      )}

      {/* Todos overview */}
      <TodosWidget />

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
