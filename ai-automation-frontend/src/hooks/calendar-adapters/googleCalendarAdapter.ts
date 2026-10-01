/**
 * Google Calendar Adapter
 *
 * Fetches events from Google Calendar via the Integration API.
 * Only available when the 'google-calendar' integration is connected.
 *
 * API: POST /api/v1/integrations/google-calendar/actions/list_events
 * Response: GoogleEventListResponse { items: GoogleCalendarEventResponse[] }
 */

import { api } from '../../api/client';
import type { GoogleEventListResponse, UnifiedCalendarEvent } from '../../types';
import type { CalendarSourceAdapter } from './types';

function parseGoogleDateTime(dt: { dateTime?: string; date?: string }): { date: Date; allDay: boolean } {
  if (dt.dateTime) return { date: new Date(dt.dateTime), allDay: false };
  if (dt.date) return { date: new Date(dt.date + 'T00:00:00'), allDay: true };
  return { date: new Date(), allDay: false };
}

export const googleCalendarAdapter: CalendarSourceAdapter<UnifiedCalendarEvent[]> = {
  sourceId: 'google-calendar',
  label: 'Google Calendar',
  color: 'bg-chart-1',

  isAvailable: (integrations) =>
    integrations.some((p) => p.providerId === 'google-calendar' && p.status === 'CONNECTED'),

  fetchEvents: async (from, to) => {
    const data = await api.post<GoogleEventListResponse>(
      '/integrations/google-calendar/actions/list_events',
      { timeMin: from, timeMax: to, maxResults: 200 }
    );

    return (data.items ?? []).map((item): UnifiedCalendarEvent => {
      const { date: start, allDay } = parseGoogleDateTime(item.start);
      const end = item.end ? parseGoogleDateTime(item.end).date : undefined;

      return {
        id: `gcal-${item.id}`,
        title: item.summary || '(Kein Titel)',
        start,
        end,
        allDay,
        source: 'google-calendar',
        sourceLabel: 'Google Calendar',
        color: 'bg-chart-1',
        location: item.location,
        link: item.htmlLink,
      };
    });
  },
};
