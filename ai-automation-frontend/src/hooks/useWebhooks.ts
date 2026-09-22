import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { WebhookTrigger, CreateWebhookRequest, WebhookSigningSecret } from '../types';

export function useWebhooks() {
  return useQuery({
    queryKey: ['webhooks'],
    queryFn: () => api.get<WebhookTrigger[]>('/webhooks'),
  });
}

/** Webhooks that start this workflow; `enabled` = false while the webhooks module is off. */
export function useWebhooksForWorkflow(workflowId: string, enabled = true) {
  return useQuery({
    queryKey: ['webhooks', 'workflow', workflowId],
    queryFn: () => api.get<WebhookTrigger[]>(`/webhooks/workflow/${workflowId}`),
    enabled: !!workflowId && enabled,
  });
}

export function useCreateWebhook() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateWebhookRequest) =>
      api.post<WebhookTrigger>('/webhooks', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['webhooks'] });
      toast.success(t.toast.webhookCreated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.webhookCreateFailed)),
  });
}

export function useToggleWebhook() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) =>
      api.post<WebhookTrigger>(`/webhooks/${id}/${enable ? 'enable' : 'disable'}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['webhooks'] });
      toast.success(t.toast.webhookToggled);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.webhookToggleFailed)),
  });
}

export function useRegenerateWebhookToken() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.post<WebhookTrigger>(`/webhooks/${id}/regenerate-token`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['webhooks'] });
      toast.success(t.toast.webhookTokenRegenerated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.webhookTokenRegenerateFailed)),
  });
}

/** Sets or rotates the signing secret (module webhook-signatures); the response is the only copy of it. */
export function useGenerateWebhookSecret() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.post<WebhookSigningSecret>(`/webhooks/${id}/secret`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['webhooks'] }),
    onError: (err) => toast.error(apiErrorMessage(err, t.webhooks.secretFailed)),
  });
}

export function useRemoveWebhookSecret() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/webhooks/${id}/secret`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['webhooks'] });
      toast.success(t.webhooks.secretRemoved);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.webhooks.secretFailed)),
  });
}

export function useDeleteWebhook() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/webhooks/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['webhooks'] });
      toast.success(t.toast.webhookDeleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.webhookDeleteFailed)),
  });
}
