import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useI18nStore, useT } from '../../i18n';
import type { WorkflowWidgetEntry } from '../dashboard/workflowWidgetContract';
import { TONE_DOT, ago, lastRunAt, lightOf, runPath, shownTone } from './goalWorkflowStatus';

/**
 * „Letztes Ergebnis“ des Wächters (c60bf914): Ampel des letzten Laufs und wann er war, als Link auf
 * den Lauf. Der Wächter meldet immer eine Ampel; läuft er gerade oder scheiterte er, zeigt der Punkt
 * das statt einer alten Ampel.
 */
export function GuardLastResult({ workflowId, entry }: { workflowId: string; entry: WorkflowWidgetEntry }) {
  const t = useT();
  const g = t.goals.guard;
  const locale = useI18nStore((s) => s.locale);
  const [now] = useState(() => new Date());
  const run = entry.run!;
  const light = lightOf(entry);
  const tone = shownTone(entry, true);
  const at = lastRunAt(entry);
  const what = tone === 'info' || tone === 'danger' || !light ? t.runs.statuses[run.status] : g.lights[light];
  return (
    <Link to={runPath(workflowId, run.runId)} data-testid="goal-guard-last-result"
      className="inline-flex items-center gap-1.5 rounded hover:text-accent focus-ring">
      <span className={`h-2 w-2 shrink-0 rounded-full ${TONE_DOT[tone]}`} aria-hidden="true" />
      {g.lastResult.replace('{what}', what)}
      {at && <span className="text-text-tertiary">{` · ${ago(at, now, locale)}`}</span>}
    </Link>
  );
}
