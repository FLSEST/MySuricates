import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useI18nStore, useT } from '../i18n';
import { downloadJSON } from '../utils/downloadFile';
import { resolveTemplateName } from '../utils/templateUtils';
import type { WorkflowTemplate, CreateWorkflowTemplateRequest, InstallTemplateRequest, TemplateInstallResponse } from '../types';

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

/**
 * The route itself is unknown (`GlobalExceptionHandler.handleNoResourceFound`: "No endpoint: …"), not
 * a missing template or blueprint, which the new endpoint reports as 404 too.
 */
function isMissingEndpoint(err: unknown): boolean {
  const e = err as { status?: number; message?: string; error?: string } | null;
  return e?.status === 404 && /^No endpoint/.test(e.message ?? e.error ?? '');
}

/**
 * Creates a workflow from a template on the server, together with the blueprints its sub-workflow
 * blocks call, wired to their new ids (TODO 093d2f11). Used for blueprint packages; names and steps
 * come in the UI language.
 */
export function useInstallTemplate() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  return useMutation({
    mutationFn: ({ templateId, ...request }: InstallTemplateRequest & { templateId: string }) =>
      api.post<TemplateInstallResponse>(`/workflow-templates/${encodeURIComponent(templateId)}/install`, { ...request, locale }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.toast.workflowCreated);
    },
    // An older backend (the frontend goes live before the restart) has no install endpoint: say so
    // instead of a bare 404, and never fall back to a plain create that would name a blueprint
    onError: (err) => toast.error(isMissingEndpoint(err)
      ? t.blueprints.installNeedsNewerBackend
      : apiErrorMessage(err, t.toast.workflowCreateFailed)),
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

export function exportTemplate(template: WorkflowTemplate, locale: string) {
  const name = resolveTemplateName(template.name, locale).replace(/\s+/g, '_').toLowerCase();
  downloadJSON(template, `template_${name}.json`);
}
