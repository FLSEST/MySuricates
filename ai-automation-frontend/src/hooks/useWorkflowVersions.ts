import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { WorkflowVersion, PageResponse } from '../types';

export function useWorkflowVersions(workflowId: string, page = 0, size = 20) {
  return useQuery({
    queryKey: ['workflow-versions', workflowId, page, size],
    queryFn: () => api.get<PageResponse<WorkflowVersion>>(
      `/workflows/${workflowId}/versions?page=${page}&size=${size}`
    ),
    enabled: !!workflowId,
  });
}

export function useRestoreWorkflowVersion(workflowId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (versionId: string) =>
      api.post(`/workflows/${workflowId}/versions/${versionId}/restore`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['workflow-versions', workflowId] });
      toast.success(t.workflows.versionRestored);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.workflows.versionRestoreFailed)),
  });
}
