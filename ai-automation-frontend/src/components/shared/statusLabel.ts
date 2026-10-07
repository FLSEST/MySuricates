import { useT } from '../../i18n';

/**
 * Translated label of a status: run wording first, then step, then project/container wording;
 * an unknown value stays as it is. Shared by the status badges of runs, chains and projects.
 */
export function useStatusLabel(): (status: string) => string {
  const t = useT();
  const labels: Record<string, string> = { ...t.projects.statuses, ...t.runs.stepStatuses, ...t.runs.statuses };
  return (status) => labels[status] ?? status;
}
