import type { Locale } from '../../i18n';
import { formatRelativeUnit } from '../../utils/localeFormat';
import type { RunStatus } from '../../types';
import type { WorkflowResultStatus, WorkflowWidgetEntry } from '../dashboard/workflowWidgetContract';

/**
 * State of a workflow at the goal (52bc1e8c) from the existing read path `/dashboard/workflow-results`:
 * colour of the last run, for a workflow with role STATUS the traffic light of its result. Pure.
 */

export type RunTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

const RUN_TONE: Record<RunStatus, RunTone> = {
  COMPLETED: 'success',
  COMPLETED_WITH_ERRORS: 'warning',
  FAILED: 'danger',
  INTERRUPTED: 'danger',
  CANCELLED: 'neutral',
  RUNNING: 'info',
  PENDING: 'info',
  PAUSED: 'info',
};

/** Dot per tone - fixed class names for Tailwind. */
export const TONE_DOT: Record<RunTone, string> = {
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-danger',
  info: 'bg-info',
  neutral: 'bg-border-strong',
};

const LIGHT_TONE: Record<WorkflowResultStatus, RunTone> = { GREEN: 'success', YELLOW: 'warning', RED: 'danger' };

/** Tone of the last run; neutral without a run. */
export function runTone(entry: WorkflowWidgetEntry | null | undefined): RunTone {
  return entry?.run ? RUN_TONE[entry.run.status] : 'neutral';
}

/** The traffic light of a result: from the result contract, otherwise the gate status of the run; null if there is none. */
export function lightOf(entry: WorkflowWidgetEntry | null | undefined): WorkflowResultStatus | null {
  return entry?.result?.status ?? entry?.run?.gateStatus ?? null;
}

/**
 * What the dot of a workflow shows: for „liefert den Stand“ the traffic light, as long as the last run
 * is not currently running or failed - otherwise always the run.
 */
export function shownTone(entry: WorkflowWidgetEntry | null | undefined, status: boolean): RunTone {
  const run = runTone(entry);
  const light = lightOf(entry);
  return status && light && run !== 'info' && run !== 'danger' ? LIGHT_TONE[light] : run;
}

/** Time of the last run: end, otherwise start. */
export function lastRunAt(entry: WorkflowWidgetEntry | null | undefined): string | null {
  return entry?.run ? entry.run.completedAt ?? entry.run.startedAt : null;
}

/** „vor 5 Minuten“, „vor 3 Stunden“, „vor 2 Tagen“ - in the language of the UI. */
export function ago(iso: string, now: Date, locale: Locale): string {
  const minutes = Math.max(0, Math.round((now.getTime() - Date.parse(iso)) / 60_000));
  if (minutes < 60) return formatRelativeUnit(-minutes, 'minute', locale);
  const hours = Math.round(minutes / 60);
  if (hours < 24) return formatRelativeUnit(-hours, 'hour', locale);
  return formatRelativeUnit(-Math.round(hours / 24), 'day', locale);
}

/** Link to the run - the page of the run in the workflow. */
export const runPath = (workflowId: string, runId: string) => `/workflows/${workflowId}/run/${runId}`;

/** Is the last run still running (or waiting)? Then ▶ does not start a second one alongside. */
export function runActive(entry: WorkflowWidgetEntry | null | undefined): boolean {
  return runTone(entry) === 'info';
}
