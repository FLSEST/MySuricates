import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { StagingStatus, SyncResult } from '../types';

export function useStagingStatus() {
  return useQuery({
    queryKey: ['staging', 'status'],
    queryFn: () => api.get<StagingStatus>('/staging/status'),
    refetchInterval: (query) => {
      const phase = query.state.data?.promotePhase;
      if (phase && phase !== 'IDLE' && phase !== 'ERROR') return 3_000;
      return 15_000;
    },
  });
}

export function useStagingStart() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/staging/start'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staging'] }),
  });
}

export function useStagingStop() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/staging/stop'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staging'] }),
  });
}

export function useStagingPromote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/staging/promote'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staging'] }),
  });
}

export function useStagingCancel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/staging/promote/cancel'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staging'] }),
  });
}

export function useStagingSync() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<SyncResult>('/staging/sync-definitions'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staging'] }),
  });
}
