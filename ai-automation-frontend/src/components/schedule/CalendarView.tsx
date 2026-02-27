import { useState, useMemo } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useCalendarData } from '../../hooks/useSchedules';
import { useT } from '../../i18n';
import { Spinner } from '../ui';
import type { CalendarEvent } from '../../types';

function getDaysInMonth(year: number, month: number) {
  return new Date(year, month + 1, 0).getDate();
}

function getFirstDayOfWeek(year: number, month: number) {
  const day = new Date(year, month, 1).getDay();
  return day === 0 ? 6 : day - 1; // Monday = 0
}

const WEEKDAYS_EN = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const WEEKDAYS_DE = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
const MONTHS_EN = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const MONTHS_DE = ['Januar', 'Februar', 'Maerz', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];

export function CalendarView() {
  const t = useT();
  const isGerman = t.common.save === 'Speichern';
  const weekdays = isGerman ? WEEKDAYS_DE : WEEKDAYS_EN;
  const months = isGerman ? MONTHS_DE : MONTHS_EN;

  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth());
  const [selectedDay, setSelectedDay] = useState<number | null>(null);

  const from = new Date(year, month, 1).toISOString();
  const to = new Date(year, month + 1, 0, 23, 59, 59).toISOString();

  const { data, isLoading } = useCalendarData(from, to);

  const events = useMemo<CalendarEvent[]>(() => {
    if (!data) return [];
    const result: CalendarEvent[] = [];

    for (const s of data.upcoming) {
      const date = s.nextRunAt || s.scheduledAt;
      if (date) {
        result.push({
          id: s.id,
          title: s.workflowName,
          date,
          type: 'scheduled',
          workflowId: s.workflowId,
          status: s.scheduleType === 'ONCE' ? 'ONCE' : 'CRON',
        });
      }
    }

    for (const r of data.pastRuns) {
      if (r.startedAt) {
        result.push({
          id: r.id,
          title: r.workflowName,
          date: r.startedAt,
          type: 'past_run',
          status: r.status,
          workflowId: r.workflowId,
        });
      }
    }

    return result;
  }, [data]);

  const eventsByDay = useMemo(() => {
    const map: Record<number, CalendarEvent[]> = {};
    for (const ev of events) {
      const d = new Date(ev.date);
      if (d.getFullYear() === year && d.getMonth() === month) {
        const day = d.getDate();
        if (!map[day]) map[day] = [];
        map[day].push(ev);
      }
    }
    return map;
  }, [events, year, month]);

  const daysInMonth = getDaysInMonth(year, month);
  const firstDay = getFirstDayOfWeek(year, month);
  const today = now.getFullYear() === year && now.getMonth() === month ? now.getDate() : -1;

  const prevMonth = () => {
    if (month === 0) { setMonth(11); setYear(year - 1); }
    else setMonth(month - 1);
    setSelectedDay(null);
  };

  const nextMonth = () => {
    if (month === 11) { setMonth(0); setYear(year + 1); }
    else setMonth(month + 1);
    setSelectedDay(null);
  };

  const cells: (number | null)[] = [];
  for (let i = 0; i < firstDay; i++) cells.push(null);
  for (let d = 1; d <= daysInMonth; d++) cells.push(d);
  while (cells.length % 7 !== 0) cells.push(null);

  const selectedEvents = selectedDay ? (eventsByDay[selectedDay] ?? []) : [];

  return (
    <div className="space-y-4">
      {/* Month Navigation */}
      <div className="flex items-center justify-between">
        <button onClick={prevMonth} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary">
          <ChevronLeft className="w-5 h-5" />
        </button>
        <h2 className="text-lg font-semibold text-text">
          {months[month]} {year}
        </h2>
        <button onClick={nextMonth} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary">
          <ChevronRight className="w-5 h-5" />
        </button>
      </div>

      {isLoading && (
        <div className="flex justify-center py-4">
          <Spinner text={t.common.loading} />
        </div>
      )}

      {/* Calendar Grid */}
      <div className="bg-surface rounded-xl border border-border overflow-hidden">
        {/* Weekday headers */}
        <div className="grid grid-cols-7 border-b border-border">
          {weekdays.map((d) => (
            <div key={d} className="py-2 text-center text-xs font-medium text-text-secondary uppercase">
              {d}
            </div>
          ))}
        </div>

        {/* Day cells */}
        <div className="grid grid-cols-7">
          {cells.map((day, i) => {
            const dayEvents = day ? (eventsByDay[day] ?? []) : [];
            const isToday = day === today;
            const isSelected = day === selectedDay;
            const scheduledCount = dayEvents.filter(e => e.type === 'scheduled').length;
            const successCount = dayEvents.filter(e => e.type === 'past_run' && e.status === 'COMPLETED').length;
            const failedCount = dayEvents.filter(e => e.type === 'past_run' && e.status === 'FAILED').length;

            return (
              <button
                key={i}
                disabled={!day}
                onClick={() => day && setSelectedDay(day === selectedDay ? null : day)}
                className={`
                  relative h-20 p-1.5 border-b border-r border-border text-left transition-colors
                  ${!day ? 'bg-surface-secondary cursor-default' : 'hover:bg-surface-hover cursor-pointer'}
                  ${isSelected ? 'bg-accent/10 ring-1 ring-accent ring-inset' : ''}
                `}
              >
                {day && (
                  <>
                    <span className={`
                      text-sm font-medium inline-flex items-center justify-center w-6 h-6 rounded-full
                      ${isToday ? 'bg-accent text-white' : 'text-text'}
                    `}>
                      {day}
                    </span>
                    {dayEvents.length > 0 && (
                      <div className="flex gap-1 mt-1 flex-wrap">
                        {scheduledCount > 0 && (
                          <span className="inline-block w-2 h-2 rounded-full bg-blue-500" title={`${scheduledCount} scheduled`} />
                        )}
                        {successCount > 0 && (
                          <span className="inline-block w-2 h-2 rounded-full bg-green-500" title={`${successCount} successful`} />
                        )}
                        {failedCount > 0 && (
                          <span className="inline-block w-2 h-2 rounded-full bg-red-500" title={`${failedCount} failed`} />
                        )}
                      </div>
                    )}
                  </>
                )}
              </button>
            );
          })}
        </div>
      </div>

      {/* Legend */}
      <div className="flex items-center gap-4 text-xs text-text-secondary">
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-2.5 h-2.5 rounded-full bg-blue-500" />
          {t.schedules.scheduledAt.replace(' At', '').replace(' fuer', '')}
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-2.5 h-2.5 rounded-full bg-green-500" />
          {t.workflows.execution.completed}
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-2.5 h-2.5 rounded-full bg-red-500" />
          {t.workflows.execution.failed}
        </span>
      </div>

      {/* Selected Day Detail */}
      {selectedDay !== null && (
        <div className="bg-surface rounded-xl border border-border p-4">
          <h3 className="text-sm font-semibold text-text mb-3">
            {selectedDay}. {months[month]} {year}
          </h3>
          {selectedEvents.length === 0 ? (
            <p className="text-sm text-text-secondary">{t.schedules.noEventsOnDay}</p>
          ) : (
            <div className="space-y-2">
              {selectedEvents.map((ev) => (
                <div key={ev.id} className="flex items-center gap-3 p-2 rounded-lg bg-surface-secondary">
                  <span className={`w-2 h-2 rounded-full shrink-0 ${
                    ev.type === 'scheduled' ? 'bg-blue-500' :
                    ev.status === 'COMPLETED' ? 'bg-green-500' :
                    ev.status === 'FAILED' ? 'bg-red-500' : 'bg-gray-400'
                  }`} />
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium text-text truncate">{ev.title}</p>
                    <p className="text-xs text-text-secondary">
                      {new Date(ev.date).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                      {' — '}
                      {ev.type === 'scheduled'
                        ? (ev.status === 'ONCE' ? t.schedules.once : t.schedules.recurring)
                        : ev.status}
                    </p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
