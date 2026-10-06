import { createContext, useContext } from 'react';
import type { WorkflowWidgetEntry } from '../dashboard/workflowWidgetContract';
import type { GoalWorkflow } from '../../types';

/**
 * The workflows of the goal for all views of the goal page (52bc1e8c): loaded once, shown at the goal in the
 * header and at every work package in outline and timeline. Without a provider (e.g. outside
 * the goal page) the packages show no workflows.
 */
export interface GoalWorkflowsValue {
  goalId: string;
  /** The backend knows workflows at the goal; otherwise only the guard and no assigning. */
  supported: boolean;
  attachments: GoalWorkflow[];
  /** Last run and result per workflow ID. */
  results: Map<string, WorkflowWidgetEntry>;
  /** Opens „Workflow zuordnen“, prefilled with the package (null = whole goal). */
  openAssign: (workPackageId: string | null) => void;
}

export const GoalWorkflowsContext = createContext<GoalWorkflowsValue | null>(null);

export const useGoalWorkflowsScope = () => useContext(GoalWorkflowsContext);

/** The assignments at the goal (`null`) or at a package - in the order of the server. */
export function attachmentsAt(attachments: GoalWorkflow[], workPackageId: string | null): GoalWorkflow[] {
  return attachments.filter((item) => (item.workPackageId ?? null) === workPackageId);
}
