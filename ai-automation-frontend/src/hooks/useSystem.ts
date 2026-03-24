import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { SystemHealth, SystemInfo, RemoteAccessStatus, BrowseDirectoryResponse, BackupInfo } from '../types';

export function useSystemHealth() {
  return useQuery({
    queryKey: ['system', 'health'],
    queryFn: () => api.get<SystemHealth>('/system/health'),
    refetchInterval: 30_000,
  });
}

export function useSystemInfo() {
  return useQuery({
    queryKey: ['system', 'info'],
    queryFn: () => api.get<SystemInfo>('/system/info'),
  });
}

export function useRemoteAccessStatus() {
  return useQuery({
    queryKey: ['system', 'remote-access'],
    queryFn: () => api.get<RemoteAccessStatus>('/system/remote-access/status'),
  });
}

export function useEnableRemoteAccess() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<RemoteAccessStatus>('/system/remote-access/enable'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['system', 'remote-access'] }),
  });
}

export function useDisableRemoteAccess() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/system/remote-access/disable'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['system', 'remote-access'] }),
  });
}

export function useBackupList() {
  return useQuery({
    queryKey: ['system', 'backups'],
    queryFn: () => api.get<BackupInfo[]>('/system/backups'),
  });
}

export function useAiProviders() {
  return useQuery({
    queryKey: ['system', 'ai-providers'],
    queryFn: () => api.get<{ providers: string[]; default: string }>('/system/ai-providers'),
  });
}

export function useUpdateSystemSetting() {
  return useMutation({
    mutationFn: ({ key, value }: { key: string; value: string }) =>
      api.put(`/system/settings/${key}`, { value }),
  });
}

export function useAssistantModelSetting() {
  return useQuery({
    queryKey: ['system', 'settings', 'assistant-model'],
    queryFn: async () => {
      const r = await api.get<{ value: string }>('/system/settings/assistant-model');
      return r.value ?? '';
    },
    staleTime: 5 * 60 * 1000,
  });
}

const DEFAULT_THINKING_PHRASES = [
  'Analysiere Runs…', 'Durchsuche Todos…', 'Prüfe Workflows…', 'Denke nach…',
];

export function useAssistantThinkingPhrases() {
  return useQuery({
    queryKey: ['system', 'settings', 'assistant-thinking-phrases'],
    queryFn: async () => {
      const r = await api.get<{ value: string }>('/system/settings/assistant-thinking-phrases');
      return JSON.parse(r.value) as string[];
    },
    staleTime: 10 * 60 * 1000,
    placeholderData: DEFAULT_THINKING_PHRASES,
  });
}

export function useRepairAllFailed() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (force?: boolean) =>
      api.post<{ queued: number }>(`/system/repair-failed?force=${force ?? false}`),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.toast.repairAllStarted.replace('{count}', String(data.queued)));
    },
    onError: () => toast.error(t.toast.repairFailed),
  });
}

export function useBrowseDirectory(path: string | null) {
  return useQuery({
    queryKey: ['system', 'browse', path],
    queryFn: () => api.get<BrowseDirectoryResponse>(`/system/browse${path ? `?path=${encodeURIComponent(path)}` : ''}`),
    enabled: path !== null,
  });
}
