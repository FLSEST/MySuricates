import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { api } from '../api/client';
import type { BrokenWorkflow } from '../types';

/**
 * Workflows whose most recent run failed (FAILED / COMPLETED_WITH_ERRORS) — the data for the
 * /debug "what is currently broken" page. Deduplicated per workflow on the backend.
 *
 * Restarting a broken workflow reuses {@code useRetryExecution} (POST /executions/{runId}/retry).
 */
export function useBrokenWorkflows() {
  return useQuery({
    queryKey: ['debug', 'failed-workflows'],
    queryFn: () => api.get<BrokenWorkflow[]>('/debug/failed-workflows'),
    refetchInterval: 30_000,
    placeholderData: keepPreviousData,
  });
}
