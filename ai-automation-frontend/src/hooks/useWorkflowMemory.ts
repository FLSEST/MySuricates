import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { WorkflowMemory, ExtractMemoryRequest } from '../types';
import { useModuleEnabled } from './useModules';

export function useWorkflowMemories(workflowId: string) {
  const moduleEnabled = useModuleEnabled('memory');
  return useQuery({
    queryKey: ['workflow-memory', workflowId],
    queryFn: () => api.get<WorkflowMemory[]>(`/workflows/${workflowId}/memory`),
    enabled: moduleEnabled && !!workflowId,
  });
}

export function useExtractMemory(workflowId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: ExtractMemoryRequest) =>
      api.post<WorkflowMemory>(`/workflows/${workflowId}/memory/extract`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflow-memory', workflowId] });
      toast.success(t.memory.extracted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.memory.extractFailed)),
  });
}

export function useActivateMemory(workflowId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (memoryId: string) =>
      api.put(`/workflows/${workflowId}/memory/${memoryId}/activate`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflow-memory', workflowId] });
      toast.success(t.memory.activated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.memory.activateFailed)),
  });
}

export function useUpdateMemory(workflowId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ memoryId, title, content }: { memoryId: string; title?: string; content?: string }) =>
      api.put<WorkflowMemory>(`/workflows/${workflowId}/memory/${memoryId}`, { title, content }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflow-memory', workflowId] });
      toast.success(t.memory.updated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.memory.updateFailed)),
  });
}

export function useDeleteMemory(workflowId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (memoryId: string) =>
      api.delete(`/workflows/${workflowId}/memory/${memoryId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflow-memory', workflowId] });
      toast.success(t.memory.deleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.memory.deleteFailed)),
  });
}
