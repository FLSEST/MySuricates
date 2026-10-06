import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { useT } from '../../i18n';
import { useGoalWorkflowResults, useGoalWorkflows } from '../../hooks/useGoalWorkflows';
import { AssignWorkflowDialog } from './AssignWorkflowDialog';
import { GoalWorkflowsContext, type GoalWorkflowsValue } from './goalWorkflowsContext';
import type { GoalDetail } from '../../types';

/**
 * Loads the goal's workflows and their last run once for the whole goal page (52bc1e8c) and
 * holds the dialog „Workflow zuordnen“ — header, outline and timeline read from the context.
 */
export function GoalWorkflowsProvider({ detail, children }: { detail: GoalDetail; children: ReactNode }) {
  const t = useT();
  const goalId = detail.goal.id;
  const { data } = useGoalWorkflows(goalId, detail.goal.guard, t.goals.workflows.guard);
  const attachments = useMemo(() => data?.attachments ?? [], [data]);
  const { data: results } = useGoalWorkflowResults(attachments.map((item) => item.workflowId));
  const [assigning, setAssigning] = useState<{ target: string | null } | null>(null);
  const openAssign = useCallback((workPackageId: string | null) => setAssigning({ target: workPackageId }), []);

  const value = useMemo<GoalWorkflowsValue>(() => ({
    goalId,
    supported: data?.supported ?? false,
    attachments,
    results: new Map((results ?? []).map((entry) => [entry.workflowId, entry])),
    openAssign,
  }), [goalId, data?.supported, attachments, results, openAssign]);

  return (
    <GoalWorkflowsContext.Provider value={value}>
      {children}
      {assigning && (
        <AssignWorkflowDialog goalId={goalId} projects={detail.projects} workPackages={detail.workPackages}
          attachments={attachments} initialTarget={assigning.target} onClose={() => setAssigning(null)} />
      )}
    </GoalWorkflowsContext.Provider>
  );
}
