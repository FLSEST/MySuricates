import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys } from './useGoals';
import type { Goal, GoalGuardRequest, ScheduledWorkflow } from '../types';

/**
 * Load the dashboard registry (including chart widgets) only when switching on – the goal page
 * never needs it otherwise.
 */
async function addGuardTile(workflowId: string): Promise<boolean> {
  const { addWorkflowResultTile } = await import('../components/dashboard/workflowResultTile');
  return addWorkflowResultTile(workflowId);
}

/** `HH:MM` → hour and minute; null for invalid input. */
function parseTime(time: string): { hour: number; minute: number } | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(time);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  return hour > 23 || minute > 59 ? null : { hour, minute };
}

/** `HH:MM` → Spring CRON with seconds, daily at this time; null for invalid input. */
export function dailyCron(time: string): string | null {
  const parsed = parseTime(time);
  return parsed ? `0 ${parsed.minute} ${parsed.hour} * * *` : null;
}

/** Fields of a Spring CRON with a fixed time (second, minute and hour each a number), otherwise null. */
function fixedTimeFields(cron: string): string[] | null {
  const fields = cron.trim().split(/\s+/);
  return fields.length === 6 && fields.slice(0, 3).every((field) => /^\d{1,2}$/.test(field)) ? fields : null;
}

/** The fixed time of a Spring CRON as `HH:MM` – null if it has none (e.g. "every 15 minutes"). */
export function timeOfCron(cron: string): string | null {
  const fields = fixedTimeFields(cron);
  return fields ? `${fields[2]!.padStart(2, '0')}:${fields[1]!.padStart(2, '0')}` : null;
}

/** Day, month and weekday arbitrary: the CRON runs every day. */
export function isDailyCron(cron: string): boolean {
  const fields = fixedTimeFields(cron);
  return !!fields && fields.slice(3).every((field) => field === '*' || field === '?');
}

/**
 * Sets the time of a CRON with a fixed time and leaves days, months and weekdays alone –
 * whoever set the guard to weekdays under "Zeitpläne" keeps that. Null for an invalid
 * time or a CRON without a fixed time.
 */
export function cronWithTime(cron: string, time: string): string | null {
  const fields = fixedTimeFields(cron);
  const parsed = parseTime(time);
  if (!fields || !parsed) return null;
  return [fields[0], String(parsed.minute), String(parsed.hour), ...fields.slice(3)].join(' ');
}

/**
 * Change the guard's time on the goal page (TODO ff6f08fd) – via the schedule API as under
 * "Zeitpläne": only the CRON of its schedule changes, a paused guard stays paused.
 */
export function useSetGuardTime() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ scheduleId, cron }: { scheduleId: string; cron: string }) =>
      api.put<ScheduledWorkflow>(`/schedules/${scheduleId}`, { cronExpression: cron }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: goalKeys.all });
      qc.invalidateQueries({ queryKey: ['schedules'] });
      toast.success(t.goals.guard.timeSaved);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.guard.timeSaveFailed)),
  });
}

/**
 * Goal guard on and off (TODO e192df18, backend 259f4f66). When switching on, the result tile of its
 * workflow goes onto the dashboard – as from the catalog "Kachel hinzufügen" – if no
 * view shows it yet. Switching off only pauses the schedule and leaves the tile in place.
 */
export function useSetGoalGuard() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, request }: { id: string; request: GoalGuardRequest }) => api.post<Goal>(`/goals/${id}/guard`, request),
    onSuccess: (goal, { request }) => {
      qc.invalidateQueries({ queryKey: goalKeys.all });
      qc.invalidateQueries({ queryKey: ['schedules'] });
      qc.invalidateQueries({ queryKey: ['workflows'] });
      if (!request.enabled) {
        toast.success(t.goals.guard.disabled);
        return;
      }
      toast.success(t.goals.guard.enabled);
      const workflowId = goal.guard?.workflowId;
      // Do not wait: the dialog closes immediately, the tile follows as soon as the registry is loaded.
      if (workflowId) {
        void addGuardTile(workflowId)
          .then((added) => { if (added) toast.success(t.goals.guard.tileAdded); })
          .catch(() => { /* without a tile the guard runs anyway; it can be added in the catalog */ });
      }
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.guard.saveFailed)),
  });
}
