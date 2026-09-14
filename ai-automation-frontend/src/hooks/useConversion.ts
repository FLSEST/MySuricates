import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { WorkflowSuggestion, Workflow } from '../types';
import { useModuleEnabled } from './useModules';

export function useConversionSuggestion(runId: string, enabled = true) {
  const moduleEnabled = useModuleEnabled('conversion');
  return useQuery({
    queryKey: ['conversion', 'suggest', runId],
    queryFn: () => api.get<WorkflowSuggestion>(`/conversions/runs/${runId}/suggest`),
    enabled: moduleEnabled && !!runId && enabled,
    retry: false,
  });
}

export function useCreateWorkflowFromSuggestion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ runId, projectId, name }: { runId: string; projectId: string; name?: string }) =>
      api.post<Workflow>(`/conversions/runs/${runId}/create-workflow`, { projectId, name }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
    },
  });
}
