import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { WorkflowChain, ChainRun, CreateChainRequest, UpdateChainRequest, PageResponse } from '../types';

export function useChains() {
  return useQuery({
    queryKey: ['chains'],
    queryFn: () => api.get<WorkflowChain[]>('/chains'),
  });
}

export function useChain(id: string) {
  return useQuery({
    queryKey: ['chains', id],
    queryFn: () => api.get<WorkflowChain>(`/chains/${id}`),
    enabled: !!id,
  });
}

export function useAllChainRuns(params: { page: number; size: number; status?: string }) {
  const query = new URLSearchParams({
    page: String(params.page),
    size: String(params.size),
  });
  if (params.status) query.set('status', params.status);

  return useQuery({
    queryKey: ['chainRuns', params],
    queryFn: () => api.get<PageResponse<ChainRun>>(`/chains/runs?${query}`),
  });
}

export function useChainRuns(chainId: string) {
  return useQuery({
    queryKey: ['chains', chainId, 'runs'],
    queryFn: () => api.get<ChainRun[]>(`/chains/${chainId}/runs`),
    enabled: !!chainId,
  });
}

export function useChainRun(chainId: string, runId: string) {
  return useQuery({
    queryKey: ['chains', chainId, 'runs', runId],
    queryFn: () => api.get<ChainRun>(`/chains/${chainId}/runs/${runId}`),
    enabled: !!chainId && !!runId,
    refetchInterval: (query) => {
      const data = query.state.data;
      if (data && (data.status === 'COMPLETED' || data.status === 'FAILED' || data.status === 'CANCELLED' || data.status === 'COMPLETED_WITH_ERRORS')) {
        return false;
      }
      return 2000;
    },
  });
}

export function useCreateChain() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateChainRequest) =>
      api.post<WorkflowChain>('/chains', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['chains'] });
      toast.success(t.toast.chainCreated);
    },
    onError: () => toast.error(t.toast.chainCreateFailed),
  });
}

export function useUpdateChain() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateChainRequest }) =>
      api.put<WorkflowChain>(`/chains/${id}`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['chains'] });
      toast.success(t.toast.chainUpdated);
    },
    onError: () => toast.error(t.toast.chainUpdateFailed),
  });
}

export function useDeleteChain() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/chains/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['chains'] });
      toast.success(t.toast.chainDeleted);
    },
    onError: () => toast.error(t.toast.chainDeleteFailed),
  });
}

export function useExecuteChain() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, inputContext }: { id: string; inputContext?: Record<string, unknown> }) =>
      api.post<ChainRun>(`/chains/${id}/execute`, inputContext),
    onSuccess: (_, variables) => {
      qc.invalidateQueries({ queryKey: ['chains'] });
      qc.invalidateQueries({ queryKey: ['chains', variables.id, 'runs'] });
      toast.success(t.toast.chainExecuted);
    },
    onError: () => toast.error(t.toast.chainExecuteFailed),
  });
}

export function useCancelChainRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: string) => api.post(`/chains/runs/${runId}/cancel`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['chains'] });
    },
  });
}
