import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { IntegrationProviderInfo, TaskTarget } from '../types';
import { useModuleEnabled } from './useModules';

/** Name of the connection every provider has; calls without a name mean this one. */
export const DEFAULT_CONNECTION = 'default';

/** Same rule as the backend (IntegrationConnectionNames): 1–40 lower-case letters, digits or dashes. */
export const CONNECTION_NAME_PATTERN = /^[a-z0-9][a-z0-9-]{0,39}$/;

/** A provider, optionally narrowed to one of its named connections. */
export type ConnectionRef = string | { providerId: string; connection?: string };

function refOf(ref: ConnectionRef): { providerId: string; connection?: string } {
  return typeof ref === 'string' ? { providerId: ref } : ref;
}

/** `connection=<name>` for a named connection; nothing for the default one (older backends ignore it anyway). */
export function connectionParam(connection?: string): string {
  return connection && connection !== DEFAULT_CONNECTION
    ? `connection=${encodeURIComponent(connection)}`
    : '';
}

function withConnection(path: string, connection?: string): string {
  const param = connectionParam(connection);
  return param ? `${path}${path.includes('?') ? '&' : '?'}${param}` : path;
}

export function useIntegrations() {
  const moduleEnabled = useModuleEnabled('integrations');
  return useQuery({
    queryKey: ['integrations'],
    queryFn: () => api.get<IntegrationProviderInfo[]>('/integrations'),
    enabled: moduleEnabled,
  });
}

/** Selectable export targets for a task-capable provider (GitHub repos, …). */
export function useTaskTargets(providerId: string, enabled: boolean) {
  const moduleEnabled = useModuleEnabled('integrations');
  return useQuery({
    queryKey: ['task-targets', providerId],
    queryFn: () => api.get<TaskTarget[]>(`/integrations/${providerId}/task-targets`),
    enabled: moduleEnabled && enabled && !!providerId,
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
    mutationFn: async ({ providerId, scopes, connection }: { providerId: string; scopes?: string[]; connection?: string }) => {
      const params = new URLSearchParams({ frontendOrigin: window.location.origin });
      scopes?.forEach(scope => params.append('scopes', scope));
      if (connection && connection !== DEFAULT_CONNECTION) params.append('connection', connection);
      const { authorizationUrl, callbackOrigin } = await api.get<{ authorizationUrl: string; callbackOrigin?: string }>(
        `/integrations/${providerId}/connect?${params}`
      );

      return new Promise<void>((resolve, reject) => {
        const popup = window.open(authorizationUrl, 'oauth-popup', 'width=600,height=700');
        if (!popup) {
          reject(new Error('Popup blocked'));
          return;
        }

        const handler = (event: MessageEvent) => {
          if (event.origin === (callbackOrigin ?? window.location.origin)
              && event.source === popup
              && event.data?.type === 'oauth-callback'
              && event.data.provider === providerId
              && event.data.status === 'connected') {
            clearInterval(pollTimer);
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
    mutationFn: (ref: ConnectionRef) => {
      const { providerId, connection } = refOf(ref);
      return api.delete(withConnection(`/integrations/${providerId}/connection`, connection));
    },
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
    mutationFn: (ref: ConnectionRef) => {
      const { providerId, connection } = refOf(ref);
      return api.delete(withConnection(`/integrations/${providerId}/credentials`, connection));
    },
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
    mutationFn: ({ providerId, clientId, clientSecret, connection }: {
      providerId: string;
      clientId: string;
      clientSecret: string;
      connection?: string;
    }) => api.post<IntegrationProviderInfo>(
      withConnection(`/integrations/${providerId}/credentials`, connection),
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
    mutationFn: ({ providerId, action, params, connection }: {
      providerId: string;
      action: string;
      params?: Record<string, unknown>;
      connection?: string;
    }) => api.post<unknown>(withConnection(`/integrations/${providerId}/actions/${action}`, connection), params ?? {}),
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.integrationActionFailed)),
  });
}
