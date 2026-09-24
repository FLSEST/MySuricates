import { useState, useMemo } from 'react';
import { ChevronLeft, ChevronRight, CalendarDays, CalendarRange, Clock } from 'lucide-react';
import { Button, Spinner } from '../ui';
import { CalendarSourceFilter } from './CalendarSourceFilter';
import { CalendarEventList } from './CalendarEventList';
import { CalendarWeekView } from './CalendarWeekView';
import { CalendarDayView } from './CalendarDayView';
import { useCalendarAggregated } from '../../hooks/useCalendarAggregated';
import { useT, useI18nStore } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import type { UnifiedCalendarEvent } from '../../types';

type ViewMode = 'month' | 'week' | 'day';

function getDaysInMonth(year: number, month: number) {
  return new Date(year, month + 1, 0).getDate();
}

function getFirstDayOfWeek(year: number, month: number) {
  const day = new Date(year, month, 1).getDay();
  return day === 0 ? 6 : day - 1;
}

function getMonday(date: Date): Date {
  const d = new Date(date);
  const day = d.getDay();
  const diff = day === 0 ? -6 : 1 - day;
  d.setDate(d.getDate() + diff);
  d.setHours(0, 0, 0, 0);
  return d;
}

const WEEKDAYS_EN = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const WEEKDAYS_DE = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
const MONTHS_EN = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const MONTHS_DE = ['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];

const VIEW_LABELS = {
  month: { de: 'Monat', en: 'Month' },
  week: { de: 'Woche', en: 'Week' },
  day: { de: 'Tag', en: 'Day' },
};

export function UnifiedCalendarGrid() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const isGerman = locale === 'de';
  const weekdays = isGerman ? WEEKDAYS_DE : WEEKDAYS_EN;
  const months = isGerman ? MONTHS_DE : MONTHS_EN;

  const now = new Date();
  const [viewMode, setViewMode] = useState<ViewMode>('month');
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth());
  const [selectedDay, setSelectedDay] = useState<number | null>(now.getDate());
  const [currentDate, setCurrentDate] = useState(now);
  const [enabledSources, setEnabledSources] = useState<Set<string>>(
    new Set(['google-calendar', 'trello', 'schedules', 'runs'])
  );

  // Compute date range based on view mode
  const { from, to } = useMemo(() => {
    if (viewMode === 'month') {
      return {
        from: new Date(year, month, 1).toISOString(),
        to: new Date(year, month + 1, 0, 23, 59, 59).toISOString(),
      };
    }
    if (viewMode === 'week') {
      const monday = getMonday(currentDate);
      const sunday = new Date(monday);
      sunday.setDate(sunday.getDate() + 6);
      sunday.setHours(23, 59, 59);
      return { from: monday.toISOString(), to: sunday.toISOString() };
    }
    // day
    const dayStart = new Date(currentDate.getFullYear(), currentDate.getMonth(), currentDate.getDate());
    const dayEnd = new Date(currentDate.getFullYear(), currentDate.getMonth(), currentDate.getDate(), 23, 59, 59);
    return { from: dayStart.toISOString(), to: dayEnd.toISOString() };
  }, [viewMode, year, month, currentDate]);

  const { events, sources, isLoading, setSourceColor } = useCalendarAggregated(from, to, enabledSources);

  const toggleSource = (sourceId: string) => {
    setEnabledSources((prev) => {
      const next = new Set(prev);
      if (next.has(sourceId)) next.delete(sourceId);
      else next.add(sourceId);
      return next;
    });
  };

  // Navigation handlers
  const goToday = () => {
    const n = new Date();
    setYear(n.getFullYear());
    setMonth(n.getMonth());
    setSelectedDay(n.getDate());
    setCurrentDate(n);
  };

  const goPrev = () => {
    if (viewMode === 'month') {
      if (month === 0) { setMonth(11); setYear(year - 1); }
      else setMonth(month - 1);
      setSelectedDay(null);
    } else if (viewMode === 'week') {
      const d = new Date(currentDate);
      d.setDate(d.getDate() - 7);
      setCurrentDate(d);
    } else {
      const d = new Date(currentDate);
      d.setDate(d.getDate() - 1);
      setCurrentDate(d);
    }
  };

  const goNext = () => {
    if (viewMode === 'month') {
      if (month === 11) { setMonth(0); setYear(year + 1); }
      else setMonth(month + 1);
      setSelectedDay(null);
    } else if (viewMode === 'week') {
      const d = new Date(currentDate);
      d.setDate(d.getDate() + 7);
      setCurrentDate(d);
    } else {
      const d = new Date(currentDate);
      d.setDate(d.getDate() + 1);
      setCurrentDate(d);
    }
  };

  // Title for header
  const headerTitle = useMemo(() => {
    if (viewMode === 'month') return `${months[month]} ${year}`;
    if (viewMode === 'week') {
      const monday = getMonday(currentDate);
      const sunday = new Date(monday);
      sunday.setDate(sunday.getDate() + 6);
      const fmtDay = (d: Date) => `${d.getDate()}. ${months[d.getMonth()]}`;
      if (monday.getMonth() === sunday.getMonth()) {
        return `${monday.getDate()}. – ${fmtDay(sunday)} ${sunday.getFullYear()}`;
      }
      return `${fmtDay(monday)} – ${fmtDay(sunday)} ${sunday.getFullYear()}`;
    }
    return formatDate(currentDate, locale, {
      weekday: 'long', day: 'numeric', month: 'long', year: 'numeric',
    });
  }, [viewMode, month, year, currentDate, months, locale]);

  const isNotToday = viewMode === 'month'
    ? (year !== now.getFullYear() || month !== now.getMonth())
    : currentDate.toDateString() !== now.toDateString();

  return (
    <div className="space-y-4">
      {/* Source Filters */}
      <CalendarSourceFilter sources={sources} onToggle={toggleSource} onColorChange={setSourceColor} />

      {/* Navigation + View Mode Toggle */}
      <div className="flex items-center justify-between">
        <button onClick={goPrev} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary">
          <ChevronLeft className="w-5 h-5" />
        </button>
        <div className="flex items-center gap-3">
          <h2 className="text-lg font-semibold text-text">{headerTitle}</h2>
          {isNotToday && (
            <Button size="sm" variant="ghost" onClick={goToday}>
              {t.calendar.today}
            </Button>
          )}
        </div>
        <button onClick={goNext} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary">
          <ChevronRight className="w-5 h-5" />
        </button>
      </div>

      {/* View Mode Buttons */}
      <div className="flex justify-center">
        <div className="inline-flex rounded-lg border border-border bg-surface overflow-hidden">
          {(['month', 'week', 'day'] as ViewMode[]).map((mode) => {
            const Icon = mode === 'month' ? CalendarDays : mode === 'week' ? CalendarRange : Clock;
            return (
              <button
                key={mode}
                onClick={() => {
                  setViewMode(mode);
                  if (mode === 'week' || mode === 'day') {
                    const d = selectedDay
                      ? new Date(year, month, selectedDay)
                      : new Date(year, month, 1);
                    setCurrentDate(d);
                  }
                }}
                className={`
                  inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium transition-colors
                  ${viewMode === mode
                    ? 'bg-accent text-text-inverse'
                    : 'text-text-secondary hover:bg-surface-hover'
                  }
                `}
              >
                <Icon className="w-3.5 h-3.5" />
                {VIEW_LABELS[mode][isGerman ? 'de' : 'en']}
              </button>
            );
          })}
        </div>
      </div>

      {isLoading && (
        <div className="flex justify-center py-4">
          <Spinner text={t.common.loading} />
        </div>
      )}

      {/* Views */}
      {viewMode === 'month' && (
        <MonthGrid
          year={year}
          month={month}
          events={events}
          selectedDay={selectedDay}
          onSelectDay={(day) => setSelectedDay(day === selectedDay ? null : day)}
          onDayDoubleClick={(day) => {
            setCurrentDate(new Date(year, month, day));
            setViewMode('day');
          }}
          weekdays={weekdays}
        />
      )}

      {viewMode === 'week' && (
        <CalendarWeekView
          weekStart={getMonday(currentDate)}
          events={events}
          onDayClick={(date) => {
            setCurrentDate(date);
            setViewMode('day');
          }}
        />
      )}

      {viewMode === 'day' && (
        <CalendarDayView date={currentDate} events={events} />
      )}

      {/* Selected Day Events (month view only) */}
      {viewMode === 'month' && selectedDay !== null && (
        <CalendarEventList
          events={events.filter(
            (ev) => ev.start.getFullYear() === year && ev.start.getMonth() === month && ev.start.getDate() === selectedDay
          )}
          dayLabel={`${selectedDay}. ${months[month]} ${year}`}
        />
      )}
    </div>
  );
}

