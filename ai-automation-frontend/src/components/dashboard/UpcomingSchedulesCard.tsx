import { CalendarClock } from 'lucide-react';
import { Card, CardHeader } from '../ui';
import { useRemainingToday, useRemainingThisWeek } from '../../hooks/useSchedules';
import { useT, useI18nStore, type Locale } from '../../i18n';
import { formatDateTime } from '../../utils/formatTime';
import { formatDate, formatTimeOfDay } from '../../utils/localeFormat';
import { WidgetSkeleton } from './WidgetSkeleton';
import { useDashboardData } from './dashboardDataContext';

function scheduleTime(iso: string, locale: Locale): string {
  try {
    return formatTimeOfDay(iso, locale, { hour: '2-digit', minute: '2-digit' });
  } catch {
    return '—';
  }
}

function scheduleDay(iso: string, locale: Locale): string {
  try {
    return formatDate(iso, locale, { weekday: 'short', day: '2-digit', month: '2-digit' });
  } catch {
    return '—';
  }
}

export function UpcomingSchedulesCard() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { projectId } = useDashboardData().filter;
  const { data: today, isLoading: todayLoading } = useRemainingToday(projectId);
  const { data: week, isLoading: weekLoading } = useRemainingThisWeek(projectId);
  if (todayLoading || weekLoading) return <WidgetSkeleton size="chart" />;

  // Runs inside a skip-until pause are already left out by the backend (a776fd2a); say why.
  const pausedUntil = today?.pausedUntil ?? week?.pausedUntil ?? null;
  // Under the maintenance switch the backend lists no run at all — nothing starts (7704b1e9).
  const maintenance = !!(today?.maintenance || week?.maintenance);

  // Combine today + rest-of-week, deduplicate by scheduleId, limit to 5
  const allSchedules = [
    ...(today?.schedules ?? []).map((s) => ({ ...s, isToday: true })),
    ...(week?.schedules ?? [])
      .filter((ws) => !(today?.schedules ?? []).some((ts) => ts.scheduleId === ws.scheduleId))
      .map((s) => ({ ...s, isToday: false })),
  ].slice(0, 5);

  return (
    <Card>
      <CardHeader title={t.dashboard.upcomingSchedules} />
      {maintenance && (
        <p className="mb-2 text-[10px] text-info" data-testid="upcoming-schedules-maintenance">
          {t.schedules.maintenanceNothingStarts}
        </p>
      )}
      {pausedUntil && (
        <p className="mb-2 text-[10px] text-info" data-testid="upcoming-schedules-paused">
          {t.dashboard.schedulesPausedUntil.replace('{until}', () => formatDateTime(pausedUntil, locale))}
        </p>
      )}
      {allSchedules.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 text-text-tertiary">
          <CalendarClock className="w-7 h-7 mb-2 opacity-40" />
          <p className="text-xs">{t.dashboard.noUpcomingSchedules}</p>
        </div>
      ) : (
        <div className="space-y-2">
          {allSchedules.map((schedule) => (
            <div
              key={schedule.scheduleId + schedule.scheduledAt}
              className="flex items-center justify-between gap-2"
            >
              <div className="flex items-center gap-2 min-w-0">
                <div className={`w-1.5 h-1.5 rounded-full shrink-0 ${
                  schedule.isToday ? 'bg-accent' : 'bg-text-tertiary/40'
                }`} />
                <div className="min-w-0">
                  <span className="text-xs text-text truncate block">
                    {schedule.workflowName ?? schedule.chainName}
                  </span>
                  {schedule.projectName ? (
                    <span className="text-[10px] text-accent/70 truncate block">{schedule.projectName}</span>
                  ) : schedule.targetType === 'CHAIN' ? (
                    <span className="text-[10px] text-accent/70 truncate block">Chain</span>
                  ) : null}
                </div>
              </div>
              <span className="text-[10px] text-text-tertiary shrink-0 tabular-nums">
                {schedule.isToday
                  ? scheduleTime(schedule.scheduledAt, locale)
                  : scheduleDay(schedule.scheduledAt, locale)
                }
              </span>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
