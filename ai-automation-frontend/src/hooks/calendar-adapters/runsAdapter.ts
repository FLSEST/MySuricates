/**
 * Workflow Runs Adapter
 *
 * Fetches past workflow run executions from the Schedules Calendar API.
 * Always available (system source, no integration required).
 *
 * API: GET /api/v1/schedules/calendar?from={ISO}&to={ISO}
 * Uses the `pastRuns` array from CalendarDataResponse.
 *
 * Color coding: success token for COMPLETED, danger token for FAILED.
 *
 * Newer backends send at most 1000 past runs per range plus `pastRunsTotal` (86777fb4); then the
 * result carries `limited`, and the calendar names the cut. Older backends send all runs and no total.
 */

import { api } from '../../api/client';
import type { CalendarDataResponse, UnifiedCalendarEvent } from '../../types';
import type { CalendarSourceAdapter, CalendarSourceResult } from './types';

export const runsAdapter: CalendarSourceAdapter = {
  sourceId: 'runs',
  label: 'Runs',
  color: 'bg-success',

  isAvailable: () => true,

  fetchEvents: async (from, to): Promise<CalendarSourceResult> => {
    const data = await api.get<CalendarDataResponse>(
      `/schedules/calendar?from=${from}&to=${to}`
    );
    const pastRuns = data.pastRuns ?? [];
    const total = data.pastRunsTotal;

    const events = pastRuns
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
          color: isFailed ? 'bg-danger' : 'bg-success',
          status: r.status,
          link: `/workflows/${r.workflowId}/run/${r.id}`,
        };
      });
    return total != null && total > pastRuns.length
      ? { events, limited: { shown: pastRuns.length, total } }
      : { events };
  },
};
