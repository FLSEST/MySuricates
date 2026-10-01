import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys } from './useGoals';
import type { Goal, GoalGuardRequest, ScheduledWorkflow } from '../types';

/**
 * Die Dashboard-Registry (samt Diagramm-Widgets) erst beim Einschalten laden – die Zielseite braucht
 * sie sonst nie.
 */
async function addGuardTile(workflowId: string): Promise<boolean> {
  const { addWorkflowResultTile } = await import('../components/dashboard/workflowResultTile');
  return addWorkflowResultTile(workflowId);
}

/** `HH:MM` → Stunde und Minute; null für eine ungültige Eingabe. */
function parseTime(time: string): { hour: number; minute: number } | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(time);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  return hour > 23 || minute > 59 ? null : { hour, minute };
}

/** `HH:MM` → Spring-CRON mit Sekunden, täglich zu dieser Uhrzeit; null für eine ungültige Eingabe. */
export function dailyCron(time: string): string | null {
  const parsed = parseTime(time);
  return parsed ? `0 ${parsed.minute} ${parsed.hour} * * *` : null;
}

/** Felder eines Spring-CRONs mit fester Uhrzeit (Sekunde, Minute und Stunde je eine Zahl), sonst null. */
function fixedTimeFields(cron: string): string[] | null {
  const fields = cron.trim().split(/\s+/);
  return fields.length === 6 && fields.slice(0, 3).every((field) => /^\d{1,2}$/.test(field)) ? fields : null;
}

/** Die feste Uhrzeit eines Spring-CRONs als `HH:MM` – null, wenn er keine hat (etwa „alle 15 Minuten“). */
export function timeOfCron(cron: string): string | null {
  const fields = fixedTimeFields(cron);
  return fields ? `${fields[2]!.padStart(2, '0')}:${fields[1]!.padStart(2, '0')}` : null;
}

/** Tag, Monat und Wochentag beliebig: der CRON läuft jeden Tag. */
export function isDailyCron(cron: string): boolean {
  const fields = fixedTimeFields(cron);
  return !!fields && fields.slice(3).every((field) => field === '*' || field === '?');
}

/**
 * Setzt die Uhrzeit eines CRONs mit fester Uhrzeit und lässt Tage, Monate und Wochentage stehen –
 * wer den Wächter unter „Zeitpläne“ auf Werktage gestellt hat, behält das. Null für eine ungültige
 * Uhrzeit oder einen CRON ohne feste Uhrzeit.
 */
export function cronWithTime(cron: string, time: string): string | null {
  const fields = fixedTimeFields(cron);
  const parsed = parseTime(time);
  if (!fields || !parsed) return null;
  return [fields[0], String(parsed.minute), String(parsed.hour), ...fields.slice(3)].join(' ');
}

/**
 * Uhrzeit des Wächters an der Zielseite ändern (TODO ff6f08fd) – über die Zeitplan-API wie unter
 * „Zeitpläne“: nur der CRON seines Zeitplans ändert sich, ein pausierter Wächter bleibt pausiert.
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
 * Ziel-Wächter an und aus (TODO e192df18, Backend 259f4f66). Beim Einschalten kommt – wie aus dem
 * Katalog „Kachel hinzufügen“ – die Ergebnis-Kachel seines Workflows aufs Dashboard, falls noch keine
 * Ansicht sie zeigt. Ausschalten pausiert nur den Zeitplan und lässt die Kachel stehen.
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
      // Nicht abwarten: der Dialog schließt sofort, die Kachel folgt, sobald die Registry geladen ist.
      if (workflowId) {
        void addGuardTile(workflowId)
          .then((added) => { if (added) toast.success(t.goals.guard.tileAdded); })
          .catch(() => { /* ohne Kachel läuft der Wächter trotzdem; sie lässt sich im Katalog ergänzen */ });
      }
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.guard.saveFailed)),
  });
}
