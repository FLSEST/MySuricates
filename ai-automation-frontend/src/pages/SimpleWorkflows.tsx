import { useState, useMemo, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { GitBranch, Play, Search, Pin, Trash2 } from 'lucide-react';
import { Button, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { SimpleRow, StatusDot } from '../components/simple/kit';
import { useSimpleRun } from '../components/simple/useSimpleRun';
import { WorkflowCreateFlow, type CreateStep } from '../components/workflow/WorkflowCreateFlow';
import { FirstProjectActions } from '../components/project/FirstProjectActions';
import {
  useWorkflows,
  useCreateWorkflow,
  useDeleteWorkflow,
} from '../hooks/useWorkflows';
import { useSettingsStore } from '../stores/settingsStore';
import { useProjects } from '../hooks/useProjects';
import { useT, useI18nStore } from '../i18n';
import { resolveTemplateName } from '../utils/templateUtils';
import { curateWorkflows } from '../utils/curateWorkflows';
import { AREA_COLORS } from '../utils/homeTiles';
import { useLatestRunStatuses } from '../hooks/useLatestRunStatuses';
import type { CreateWorkflowRequest, WorkflowTemplate, PoolWorkflowEntry } from '../types';
import { useInstallPoolWorkflow } from '../hooks/usePool';

export function SimpleWorkflows() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();

  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const { data: projects } = useProjects();
  const createWorkflow = useCreateWorkflow();
  const installPoolWorkflow = useInstallPoolWorkflow();
  const simpleRun = useSimpleRun();
  const deleteWorkflow = useDeleteWorkflow();

  // `?new=1` (the Simple home's "start from a template") opens the create dialog right away
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedCreate = searchParams.get('new') === '1';
  const [createStep, setCreateStep] = useState<CreateStep>(requestedCreate ? 'template' : 'closed');
  const [selectedTemplate, setSelectedTemplate] = useState<WorkflowTemplate | null>(null);
  const [form, setForm] = useState<CreateWorkflowRequest>({ projectId: '', name: '' });
  const [search, setSearch] = useState('');
  const [showAll, setShowAll] = useState(false);
  const { pinnedWorkflowIds, togglePinnedWorkflow } = useSettingsStore();
  const latestStatus = useLatestRunStatuses();

  const filtered = useMemo(() => {
    const base = [...(workflows ?? [])];
    if (!search) return base;
    const q = search.toLowerCase();
    return base.filter(
      (w) =>
        w.name.toLowerCase().includes(q) ||
        (w.description?.toLowerCase().includes(q) ?? false),
    );
  }, [workflows, search]);

  const { curated, rest } = useMemo(
    () => curateWorkflows(filtered, pinnedWorkflowIds, 6),
    [filtered, pinnedWorkflowIds],
  );

  const visible = search ? [...curated, ...rest] : showAll ? [...curated, ...rest] : curated;

  const openCreate = () => {
    setCreateStep('template');
    setSelectedTemplate(null);
    setForm({ projectId: '', name: '' });
  };

  // The param has done its job once the dialog is open; dropping it keeps back/reload from reopening it
  useEffect(() => {
    if (!requestedCreate) return;
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('new');
      return next;
    }, { replace: true });
  }, [requestedCreate, setSearchParams]);

  const handleTemplateSelect = (template: WorkflowTemplate | null) => {
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

  const handlePoolSelect = (entry: PoolWorkflowEntry) => {
    setSelectedTemplate(null);
    setForm({
      projectId: '',
      name: resolveTemplateName(entry.name, locale),
      description: resolveTemplateName(entry.description, locale),
      icon: entry.icon,
      blocks: entry.blocks,
      instructions: entry.instructions ?? undefined,
      model: entry.model ?? undefined,
      metadata: { poolSource: entry.id },
      ...(entry.defaultContext
        ? { defaultContext: Object.fromEntries(Object.entries(entry.defaultContext).map(([k, v]) => [k, String(v)])) }
        : {}),
    } as CreateWorkflowRequest);
    setCreateStep('details');
  };

  const handleCreate = () => {
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

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="animate-fade-in max-w-4xl mx-auto space-y-6">
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-[28px] font-bold text-text tracking-tight leading-none">{t.simple.home.tiles.workflows.title}</h1>
        <Button icon={<GitBranch className="w-4 h-4" />} onClick={openCreate}>
          {t.workflows.create}
        </Button>
      </div>

      {(workflows?.length ?? 0) > 0 && (
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-text-tertiary pointer-events-none" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t.workflows.searchPlaceholder}
            className="w-full pl-9 pr-3 py-2 text-sm bg-surface border border-border rounded-lg text-text placeholder:text-text-tertiary focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </div>
      )}

      {!search && projects?.length === 0 ? (
        <EmptyState
          icon={<GitBranch className="w-8 h-8 text-text-tertiary" />}
          title={t.projects.firstProject.title}
          description={t.projects.firstProject.workflowsHint}
          action={<FirstProjectActions />}
        />
      ) : visible.length === 0 ? (
        <EmptyState
          icon={<GitBranch className="w-8 h-8 text-text-tertiary" />}
          title={t.workflows.title}
          description={t.workflows.noWorkflows}
          action={
            <Button size="sm" onClick={openCreate}>
              {t.workflows.create}
            </Button>
          }
        />
      ) : (
        <div className="space-y-2">
          {visible.map((workflow) => (
            <SimpleRow key={workflow.id} color={AREA_COLORS.workflows}>
              <button
                className="flex items-center gap-3 min-w-0 text-left"
                onClick={() => navigate(`/workflows/${workflow.id}`)}
              >
                <span className="text-lg">{workflow.icon || '⚡'}</span>
                <div className="min-w-0">
                  <p className="text-sm font-medium text-text truncate">{workflow.name}</p>
                  {workflow.description && (
                    <p className="text-xs text-text-tertiary truncate">{workflow.description}</p>
                  )}
                </div>
              </button>
              <div className="flex items-center gap-2 shrink-0">
                <StatusDot status={latestStatus.get(workflow.id)} />
                <button
                  aria-label="pin"
                  className={pinnedWorkflowIds.includes(workflow.id) ? 'text-accent' : 'text-text-tertiary hover:text-text'}
                  onClick={() => togglePinnedWorkflow(workflow.id)}
                >
                  <Pin className="w-3.5 h-3.5" />
                </button>
                <button
                  aria-label={t.common.delete}
                  className="text-text-tertiary hover:text-danger transition-colors"
                  onClick={() => { if (window.confirm(t.common.deleteConfirm)) deleteWorkflow.mutate(workflow.id); }}
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
                <Button
                  size="sm"
                  variant="secondary"
                  icon={<Play className="w-3.5 h-3.5" />}
                  onClick={() => simpleRun.run(workflow)}
                  loading={simpleRun.isPending}
                >
                  {t.workflows.execute}
                </Button>
              </div>
            </SimpleRow>
          ))}
          {!search && rest.length > 0 && (
            <button
              className="text-sm text-accent hover:underline mt-1"
              onClick={() => setShowAll((v) => !v)}
            >
              {showAll ? t.simple.workflows.showLess : `${t.simple.workflows.showAll} (${rest.length})`}
            </button>
          )}
        </div>
      )}

      {simpleRun.dialog}

      <WorkflowCreateFlow
        t={t}
        locale={locale}
        createStep={createStep}
        selectedTemplate={selectedTemplate}
        form={form}
        projects={projects}
        createLoading={createWorkflow.isPending || installPoolWorkflow.isPending}
        onFormChange={setForm}
        onTemplateSelect={handleTemplateSelect}
        onPoolSelect={handlePoolSelect}
        onCreate={handleCreate}
        onClose={() => { setCreateStep('closed'); setSelectedTemplate(null); setForm({ projectId: '', name: '' }); }}
        onBackToTemplates={() => setCreateStep('template')}
        variant="simple"
      />
    </div>
  );
}
