import { useState, useMemo } from 'react';
import { ChevronLeft, ChevronRight, CalendarDays, CalendarRange, Clock } from 'lucide-react';
import { Button, Spinner } from '../ui';
import { CalendarSourceFilter } from './CalendarSourceFilter';
import { CalendarEventList } from './CalendarEventList';
import { CalendarMonthView } from './CalendarMonthView';
import { CalendarWeekView } from './CalendarWeekView';
import { CalendarDayView } from './CalendarDayView';
import { useCalendarAggregated } from '../../hooks/useCalendarAggregated';
import { useCalendarNavigation, type CalendarViewMode } from '../../hooks/useCalendarNavigation';
import { useT, useI18nStore } from '../../i18n';
import { formatDate, formatDateRange, formatNumber } from '../../utils/localeFormat';

const DAY_MONTH_YEAR = { day: 'numeric', month: 'long', year: 'numeric' } as const;

export function UnifiedCalendarGrid() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  const {
    viewMode, year, month, selectedDay, currentDate, weekStart, from, to, isNotToday,
    goPrev, goNext, goToday, switchView, toggleDay, openDay,
  } = useCalendarNavigation();
  const [enabledSources, setEnabledSources] = useState<Set<string>>(
    new Set(['google-calendar', 'caldav', 'trello', 'schedules', 'runs'])
  );

  const { events, sources, limits = [], isLoading, setSourceColor } = useCalendarAggregated(from, to, enabledSources);

  const toggleSource = (sourceId: string) => {
    setEnabledSources((prev) => {
      const next = new Set(prev);
      if (next.has(sourceId)) next.delete(sourceId);
      else next.add(sourceId);
      return next;
    });
  };

  // Title for header, written the way the UI language writes dates (6bb7ed36)
  const headerTitle = useMemo(() => {
    if (viewMode === 'month') return formatDate(new Date(year, month, 1), locale, { month: 'long', year: 'numeric' });
    if (viewMode === 'week') {
      const sunday = new Date(weekStart);
      sunday.setDate(sunday.getDate() + 6);
      return formatDateRange(weekStart, sunday, locale, DAY_MONTH_YEAR);
    }
    return formatDate(currentDate, locale, { weekday: 'long', ...DAY_MONTH_YEAR });
  }, [viewMode, month, year, currentDate, weekStart, locale]);

  return (
    <div className="space-y-4">
      {/* Source Filters */}
      <CalendarSourceFilter sources={sources} onToggle={toggleSource} onColorChange={setSourceColor} />

      {/* Navigation + View Mode Toggle */}
      <div className="flex items-center justify-between">
        <button onClick={goPrev} aria-label={t.calendar.previous} title={t.calendar.previous} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary">
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
        <button onClick={goNext} aria-label={t.calendar.next} title={t.calendar.next} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary">
          <ChevronRight className="w-5 h-5" />
        </button>
      </div>

      {/* View Mode Buttons */}
      <div className="flex justify-center">
        <div className="inline-flex rounded-lg border border-border bg-surface overflow-hidden">
          {(['month', 'week', 'day'] as CalendarViewMode[]).map((mode) => {
            const Icon = mode === 'month' ? CalendarDays : mode === 'week' ? CalendarRange : Clock;
            return (
              <button
                key={mode}
                onClick={() => switchView(mode)}
                className={`
                  inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium transition-colors
                  ${viewMode === mode
                    ? 'bg-accent text-text-inverse'
                    : 'text-text-secondary hover:bg-surface-hover'
                  }
                `}
              >
                <Icon className="w-3.5 h-3.5" />
                {t.calendar.views[mode]}
              </button>
            );
          })}
        </div>
      </div>

      {/* A source that cut its list (runs: newest 1000 per range, 86777fb4) says so */}
      {limits.map((limit) => (
        <p key={limit.sourceId} className="text-xs text-text-tertiary" data-testid="calendar-source-limited">
          {(limit.sourceId === 'runs' ? t.calendar.runsLimited : t.calendar.sourceLimited)
            .replace('{source}', limit.label)
            .replace('{shown}', formatNumber(limit.shown, locale))
            .replace('{total}', formatNumber(limit.total, locale))}
        </p>
      ))}

      {isLoading && (
        <div className="flex justify-center py-4">
          <Spinner text={t.common.loading} />
        </div>
      )}

      {/* Views */}
      {viewMode === 'month' && (
        <CalendarMonthView
          year={year}
          month={month}
          events={events}
          selectedDay={selectedDay}
          onSelectDay={toggleDay}
          onDayDoubleClick={(day) => openDay(new Date(year, month, day))}
        />
      )}

      {viewMode === 'week' && (
        <CalendarWeekView weekStart={weekStart} events={events} onDayClick={openDay} />
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
          dayLabel={formatDate(new Date(year, month, selectedDay), locale, DAY_MONTH_YEAR)}
        />
      )}
    </div>
  );
}
