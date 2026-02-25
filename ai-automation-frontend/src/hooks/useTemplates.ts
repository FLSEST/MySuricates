import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { WorkflowTemplate, CreateWorkflowTemplateRequest } from '../types';

export function useTemplates() {
  return useQuery({
    queryKey: ['workflow-templates'],
    queryFn: () => api.get<WorkflowTemplate[]>('/workflow-templates'),
  });
}

export function useCreateTemplate() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateWorkflowTemplateRequest) =>
      api.post<WorkflowTemplate>('/workflow-templates', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflow-templates'] });
      toast.success(t.toast.workflowCreated);
    },
    onError: () => toast.error(t.toast.workflowCreateFailed),
  });
}

export function useDeleteTemplate() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/workflow-templates/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflow-templates'] });
      toast.success(t.toast.workflowDeleted);
    },
    onError: () => toast.error(t.toast.workflowDeleteFailed),
  });
}

export function useSaveWorkflowAsTemplate() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (workflowId: string) =>
      api.post<WorkflowTemplate>(`/workflow-templates/from-workflow/${workflowId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflow-templates'] });
      toast.success(t.templates.savedAsTemplate);
    },
    onError: () => toast.error(t.toast.workflowCreateFailed),
  });
}
