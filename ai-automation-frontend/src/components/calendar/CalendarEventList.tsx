import { MapPin, ExternalLink } from 'lucide-react';
import type { UnifiedCalendarEvent } from '../../types';
import { useI18nStore, useT, type Locale } from '../../i18n';
import { formatTimeOfDay } from '../../utils/localeFormat';

function formatTime(date: Date, locale: Locale): string {
  return formatTimeOfDay(date, locale, { hour: '2-digit', minute: '2-digit' });
}

interface Props {
  events: UnifiedCalendarEvent[];
  dayLabel: string;
}

export function CalendarEventList({ events, dayLabel }: Props) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  if (events.length === 0) {
    return (
      <div className="bg-surface rounded-xl border border-border p-4">
        <h3 className="text-sm font-semibold text-text mb-2">{dayLabel}</h3>
        <p className="text-sm text-text-secondary">{t.calendar.noEvents}</p>
      </div>
    );
  }

  const allDay = events.filter((e) => e.allDay);
  const timed = events.filter((e) => !e.allDay);

  return (
    <div className="bg-surface rounded-xl border border-border p-4">
      <h3 className="text-sm font-semibold text-text mb-3">{dayLabel}</h3>
      <div className="space-y-2">
        {allDay.map((ev) => (
          <EventItem key={ev.id} event={ev} timeLabel={t.calendar.allDay} />
        ))}
        {timed.map((ev) => (
          <EventItem
            key={ev.id}
            event={ev}
            timeLabel={`${formatTime(ev.start, locale)}${ev.end ? ` – ${formatTime(ev.end, locale)}` : ''}`}
          />
        ))}
      </div>
    </div>
  );
}

function EventItem({ event, timeLabel }: { event: UnifiedCalendarEvent; timeLabel: string }) {
  const inner = (
    <div className="flex items-start gap-3 p-2.5 rounded-lg bg-surface-secondary group hover:bg-surface-hover transition-colors">
      <span className={`w-1 self-stretch rounded-full shrink-0 ${event.color}`} />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-text truncate">{event.title}</p>
        <div className="flex items-center gap-2 mt-0.5">
          <span className="text-xs text-text-secondary">{timeLabel}</span>
          <span className="text-xs text-text-tertiary">·</span>
          <span className="text-xs text-text-tertiary">{event.sourceLabel}</span>
          {event.status && event.source === 'runs' && (
            <>
              <span className="text-xs text-text-tertiary">·</span>
              <span className={`text-xs ${event.status === 'COMPLETED' ? 'text-success' : event.status === 'FAILED' ? 'text-danger' : 'text-text-tertiary'}`}>
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
      {event.link && event.source === 'google-calendar' && (
        <ExternalLink className="w-3.5 h-3.5 text-text-tertiary opacity-0 group-hover:opacity-100 transition-opacity shrink-0 mt-1" />
      )}
    </div>
  );

  if (event.link && event.source === 'google-calendar') {
    return (
      <a href={event.link} target="_blank" rel="noopener noreferrer">
        {inner}
      </a>
    );
  }

  if (event.link && event.source !== 'google-calendar') {
    // Internal links use router (but we keep it simple with <a> for now)
    return <a href={event.link}>{inner}</a>;
  }

  return inner;
}
