import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT, useI18nStore } from '../i18n';
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

export interface AiProviderCapabilities {
  supportsSystemPrompt: boolean;
  supportsModelSelection: boolean;
  supportsMaxTurns: boolean;
  supportsStreamJson: boolean;
  supportsAllowedTools: boolean;
  tokenTracking: boolean;
  supportsMultiTurnReasoning: boolean;
  supportsEffort: boolean;
}

export function useAiProviders() {
  return useQuery({
    queryKey: ['system', 'ai-providers'],
    queryFn: () =>
      api.get<{
        providers: string[];
        default: string;
        capabilities?: Record<string, AiProviderCapabilities>;
      }>('/system/ai-providers'),
  });
}

export interface PiCatalog {
  installed: boolean;
  authenticatedProviders: string[];
  customProviders: string[];
  defaultProvider: string | null;
  defaultModel: string | null;
}

export function usePiCatalog(enabled = true) {
  return useQuery({
    queryKey: ['system', 'ai-providers', 'PI', 'catalog'],
    queryFn: () => api.get<PiCatalog>('/system/ai-providers/PI/catalog'),
    staleTime: 60_000, // pi config rarely changes mid-session
    enabled,
  });
}

/** One selectable model from the host-discovered catalog. */
export interface ModelOption {
  value: string;
  label: string;
  description?: string | null;
  contextWindow?: number | null;
  supportsThinking?: boolean | null;
}

/** Models a provider offers on this host; `available=false` means the probe failed (see `reason`). */
export interface ModelCatalog {
  providerId: string;
  available: boolean;
  source: string;
  fetchedAt: string | null;
  reason: string | null;
  models: ModelOption[];
}

const providerModelsKey = (providerId: string) => ['system', 'ai-providers', providerId.toUpperCase(), 'models'];

export function useProviderModels(providerId: string | null | undefined) {
  const id = (providerId ?? '').toUpperCase();
  return useQuery({
    queryKey: providerModelsKey(id || 'NONE'),
    queryFn: () => api.get<ModelCatalog>(`/system/ai-providers/${id}/models`),
    enabled: id !== '',
    staleTime: 60_000, // backend caches per its own TTL; avoid refetch storms on re-render
  });
}

export function useRefreshProviderModels() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (providerId: string) =>
      api.post<ModelCatalog>(`/system/ai-providers/${providerId.toUpperCase()}/models/refresh`),
    onSuccess: (data, providerId) => qc.setQueryData(providerModelsKey(providerId), data),
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

export function useAutoRepairModeSetting() {
  return useQuery({
    queryKey: ['system', 'settings', 'auto-repair-mode'],
    queryFn: async () => {
      const r = await api.get<{ value: string }>('/system/settings/auto-repair-mode');
      return r.value || 'auto';
    },
    staleTime: 5 * 60 * 1000,
  });
}

export function useSuggestionsModelSetting() {
  return useQuery({
    queryKey: ['system', 'settings', 'suggestions-model'],
    queryFn: async () => {
      const r = await api.get<{ value: string }>('/system/settings/suggestions-model');
      return r.value ?? '';
    },
    staleTime: 5 * 60 * 1000,
  });
}

const DEFAULT_THINKING_PHRASES: Record<string, string[]> = {
  de: ['Denke nach…', 'Analysiere…', 'Überlege kurz…', 'Einen Moment…'],
  en: ['Thinking…', 'Analyzing…', 'Just a moment…', 'Processing…'],
};

export function useAssistantThinkingPhrases() {
  const locale = useI18nStore((s) => s.locale);
  return useQuery({
    queryKey: ['system', 'settings', 'assistant-thinking-phrases', locale],
    queryFn: async () => {
      const r = await api.get<{ value: string }>('/system/settings/assistant-thinking-phrases');
      const parsed = JSON.parse(r.value) as string[] | Record<string, string[]>;
      // Locale-keyed map (new format)
      if (!Array.isArray(parsed)) {
        return (parsed[locale] ?? parsed['de'] ?? Object.values(parsed)[0]) as string[];
      }
      // Legacy plain array
      return parsed as string[];
    },
    staleTime: 10 * 60 * 1000,
    placeholderData: DEFAULT_THINKING_PHRASES[locale] ?? DEFAULT_THINKING_PHRASES['de'],
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
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.repairFailed)),
  });
}

export interface ExecutionConfig {
  executionMaxConcurrent: number;
  claudeMaxConcurrentCalls: number;
  claudeReservedForInteractive: number;
  claudeTimeoutSeconds: number;
  claudeInterCallDelayMs: number;
  chainWorkflowTimeoutMinutes: number;
  rateLimitEnabled: number;
  rateLimitRequestsPerMinute: number;
  assistantChatTimeoutSeconds: number;
  schedulerCatchUpOnResume: number;
  schedulerResumeGraceMinutes: number;
  summarizationTimeoutSeconds: number;
  factoryTimeoutSeconds: number;
  runRetentionDays?: number;
  runRetentionMaxPerNight?: number;
  wishTotalTimeoutMinutes?: number;
  wishWorkflowWaitMinutes?: number;
  wishDefaultShellTimeoutSeconds?: number;
  wishDefaultAgenticTotalTimeoutSeconds?: number;
  modelCatalogTtlSeconds?: number;
  /** running + queued (a queued run already holds or is about to hold a permit) */
  activeRuns: number;
  /** subset of activeRuns still waiting for a permit or container */
  queuedRuns?: number;
}

export function useExecutionConfig() {
  return useQuery({
    queryKey: ['system', 'execution-config'],
    queryFn: () => api.get<ExecutionConfig>('/system/execution-config'),
    staleTime: 30_000,
  });
}

export function useUpdateExecutionConfig() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (config: Partial<Omit<ExecutionConfig, 'activeRuns'>>) =>
      api.put<ExecutionConfig>('/system/execution-config', config),
    onSuccess: (data) => {
      qc.setQueryData(['system', 'execution-config'], data);
      toast.success(t.settings.executionSaved);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.settings.executionSaveFailed)),
  });
}

export function useBrowseDirectory(path: string | null) {
  return useQuery({
    queryKey: ['system', 'browse', path],
    queryFn: () => api.get<BrowseDirectoryResponse>(`/system/browse${path ? `?path=${encodeURIComponent(path)}` : ''}`),
    enabled: path !== null,
  });
}
