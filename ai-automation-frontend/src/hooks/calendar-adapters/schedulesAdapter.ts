/**
 * Schedules Adapter
 *
 * Fetches upcoming scheduled workflow/chain runs from the Schedules API.
 * Always available (system source, no integration required).
 *
 * API: GET /api/v1/schedules/calendar?from={ISO}&to={ISO}
 * Uses the `upcoming` array from CalendarDataResponse.
 */

import { api } from '../../api/client';
import type { CalendarDataResponse, UnifiedCalendarEvent } from '../../types';
import type { CalendarSourceAdapter } from './types';

export const schedulesAdapter: CalendarSourceAdapter = {
  sourceId: 'schedules',
  label: 'Zeitpläne',
  color: 'bg-violet-500',

  isAvailable: () => true,

  fetchEvents: async (from, to) => {
    const data = await api.get<CalendarDataResponse>(
      `/schedules/calendar?from=${from}&to=${to}`
    );

    return (data.upcoming ?? [])
      .filter((s) => s.nextRunAt || s.scheduledAt)
      .map((s): UnifiedCalendarEvent => ({
        id: `sched-${s.id}`,
        title: s.workflowName ?? s.chainName ?? '',
        start: new Date(s.nextRunAt || s.scheduledAt!),
        allDay: false,
        source: 'schedules',
        sourceLabel: 'Zeitpläne',
        color: 'bg-violet-500',
        status: s.scheduleType === 'ONCE' ? 'ONCE' : 'CRON',
        link: s.workflowId ? `/workflows/${s.workflowId}` : undefined,
      }));
  },
};
