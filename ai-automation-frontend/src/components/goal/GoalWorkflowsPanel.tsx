import { Play, Plus, Workflow } from 'lucide-react';
import { useT } from '../../i18n';
import { useGoalWorkflowStart } from '../../hooks/useGoalWorkflows';
import { GoalWorkflowChip } from './GoalWorkflowChip';
import { TONE_DOT, shownTone } from './goalWorkflowStatus';
import type { WorkflowWidgetEntry } from '../dashboard/workflowWidgetContract';
import type { GoalWorkflow } from '../../types';
import { attachmentsAt, useGoalWorkflowsScope } from './goalWorkflowsContext';

/**
 * Die Workflows am ganzen Ziel (52bc1e8c), im Kopf der Zielseite neben dem Wächter: der Wächter
 * zuerst, dann die zugeordneten Workflows als Chips mit Stand und ▶, dahinter „Workflow zuordnen“.
 * Kennt das Backend das noch nicht, steht nur der Wächter da und ein Hinweis statt des Knopfs.
 */
export function GoalWorkflowsPanel() {
  const tw = useT().goals.workflows;
  const scope = useGoalWorkflowsScope();
  if (!scope) return null;
  const items = attachmentsAt(scope.attachments, null);
  if (!scope.supported && items.length === 0) return null;
  return (
    <section aria-label={tw.title} data-testid="goal-workflows" className="flex flex-wrap items-center gap-x-2 gap-y-1.5">
      <span className="inline-flex items-center gap-1 text-xs font-medium text-text-secondary" title={tw.hint}>
        <Workflow className="h-3.5 w-3.5" aria-hidden="true" />
        {tw.title}
      </span>
      {items.map((attachment) => (
        <GoalWorkflowChip key={attachment.id} attachment={attachment} entry={scope.results.get(attachment.workflowId)} />
      ))}
      {scope.supported ? (
        <button type="button" onClick={() => scope.openAssign(null)}
          className="inline-flex h-7 items-center gap-1 rounded-md border border-dashed border-border-strong px-2 text-xs text-text-secondary hover:border-accent hover:text-accent focus-ring">
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          {tw.assign}
        </button>
      ) : (
        <span className="text-xs text-text-tertiary">{tw.legacy}</span>
      )}
    </section>
  );
}

/**
 * Workflows an einem Arbeitspaket (52bc1e8c), klein – in der Gliederung und in der Zeilenbeschriftung
 * des Zeitstrahls. Ohne Workflows bleibt es leer; zugeordnet wird im Kopf oder in der Paket-Karte.
 */
export function PackageWorkflows({ workPackageId, className = '' }: { workPackageId: string; className?: string }) {
  const scope = useGoalWorkflowsScope();
  if (!scope) return null;
  const items = attachmentsAt(scope.attachments, workPackageId);
  if (items.length === 0) return null;
  return (
    <div className={`flex flex-wrap items-center gap-1 ${className}`} data-testid={`package-workflows-${workPackageId}`}>
      {items.map((attachment) => (
        <GoalWorkflowChip key={attachment.id} attachment={attachment} entry={scope.results.get(attachment.workflowId)} compact />
      ))}
    </div>
  );
}

/**
 * Die Workflows eines Pakets in seiner Karte am Zeitstrahl: schlicht, ohne eigene Karte (die läge in
 * der Karte), mit ▶ und „Workflow zuordnen“ – das schließt die Karte und öffnet den Dialog.
 */
export function PackageWorkflowList({ workPackageId, onAssign }: { workPackageId: string; onAssign: () => void }) {
  const tw = useT().goals.workflows;
  const scope = useGoalWorkflowsScope();
  if (!scope) return null;
  const items = attachmentsAt(scope.attachments, workPackageId);
  if (items.length === 0 && !scope.supported) return null;
  return (
    <div data-testid={`package-workflow-list-${workPackageId}`}>
      <p className="text-xs font-medium text-text-secondary">{tw.title}</p>
      {items.length === 0 ? <p className="text-xs text-text-tertiary">{tw.packageEmpty}</p> : (
        <ul className="mt-1 space-y-0.5 text-xs">
          {items.map((item) => (
            <PackageWorkflowItem key={item.id} attachment={item} entry={scope.results.get(item.workflowId)} />
          ))}
        </ul>
      )}
      {scope.supported && (
        <button type="button" onClick={onAssign}
          className="mt-1 inline-flex items-center gap-1 rounded text-xs text-text-secondary hover:text-accent focus-ring">
          <Plus className="h-3 w-3" aria-hidden="true" />
          {tw.assign}
        </button>
      )}
    </div>
  );
}

/** Eine Zeile der Paket-Karte: Punkt, Name, Rolle und ▶ – ▶ teilt sich die Sperre mit allen anderen Knöpfen. */
function PackageWorkflowItem({ attachment, entry }: { attachment: GoalWorkflow; entry: WorkflowWidgetEntry | undefined }) {
  const tw = useT().goals.workflows;
  const start = useGoalWorkflowStart(attachment.goalId, attachment.workflowId, attachment.workPackageId, attachment.workflowName, entry);
  return (
    <li className="flex items-center gap-1.5">
      <span className={`h-2 w-2 shrink-0 rounded-full ${TONE_DOT[shownTone(entry, attachment.role === 'STATUS')]}`} aria-hidden="true" />
      <span className="min-w-0 flex-1 truncate text-text" title={attachment.workflowName}>{attachment.workflowName}</span>
      <span className="shrink-0 text-text-tertiary">{tw.roles[attachment.role]}</span>
      <button type="button" aria-label={tw.start.replace('{name}', attachment.workflowName)} disabled={start.busy}
        title={start.running ? tw.running : undefined} onClick={start.start}
        className="rounded p-0.5 text-text-secondary hover:text-accent focus-ring disabled:opacity-50">
        <Play className="h-3 w-3" aria-hidden="true" />
      </button>
    </li>
  );
}
