import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { parseWorkflowEntry, type WorkflowWidgetEntry } from '../components/dashboard/workflowWidgetContract';

/** Query key of the one workflow-widget request per dashboard — used to invalidate on a finished run. */
export const WORKFLOW_RESULTS_QUERY_KEY = ['dashboard', 'workflow-results'] as const;

interface WorkflowWidgetOptions {
  /** True while the run-completed socket is not connected: then, and only then, the data is polled. */
  polling: boolean;
}

/**
 * The latest run and the validated contract of every workflow shown in the current view — one
 * request per dashboard, not one per widget (spec E3). Updates arrive through the run-completed
 * broadcast; the 60 s interval is the fallback for a missing WebSocket connection.
 *
 * Every entry goes through parseWorkflowEntry: what does not match the contract never reaches a widget.
 *
 * The ids are deduplicated and sorted before they become the query key: the backend already dedupes
 * and caps them, so this is only about a stable key — reordering widgets on the dashboard must not
 * churn the cache key and trigger a redundant refetch.
 */
export function useWorkflowWidgetResults(workflowIds: readonly string[], options: WorkflowWidgetOptions) {
  const ids = [...new Set(workflowIds)].sort();
  return useQuery({
    queryKey: [...WORKFLOW_RESULTS_QUERY_KEY, ids.join(',')],
    enabled: ids.length > 0,
    queryFn: async () => {
      const params = new URLSearchParams();
      for (const workflowId of ids) params.append('workflowIds', workflowId);
      const raw = await api.get<unknown>(`/dashboard/workflow-results?${params.toString()}`);
      if (!Array.isArray(raw)) return [];
      return raw.map(parseWorkflowEntry).filter((entry): entry is WorkflowWidgetEntry => entry !== null);
    },
    refetchInterval: options.polling ? 60_000 : false,
    refetchOnWindowFocus: true,
    staleTime: 10_000,
  });
}
