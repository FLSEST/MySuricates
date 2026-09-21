import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useT } from '../i18n';
import { useToast } from './useToast';

export interface TraySettings {
  popupsEnabled: boolean;
  notifyApprovals: boolean;
  notifyFailures: boolean;
  notifySuccess: boolean;
  publicUrl: string;
}

const traySettingsKey = ['system', 'tray-config'] as const;

export function useTraySettings() {
  return useQuery({
    queryKey: traySettingsKey,
    queryFn: () => api.get<TraySettings>('/system/tray-config'),
  });
}

export function useUpdateTraySettings() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const t = useT();

  return useMutation({
    mutationFn: (settings: TraySettings) => api.put<TraySettings>('/system/tray-config', settings),
    onSuccess: (settings) => {
      queryClient.setQueryData(traySettingsKey, settings);
      toast.success(t.settings.traySaved);
    },
    onError: (error) => toast.error(apiErrorMessage(error, t.settings.traySaveFailed)),
  });
}
