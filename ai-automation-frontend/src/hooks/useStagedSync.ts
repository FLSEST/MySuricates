import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { SyncTarget, SyncResult, SyncPreview, SyncDefinitionsRequest } from '../types';
import { useModuleEnabled } from './useModules';

/**
 * Sync targets of the staged setup (84a78bb7). An older backend has no such endpoint (404): the
 * query fails quietly and the card stays hidden — no retry, no toast.
 */
export function useStagedTargets() {
  const moduleEnabled = useModuleEnabled('staging');
  return useQuery({
    queryKey: ['staging', 'targets'],
    queryFn: async () => {
      const data = await api.get<SyncTarget[]>('/staging/targets');
      return Array.isArray(data) ? data : [];
    },
    enabled: moduleEnabled,
    retry: false,
    refetchInterval: 30_000,
  });
}

export function useStagedSyncPreview(target: string | null) {
  return useQuery({
    queryKey: ['staging', 'targets', target, 'sync-preview'],
    queryFn: () => api.get<SyncPreview>(`/staging/targets/${encodeURIComponent(target ?? '')}/sync-preview`),
    enabled: target !== null,
    retry: false,
  });
}

export function useStagedSync() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ target, request }: { target: string; request?: SyncDefinitionsRequest }) =>
      api.post<SyncResult>(`/staging/targets/${encodeURIComponent(target)}/sync-definitions`, request ?? undefined),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staging', 'targets'] }),
  });
}
