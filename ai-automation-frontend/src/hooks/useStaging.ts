import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { StagingStatus, SyncResult, SyncPreview, SyncDefinitionsRequest } from '../types';
import { useModuleEnabled } from './useModules';

export function useStagingStatus() {
  const moduleEnabled = useModuleEnabled('staging');
  return useQuery({
    queryKey: ['staging', 'status'],
    queryFn: () => api.get<StagingStatus>('/staging/status'),
    refetchInterval: (query) => {
      const phase = query.state.data?.promotePhase;
      if (phase && phase !== 'IDLE' && phase !== 'ERROR') return 1_000;
      return 15_000;
    },
    enabled: moduleEnabled,
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

export function useStagingSyncPreview(enabled: boolean) {
  return useQuery({
    queryKey: ['staging', 'sync-preview'],
    queryFn: () => api.get<SyncPreview>('/staging/sync-preview'),
    enabled,
  });
}

export function useStagingSync() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (request?: SyncDefinitionsRequest) =>
      api.post<SyncResult>('/staging/sync-definitions', request ?? undefined),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staging'] }),
  });
}
