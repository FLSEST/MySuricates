/**
 * Workflow Runs Adapter
 *
 * Fetches past workflow run executions from the Schedules Calendar API.
 * Always available (system source, no integration required).
 *
 * API: GET /api/v1/schedules/calendar?from={ISO}&to={ISO}
 * Uses the `pastRuns` array from CalendarDataResponse.
 *
 * Color coding: green for COMPLETED, red for FAILED.
 */

import { api } from '../../api/client';
import type { CalendarDataResponse, UnifiedCalendarEvent } from '../../types';
import type { CalendarSourceAdapter } from './types';

export const runsAdapter: CalendarSourceAdapter = {
  sourceId: 'runs',
  label: 'Runs',
  color: 'bg-green-500',

  isAvailable: () => true,

  fetchEvents: async (from, to) => {
    const data = await api.get<CalendarDataResponse>(
      `/schedules/calendar?from=${from}&to=${to}`
    );

    return (data.pastRuns ?? [])
      .filter((r) => r.startedAt)
      .map((r): UnifiedCalendarEvent => {
        const isFailed = r.status === 'FAILED';
        return {
          id: `run-${r.id}`,
          title: r.workflowName,
          start: new Date(r.startedAt!),
          allDay: false,
          source: 'runs',
          sourceLabel: 'Runs',
          color: isFailed ? 'bg-red-500' : 'bg-green-500',
          status: r.status,
          link: `/workflows/${r.workflowId}/run/${r.id}`,
        };
      });
  },
};
