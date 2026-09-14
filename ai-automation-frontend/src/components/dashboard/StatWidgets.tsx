import {
  FolderKanban, GitBranch, Zap, TrendingUp, Coins, CalendarClock, CalendarRange, Info,
} from 'lucide-react';
import { Card } from '../ui';
import { useDashboard } from '../../hooks/useLearning';
import { useProjects } from '../../hooks/useProjects';
import { useRemainingToday, useRemainingThisWeek } from '../../hooks/useSchedules';
import { useSystemInfo } from '../../hooks/useSystem';
import { useT } from '../../i18n';

function StatCard({ icon, label, value, accent }: {
  icon: React.ReactNode;
  label: string;
  value: string | number;
  accent?: string;
}) {
  return (
    <Card className="flex items-center gap-3 py-3! px-4!">
      <div className={`p-2 rounded-lg ${accent ?? 'bg-accent/10'}`}>
        {icon}
      </div>
      <div className="min-w-0">
        <p className="text-xs text-text-secondary truncate">{label}</p>
        <p className="text-lg font-bold text-text">{value}</p>
      </div>
    </Card>
  );
}

export function ActiveProjectsStat() {
  const t = useT();
  const { data: projects } = useProjects();
  const activeProjects = projects?.filter((p) => p.status === 'RUNNING').length ?? 0;
  return (
    <StatCard
      icon={<FolderKanban className="w-4 h-4 text-accent" />}
      label={t.dashboard.activeProjects}
      value={activeProjects}
      accent="bg-accent/10"
    />
  );
}

export function TotalWorkflowsStat() {
  const t = useT();
  const { data: stats } = useDashboard();
  return (
    <StatCard
      icon={<GitBranch className="w-4 h-4 text-success" />}
      label={t.dashboard.totalWorkflows}
      value={stats?.totalWorkflows ?? 0}
      accent="bg-success-subtle"
    />
  );
}

export function RunsTodayStat() {
  const t = useT();
  const { data: stats } = useDashboard();
  return (
    <StatCard
      icon={<Zap className="w-4 h-4 text-warning" />}
      label={t.dashboard.runsToday}
      value={stats?.runsToday ?? 0}
      accent="bg-warning-subtle"
    />
  );
}

export function SuccessRateStat() {
  const t = useT();
  const { data: stats } = useDashboard();
  return (
    <StatCard
      icon={<TrendingUp className="w-4 h-4 text-accent" />}
      label={t.dashboard.successRate}
      value={stats?.successRate != null ? `${(stats.successRate * 100).toFixed(0)}%` : '-'}
      accent="bg-accent/10"
    />
  );
}

export function TotalCostStat() {
  const t = useT();
  const { data: stats } = useDashboard();
  const cost = stats?.totalCostUsd30d;
  return (
    <StatCard
      icon={<Coins className="w-4 h-4 text-warning" />}
      label={t.dashboard.totalCost}
      value={cost != null && cost > 0
        ? `$${cost < 0.01 ? cost.toFixed(4) : cost.toFixed(2)}`
        : '$0.00'}
      accent="bg-warning-subtle"
    />
  );
}

export function PlannedTodayStat() {
  const t = useT();
  const { data: remainingToday } = useRemainingToday();
  return (
    <StatCard
      icon={<CalendarClock className="w-4 h-4 text-blue-500" />}
      label={t.dashboard.plannedToday}
      value={remainingToday?.count ?? 0}
      accent="bg-blue-500/10"
    />
  );
}

export function PlannedThisWeekStat() {
  const t = useT();
  const { data: remainingThisWeek } = useRemainingThisWeek();
  return (
    <StatCard
      icon={<CalendarRange className="w-4 h-4 text-violet-500" />}
      label={t.dashboard.plannedThisWeek}
      value={remainingThisWeek?.count ?? 0}
      accent="bg-violet-500/10"
    />
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

export function BuildInfoStat() {
  const t = useT();
  const { data: systemInfo } = useSystemInfo();
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
