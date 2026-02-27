import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
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
    mutationFn: ({ poolId, projectId, name }: { poolId: string; projectId: string; name?: string }) =>
      api.post<{ workflowId: string; workflowName: string }>(`/pool/${poolId}/install`, { projectId, name }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.toast.poolWorkflowInstalled);
    },
    onError: () => toast.error(t.toast.poolInstallFailed),
  });
}