// --- Month Grid (extracted from original) ---

function MonthGrid({
  year, month, events, selectedDay, onSelectDay, onDayDoubleClick, weekdays,
}: {
  year: number;
  month: number;
  events: UnifiedCalendarEvent[];
  selectedDay: number | null;
  onSelectDay: (day: number) => void;
  onDayDoubleClick: (day: number) => void;
  weekdays: string[];
}) {
  const now = new Date();
  const today = now.getFullYear() === year && now.getMonth() === month ? now.getDate() : -1;
  const daysInMonth = getDaysInMonth(year, month);
  const firstDay = getFirstDayOfWeek(year, month);

  const eventsByDay = useMemo(() => {
    const map: Record<number, UnifiedCalendarEvent[]> = {};
    const monthStart = new Date(year, month, 1);
    const monthEnd = new Date(year, month + 1, 0);
    for (const ev of events) {
      const evEnd = ev.end ?? ev.start;
      if (evEnd < monthStart || ev.start > monthEnd) continue;
      const rangeStart = ev.start < monthStart ? 1 : ev.start.getDate();
      const rangeEnd = evEnd > monthEnd ? monthEnd.getDate() : evEnd.getDate();
      for (let d = rangeStart; d <= rangeEnd; d++) {
        (map[d] ??= []).push(ev);
      }
    }
    return map;
  }, [events, year, month]);

  const cells: (number | null)[] = [];
  for (let i = 0; i < firstDay; i++) cells.push(null);
  for (let d = 1; d <= daysInMonth; d++) cells.push(d);
  while (cells.length % 7 !== 0) cells.push(null);

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

  return (
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
              onClick={() => day && onSelectDay(day)}
              onDoubleClick={() => day && onDayDoubleClick(day)}
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
                    ${isToday ? 'bg-accent text-text-inverse' : 'text-text'}
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
  );
}
