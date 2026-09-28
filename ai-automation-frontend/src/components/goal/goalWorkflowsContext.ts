import { createContext, useContext } from 'react';
import type { WorkflowWidgetEntry } from '../dashboard/workflowWidgetContract';
import type { GoalWorkflow } from '../../types';

/**
 * Die Workflows des Ziels für alle Ansichten der Zielseite (52bc1e8c): einmal geladen, am Ziel im
 * Kopf und an jedem Arbeitspaket in Gliederung und Zeitstrahl gezeigt. Ohne Anbieter (etwa außerhalb
 * der Zielseite) zeigen die Pakete keine Workflows.
 */
export interface GoalWorkflowsValue {
  goalId: string;
  /** Das Backend kennt Workflows am Ziel; sonst nur der Wächter und kein Zuordnen. */
  supported: boolean;
  attachments: GoalWorkflow[];
  /** Letzter Lauf und Ergebnis je Workflow-ID. */
  results: Map<string, WorkflowWidgetEntry>;
  /** Öffnet „Workflow zuordnen“, vorbelegt mit dem Paket (null = ganzes Ziel). */
  openAssign: (workPackageId: string | null) => void;
}

export const GoalWorkflowsContext = createContext<GoalWorkflowsValue | null>(null);

export const useGoalWorkflowsScope = () => useContext(GoalWorkflowsContext);

/** Die Zuordnungen am Ziel (`null`) bzw. an einem Paket – in der Reihenfolge des Servers. */
export function attachmentsAt(attachments: GoalWorkflow[], workPackageId: string | null): GoalWorkflow[] {
  return attachments.filter((item) => (item.workPackageId ?? null) === workPackageId);
}
