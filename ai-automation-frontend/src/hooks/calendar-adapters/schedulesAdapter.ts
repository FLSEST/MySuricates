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
import type { CalendarDataResponse, ScheduledWorkflow, UnifiedCalendarEvent } from '../../types';
import type { CalendarSourceAdapter } from './types';

export const schedulesAdapter: CalendarSourceAdapter = {
  sourceId: 'schedules',
  label: 'Zeitpläne',
  color: 'bg-chart-3',

  isAvailable: () => true,

  fetchEvents: async (from, to) => {
    const data = await api.get<CalendarDataResponse>(
      `/schedules/calendar?from=${from}&to=${to}`
    );

    // nextRunAt is the run that really comes: behind an active skip-until pause, and null for a
    // one-off the pause lets expire — then scheduledAt is no fallback (a776fd2a).
    const dateOf = (s: ScheduledWorkflow) => (s.pausedUntil ? s.nextRunAt : s.nextRunAt || s.scheduledAt);
    return (data.upcoming ?? [])
      .filter((s) => dateOf(s))
      .map((s): UnifiedCalendarEvent => ({
        id: `sched-${s.id}`,
        title: s.workflowName ?? s.chainName ?? '',
        start: new Date(dateOf(s)!),
        allDay: false,
        source: 'schedules',
        sourceLabel: 'Zeitpläne',
        color: 'bg-chart-3',
        status: s.scheduleType === 'ONCE' ? 'ONCE' : 'CRON',
        link: s.workflowId ? `/workflows/${s.workflowId}` : undefined,
      }));
  },
};
