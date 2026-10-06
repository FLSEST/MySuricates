import {
  FolderKanban, GitBranch, Zap, TrendingUp, Coins, CalendarClock, CalendarRange, Info, AlertTriangle,
} from 'lucide-react';
import { Card } from '../ui';
import { useDashboardData } from './dashboardDataContext';
import { useProjects } from '../../hooks/useProjects';
import { useRemainingToday, useRemainingThisWeek } from '../../hooks/useSchedules';
import { useSystemInfo } from '../../hooks/useSystem';
import { useI18nStore, useT, type Locale } from '../../i18n';
import { formatTimestamp, formatUsd } from '../../utils/localeFormat';
import { WidgetSkeleton } from './WidgetSkeleton';

function StatCard({ icon, label, value, accent, hint }: {
  icon: React.ReactNode;
  label: string;
  value: string | number;
  accent?: string;
  /** Small line under the value, e.g. that the tile ignores the view's project filter. */
  hint?: string;
}) {
  return (
    <Card className="flex items-center gap-3 py-3! px-4!">
      <div className={`p-2 rounded-lg ${accent ?? 'bg-accent/10'}`}>
        {icon}
      </div>
      {/* The title wraps instead of being cut off; the tooltip carries it for narrow tiles too. */}
      <div className="min-w-0">
        <p className="text-xs text-text-secondary break-words" title={label}>{label}</p>
        <p className="text-lg font-bold text-text">{value}</p>
        {hint && <p className="text-[10px] text-text-tertiary">{hint}</p>}
      </div>
    </Card>
  );
}

export function ActiveProjectsStat() {
  const t = useT();
  const { data: projects, isLoading } = useProjects();
  // Counts every project by design; under a project filter the tile says so instead of showing 0/1.
  const { projectId } = useDashboardData().filter;
  if (isLoading) return <WidgetSkeleton size="stat" />;
  return (
    <StatCard
      icon={<FolderKanban className="w-4 h-4 text-accent" />}
      label={t.dashboard.activeProjects}
      value={projects ? projects.filter((p) => p.status === 'RUNNING').length : '-'}
      accent="bg-accent/10"
      hint={projectId ? t.dashboard.filter.allProjectsHint : undefined}
    />
  );
}

export function TotalWorkflowsStat() {
  const t = useT();
  const { data: stats, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="stat" />;
  return (
    <StatCard
      icon={<GitBranch className="w-4 h-4 text-success" />}
      label={t.dashboard.totalWorkflows}
      value={stats?.totalWorkflows ?? '-'}
      accent="bg-success-subtle"
    />
  );
}

export function RunsTodayStat() {
  const t = useT();
  const { data: stats, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="stat" />;
  return (
    <StatCard
      icon={<Zap className="w-4 h-4 text-warning" />}
      label={t.dashboard.runsToday}
      value={stats?.runsToday ?? '-'}
      accent="bg-warning-subtle"
    />
  );
}

export function SuccessRateStat() {
  const t = useT();
  const { data: stats, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="stat" />;
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
  const locale = useI18nStore((s) => s.locale);
  // The range of the numbers on screen: while another filter loads, the previous result is still shown.
  const { data: stats, isLoading, dataDays } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="stat" />;
  const cost = stats?.totalCostUsd30d;
  return (
    <StatCard
      icon={<Coins className="w-4 h-4 text-warning" />}
      label={t.dashboard.totalCostDays.replace('{days}', () => String(dataDays))}
      value={!stats ? '-' : formatUsd(cost != null && cost > 0 ? cost : 0, locale)}
      accent="bg-warning-subtle"
    />
  );
}

export function PlannedTodayStat() {
  const t = useT();
  const { data: remainingToday, isLoading } = useRemainingToday(useDashboardData().filter.projectId);
  if (isLoading) return <WidgetSkeleton size="stat" />;
  return (
    <StatCard
      icon={<CalendarClock className="w-4 h-4 text-info" />}
      label={t.dashboard.plannedToday}
      value={remainingToday?.count ?? '-'}
      accent="bg-info-subtle"
    />
  );
}

export function PlannedThisWeekStat() {
  const t = useT();
  const { data: remainingThisWeek, isLoading } = useRemainingThisWeek(useDashboardData().filter.projectId);
  if (isLoading) return <WidgetSkeleton size="stat" />;
  return (
    <StatCard
      icon={<CalendarRange className="w-4 h-4 text-info" />}
      label={t.dashboard.plannedThisWeek}
      value={remainingThisWeek?.count ?? '-'}
      accent="bg-info-subtle"
    />
  );
}

function formatBuildTime(iso: string | undefined, locale: Locale): string {
  if (!iso || iso === 'unknown') return '?';
  try {
    return formatTimestamp(iso, locale, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  } catch {
    return iso;
  }
}

export function BuildInfoStat() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: systemInfo } = useSystemInfo();
  // Between two releases both files carry X.Y.Z-SNAPSHOT; the backend delivers the number
  // already without the suffix (SystemIdentityService), here it is dropped the same way.
  const feVersion = __APP_VERSION__.split('-')[0];
  const beVersion = systemInfo?.version;
  // Compare only once the backend has answered — otherwise the tile reported drift while loading.
  // If the numbers differ, a new frontend is running against an old backend JAR (test container:
  // new endpoints return 404, the page hangs on „loading").
  const mismatch = Boolean(beVersion) && beVersion !== feVersion;

  return (
    <Card className="flex items-center gap-3 py-3! px-4!">
      <div className="p-2 rounded-lg bg-surface-hover">
        <Info className="w-4 h-4 text-text-secondary" />
      </div>
      <div className="min-w-0">
        <p className="text-xs text-text-secondary">{t.dashboard.version}</p>
        <div className="flex items-center gap-1.5 flex-wrap">
          <p className="text-lg font-bold text-text">
            {beVersion ? `v${beVersion}` : '-'}
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
        <div className={`flex gap-2 items-center flex-wrap text-[9px] ${mismatch ? 'text-warning' : 'text-text-tertiary'}`}>
          <span className="whitespace-nowrap" title={`${t.dashboard.backendBuild}: ${formatBuildTime(systemInfo?.buildTimestamp, locale)}`}>
            BE {beVersion ?? '?'}
          </span>
          <span className="whitespace-nowrap" title={`${t.dashboard.frontendBuild}: ${formatBuildTime(__BUILD_TIMESTAMP__, locale)}`}>
            FE {feVersion}
          </span>
          {mismatch && (
            <span title={t.dashboard.versionMismatch} aria-label={t.dashboard.versionMismatch} className="flex items-center">
              <AlertTriangle className="w-2.5 h-2.5 shrink-0" />
            </span>
          )}
        </div>
      </div>
    </Card>
  );
}
