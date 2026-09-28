import type { Locale } from '../../i18n';
import { formatRelativeUnit } from '../../utils/localeFormat';
import type { RunStatus } from '../../types';
import type { WorkflowResultStatus, WorkflowWidgetEntry } from '../dashboard/workflowWidgetContract';

/**
 * Stand eines Workflows am Ziel (52bc1e8c) aus dem vorhandenen Lesepfad `/dashboard/workflow-results`:
 * Farbe des letzten Laufs, bei einem Workflow mit Rolle STATUS die Ampel seines Ergebnisses. Rein.
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

/** Punkt je Ton – feste Klassennamen für Tailwind. */
export const TONE_DOT: Record<RunTone, string> = {
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-danger',
  info: 'bg-info',
  neutral: 'bg-border-strong',
};

const LIGHT_TONE: Record<WorkflowResultStatus, RunTone> = { GREEN: 'success', YELLOW: 'warning', RED: 'danger' };

/** Ton des letzten Laufs; ohne Lauf neutral. */
export function runTone(entry: WorkflowWidgetEntry | null | undefined): RunTone {
  return entry?.run ? RUN_TONE[entry.run.status] : 'neutral';
}

/** Die Ampel eines Ergebnisses: aus dem Ergebnis-Vertrag, sonst dem Gate-Status des Laufs; null, wenn keins da ist. */
export function lightOf(entry: WorkflowWidgetEntry | null | undefined): WorkflowResultStatus | null {
  return entry?.result?.status ?? entry?.run?.gateStatus ?? null;
}

/**
 * Was der Punkt eines Workflows zeigt: bei „liefert den Stand“ die Ampel, solange der letzte Lauf
 * nicht gerade läuft oder scheiterte – sonst immer den Lauf.
 */
export function shownTone(entry: WorkflowWidgetEntry | null | undefined, status: boolean): RunTone {
  const run = runTone(entry);
  const light = lightOf(entry);
  return status && light && run !== 'info' && run !== 'danger' ? LIGHT_TONE[light] : run;
}

/** Zeitpunkt des letzten Laufs: Ende, sonst Start. */
export function lastRunAt(entry: WorkflowWidgetEntry | null | undefined): string | null {
  return entry?.run ? entry.run.completedAt ?? entry.run.startedAt : null;
}

/** „vor 5 Minuten“, „vor 3 Stunden“, „vor 2 Tagen“ – in der Sprache der Oberfläche. */
export function ago(iso: string, now: Date, locale: Locale): string {
  const minutes = Math.max(0, Math.round((now.getTime() - Date.parse(iso)) / 60_000));
  if (minutes < 60) return formatRelativeUnit(-minutes, 'minute', locale);
  const hours = Math.round(minutes / 60);
  if (hours < 24) return formatRelativeUnit(-hours, 'hour', locale);
  return formatRelativeUnit(-Math.round(hours / 24), 'day', locale);
}

/** Link zum Lauf – die Seite des Laufs im Workflow. */
export const runPath = (workflowId: string, runId: string) => `/workflows/${workflowId}/run/${runId}`;

/** Läuft der letzte Lauf noch (oder wartet er)? Dann startet ▶ keinen zweiten daneben. */
export function runActive(entry: WorkflowWidgetEntry | null | undefined): boolean {
  return runTone(entry) === 'info';
}
