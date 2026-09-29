import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useCreateWorkflow } from './useWorkflows';
import { useInstallPoolWorkflow } from './usePool';
import { useSettingsStore } from '../stores/settingsStore';
import { useI18nStore } from '../i18n';
import { resolveTemplateName } from '../utils/templateUtils';
import type { CreateStep } from '../components/workflow/WorkflowCreateFlow';
import type { CreateWorkflowRequest, WorkflowTemplate, PoolWorkflowEntry } from '../types';

/**
 * Anlegen eines Workflows aus der Liste: erst Vorlage, Pool-Eintrag oder leer wählen, dann
 * Details. Ein Pool-Eintrag mit Projekt wird über den Pool installiert, alles andere neu angelegt
 * (mit Auto-TODO, wenn das die Voreinstellung ist); danach geht es zum neuen Workflow.
 */
export function useWorkflowCreateFlow() {
  const navigate = useNavigate();
  const locale = useI18nStore((s) => s.locale);
  const createWorkflow = useCreateWorkflow();
  const installPoolWorkflow = useInstallPoolWorkflow();
  const [createStep, setCreateStep] = useState<CreateStep>('closed');
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
    createLoading: createWorkflow.isPending || installPoolWorkflow.isPending,
  };
}
