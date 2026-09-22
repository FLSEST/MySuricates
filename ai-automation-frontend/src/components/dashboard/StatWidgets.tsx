import {
  FolderKanban, GitBranch, Zap, TrendingUp, Coins, CalendarClock, CalendarRange, Info, AlertTriangle,
} from 'lucide-react';
import { Card } from '../ui';
import { useDashboardData } from './dashboardDataContext';
import { useProjects } from '../../hooks/useProjects';
import { useRemainingToday, useRemainingThisWeek } from '../../hooks/useSchedules';
import { useSystemInfo } from '../../hooks/useSystem';
import { useT } from '../../i18n';
import { WidgetSkeleton } from './WidgetSkeleton';

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
  const { data: projects, isLoading } = useProjects();
  if (isLoading) return <WidgetSkeleton size="stat" />;
  return (
    <StatCard
      icon={<FolderKanban className="w-4 h-4 text-accent" />}
      label={t.dashboard.activeProjects}
      value={projects ? projects.filter((p) => p.status === 'RUNNING').length : '-'}
      accent="bg-accent/10"
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
  // The range of the numbers on screen: while another filter loads, the previous result is still shown.
  const { data: stats, isLoading, dataDays } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="stat" />;
  const cost = stats?.totalCostUsd30d;
  return (
    <StatCard
      icon={<Coins className="w-4 h-4 text-warning" />}
      label={t.dashboard.totalCostDays.replace('{days}', () => String(dataDays))}
      value={!stats ? '-' : cost != null && cost > 0
        ? `$${cost < 0.01 ? cost.toFixed(4) : cost.toFixed(2)}`
        : '$0.00'}
      accent="bg-warning-subtle"
    />
  );
}

export function PlannedTodayStat() {
  const t = useT();
  const { data: remainingToday, isLoading } = useRemainingToday();
  if (isLoading) return <WidgetSkeleton size="stat" />;
  return (
    <StatCard
      icon={<CalendarClock className="w-4 h-4 text-blue-500" />}
      label={t.dashboard.plannedToday}
      value={remainingToday?.count ?? '-'}
      accent="bg-blue-500/10"
    />
  );
}

export function PlannedThisWeekStat() {
  const t = useT();
  const { data: remainingThisWeek, isLoading } = useRemainingThisWeek();
  if (isLoading) return <WidgetSkeleton size="stat" />;
  return (
    <StatCard
      icon={<CalendarRange className="w-4 h-4 text-violet-500" />}
      label={t.dashboard.plannedThisWeek}
      value={remainingThisWeek?.count ?? '-'}
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
  // Zwischen zwei Releases steht in beiden Dateien X.Y.Z-SNAPSHOT; das Backend liefert die Nummer
  // schon ohne Suffix (SystemIdentityService), hier faellt es genauso weg.
  const feVersion = __APP_VERSION__.split('-')[0];
  const beVersion = systemInfo?.version;
  // Erst vergleichen, wenn das Backend geantwortet hat — sonst meldete die Kachel beim Laden Drift.
  // Weichen die Nummern ab, laeuft ein neues Frontend gegen ein altes Backend-JAR (Test-Container:
  // neue Endpunkte liefern 404, die Seite haengt auf „loading").
  const mismatch = Boolean(beVersion) && beVersion !== feVersion;

  return (
    <Card className="flex items-center gap-3 py-3! px-4!">
      <div className="p-2 rounded-lg bg-teal-500/10">
        <Info className="w-4 h-4 text-teal-500" />
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
          <span className="whitespace-nowrap" title={`${t.dashboard.backendBuild}: ${formatBuildTime(systemInfo?.buildTimestamp)}`}>
            BE {beVersion ?? '?'}
          </span>
          <span className="whitespace-nowrap" title={`${t.dashboard.frontendBuild}: ${formatBuildTime(__BUILD_TIMESTAMP__)}`}>
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
