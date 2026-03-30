import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { PoolWorkflowEntry } from '../types';

export function usePoolWorkflows() {
  return useQuery({
    queryKey: ['pool'],
    queryFn: () => api.get<PoolWorkflowEntry[]>('/pool'),
  });
}

export function useInstallPoolWorkflow() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ poolId, projectId, name, forceCreate }: { poolId: string; projectId: string; name?: string; forceCreate?: boolean }) =>
      api.post<{ workflowId: string; workflowName: string; created: boolean }>(`/pool/${poolId}/install`, { projectId, name, forceCreate }),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(data.created ? t.toast.poolWorkflowInstalled : t.toast.poolWorkflowUpdated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.poolInstallFailed)),
  });
}
