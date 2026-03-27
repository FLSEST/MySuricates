import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { DialogSessionInfo, DialogWorkflowSuggestion, Workflow } from '../types';

export function useDialogSessions(projectPath?: string, limit = 50) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (projectPath) params.set('projectPath', projectPath);

  return useQuery({
    queryKey: ['dialog', 'sessions', projectPath, limit],
    queryFn: () => api.get<DialogSessionInfo[]>(`/dialog/sessions?${params}`),
    staleTime: 30_000,
  });
}

export function useDialogProjects() {
  return useQuery({
    queryKey: ['dialog', 'projects'],
    queryFn: () => api.get<string[]>('/dialog/projects'),
    staleTime: 60_000,
  });
}

export function useAnalyzeDialog() {
  return useMutation({
    mutationFn: (filePath: string) =>
      api.post<DialogWorkflowSuggestion>('/dialog/sessions/analyze', { filePath }),
  });
}

export function useCreateWorkflowFromDialog() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      suggestion,
      projectId,
      name,
    }: {
      suggestion: DialogWorkflowSuggestion;
      projectId: string;
      name?: string;
    }) => api.post<Workflow>('/dialog/sessions/create-workflow', { suggestion, projectId, name }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
    },
  });
}
