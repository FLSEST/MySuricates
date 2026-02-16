import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
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
    onError: () => toast.error(t.toast.webhookCreateFailed),
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
    onError: () => toast.error(t.toast.webhookToggleFailed),
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
    onError: () => toast.error(t.toast.webhookDeleteFailed),
  });
}
