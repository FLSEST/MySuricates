import { useMemo } from 'react';
import { useI18nStore } from '../../i18n';
import { weekdayNames } from '../../utils/localeFormat';
import type { UnifiedCalendarEvent } from '../../types';


function getDaysInMonth(year: number, month: number) {
  return new Date(year, month + 1, 0).getDate();
}

function getFirstDayOfWeek(year: number, month: number) {
  const day = new Date(year, month, 1).getDay();
  return day === 0 ? 6 : day - 1;
}

interface Props {
  year: number;
  month: number;
  events: UnifiedCalendarEvent[];
  selectedDay: number | null;
  onSelectDay: (day: number) => void;
  onDayDoubleClick: (day: number) => void;
}

export function CalendarMonthView({ year, month, events, selectedDay, onSelectDay, onDayDoubleClick }: Props) {
  const locale = useI18nStore((s) => s.locale);
  const weekdays = weekdayNames(locale);
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
