import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { WorkflowAnalysis } from '../types';

/**
 * Findings of the deterministic definition check (analyzer, no AI call). Core endpoint, not behind a
 * module. The workflow's `updatedAt` is part of the key, so an edit re-checks the definition without
 * extra invalidation; an older backend without the endpoint just yields no findings.
 */
export function useWorkflowAnalysis(workflowId: string | undefined, updatedAt: string | undefined) {
  return useQuery({
    queryKey: ['workflow-analysis', workflowId, updatedAt],
    queryFn: () => api.get<WorkflowAnalysis>(`/workflows/${workflowId}/analyze`),
    enabled: !!workflowId,
    staleTime: 60_000,
    retry: false,
  });
}
