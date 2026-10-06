import { Plus, Workflow } from 'lucide-react';
import { useT } from '../../i18n';
import { GoalWorkflowChip } from './GoalWorkflowChip';
import { attachmentsAt, useGoalWorkflowsScope } from './goalWorkflowsContext';

/**
 * The workflows on the whole goal (52bc1e8c), in the header of the goal page next to the guard: the guard
 * first, then the assigned workflows as chips with status and ▶, behind them „Workflow zuordnen“.
 * If the backend does not know this yet, only the guard is shown, and a hint instead of the button.
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
 * Workflows on a work package (52bc1e8c), small — in the outline and in the row label
 * of the timeline. Without workflows it stays empty; assigning happens in the header or in the package card.
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
