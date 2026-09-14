import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { TestingStatus, SyncResult, SyncPreview, SyncDefinitionsRequest } from '../types';
import { useModuleEnabled } from './useModules';

export function useTestingStatus() {
  const moduleEnabled = useModuleEnabled('staging');
  return useQuery({
    queryKey: ['testing', 'status'],
    queryFn: () => api.get<TestingStatus>('/testing/status'),
    refetchInterval: 15_000,
    enabled: moduleEnabled,
  });
}

export function useTestingStart() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/testing/start'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['testing'] }),
  });
}

export function useTestingStop() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/testing/stop'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['testing'] }),
  });
}

export function useTestingReset() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/testing/reset'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['testing'] }),
  });
}

export function useTestingSyncPreview(enabled: boolean) {
  return useQuery({
    queryKey: ['testing', 'sync-preview'],
    queryFn: () => api.get<SyncPreview>('/testing/sync-preview'),
    enabled,
  });
}

export function useTestingSync() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (request?: SyncDefinitionsRequest) =>
      api.post<SyncResult>('/testing/sync-definitions', request ?? undefined),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['testing'] }),
  });
}

export function useTestingPullChanges() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<SyncResult>('/testing/pull-changes'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}
