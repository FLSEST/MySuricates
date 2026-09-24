/**
 * CalDAV Adapter
 *
 * Fetches events from all calendars of the connected CalDAV account (Nextcloud, Radicale, …).
 * Only available when the 'caldav' integration is connected.
 *
 * The server expands series itself (`<C:expand>`), so every occurrence arrives as its own
 * event with a `recurrenceId`; the id therefore combines href and occurrence.
 *
 * API: POST /api/v1/integrations/caldav/actions/list_events (no `calendar` = all calendars)
 */

import { api } from '../../api/client';
import type { UnifiedCalendarEvent } from '../../types';
import type { CalendarSourceAdapter } from './types';

interface CalDavDateTime {
  dateTime?: string;
  date?: string;
  timeZone?: string;
}

export interface CalDavEvent {
  uid?: string;
  summary?: string;
  location?: string;
  start?: CalDavDateTime;
  end?: CalDavDateTime;
  allDay?: boolean;
  recurrenceId?: string;
  href: string;
  calendar?: string;
}

export interface CalDavEventList {
  events?: CalDavEvent[];
}

const COLOR = 'bg-teal-500';

/** All-day dates are local calendar days; date-times carry their offset (or are floating local time). */
function toDate(dt: CalDavDateTime): Date {
  if (dt.date) return new Date(dt.date + 'T00:00:00');
  return new Date(dt.dateTime!);
}

export function toUnifiedEvents(data: CalDavEventList): UnifiedCalendarEvent[] {
  return (data.events ?? [])
    .filter((e) => e.start && (e.start.date || e.start.dateTime))
    .map((e): UnifiedCalendarEvent => ({
      id: `caldav-${e.href}${e.recurrenceId ? `#${e.recurrenceId}` : ''}`,
      title: e.summary || '(Kein Titel)',
      start: toDate(e.start!),
      end: e.end && (e.end.date || e.end.dateTime) ? toDate(e.end) : undefined,
      allDay: Boolean(e.start!.date),
      source: 'caldav',
      sourceLabel: 'CalDAV',
      color: COLOR,
      location: e.location,
    }));
}

export const caldavAdapter: CalendarSourceAdapter = {
  sourceId: 'caldav',
  label: 'CalDAV',
  color: COLOR,

  isAvailable: (integrations) =>
    integrations.some((p) => p.providerId === 'caldav' && p.status === 'CONNECTED'),

  fetchEvents: async (from, to) => {
    const data = await api.post<CalDavEventList>(
      '/integrations/caldav/actions/list_events',
      { from, to, limit: 500 }
    );
    return toUnifiedEvents(data);
  },
};
