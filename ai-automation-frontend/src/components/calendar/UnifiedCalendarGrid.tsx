import { useState, useMemo } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button, Spinner } from '../ui';
import { CalendarSourceFilter } from './CalendarSourceFilter';
import { CalendarEventList } from './CalendarEventList';
import { useCalendarAggregated } from '../../hooks/useCalendarAggregated';
import { useT } from '../../i18n';
import type { UnifiedCalendarEvent } from '../../types';

function getDaysInMonth(year: number, month: number) {
  return new Date(year, month + 1, 0).getDate();
}

function getFirstDayOfWeek(year: number, month: number) {
  const day = new Date(year, month, 1).getDay();
  return day === 0 ? 6 : day - 1;
}

const WEEKDAYS_EN = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const WEEKDAYS_DE = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
const MONTHS_EN = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const MONTHS_DE = ['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];

export function UnifiedCalendarGrid() {
  const t = useT();
  const isGerman = t.common.save === 'Speichern';
  const weekdays = isGerman ? WEEKDAYS_DE : WEEKDAYS_EN;
  const months = isGerman ? MONTHS_DE : MONTHS_EN;

  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth());
  const [selectedDay, setSelectedDay] = useState<number | null>(now.getDate());
  const [enabledSources, setEnabledSources] = useState<Set<string>>(
    new Set(['google-calendar', 'schedules', 'runs'])
  );

  const from = new Date(year, month, 1).toISOString();
  const to = new Date(year, month + 1, 0, 23, 59, 59).toISOString();

  const { events, sources, isLoading } = useCalendarAggregated(from, to, enabledSources);

  const toggleSource = (sourceId: string) => {
    setEnabledSources((prev) => {
      const next = new Set(prev);
      if (next.has(sourceId)) next.delete(sourceId);
      else next.add(sourceId);
      return next;
    });
  };

  const eventsByDay = useMemo(() => {
    const map: Record<number, UnifiedCalendarEvent[]> = {};
    for (const ev of events) {
      if (ev.start.getFullYear() === year && ev.start.getMonth() === month) {
        const day = ev.start.getDate();
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

  const goToday = () => {
    const n = new Date();
    setYear(n.getFullYear());
    setMonth(n.getMonth());
    setSelectedDay(n.getDate());
  };

  const cells: (number | null)[] = [];
  for (let i = 0; i < firstDay; i++) cells.push(null);
  for (let d = 1; d <= daysInMonth; d++) cells.push(d);
  while (cells.length % 7 !== 0) cells.push(null);

  const selectedEvents = selectedDay ? (eventsByDay[selectedDay] ?? []) : [];

  // Collect unique source colors for dots per day
  const getSourceDots = (dayEvents: UnifiedCalendarEvent[]) => {
    const seen = new Set<string>();
    const dots: { color: string; count: number }[] = [];
    for (const ev of dayEvents) {
      if (!seen.has(ev.color)) {
        seen.add(ev.color);
        dots.push({ color: ev.color, count: dayEvents.filter((e) => e.color === ev.color).length });
      }
    }
    return dots;
  };

  const dayLabel = selectedDay
    ? `${selectedDay}. ${months[month]} ${year}`
    : '';

  return (
    <div className="space-y-4">
      {/* Source Filters */}
      <CalendarSourceFilter sources={sources} onToggle={toggleSource} />

      {/* Month Navigation */}
      <div className="flex items-center justify-between">
        <button onClick={prevMonth} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary">
          <ChevronLeft className="w-5 h-5" />
        </button>
        <div className="flex items-center gap-3">
          <h2 className="text-lg font-semibold text-text">
            {months[month]} {year}
          </h2>
          {(year !== now.getFullYear() || month !== now.getMonth()) && (
            <Button size="sm" variant="ghost" onClick={goToday}>
              {t.calendar.today}
            </Button>
          )}
        </div>
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
        <div className="grid grid-cols-7 border-b border-border">
          {weekdays.map((d) => (
            <div key={d} className="py-2 text-center text-xs font-medium text-text-secondary uppercase">
              {d}
            </div>
          ))}
        </div>

        <div className="grid grid-cols-7">
          {cells.map((day, i) => {
            const dayEvents = day ? (eventsByDay[day] ?? []) : [];
            const isToday = day === today;
            const isSelected = day === selectedDay;
            const dots = getSourceDots(dayEvents);

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
                    {dots.length > 0 && (
                      <div className="flex gap-1 mt-1 flex-wrap">
                        {dots.map((dot, j) => (
                          <span
                            key={j}
                            className={`inline-block w-2 h-2 rounded-full ${dot.color}`}
                            title={`${dot.count}`}
                          />
                        ))}
                      </div>
                    )}
                    {dayEvents.length > 3 && (
                      <span className="text-[10px] text-text-tertiary mt-0.5 block">
                        +{dayEvents.length}
                      </span>
                    )}
                  </>
                )}
              </button>
            );
          })}
        </div>
      </div>

      {/* Selected Day Events */}
      {selectedDay !== null && (
        <CalendarEventList events={selectedEvents} dayLabel={dayLabel} />
      )}
    </div>
  );
}
