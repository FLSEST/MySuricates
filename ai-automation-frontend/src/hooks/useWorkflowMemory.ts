import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { WorkflowMemory, ExtractMemoryRequest } from '../types';

export function useWorkflowMemories(workflowId: string) {
  return useQuery({
    queryKey: ['workflow-memory', workflowId],
    queryFn: () => api.get<WorkflowMemory[]>(`/workflows/${workflowId}/memory`),
    enabled: !!workflowId,
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
    onError: (error) => {
      const msg = (error as { error?: string })?.error;
      toast.error(msg ? `${t.memory.extractFailed}: ${msg}` : t.memory.extractFailed);
    },
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
    onError: () => toast.error(t.memory.activateFailed),
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
    onError: () => toast.error(t.memory.updateFailed),
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
    onError: () => toast.error(t.memory.deleteFailed),
  });
}
