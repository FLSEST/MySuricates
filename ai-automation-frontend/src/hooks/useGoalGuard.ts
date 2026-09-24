import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys } from './useGoals';
import type { Goal, GoalGuardRequest } from '../types';

/**
 * Die Dashboard-Registry (samt Diagramm-Widgets) erst beim Einschalten laden – die Zielseite braucht
 * sie sonst nie.
 */
async function addGuardTile(workflowId: string): Promise<boolean> {
  const { addWorkflowResultTile } = await import('../components/dashboard/workflowResultTile');
  return addWorkflowResultTile(workflowId);
}

/** `HH:MM` → Spring-CRON mit Sekunden, täglich zu dieser Uhrzeit; null für eine ungültige Eingabe. */
export function dailyCron(time: string): string | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(time);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return null;
  return `0 ${minute} ${hour} * * *`;
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
