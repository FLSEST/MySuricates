import { useMemo, useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { GitBranch, Trash2 } from 'lucide-react';
import { Button, ConfirmModal, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { WorkflowListHeader } from '../components/workflow/WorkflowListHeader';
import { WorkflowListToolbar } from '../components/workflow/WorkflowListToolbar';
import { SealFilterTabs, type SealFilter } from '../components/workflow/SealFilterTabs';
import { WorkflowCreateFlow, type CreateStep } from '../components/workflow/WorkflowCreateFlow';
import { WorkflowImportModal } from '../components/workflow/WorkflowImportModal';
import { ProjectGroup } from '../components/workflow/ProjectGroup';
import { DiscoveryView } from '../components/workflow/DiscoveryView';
import { WorkflowDetailPanel } from '../components/workflow/WorkflowDetailPanel';
import { FirstProjectActions } from '../components/project/FirstProjectActions';
import { Hint } from '../components/hints/Hint';
import {
  useWorkflows,
  useCreateWorkflow,
  useUpdateWorkflow,
  useExecuteWorkflow,
  useDeleteWorkflow,
  useImportWorkflow,
} from '../hooks/useWorkflows';
import { useSettingsStore } from '../stores/settingsStore';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
import { readContextSchema } from '../utils/contextSchema';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import type { CreateWorkflowRequest, Workflow, WorkflowExportData, WorkflowTemplate, PoolWorkflowEntry } from '../types';
import { useI18nStore } from '../i18n';
import { resolveTemplateName } from '../utils/templateUtils';
import { useSaveWorkflowAsTemplate } from '../hooks/useTemplates';
import { useInstallPoolWorkflow } from '../hooks/usePool';

// ── Persistence helpers ──────────────────────────────────────

const STORAGE_KEY = 'workflows-collapsed-projects';
const SELECTED_KEY = 'workflows-selected-id';
const SHOW_DETAILS_KEY = 'workflows-show-row-details';
const VIEW_MODE_KEY = 'workflows-view-mode';

type ViewMode = 'classic' | 'discovery';

function loadCollapsed(): Set<string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return new Set(JSON.parse(raw));
  } catch { /* ignore */ }
  return new Set(['__all__']);
}

function saveCollapsed(set: Set<string>) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...set]));
  } catch { /* ignore */ }
}

function loadSelectedId(): string | null {
  try {
    return localStorage.getItem(SELECTED_KEY);
  } catch { return null; }
}

function saveSelectedId(id: string | null) {
  try {
    if (id) localStorage.setItem(SELECTED_KEY, id);
    else localStorage.removeItem(SELECTED_KEY);
  } catch { /* ignore */ }
}

// ── Main Component ──────────────────────────────────────────

