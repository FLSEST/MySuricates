/**
 * Taskwarrior Adapter
 *
 * Fetches pending Taskwarrior tasks that have a due date and shows them as calendar events.
 * Only available when the 'taskwarrior' integration is connected.
 *
 * Taskwarrior runs as a local `task` CLI on the backend host; the integration's `list_tasks`
 * action returns the Taskwarrior export JSON (an array of task objects). Due dates use
 * Taskwarrior's compact UTC format ("YYYYMMDDTHHMMSSZ"), which `Date` does not parse natively,
 * so we normalize it first. Tasks are filtered to the requested range client-side, mirroring
 * the Trello adapter.
 *
 * API: POST /api/v1/integrations/taskwarrior/actions/list_tasks
 */

import { api } from '../../api/client';
import type { UnifiedCalendarEvent } from '../../types';
import type { CalendarSourceAdapter } from './types';

interface TaskwarriorTask {
  uuid: string;
  id?: number;
  description: string;
  status?: string;
  /** Compact UTC timestamp, e.g. "20260626T120000Z". */
  due?: string;
  project?: string;
  priority?: string;
  tags?: string[];
}

/** Converts Taskwarrior's "YYYYMMDDTHHMMSSZ" timestamp into a Date, or null if unparseable. */
function parseTaskwarriorDate(value: string | undefined): Date | null {
  if (!value) return null;
  const m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(value);
  if (!m) return null;
  const iso = `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]}Z`;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

export const taskwarriorAdapter: CalendarSourceAdapter = {
  sourceId: 'taskwarrior',
  label: 'Taskwarrior',
  color: 'bg-amber-500',

  isAvailable: (integrations) =>
    integrations.some((p) => p.providerId === 'taskwarrior' && p.status === 'CONNECTED'),

  fetchEvents: async (from, to) => {
    const tasks = await api.post<TaskwarriorTask[]>(
      '/integrations/taskwarrior/actions/list_tasks',
      { filter: 'status:pending' }
    );

    const fromDate = new Date(from);
    const toDate = new Date(to);

    return (tasks ?? [])
      .map((task) => ({ task, due: parseTaskwarriorDate(task.due) }))
      .filter((entry): entry is { task: TaskwarriorTask; due: Date } =>
        entry.due !== null && entry.due >= fromDate && entry.due <= toDate)
      .map(({ task, due }): UnifiedCalendarEvent => ({
        id: `tw-${task.uuid}`,
        title: task.description,
        start: due,
        allDay: true,
        source: 'taskwarrior',
        sourceLabel: 'Taskwarrior',
        color: 'bg-amber-500',
        status: 'DUE',
      }));
  },
};
