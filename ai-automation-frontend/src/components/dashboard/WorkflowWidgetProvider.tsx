import { useCallback, useEffect, useMemo, useRef, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ResolvedWidget } from '../../hooks/useDashboardLayout';
import { useRunCompletedSocket, type RunCompletedMessage } from '../../hooks/useRunCompletedSocket';
import { WORKFLOW_RESULTS_QUERY_KEY, useWorkflowWidgetResults } from '../../hooks/useWorkflowWidgets';
import { workflowIdsOf } from './workflowWidgetContract';
import { WorkflowWidgetContext, type WorkflowWidgetValue } from './workflowWidgetContext';

interface WorkflowWidgetProviderProps {
  /** The shown widgets of the view; the workflow widgets among them decide what is loaded. */
  widgets: readonly ResolvedWidget[];
  children: ReactNode;
}

/**
 * Loads the data of all workflow widgets of the shown view with one request and refreshes it when a
 * run of one of those workflows ends (spec E3): one request and one WebSocket subscription per
 * dashboard, instead of a 30 s timer per widget instance. Without workflow widgets nothing is
 * requested and no socket is opened.
 */
export function WorkflowWidgetProvider({ widgets, children }: WorkflowWidgetProviderProps) {
  const queryClient = useQueryClient();
  const workflowIds = useMemo(() => workflowIdsOf(widgets), [widgets]);

  // Read inside the socket callback, so a changed view never re-subscribes.
  const shownIds = useRef(new Set(workflowIds));
  useEffect(() => { shownIds.current = new Set(workflowIds); }, [workflowIds]);

  const onRunCompleted = useCallback((message: RunCompletedMessage) => {
    if (shownIds.current.has(message.workflowId)) {
      void queryClient.invalidateQueries({ queryKey: WORKFLOW_RESULTS_QUERY_KEY });
    }
  }, [queryClient]);

  const connected = useRunCompletedSocket(workflowIds.length > 0, onRunCompleted);
  const { data, isLoading, error } = useWorkflowWidgetResults(workflowIds, { polling: !connected });

  const value = useMemo<WorkflowWidgetValue>(() => ({
    entries: new Map((data ?? []).map((entry) => [entry.workflowId, entry])),
    isLoading: workflowIds.length > 0 && isLoading,
    error: error ?? null,
  }), [data, isLoading, error, workflowIds.length]);

  return <WorkflowWidgetContext.Provider value={value}>{children}</WorkflowWidgetContext.Provider>;
}
