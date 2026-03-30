import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { CompletionTrigger, CreateCompletionTriggerRequest } from '../types';

export function useCompletionTriggersAsSource(workflowId: string) {
  return useQuery({
    queryKey: ['completion-triggers', 'source', workflowId],
    queryFn: () => api.get<CompletionTrigger[]>(`/completion-triggers/workflow/${workflowId}/as-source`),
    enabled: !!workflowId,
  });
}

export function useCompletionTriggersAsTarget(workflowId: string) {
  return useQuery({
    queryKey: ['completion-triggers', 'target', workflowId],
    queryFn: () => api.get<CompletionTrigger[]>(`/completion-triggers/workflow/${workflowId}/as-target`),
    enabled: !!workflowId,
  });
}

export function useCreateCompletionTrigger() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateCompletionTriggerRequest) =>
      api.post<CompletionTrigger>('/completion-triggers', data),
    onSuccess: (_data, variables) => {
      qc.invalidateQueries({ queryKey: ['completion-triggers', 'source', variables.sourceWorkflowId] });
      qc.invalidateQueries({ queryKey: ['completion-triggers', 'target', variables.targetWorkflowId] });
      toast.success(t.toast.completionTriggerCreated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.completionTriggerCreateFailed)),
  });
}

export function useToggleCompletionTrigger(workflowId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) =>
      api.post<CompletionTrigger>(`/completion-triggers/${id}/${enable ? 'enable' : 'disable'}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['completion-triggers', 'source', workflowId] });
      qc.invalidateQueries({ queryKey: ['completion-triggers', 'target', workflowId] });
      toast.success(t.toast.completionTriggerToggled);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.completionTriggerToggleFailed)),
  });
}

export function useDeleteCompletionTrigger(workflowId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/completion-triggers/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['completion-triggers', 'source', workflowId] });
      qc.invalidateQueries({ queryKey: ['completion-triggers', 'target', workflowId] });
      toast.success(t.toast.completionTriggerDeleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.completionTriggerDeleteFailed)),
  });
}
