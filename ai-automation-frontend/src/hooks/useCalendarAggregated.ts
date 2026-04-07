import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useIntegrations } from './useIntegrations';
import { useT } from '../i18n';
import type {
  UnifiedCalendarEvent,
  CalendarSourceConfig,
  GoogleEventListResponse,
  CalendarDataResponse,
} from '../types';

function parseGoogleDateTime(dt: { dateTime?: string; date?: string }): { date: Date; allDay: boolean } {
  if (dt.dateTime) return { date: new Date(dt.dateTime), allDay: false };
  if (dt.date) return { date: new Date(dt.date + 'T00:00:00'), allDay: true };
  return { date: new Date(), allDay: false };
}

function useGoogleCalendarEvents(from: string, to: string, enabled: boolean) {
  return useQuery({
    queryKey: ['calendar', 'google-calendar', from, to],
    queryFn: () =>
      api.post<GoogleEventListResponse>('/integrations/google-calendar/actions/list_events', {
        timeMin: from,
        timeMax: to,
        maxResults: 200,
      }),
    enabled,
    staleTime: 60_000,
  });
}

function useScheduleCalendarData(from: string, to: string) {
  return useQuery({
    queryKey: ['calendar', 'schedules', from, to],
    queryFn: () => api.get<CalendarDataResponse>(`/schedules/calendar?from=${from}&to=${to}`),
    enabled: !!from && !!to,
    staleTime: 60_000,
  });
}

export function useCalendarAggregated(from: string, to: string, enabledSources: Set<string>) {
  const t = useT();
  const { data: integrations } = useIntegrations();

  const googleConnected = integrations?.some(
    (p) => p.providerId === 'google-calendar' && p.status === 'CONNECTED'
  ) ?? false;

  const googleQuery = useGoogleCalendarEvents(
    from, to,
    googleConnected && enabledSources.has('google-calendar')
  );
  const scheduleQuery = useScheduleCalendarData(from, to);

  const sources: CalendarSourceConfig[] = [
    ...(googleConnected ? [{
      sourceId: 'google-calendar',
      label: 'Google Calendar',
      color: 'bg-blue-500',
      enabled: enabledSources.has('google-calendar'),
    }] : []),
    {
      sourceId: 'schedules',
      label: t.calendar?.sources?.schedules ?? 'Schedules',
      color: 'bg-violet-500',
      enabled: enabledSources.has('schedules'),
    },
    {
      sourceId: 'runs',
      label: t.calendar?.sources?.runs ?? 'Runs',
      color: 'bg-green-500',
      enabled: enabledSources.has('runs'),
    },
  ];

  const events: UnifiedCalendarEvent[] = [];

  // Google Calendar events
  if (googleQuery.data?.items && enabledSources.has('google-calendar')) {
    for (const item of googleQuery.data.items) {
      const { date: start, allDay } = parseGoogleDateTime(item.start);
      const end = item.end ? parseGoogleDateTime(item.end).date : undefined;
      events.push({
        id: `gcal-${item.id}`,
        title: item.summary || '(Kein Titel)',
        start,
        end,
        allDay,
        source: 'google-calendar',
        sourceLabel: 'Google Calendar',
        color: 'bg-blue-500',
        location: item.location,
        link: item.htmlLink,
      });
    }
  }

  // Schedule events
  if (scheduleQuery.data && enabledSources.has('schedules')) {
    for (const s of scheduleQuery.data.upcoming) {
      const date = s.nextRunAt || s.scheduledAt;
      if (date) {
        events.push({
          id: `sched-${s.id}`,
          title: s.workflowName ?? s.chainName ?? '',
          start: new Date(date),
          allDay: false,
          source: 'schedules',
          sourceLabel: t.calendar?.sources?.schedules ?? 'Schedules',
          color: 'bg-violet-500',
          status: s.scheduleType === 'ONCE' ? 'ONCE' : 'CRON',
          link: s.workflowId ? `/workflows/${s.workflowId}` : undefined,
        });
      }
    }
  }

  // Run events
  if (scheduleQuery.data && enabledSources.has('runs')) {
    for (const r of scheduleQuery.data.pastRuns) {
      if (r.startedAt) {
        const isFailed = r.status === 'FAILED';
        events.push({
          id: `run-${r.id}`,
          title: r.workflowName,
          start: new Date(r.startedAt),
          allDay: false,
          source: 'runs',
          sourceLabel: 'Runs',
          color: isFailed ? 'bg-red-500' : 'bg-green-500',
          status: r.status,
          link: `/workflows/${r.workflowId}/run/${r.id}`,
        });
      }
    }
  }

  events.sort((a, b) => a.start.getTime() - b.start.getTime());

  return {
    events,
    sources,
    isLoading: googleQuery.isLoading || scheduleQuery.isLoading,
  };
}
