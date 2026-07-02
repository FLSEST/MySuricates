import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { IntegrationProviderInfo, TaskTarget } from '../types';

export function useIntegrations() {
  return useQuery({
    queryKey: ['integrations'],
    queryFn: () => api.get<IntegrationProviderInfo[]>('/integrations'),
  });
}

/** Selectable export targets for a task-capable provider (GitHub repos, …). */
export function useTaskTargets(providerId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['task-targets', providerId],
    queryFn: () => api.get<TaskTarget[]>(`/integrations/${providerId}/task-targets`),
    enabled: enabled && !!providerId,
    staleTime: 60_000,
  });
}

export function useIntegration(providerId: string) {
  return useQuery({
    queryKey: ['integrations', providerId],
    queryFn: () => api.get<IntegrationProviderInfo>(`/integrations/${providerId}`),
    enabled: !!providerId,
  });
}

export function useConnectIntegration() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();

  return useMutation({
    mutationFn: async ({ providerId, scopes }: { providerId: string; scopes?: string[] }) => {
      const params = scopes?.length ? `?scopes=${scopes.map(encodeURIComponent).join('&scopes=')}` : '';
      const { authorizationUrl } = await api.get<{ authorizationUrl: string }>(
        `/integrations/${providerId}/connect${params}`
      );

      return new Promise<void>((resolve, reject) => {
        const popup = window.open(authorizationUrl, 'oauth-popup', 'width=600,height=700');
        if (!popup) {
          reject(new Error('Popup blocked'));
          return;
        }

        const handler = (event: MessageEvent) => {
          if (event.data?.type === 'oauth-callback') {
            window.removeEventListener('message', handler);
            resolve();
          }
        };
        window.addEventListener('message', handler);

        const pollTimer = setInterval(() => {
          if (popup.closed) {
            clearInterval(pollTimer);
            window.removeEventListener('message', handler);
            // Popup was closed — might have been successful, refresh anyway
            resolve();
          }
        }, 500);
      });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['integrations'] });
      toast.success(t.toast.integrationConnected);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.integrationConnectFailed)),
  });
}

export function useDisconnectIntegration() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();

  return useMutation({
    mutationFn: (providerId: string) =>
      api.delete(`/integrations/${providerId}/connection`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['integrations'] });
      toast.success(t.toast.integrationDisconnected);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.integrationDisconnectFailed)),
  });
}

export function useDeleteCredentials() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();

  return useMutation({
    mutationFn: (providerId: string) =>
      api.delete(`/integrations/${providerId}/credentials`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['integrations'] });
      toast.success(t.toast.credentialsDeleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.credentialsDeleteFailed)),
  });
}

export function useSaveCredentials() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();

  return useMutation({
    mutationFn: ({ providerId, clientId, clientSecret }: {
      providerId: string;
      clientId: string;
      clientSecret: string;
    }) => api.post<IntegrationProviderInfo>(
      `/integrations/${providerId}/credentials`,
      { clientId, clientSecret }
    ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['integrations'] });
      toast.success(t.toast.credentialsSaved);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.credentialsSaveFailed)),
  });
}

export function useTestIntegrationAction() {
  const toast = useToast();
  const t = useT();

  return useMutation({
    mutationFn: ({ providerId, action, params }: {
      providerId: string;
      action: string;
      params?: Record<string, unknown>;
    }) => api.post<unknown>(`/integrations/${providerId}/actions/${action}`, params ?? {}),
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.integrationActionFailed)),
  });
}
