import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useI18nStore, useT } from '../i18n';
import { downloadJSON } from '../utils/downloadFile';
import type { WorkflowTemplate, CreateWorkflowTemplateRequest } from '../types';

/**
 * Templates with their steps in the UI language: bilingual templates carry {de,en} texts in their
 * blocks, and the backend resolves them per `locale`. The page copies the blocks unchanged into
 * the new workflow, which is then single-language.
 */
export function useTemplates({ enabled = true }: { enabled?: boolean } = {}) {
  const locale = useI18nStore((s) => s.locale);
  return useQuery({
    queryKey: ['workflow-templates', locale],
    queryFn: () => api.get<WorkflowTemplate[]>(`/workflow-templates?locale=${encodeURIComponent(locale)}`),
    enabled,
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
