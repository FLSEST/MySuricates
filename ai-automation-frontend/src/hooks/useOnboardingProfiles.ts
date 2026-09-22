import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { useModuleEnabled } from './useModules';

export interface OnboardingProfile {
  id: string;
  key: string;
  name: Record<string, string>;
  description: Record<string, string>;
  /** Markdown per language (`de`, `en`); a missing language falls back to the other when written. */
  content: Record<string, string>;
  type: 'BASE' | 'PROFILE';
  builtIn: boolean;
  sortOrder: number;
  version: number;
  historyCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface OnboardingProfileHistory {
  id: string;
  version: number;
  content: Record<string, string>;
  changedBy: string;
  changedAt: string;
}

export function useOnboardingProfiles() {
  const moduleEnabled = useModuleEnabled('onboarding-profiles');
  return useQuery({
    queryKey: ['onboarding-profiles'],
    queryFn: () => api.get<OnboardingProfile[]>('/onboarding-profiles'),
    enabled: moduleEnabled,
  });
}

export function useOnboardingProfile(id: string) {
  return useQuery({
    queryKey: ['onboarding-profiles', id],
    queryFn: () => api.get<OnboardingProfile>(`/onboarding-profiles/${id}`),
    enabled: !!id,
  });
}

export function useCreateOnboardingProfile() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: { key: string; name: Record<string, string>; description: Record<string, string>; content: Record<string, string>; type: 'BASE' | 'PROFILE' }) =>
      api.post<OnboardingProfile>('/onboarding-profiles', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['onboarding-profiles'] });
      toast.success(t.toast.profileCreated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.profileCreateFailed)),
  });
}

export function useUpdateOnboardingProfile() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: { name?: Record<string, string>; description?: Record<string, string>; content?: Record<string, string> } }) =>
      api.put<OnboardingProfile>(`/onboarding-profiles/${id}`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['onboarding-profiles'] });
      toast.success(t.toast.profileUpdated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.profileUpdateFailed)),
  });
}

export function useDeleteOnboardingProfile() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/onboarding-profiles/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['onboarding-profiles'] });
      toast.success(t.toast.profileDeleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.profileDeleteFailed)),
  });
}

export function useResetOnboardingProfile() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.post<OnboardingProfile>(`/onboarding-profiles/${id}/reset`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['onboarding-profiles'] });
      toast.success(t.toast.profileReset);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.profileResetFailed)),
  });
}

export function useOnboardingProfileHistory(profileId: string) {
  return useQuery({
    queryKey: ['onboarding-profiles', profileId, 'history'],
    queryFn: () => api.get<OnboardingProfileHistory[]>(`/onboarding-profiles/${profileId}/history`),
    enabled: !!profileId,
  });
}
