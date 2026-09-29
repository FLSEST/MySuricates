import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { BlockDefinition, WorkflowSuggestion, Workflow } from '../types';
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

export interface CreateWorkflowFromSuggestionInput {
  runId: string;
  projectId: string;
  name?: string;
  /** The reviewed suggestion from the preview: saved as shown, without generating it again (TODO 935af6d3). */
  description?: string;
  blocks?: BlockDefinition[];
}

export function useCreateWorkflowFromSuggestion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ runId, projectId, name, description, blocks }: CreateWorkflowFromSuggestionInput) =>
      api.post<Workflow>(`/conversions/runs/${runId}/create-workflow`, { projectId, name, description, blocks }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
    },
  });
}
