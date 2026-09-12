import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { WebhookTrigger, CreateWebhookRequest } from '../types';

export function useWebhooks() {
  return useQuery({
    queryKey: ['webhooks'],
    queryFn: () => api.get<WebhookTrigger[]>('/webhooks'),
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