export function Workflows() {
  const t = useT();
  const navigate = useNavigate();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const { data: projects } = useProjects();
  const createWorkflow = useCreateWorkflow();
  const installPoolWorkflow = useInstallPoolWorkflow();
  const updateWorkflow = useUpdateWorkflow();
  const executeWorkflow = useExecuteWorkflow();
  const deleteWorkflow = useDeleteWorkflow();
  const importWorkflow = useImportWorkflow();
  const locale = useI18nStore((s) => s.locale);
  const saveAsTemplate = useSaveWorkflowAsTemplate();

  const [search, setSearch] = useState('');
  const [selectedWorkflowId, setSelectedWorkflowId] = useState<string | null>(loadSelectedId);
  const [collapsedProjects, setCollapsedProjects] = useState<Set<string>>(loadCollapsed);
  const [showDetails, setShowDetails] = useState<boolean>(() => {
    try { return localStorage.getItem(SHOW_DETAILS_KEY) === '1'; } catch { return false; }
  });
  const [viewMode, setViewMode] = useState<ViewMode>(() => {
    try {
      const v = localStorage.getItem(VIEW_MODE_KEY);
      return v === 'discovery' ? 'discovery' : 'classic';
    } catch { return 'classic'; }
  });
  const [createStep, setCreateStep] = useState<CreateStep>('closed');
  const [selectedTemplate, setSelectedTemplate] = useState<WorkflowTemplate | null>(null);
  const [form, setForm] = useState<CreateWorkflowRequest>({ projectId: '', name: '' });
  const [executeDialog, setExecuteDialog] = useState<{
    open: boolean;
    workflow: Workflow | null;
    variables: string[];
  }>({ open: false, workflow: null, variables: [] });
  const executeDialogSchema = useMemo(
    () => readContextSchema(executeDialog.workflow),
    [executeDialog.workflow],
  );

  const [showImport, setShowImport] = useState(false);
  const [importData, setImportData] = useState<WorkflowExportData | null>(null);
  const [importProjectId, setImportProjectId] = useState('');
  const [importName, setImportName] = useState('');

  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [deleteConfirm, setDeleteConfirm] = useState<{
    open: boolean;
    ids: string[];
    names: string[];
  }>({ open: false, ids: [], names: [] });
  const [deleting, setDeleting] = useState(false);

  useEffect(() => { saveCollapsed(collapsedProjects); }, [collapsedProjects]);
  useEffect(() => { saveSelectedId(selectedWorkflowId); }, [selectedWorkflowId]);
  useEffect(() => {
    try { localStorage.setItem(SHOW_DETAILS_KEY, showDetails ? '1' : '0'); } catch { /* ignore */ }
  }, [showDetails]);
  useEffect(() => {
    try { localStorage.setItem(VIEW_MODE_KEY, viewMode); } catch { /* ignore */ }
  }, [viewMode]);

  const [sealFilter, setSealFilter] = useState<SealFilter>('all');
  const sealedCount = useMemo(() => (workflows ?? []).filter((w) => w.sealed).length, [workflows]);
  const filtered = useMemo(
    () => {
      const all = workflows ?? [];
      const base = sealFilter === 'all' ? all : all.filter((w) => !!w.sealed === (sealFilter === 'sealed'));
      if (!search) return base;
      const q = search.toLowerCase();
      return (
        base.filter(
          (w) =>
            w.name.toLowerCase().includes(q) ||
            w.projectName.toLowerCase().includes(q) ||
            (w.description?.toLowerCase().includes(q) ?? false),
        )
      );
    },
    [workflows, search, sealFilter],
  );

  const groupedByProject = useMemo(() => {
    const groups = new Map<string, { projectId: string; projectName: string; workflows: Workflow[] }>();
    for (const wf of filtered) {
      const key = wf.projectId;
      if (!groups.has(key)) {
        groups.set(key, { projectId: key, projectName: wf.projectName || t.common.project, workflows: [] });
      }
      groups.get(key)!.workflows.push(wf);
    }
    return Array.from(groups.values()).sort((a, b) =>
      a.projectName.localeCompare(b.projectName),
    );
  }, [filtered, t.common.project]);

  useEffect(() => {
    if (!groupedByProject.length) return;
    if (collapsedProjects.has('__all__')) {
      const allIds = new Set(groupedByProject.map((g) => g.projectId));
      setCollapsedProjects(allIds);
    }
  }, [groupedByProject.length]); // eslint-disable-line react-hooks/exhaustive-deps

  const selectedWorkflow = useMemo(() => {
    if (!filtered.length) return null;
    const found = selectedWorkflowId ? filtered.find((w) => w.id === selectedWorkflowId) : null;
    return found ?? filtered[0];
  }, [filtered, selectedWorkflowId]);

  const isProjectCollapsed = useCallback(
    (projectId: string) => collapsedProjects.has(projectId),
    [collapsedProjects],
  );

  const toggleProject = useCallback((projectId: string) => {
    setCollapsedProjects((prev) => {
      const next = new Set(prev);
      if (next.has(projectId)) next.delete(projectId);
      else next.add(projectId);
      return next;
    });
  }, []);

  const allCollapsed = groupedByProject.length > 0 && groupedByProject.every((g) => collapsedProjects.has(g.projectId));

  const toggleAll = useCallback(() => {
    setCollapsedProjects((prev) => {
      const allIds = groupedByProject.map((g) => g.projectId);
      const allAreCollapsed = allIds.every((id) => prev.has(id));
      return allAreCollapsed ? new Set() : new Set(allIds);
    });
  }, [groupedByProject]);

  const toggleSelectMode = useCallback(() => {
    setSelectMode((prev) => {
      if (prev) setSelectedIds(new Set());
      return !prev;
    });
  }, []);

  const toggleWorkflowSelect = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const toggleProjectSelect = useCallback((projectWorkflows: Workflow[]) => {
    setSelectedIds((prev) => {
      const ids = projectWorkflows.map((w) => w.id);
      const allSelected = ids.every((id) => prev.has(id));
      const next = new Set(prev);
      if (allSelected) {
        ids.forEach((id) => next.delete(id));
      } else {
        ids.forEach((id) => next.add(id));
      }
      return next;
    });
  }, []);

  const requestDeleteSingle = useCallback((wf: Workflow) => {
    setDeleteConfirm({ open: true, ids: [wf.id], names: [wf.name] });
  }, []);

  const requestDeleteSelected = useCallback(() => {
    if (selectedIds.size === 0) return;
    const names = filtered.filter((w) => selectedIds.has(w.id)).map((w) => w.name);
    setDeleteConfirm({ open: true, ids: [...selectedIds], names });
  }, [selectedIds, filtered]);

  const confirmDelete = useCallback(async () => {
    setDeleting(true);
    try {
      for (const id of deleteConfirm.ids) {
        await deleteWorkflow.mutateAsync(id);
      }
      setSelectedIds((prev) => {
        const next = new Set(prev);
        deleteConfirm.ids.forEach((id) => next.delete(id));
        return next;
      });
      if (deleteConfirm.ids.includes(selectedWorkflowId ?? '')) {
        setSelectedWorkflowId(null);
      }
    } finally {
      setDeleting(false);
      setDeleteConfirm({ open: false, ids: [], names: [] });
    }
  }, [deleteConfirm, deleteWorkflow, selectedWorkflowId]);

  const openCreate = () => {
    setCreateStep('template');
    setSelectedTemplate(null);
    setForm({ projectId: '', name: '' });
  };

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

  const handleClose = () => {
    setCreateStep('closed');
    setSelectedTemplate(null);
    setForm({ projectId: '', name: '' });
  };

  const handleExecute = (workflow: Workflow) => {
    const variables = extractWorkflowVariables(workflow.blocks);
    if (variables.length > 0) {
      setExecuteDialog({ open: true, workflow, variables });
    } else {
      executeWorkflow.mutate(
        { id: workflow.id },
        { onSuccess: (run) => navigate(`/workflows/${workflow.id}/run/${run.id}`) },
      );
    }
  };

  const handleExecuteWithContext = (context: Record<string, string>) => {
    if (!executeDialog.workflow) return;
    const workflowId = executeDialog.workflow.id;
    executeWorkflow.mutate(
      { id: workflowId, context },
      {
        onSuccess: (run) => {
          setExecuteDialog({ open: false, workflow: null, variables: [] });
          navigate(`/workflows/${workflowId}/run/${run.id}`);
        },
      },
    );
  };

  const handleImportReady = (data: WorkflowExportData) => {
    setImportData(data);
    setImportName(data.name);
    setImportProjectId('');
    setShowImport(true);
  };

  const handleImport = () => {
    if (!importData || !importName.trim()) return;
    importWorkflow.mutate(
      { ...importData, name: importName, projectId: importProjectId || '' },
      {
        onSuccess: () => {
          setShowImport(false);
          setImportData(null);
        },
      },
    );
  };

  const handleBulkToggleAutoTodo = (enable: boolean) => {
    for (const id of selectedIds) {
      updateWorkflow.mutate({
        id,
        data: { metadata: { autoTodo: { enabled: enable } } },
      });
    }
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
    <div className="flex flex-col h-full animate-fade-in">
      <WorkflowListHeader t={t} onOpenCreate={openCreate} onImportReady={handleImportReady} />
      <Hint id="workflows-start" className="mb-4" />

      {!search && projects?.length === 0 ? (
        <EmptyState
          icon={<GitBranch className="w-8 h-8 text-text-tertiary" />}
          title={t.projects.firstProject.title}
          description={t.projects.firstProject.workflowsHint}
          action={<FirstProjectActions />}
        />
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={<GitBranch className="w-8 h-8 text-text-tertiary" />}
          title={search ? t.common.noResults : t.workflows.title}
          description={search ? undefined : t.workflows.noWorkflows}
          action={
            !search && (
              <Button size="sm" onClick={openCreate}>
                {t.workflows.create}
              </Button>
            )
          }
        />
      ) : (
        <div className="flex gap-4 flex-1 min-h-0">
          <div className="w-full lg:w-96 lg:min-w-[24rem] flex flex-col min-h-0">
            <WorkflowListToolbar
              t={t}
              search={search}
              onSearchChange={setSearch}
              selectMode={selectMode}
              onToggleSelectMode={toggleSelectMode}
              allCollapsed={allCollapsed}
              onToggleAll={toggleAll}
              selectedIds={selectedIds}
              filtered={filtered}
              onRequestDeleteSelected={requestDeleteSelected}
              onBulkToggleAutoTodo={handleBulkToggleAutoTodo}
              showDetails={showDetails}
              onToggleShowDetails={() => setShowDetails((v) => !v)}
            />

            <div className="flex items-center gap-1 mb-2 text-xs" role="tablist">
              <button
                role="tab"
                aria-selected={viewMode === 'classic'}
                onClick={() => setViewMode('classic')}
                className={`px-2.5 py-1 rounded-md transition-colors ${
                  viewMode === 'classic'
                    ? 'bg-surface-raised text-text border border-border'
                    : 'text-text-tertiary hover:text-text'
                }`}
              >
                {t.workflows.viewModeClassic}
              </button>
              <button
                role="tab"
                aria-selected={viewMode === 'discovery'}
                onClick={() => setViewMode('discovery')}
                className={`px-2.5 py-1 rounded-md transition-colors ${
                  viewMode === 'discovery'
                    ? 'bg-surface-raised text-text border border-border'
                    : 'text-text-tertiary hover:text-text'
                }`}
              >
                {t.workflows.viewModeDiscovery}
              </button>
            </div>
            <div className="mb-2 text-xs">
              <SealFilterTabs t={t} value={sealFilter} onChange={setSealFilter}
                sealedCount={sealedCount} unsealedCount={(workflows?.length ?? 0) - sealedCount} />
            </div>

            <div className="flex-1 overflow-y-auto space-y-1 pr-1">
              {viewMode === 'discovery' && !selectMode ? (
                <DiscoveryView
                  t={t}
                  workflows={filtered}
                  search={search}
                  onExecute={handleExecute}
                  navigate={navigate}
                />
              ) : groupedByProject.map((group) => (
                <ProjectGroup
                  key={group.projectId}
                  projectId={group.projectId}
                  projectName={group.projectName}
                  workflows={group.workflows}
                  collapsed={isProjectCollapsed(group.projectId)}
                  onToggle={() => toggleProject(group.projectId)}
                  selectedId={selectedWorkflow?.id ?? null}
                  onSelect={(id) => setSelectedWorkflowId(id)}
                  onExecute={handleExecute}
                  onDelete={requestDeleteSingle}
                  navigate={navigate}
                  selectMode={selectMode}
                  selectedIds={selectedIds}
                  onToggleSelect={toggleWorkflowSelect}
                  onToggleProjectSelect={toggleProjectSelect}
                  showDetails={showDetails}
                />
              ))}
            </div>
          </div>

          {selectedWorkflow && !selectMode && (
            <div className="hidden lg:block flex-1 min-h-0 overflow-y-auto">
              <WorkflowDetailPanel
                workflow={selectedWorkflow}
                t={t}
                onExecute={() => handleExecute(selectedWorkflow)}
                onEdit={() => navigate(`/workflows/${selectedWorkflow.id}`)}
                onSaveAsTemplate={() => saveAsTemplate.mutate(selectedWorkflow.id)}
                saveAsTemplateLoading={saveAsTemplate.isPending}
                navigate={navigate}
              />
            </div>
          )}
        </div>
      )}

      <ConfirmModal
        open={deleteConfirm.open}
        // Closing is blocked while the deletes run (Escape, backdrop and cancel alike)
        onClose={() => !deleting && setDeleteConfirm({ open: false, ids: [], names: [] })}
        onConfirm={confirmDelete}
        title={deleteConfirm.ids.length === 1 ? t.workflows.deleteWorkflow : t.workflows.deleteWorkflows}
        confirmLabel={<>{t.common.delete} ({deleteConfirm.ids.length})</>}
        confirmIcon={<Trash2 className="w-4 h-4" />}
        variant="danger"
        isPending={deleting}
      >
        <div className="space-y-3">
          <p className="text-sm text-text-secondary">{t.workflows.deleteConfirm}</p>
          <div className="max-h-48 overflow-y-auto space-y-1">
            {deleteConfirm.names.map((name, i) => (
              <div key={i} className="flex items-center gap-2 px-2 py-1 rounded-sm bg-surface-raised text-sm text-text">
                <Trash2 className="w-3 h-3 text-danger shrink-0" />
                <span className="truncate">{name}</span>
              </div>
            ))}
          </div>
          <p className="text-xs text-danger">{t.common.deleteConfirm}</p>
        </div>
      </ConfirmModal>

      <ExecuteDialog
        open={executeDialog.open}
        onClose={() => setExecuteDialog({ open: false, workflow: null, variables: [] })}
        onExecute={handleExecuteWithContext}
        variables={executeDialog.variables}
        defaultValues={executeDialog.workflow?.defaultContext}
        optionalVariables={executeDialogSchema.optional}
        descriptions={executeDialogSchema.descriptions}
        placeholders={executeDialogSchema.placeholders}
        loading={executeWorkflow.isPending}
      />

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
        onClose={handleClose}
        onBackToTemplates={() => setCreateStep('template')}
      />

      <WorkflowImportModal
        t={t}
        open={showImport}
        importData={importData}
        importName={importName}
        importProjectId={importProjectId}
        projects={projects}
        importLoading={importWorkflow.isPending}
        onImportNameChange={setImportName}
        onImportProjectIdChange={setImportProjectId}
        onImport={handleImport}
        onClose={() => { setShowImport(false); setImportData(null); }}
      />
    </div>
  );
}
