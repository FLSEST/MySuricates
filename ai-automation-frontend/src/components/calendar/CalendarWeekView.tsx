import { useMemo } from 'react';
import { MapPin, ExternalLink } from 'lucide-react';
import type { UnifiedCalendarEvent } from '../../types';
import { useI18nStore } from '../../i18n';
import { formatDate, formatTimeOfDay } from '../../utils/localeFormat';


interface Props {
  weekStart: Date;
  events: UnifiedCalendarEvent[];
  onDayClick?: (date: Date) => void;
}

export function CalendarWeekView({ weekStart, events, onDayClick }: Props) {
  const locale = useI18nStore((s) => s.locale);
  const now = new Date();
  const todayStr = `${now.getFullYear()}-${now.getMonth()}-${now.getDate()}`;

  const days = useMemo(() => {
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(weekStart);
      d.setDate(d.getDate() + i);
      return d;
    });
  }, [weekStart]);

  const eventsByDay = useMemo(() => {
    const map: Record<string, UnifiedCalendarEvent[]> = {};
    for (const day of days) {
      const key = `${day.getFullYear()}-${day.getMonth()}-${day.getDate()}`;
      map[key] = [];
    }
    for (const ev of events) {
      const evEnd = ev.end ?? ev.start;
      const cursor = new Date(ev.start.getFullYear(), ev.start.getMonth(), ev.start.getDate());
      const endDay = new Date(evEnd.getFullYear(), evEnd.getMonth(), evEnd.getDate());
      while (cursor <= endDay) {
        const key = `${cursor.getFullYear()}-${cursor.getMonth()}-${cursor.getDate()}`;
        if (map[key]) map[key].push(ev);
        cursor.setDate(cursor.getDate() + 1);
      }
    }
    return map;
  }, [events, days]);

  return (
    <div className="bg-surface rounded-xl border border-border overflow-hidden">
      <div className="grid grid-cols-7 min-h-[400px]">
        {days.map((day, i) => {
          const key = `${day.getFullYear()}-${day.getMonth()}-${day.getDate()}`;
          const dayEvents = eventsByDay[key] ?? [];
          const isToday = key === todayStr;

          return (
            <div
              key={i}
              className={`border-r border-border last:border-r-0 flex flex-col ${isToday ? 'bg-accent/5' : ''}`}
            >
              <button
                onClick={() => onDayClick?.(day)}
                className="py-2 px-2 border-b border-border text-center hover:bg-surface-hover transition-colors"
              >
                <span className="text-xs font-medium text-text-secondary uppercase">{formatDate(day, locale, { weekday: 'short' })}</span>
                <span className={`
                  block text-lg font-semibold mt-0.5
                  ${isToday ? 'text-accent' : 'text-text'}
                `}>
                  {day.getDate()}
                </span>
                <span className="text-[10px] text-text-tertiary">
                  {formatDate(day, locale, { month: 'short' })}
                </span>
              </button>
              <div className="flex-1 p-1.5 space-y-1 overflow-y-auto">
                {dayEvents.map((ev) => (
                  <WeekEventCard key={ev.id} event={ev} />
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function WeekEventCard({ event }: { event: UnifiedCalendarEvent }) {
  const locale = useI18nStore((s) => s.locale);
  const timeLabel = event.allDay ? '' : formatTimeOfDay(event.start, locale, { hour: '2-digit', minute: '2-digit' });

  const content = (
    <div className="flex items-start gap-1.5 p-1.5 rounded-md bg-surface-secondary hover:bg-surface-hover transition-colors group text-left">
      <span className={`w-1 self-stretch rounded-full shrink-0 ${event.color}`} />
      <div className="min-w-0 flex-1">
        <p className="text-[11px] font-medium text-text truncate leading-tight">{event.title}</p>
        {timeLabel && (
          <span className="text-[10px] text-text-secondary">{timeLabel}</span>
        )}
        {event.location && (
          <div className="flex items-center gap-0.5 mt-0.5">
            <MapPin className="w-2.5 h-2.5 text-text-tertiary" />
            <span className="text-[10px] text-text-tertiary truncate">{event.location}</span>
          </div>
        )}
      </div>
      {event.link && (
        <ExternalLink className="w-3 h-3 text-text-tertiary opacity-0 group-hover:opacity-100 shrink-0" />
      )}
    </div>
  );

  if (event.link) {
    return (
      <a href={event.link} target={event.source === 'google-calendar' || event.source === 'trello' ? '_blank' : undefined} rel="noopener noreferrer">
        {content}
      </a>
    );
  }

  return content;
}
