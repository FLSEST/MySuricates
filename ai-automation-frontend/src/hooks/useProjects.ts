import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { Project, CreateProjectRequest, UpdateProjectRequest } from '../types';

export function useProjects() {
  return useQuery({
    queryKey: ['projects'],
    queryFn: () => api.get<Project[]>('/projects'),
  });
}

export function useProject(id: string) {
  return useQuery({
    queryKey: ['projects', id],
    queryFn: () => api.get<Project>(`/projects/${id}`),
    enabled: !!id,
  });
}

export function useCreateProject() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateProjectRequest) => api.post<Project>('/projects', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['projects'] });
      toast.success(t.toast.projectCreated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.projectCreateFailed)),
  });
}

export function useUpdateProject() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateProjectRequest }) =>
      api.put<Project>(`/projects/${id}`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['projects'] });
      toast.success(t.toast.projectUpdated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.projectUpdateFailed)),
  });
}

export function useArchiveProject() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/projects/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['projects'] });
      qc.invalidateQueries({ queryKey: ['projects', 'archived'] });
      toast.success(t.toast.projectArchived);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.projectArchiveFailed)),
  });
}

export function useRestoreProject() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.post<Project>(`/projects/${id}/restore`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['projects'] });
      qc.invalidateQueries({ queryKey: ['projects', 'archived'] });
      toast.success(t.toast.projectRestored);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.projectRestoreFailed)),
  });
}

export function useArchivedProjects() {
  return useQuery({
    queryKey: ['projects', 'archived'],
    queryFn: () => api.get<Project[]>('/projects/archived'),
    enabled: false,
  });
}

export function useStartContainer() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.post<Project>(`/projects/${id}/start`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['projects'] });
      toast.success(t.toast.containerStarted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.containerStartFailed)),
  });
}

export function useStopContainer() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.post<Project>(`/projects/${id}/stop`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['projects'] });
      toast.success(t.toast.containerStopped);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.containerStopFailed)),
  });
}

export function useGenerateProjectContext() {
  return useMutation({
    mutationFn: (id: string) => api.post<{ context: string }>(`/projects/${id}/generate-context`),
  });
}

// Onboarding hooks

export interface OnboardingStatus {
  enabled: boolean;
  format: string;
  profileIds: string[];
  fileExists: boolean;
  filePath: string;
}

export function useOnboardingStatus(projectId: string) {
  return useQuery({
    queryKey: ['projects', projectId, 'onboarding'],
    queryFn: () => api.get<OnboardingStatus>(`/projects/${projectId}/onboarding/status`),
    enabled: !!projectId,
  });
}

export function useEnableOnboarding() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, config }: { id: string; config: { format?: string; profileIds?: string[] } }) =>
      api.post<OnboardingStatus>(`/projects/${id}/onboarding/enable`, config),
    onSuccess: (_, vars) => {
      qc.invalidateQueries({ queryKey: ['projects', vars.id, 'onboarding'] });
      toast.success(t.toast.onboardingEnabled);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.onboardingFailed)),
  });
}

export function useDisableOnboarding() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.post<OnboardingStatus>(`/projects/${id}/onboarding/disable`),
    onSuccess: (_, id) => {
      qc.invalidateQueries({ queryKey: ['projects', id, 'onboarding'] });
      toast.success(t.toast.onboardingDisabled);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.onboardingFailed)),
  });
}

export function useRefreshOnboarding() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.post<OnboardingStatus>(`/projects/${id}/onboarding/refresh`),
    onSuccess: (_, id) => {
      qc.invalidateQueries({ queryKey: ['projects', id, 'onboarding'] });
      toast.success(t.toast.onboardingRefreshed);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.onboardingFailed)),
  });
}

export function useOnboardingPreview(projectId: string) {
  return useQuery({
    queryKey: ['projects', projectId, 'onboarding', 'preview'],
    queryFn: () => api.get<{ content: string }>(`/projects/${projectId}/onboarding/preview`),
    enabled: false,
  });
}
