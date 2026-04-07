import { useMemo } from 'react';
import { MapPin, ExternalLink } from 'lucide-react';
import type { UnifiedCalendarEvent } from '../../types';
import { useT } from '../../i18n';

function formatTime(date: Date): string {
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

interface Props {
  date: Date;
  events: UnifiedCalendarEvent[];
}

const HOURS = Array.from({ length: 24 }, (_, i) => i);

export function CalendarDayView({ date, events }: Props) {
  const t = useT();
  const isGerman = t.common.save === 'Speichern';

  const dayLabel = date.toLocaleDateString(isGerman ? 'de-DE' : 'en-US', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });

  const { allDay, timed } = useMemo(() => {
    const dayStart = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    const dayEnd = new Date(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59);

    const matching = events.filter((ev) => ev.start <= dayEnd && (ev.end ?? ev.start) >= dayStart);
    return {
      allDay: matching.filter((ev) => ev.allDay),
      timed: matching.filter((ev) => !ev.allDay),
    };
  }, [events, date]);

  const eventsByHour = useMemo(() => {
    const map: Record<number, UnifiedCalendarEvent[]> = {};
    for (const ev of timed) {
      const hour = ev.start.getHours();
      if (!map[hour]) map[hour] = [];
      map[hour].push(ev);
    }
    return map;
  }, [timed]);

  return (
    <div className="bg-surface rounded-xl border border-border overflow-hidden">
      <div className="px-4 py-3 border-b border-border">
        <h3 className="text-sm font-semibold text-text">{dayLabel}</h3>
      </div>

      {allDay.length > 0 && (
        <div className="px-4 py-2 border-b border-border bg-surface-secondary">
          <span className="text-xs font-medium text-text-secondary uppercase mb-1.5 block">
            {t.calendar.allDay}
          </span>
          <div className="space-y-1">
            {allDay.map((ev) => (
              <DayEventItem key={ev.id} event={ev} />
            ))}
          </div>
        </div>
      )}

      <div className="divide-y divide-border">
        {HOURS.map((hour) => {
          const hourEvents = eventsByHour[hour] ?? [];
          const hasEvents = hourEvents.length > 0;
          return (
            <div key={hour} className={`flex ${hasEvents ? 'min-h-[60px]' : 'h-10'}`}>
              <div className="w-14 shrink-0 px-2 py-1 text-right border-r border-border">
                <span className="text-xs text-text-tertiary">
                  {String(hour).padStart(2, '0')}:00
                </span>
              </div>
              <div className="flex-1 p-1 space-y-1">
                {hourEvents.map((ev) => (
                  <DayEventItem key={ev.id} event={ev} showTime />
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function DayEventItem({ event, showTime }: { event: UnifiedCalendarEvent; showTime?: boolean }) {
  const content = (
    <div className="flex items-start gap-2 p-2 rounded-lg bg-surface-secondary hover:bg-surface-hover transition-colors group">
      <span className={`w-1 self-stretch rounded-full shrink-0 ${event.color}`} />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-text truncate">{event.title}</p>
        <div className="flex items-center gap-2 mt-0.5">
          {showTime && (
            <span className="text-xs text-text-secondary">
              {formatTime(event.start)}
              {event.end ? ` – ${formatTime(event.end)}` : ''}
            </span>
          )}
          <span className="text-xs text-text-tertiary">{event.sourceLabel}</span>
          {event.status && (
            <>
              <span className="text-xs text-text-tertiary">·</span>
              <span className={`text-xs ${
                event.status === 'COMPLETED' || event.status === 'DONE' ? 'text-green-500'
                : event.status === 'FAILED' ? 'text-red-500'
                : 'text-text-tertiary'
              }`}>
                {event.status}
              </span>
            </>
          )}
        </div>
        {event.location && (
          <div className="flex items-center gap-1 mt-0.5">
            <MapPin className="w-3 h-3 text-text-tertiary" />
            <span className="text-xs text-text-tertiary truncate">{event.location}</span>
          </div>
        )}
      </div>
      {event.link && (
        <ExternalLink className="w-3.5 h-3.5 text-text-tertiary opacity-0 group-hover:opacity-100 shrink-0 mt-0.5" />
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
