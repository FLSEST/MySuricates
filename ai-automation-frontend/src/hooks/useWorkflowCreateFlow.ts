import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useCreateWorkflow } from './useWorkflows';
import { useInstallPoolWorkflow, usePoolWorkflows } from './usePool';
import { useInstallTemplate, useTemplates } from './useTemplates';
import { useSettingsStore } from '../stores/settingsStore';
import { useI18nStore } from '../i18n';
import { blueprintPartIds, resolveTemplateName } from '../utils/templateUtils';
import type { CreateStep } from '../components/workflow/WorkflowCreateFlow';
import type { CreateWorkflowRequest, WorkflowTemplate, PoolWorkflowEntry } from '../types';

export interface WorkflowCreateFlowOptions {
  /**
   * Also honour `?pool=<id>` (a hit of the Simple home's command field): it opens the dialog with
   * that pool entry chosen once the pool is loaded, like `?template=` does for templates.
   */
  poolParam?: boolean;
}

/**
 * Creating a workflow from the list: first pick template, pool entry or empty, then
 * details. A pool entry with a project is installed via the pool, a blueprint package (a
 * sub-workflow block names a blueprint) via the server, which creates the part too; everything else is
 * created fresh (with auto-TODO if that is the default); afterwards it goes to the new workflow.
 */
export function useWorkflowCreateFlow({ poolParam = false }: WorkflowCreateFlowOptions = {}) {
  const navigate = useNavigate();
  const locale = useI18nStore((s) => s.locale);
  const createWorkflow = useCreateWorkflow();
  const installPoolWorkflow = useInstallPoolWorkflow();
  const installTemplate = useInstallTemplate();
  // `?new=1[&template=<id>]` — a hint's blueprint link (093d2f11) — opens the dialog, with that
  // template chosen once the list is loaded; unknown ids leave the list open
  const [searchParams, setSearchParams] = useSearchParams();
  const [requested] = useState(() => {
    const pool = poolParam ? searchParams.get('pool') : null;
    return {
      open: searchParams.has('new') || searchParams.has('template') || pool !== null,
      template: searchParams.get('template'),
      pool,
    };
  });
  const [pendingTemplate, setPendingTemplate] = useState(requested.template);
  const [pendingPool, setPendingPool] = useState(requested.pool);
  const pickedTemplates = useTemplates({ enabled: !!pendingTemplate });
  const pickedPool = usePoolWorkflows({ enabled: !!pendingPool });
  const [createStep, setCreateStep] = useState<CreateStep>(requested.open ? 'template' : 'closed');
  const [selectedTemplate, setSelectedTemplate] = useState<WorkflowTemplate | null>(null);
  const [form, setForm] = useState<CreateWorkflowRequest>({ projectId: '', name: '' });

  const openCreate = () => {
    setCreateStep('template');
    setSelectedTemplate(null);
    setForm({ projectId: '', name: '' });
  };

  const selectTemplate = (template: WorkflowTemplate | null) => {
    setSelectedTemplate(template);
    if (template) {
      setForm({
        projectId: '',
        name: resolveTemplateName(template.name, locale),
        description: resolveTemplateName(template.description, locale),
        icon: template.icon,
        blocks: template.blocks,
        instructions: template.instructions ?? undefined,
        model: template.model ?? undefined,
        templateId: template.id,
      });
    } else {
      setForm({ projectId: '', name: '' });
    }
    setCreateStep('details');
  };

  // The params have done their job once the dialog is open; dropping them keeps back/reload from reopening it
  useEffect(() => {
    if (!requested.open) return;
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('new');
      next.delete('template');
      if (poolParam) next.delete('pool');
      return next;
    }, { replace: true });
  }, [requested.open, poolParam, setSearchParams]);

  if (pendingTemplate && (pickedTemplates.data || pickedTemplates.isError)) {
    const template = pickedTemplates.data?.find((tmpl) => tmpl.id === pendingTemplate);
    setPendingTemplate(null);
    if (template) selectTemplate(template);
  }

  const selectPoolEntry = (entry: PoolWorkflowEntry) => {
    setSelectedTemplate(null);
    const defaultCtx = entry.defaultContext
      ? Object.fromEntries(Object.entries(entry.defaultContext).map(([k, v]) => [k, String(v)]))
      : undefined;
    setForm({
      projectId: '',
      name: resolveTemplateName(entry.name, locale),
      description: resolveTemplateName(entry.description, locale),
      icon: entry.icon,
      blocks: entry.blocks,
      instructions: entry.instructions ?? undefined,
      model: entry.model ?? undefined,
      metadata: defaultCtx ? { poolSource: entry.id } : { poolSource: entry.id },
      ...(defaultCtx ? { defaultContext: defaultCtx } : {}),
    } as CreateWorkflowRequest);
    setCreateStep('details');
  };

  // Once the requested pool entry is known, continue with it; unknown or not loadable ⇒ the list stays open
  if (pendingPool && (pickedPool.data || pickedPool.isError)) {
    const entry = pickedPool.data?.find((e) => e.id === pendingPool);
    setPendingPool(null);
    if (entry) selectPoolEntry(entry);
  }

  const create = () => {
    const poolId = (form.metadata as Record<string, unknown> | undefined)?.poolSource as string | undefined;
    const onDone = () => {
      setCreateStep('closed');
      setForm({ projectId: '', name: '' });
      setSelectedTemplate(null);
    };

    if (poolId && form.projectId) {
      installPoolWorkflow.mutate(
        { poolId, projectId: form.projectId, name: form.name || undefined },
        { onSuccess: (data) => { onDone(); navigate(`/workflows/${data.workflowId}`); } },
      );
      return;
    }

    const autoTodoDefault = useSettingsStore.getState().autoTodoDefault;
    const payload = autoTodoDefault
      ? { ...form, metadata: { ...form.metadata, autoTodo: { enabled: true } } }
      : form;

    // A blueprint package names the blueprint it calls; the server creates both and wires the id (093d2f11)
    if (selectedTemplate && form.templateId === selectedTemplate.id && blueprintPartIds(selectedTemplate).length > 0) {
      installTemplate.mutate(
        {
          templateId: selectedTemplate.id,
          projectId: form.projectId,
          name: form.name,
          description: form.description,
          ...(payload.metadata ? { metadata: payload.metadata } : {}),
        },
        { onSuccess: (data) => { onDone(); navigate(`/workflows/${data.workflowId}`); } },
      );
      return;
    }

    createWorkflow.mutate(payload, {
      onSuccess: (wf) => { onDone(); navigate(`/workflows/${wf.id}`); },
    });
  };

  const close = () => {
    setCreateStep('closed');
    setSelectedTemplate(null);
    setForm({ projectId: '', name: '' });
  };

  return {
    locale, createStep, selectedTemplate, form, setForm,
    openCreate, selectTemplate, selectPoolEntry, create, close,
    backToTemplates: () => setCreateStep('template'),
    createLoading: createWorkflow.isPending || installPoolWorkflow.isPending || installTemplate.isPending,
  };
}
