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
