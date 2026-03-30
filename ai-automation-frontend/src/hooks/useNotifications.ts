import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { NotificationConfig, CreateNotificationConfigRequest } from '../types';

export function useNotifications() {
  return useQuery({
    queryKey: ['notifications'],
    queryFn: () => api.get<NotificationConfig[]>('/notifications'),
  });
}

export function useCreateNotification() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateNotificationConfigRequest) =>
      api.post<NotificationConfig>('/notifications', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['notifications'] });
      toast.success(t.toast.notificationCreated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.notificationCreateFailed)),
  });
}

export function useToggleNotification() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) =>
      api.post<NotificationConfig>(`/notifications/${id}/${enable ? 'enable' : 'disable'}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['notifications'] });
      toast.success(t.toast.notificationToggled);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.notificationToggleFailed)),
  });
}

export function useDeleteNotification() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/notifications/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['notifications'] });
      toast.success(t.toast.notificationDeleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.notificationDeleteFailed)),
  });
}
