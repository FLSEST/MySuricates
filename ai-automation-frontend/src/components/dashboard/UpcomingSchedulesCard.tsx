import { CalendarClock } from 'lucide-react';
import { Card, CardHeader } from '../ui';
import { useRemainingToday, useRemainingThisWeek } from '../../hooks/useSchedules';
import { useT } from '../../i18n';

function formatTime(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } catch {
    return '—';
  }
}

function formatDate(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleDateString([], { weekday: 'short', day: '2-digit', month: '2-digit' });
  } catch {
    return '—';
  }
}

export function UpcomingSchedulesCard() {
  const t = useT();
  const { data: today } = useRemainingToday();
  const { data: week } = useRemainingThisWeek();

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
                <span className="text-xs text-text truncate">{schedule.workflowName}</span>
              </div>
              <span className="text-[10px] text-text-tertiary shrink-0 tabular-nums">
                {schedule.isToday
                  ? formatTime(schedule.scheduledAt)
                  : formatDate(schedule.scheduledAt)
                }
              </span>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
