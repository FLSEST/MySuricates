import { createContext, useContext } from 'react';
import type { WorkflowWidgetEntry } from './workflowWidgetContract';

export interface WorkflowWidgetValue {
  /** Entry per workflow id of the shown view; missing while the one request is still running. */
  entries: ReadonlyMap<string, WorkflowWidgetEntry>;
  isLoading: boolean;
  error: Error | null;
}

export const WorkflowWidgetContext = createContext<WorkflowWidgetValue | null>(null);

/** The data of one workflow widget — one shared request per dashboard (spec E3). */
export function useWorkflowWidgetEntry(workflowId: string | undefined) {
  const value = useContext(WorkflowWidgetContext);
  if (!value) throw new Error('useWorkflowWidgetEntry must be used inside a WorkflowWidgetProvider');
  return {
    entry: workflowId ? value.entries.get(workflowId) : undefined,
    isLoading: value.isLoading,
    error: value.error,
  };
}
