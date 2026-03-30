import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { downloadJSON } from '../utils/downloadFile';
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
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowCreateFailed)),
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
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowCreateFailed)),
  });
}

export async function exportTemplate(template: WorkflowTemplate, locale: string) {
  const { resolveTemplateName } = await import('../utils/templateUtils');
  const name = resolveTemplateName(template.name, locale).replace(/\s+/g, '_').toLowerCase();
  downloadJSON(template, `template_${name}.json`);
}
