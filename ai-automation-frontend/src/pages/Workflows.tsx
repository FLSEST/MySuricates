import { useMemo, useState, useCallback, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  GitBranch,
  Plus,
  Search,
  Play,
  FolderOpen,
  ChevronRight,
  ChevronDown,
  Pencil,
  Clock,
  ChevronsUpDown,
  Trash2,
  CheckSquare,
  Square,
  X,
  Upload,
  Download,
  Save,
  Bell,
  BellOff,
} from 'lucide-react';
import { Button, Input, Modal, Spinner, Select } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { ExecutionBadges } from '../components/shared/ExecutionBadges';
import { TemplateSelector } from '../components/workflow/TemplateSelector';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { WorkflowIcon } from '../utils/workflowIcon';
import {
  useWorkflows,
  useCreateWorkflow,
  useUpdateWorkflow,
  useExecuteWorkflow,
  useDeleteWorkflow,
  useWorkflowRuns,
  useImportWorkflow,
  exportWorkflow,
} from '../hooks/useWorkflows';
import { useSettingsStore } from '../stores/settingsStore';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import { formatDuration, formatDateTime } from '../utils/formatTime';
import type { CreateWorkflowRequest, Workflow, WorkflowExportData, WorkflowTemplate } from '../types';
import { useI18nStore } from '../i18n';
import { resolveTemplateName } from '../utils/templateUtils';
import { useSaveWorkflowAsTemplate } from '../hooks/useTemplates';
import { useToast } from '../hooks/useToast';

function pluralize(count: number, singular: string, plural: string) {
  return count === 1 ? singular : plural;
}

// ── Persistence helpers ──────────────────────────────────────

const STORAGE_KEY = 'workflows-collapsed-projects';
const SELECTED_KEY = 'workflows-selected-id';

function loadCollapsed(): Set<string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return new Set(JSON.parse(raw));
  } catch { /* ignore */ }
  return new Set(['__all__']); // default: all collapsed
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

// ExecutionBadges imported from ../components/shared/ExecutionBadges

// ── Main Component ──────────────────────────────────────────

type CreateStep = 'closed' | 'template' | 'details';

export function Workflows() {
  const t = useT();
  const navigate = useNavigate();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const { data: projects } = useProjects();
  const createWorkflow = useCreateWorkflow();
  const updateWorkflow = useUpdateWorkflow();
  const executeWorkflow = useExecuteWorkflow();
  const deleteWorkflow = useDeleteWorkflow();
  const importWorkflow = useImportWorkflow();
  const toast = useToast();
  const locale = useI18nStore((s) => s.locale);
  const saveAsTemplate = useSaveWorkflowAsTemplate();

  const [search, setSearch] = useState('');
  const [selectedWorkflowId, setSelectedWorkflowId] = useState<string | null>(loadSelectedId);
  const [collapsedProjects, setCollapsedProjects] = useState<Set<string>>(loadCollapsed);
  const [createStep, setCreateStep] = useState<CreateStep>('closed');
  const [selectedTemplate, setSelectedTemplate] = useState<WorkflowTemplate | null>(null);
  const [form, setForm] = useState<CreateWorkflowRequest>({ projectId: '', name: '' });
  const [executeDialog, setExecuteDialog] = useState<{
    open: boolean;
    workflow: Workflow | null;
    variables: string[];
  }>({
    open: false,
    workflow: null,
    variables: [],
  });

  // Import state
  const [showImport, setShowImport] = useState(false);
  const [importData, setImportData] = useState<WorkflowExportData | null>(null);
  const [importProjectId, setImportProjectId] = useState('');
  const [importName, setImportName] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Multi-select & delete state
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [deleteConfirm, setDeleteConfirm] = useState<{
    open: boolean;
    ids: string[];
    names: string[];
  }>({ open: false, ids: [], names: [] });
  const [deleting, setDeleting] = useState(false);

  // Persist collapsed state
  useEffect(() => { saveCollapsed(collapsedProjects); }, [collapsedProjects]);
  useEffect(() => { saveSelectedId(selectedWorkflowId); }, [selectedWorkflowId]);

  const filtered = useMemo(
    () =>
      workflows?.filter(
        (w) =>
          !search ||
          w.name.toLowerCase().includes(search.toLowerCase()) ||
          w.projectName.toLowerCase().includes(search.toLowerCase()),
      ) ?? [],
    [workflows, search],
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

  // Resolve __all__ sentinel: on first render with data, expand nothing (all collapsed)
  useEffect(() => {
    if (!groupedByProject.length) return;
    if (collapsedProjects.has('__all__')) {
      const allIds = new Set(groupedByProject.map((g) => g.projectId));
      setCollapsedProjects(allIds);
    }
  }, [groupedByProject.length]); // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-select: restore persisted or pick first visible
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

  // ── Select mode helpers ──

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
      // Clear selection for deleted items
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

  // ── Create / Execute handlers ──

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

  const handleCreate = () => {
    const autoTodoDefault = useSettingsStore.getState().autoTodoDefault;
    const payload = autoTodoDefault
      ? { ...form, metadata: { ...form.metadata, autoTodo: { enabled: true } } }
      : form;
    createWorkflow.mutate(payload, {
      onSuccess: (wf) => {
        setCreateStep('closed');
        setForm({ projectId: '', name: '' });
        setSelectedTemplate(null);
        navigate(`/workflows/${wf.id}`);
      },
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
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.workflows.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{t.workflows.subtitle}</p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            icon={<Upload className="w-4 h-4" />}
            onClick={() => fileInputRef.current?.click()}
          >
            {t.common.import}
          </Button>
          <Button icon={<Plus className="w-4 h-4" />} onClick={openCreate}>
            {t.workflows.create}
          </Button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".json"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (!file) return;
              const reader = new FileReader();
              reader.onload = (ev) => {
                try {
                  const data = JSON.parse(ev.target?.result as string) as WorkflowExportData;
                  if (!data.name || !Array.isArray(data.blocks)) {
                    toast.error(t.workflows.invalidFile);
                    return;
                  }
                  setImportData(data);
                  setImportName(data.name);
                  setImportProjectId('');
                  setShowImport(true);
                } catch {
                  toast.error(t.workflows.invalidFile);
                }
              };
              reader.readAsText(file);
              e.target.value = '';
            }}
          />
        </div>
      </div>

      {filtered.length === 0 ? (
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
          {/* Left: Workflow list */}
          <div className="w-full lg:w-80 lg:min-w-[20rem] flex flex-col min-h-0">
            <div className="flex items-center gap-2 mb-3">
              <div className="flex-1">
                <Input
                  placeholder={t.common.search}
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  icon={<Search className="w-4 h-4 text-text-tertiary" />}
                />
              </div>
              <button
                onClick={toggleSelectMode}
                className={`p-2 rounded-lg transition-colors shrink-0 ${
                  selectMode
                    ? 'bg-accent/10 text-accent'
                    : 'hover:bg-surface-raised text-text-tertiary hover:text-text'
                }`}
                title={selectMode ? t.workflows.exitSelectMode : t.workflows.selectMode}
              >
                {selectMode ? <X className="w-4 h-4" /> : <CheckSquare className="w-4 h-4" />}
              </button>
              <button
                onClick={toggleAll}
                className="p-2 rounded-lg hover:bg-surface-raised text-text-tertiary hover:text-text transition-colors shrink-0"
                title={allCollapsed ? t.workflows.expandAll : t.workflows.collapseAll}
              >
                <ChevronsUpDown className="w-4 h-4" />
              </button>
            </div>

            {/* Bulk action bar */}
            {selectMode && selectedIds.size > 0 && (
              <div className="flex items-center gap-2 mb-2 px-2 py-1.5 rounded-lg bg-accent/5 border border-accent/20">
                <span className="text-xs text-accent font-medium flex-1">
                  {selectedIds.size} {t.workflows.selected}
                </span>
                {(() => {
                  const selectedWorkflows = filtered.filter((w) => selectedIds.has(w.id));
                  const allEnabled = selectedWorkflows.every(
                    (w) => !!(w.metadata?.autoTodo as Record<string, unknown> | undefined)?.enabled,
                  );
                  return (
                    <Button
                      variant="secondary"
                      size="sm"
                      icon={allEnabled ? <BellOff className="w-3.5 h-3.5" /> : <Bell className="w-3.5 h-3.5" />}
                      onClick={() => {
                        const newEnabled = !allEnabled;
                        for (const id of selectedIds) {
                          updateWorkflow.mutate({
                            id,
                            data: { metadata: { autoTodo: { enabled: newEnabled } } },
                          });
                        }
                      }}
                    >
                      {allEnabled ? t.workflows.disableAutoTodo : t.workflows.enableAutoTodo}
                    </Button>
                  );
                })()}
                <Button
                  variant="danger"
                  size="sm"
                  icon={<Trash2 className="w-3.5 h-3.5" />}
                  onClick={requestDeleteSelected}
                >
                  {t.common.delete}
                </Button>
              </div>
            )}

            <div className="flex-1 overflow-y-auto space-y-1 pr-1">
              {groupedByProject.map((group) => (
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
                />
              ))}
            </div>
          </div>

          {/* Right: Detail panel (desktop only) */}
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

      {/* Delete confirmation modal */}
      <Modal
        open={deleteConfirm.open}
        onClose={() => !deleting && setDeleteConfirm({ open: false, ids: [], names: [] })}
        title={deleteConfirm.ids.length === 1 ? t.workflows.deleteWorkflow : t.workflows.deleteWorkflows}
        size="sm"
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => setDeleteConfirm({ open: false, ids: [], names: [] })}
              disabled={deleting}
            >
              {t.common.cancel}
            </Button>
            <Button
              variant="danger"
              icon={<Trash2 className="w-4 h-4" />}
              onClick={confirmDelete}
              loading={deleting}
            >
              {t.common.delete} ({deleteConfirm.ids.length})
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <p className="text-sm text-text-secondary">{t.workflows.deleteConfirm}</p>
          <div className="max-h-48 overflow-y-auto space-y-1">
            {deleteConfirm.names.map((name, i) => (
              <div key={i} className="flex items-center gap-2 px-2 py-1 rounded bg-surface-raised text-sm text-text">
                <Trash2 className="w-3 h-3 text-danger shrink-0" />
                <span className="truncate">{name}</span>
              </div>
            ))}
          </div>
          <p className="text-xs text-danger">{t.common.deleteConfirm}</p>
        </div>
      </Modal>

      {/* Execute with variables dialog */}
      <ExecuteDialog
        open={executeDialog.open}
        onClose={() => setExecuteDialog({ open: false, workflow: null, variables: [] })}
        onExecute={handleExecuteWithContext}
        variables={executeDialog.variables}
        defaultValues={executeDialog.workflow?.defaultContext}
        loading={executeWorkflow.isPending}
      />

      {/* Step 1: Template selection modal */}
      <Modal open={createStep === 'template'} onClose={handleClose} title={t.workflows.create} size="lg">
        <TemplateSelector onSelect={handleTemplateSelect} />
      </Modal>

      {/* Step 2: Details modal */}
      <Modal
        open={createStep === 'details'}
        onClose={handleClose}
        title={t.workflows.create}
        footer={
          <>
            <Button variant="secondary" onClick={() => setCreateStep('template')}>
              {t.common.back}
            </Button>
            <Button
              onClick={handleCreate}
              loading={createWorkflow.isPending}
              disabled={!form.name || !form.projectId}
            >
              {t.common.create}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {selectedTemplate && (
            <div className="flex items-center gap-2 p-3 rounded-lg bg-accent/5 border border-accent/20">
              <span className="text-xl">{selectedTemplate.icon}</span>
              <div>
                <p className="text-sm font-medium text-text">
                  {resolveTemplateName(selectedTemplate.name, locale)}
                </p>
                <p className="text-xs text-text-secondary">
                  {selectedTemplate.blocks.length} {t.workflows.blocks.toLowerCase()}
                </p>
              </div>
            </div>
          )}
          <Select
            label={t.common.project}
            value={form.projectId}
            onChange={(e) => setForm({ ...form, projectId: e.target.value })}
            options={[
              { value: '', label: t.common.selectProject },
              ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
            ]}
          />
          <Input
            label={t.common.name}
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            placeholder="Review Pull Request"
          />
          <Input
            label={t.common.description}
            value={form.description ?? ''}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
            placeholder="Optional description"
          />
        </div>
      </Modal>

      {/* Import workflow modal */}
      <Modal
        open={showImport}
        onClose={() => { setShowImport(false); setImportData(null); }}
        title={t.workflows.importWorkflow}
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => { setShowImport(false); setImportData(null); }}>
              {t.common.cancel}
            </Button>
            <Button
              onClick={() => {
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
              }}
              loading={importWorkflow.isPending}
              disabled={!importName.trim()}
            >
              {t.common.import}
            </Button>
          </>
        }
      >
        {importData && (
          <div className="space-y-4">
            <div className="flex items-center gap-2 p-3 rounded-lg bg-accent/5 border border-accent/20">
              <span className="text-xl">{importData.icon || '📋'}</span>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-text">{importData.name}</p>
                <p className="text-xs text-text-secondary flex items-center gap-2">
                  <span>{importData.blocks?.length ?? 0} {t.workflows.blocks.toLowerCase()}</span>
                  <ExecutionBadges blocks={importData.blocks ?? []} />
                </p>
              </div>
            </div>
            <div>
              <Select
                label={t.workflows.importSelectProject}
                value={importProjectId}
                onChange={(e) => setImportProjectId(e.target.value)}
                options={[
                  { value: '', label: t.workflows.importNoProject },
                  ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
                ]}
              />
              {!importProjectId && (
                <p className="text-xs text-text-tertiary mt-1">{t.workflows.importCollectionHint}</p>
              )}
            </div>
            <Input
              label={t.common.name}
              value={importName}
              onChange={(e) => setImportName(e.target.value)}
            />
          </div>
        )}
      </Modal>
    </div>
  );
}

// ── Sub-Components ──────────────────────────────────────────

function ProjectGroup({
  projectName,
  workflows,
  collapsed,
  onToggle,
  selectedId,
  onSelect,
  onExecute,
  onDelete,
  navigate,
  selectMode,
  selectedIds,
  onToggleSelect,
  onToggleProjectSelect,
}: {
  projectId: string;
  projectName: string;
  workflows: Workflow[];
  collapsed: boolean;
  onToggle: () => void;
  selectedId: string | null;
  onSelect: (id: string) => void;
  onExecute: (w: Workflow) => void;
  onDelete: (w: Workflow) => void;
  navigate: ReturnType<typeof useNavigate>;
  selectMode: boolean;
  selectedIds: Set<string>;
  onToggleSelect: (id: string) => void;
  onToggleProjectSelect: (workflows: Workflow[]) => void;
}) {
  const allSelected = workflows.every((w) => selectedIds.has(w.id));
  const someSelected = !allSelected && workflows.some((w) => selectedIds.has(w.id));

  return (
    <div>
      <div className="flex items-center gap-1">
        {selectMode && !collapsed && (
          <button
            onClick={(e) => { e.stopPropagation(); onToggleProjectSelect(workflows); }}
            className="p-0.5 shrink-0 text-text-tertiary hover:text-accent transition-colors"
            title={allSelected ? 'Alle abwählen' : 'Alle auswählen'}
          >
            {allSelected ? (
              <CheckSquare className="w-3.5 h-3.5 text-accent" />
            ) : someSelected ? (
              <CheckSquare className="w-3.5 h-3.5 text-accent/50" />
            ) : (
              <Square className="w-3.5 h-3.5" />
            )}
          </button>
        )}
        <button
          onClick={onToggle}
          className="flex items-center gap-1.5 flex-1 px-2 py-1.5 text-left rounded-md hover:bg-surface-raised transition-colors group"
        >
          {collapsed ? (
            <ChevronRight className="w-3.5 h-3.5 text-text-tertiary" />
          ) : (
            <ChevronDown className="w-3.5 h-3.5 text-text-tertiary" />
          )}
          <FolderOpen className="w-3.5 h-3.5 text-text-tertiary" />
          <span className="text-sm font-medium text-text-secondary truncate">{projectName}</span>
          {workflows[0]?.projectSubtitle && (
            <span className="text-xs text-text-tertiary truncate">{workflows[0].projectSubtitle}</span>
          )}
          <span className="text-xs text-text-tertiary ml-auto shrink-0">({workflows.length})</span>
        </button>
      </div>
      {!collapsed && (
        <div className="ml-3 mt-0.5 space-y-0.5">
          {workflows.map((wf) => (
            <WorkflowRow
              key={wf.id}
              workflow={wf}
              selected={wf.id === selectedId}
              onSelect={() => onSelect(wf.id)}
              onExecute={() => onExecute(wf)}
              onDelete={() => onDelete(wf)}
              navigate={navigate}
              selectMode={selectMode}
              isChecked={selectedIds.has(wf.id)}
              onToggleCheck={() => onToggleSelect(wf.id)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function WorkflowRow({
  workflow,
  selected,
  onSelect,
  onExecute,
  onDelete,
  navigate,
  selectMode,
  isChecked,
  onToggleCheck,
}: {
  workflow: Workflow;
  selected: boolean;
  onSelect: () => void;
  onExecute: () => void;
  onDelete: () => void;
  navigate: ReturnType<typeof useNavigate>;
  selectMode: boolean;
  isChecked: boolean;
  onToggleCheck: () => void;
}) {
  const handleClick = () => {
    if (selectMode) {
      onToggleCheck();
      return;
    }
    if (window.innerWidth < 1024) {
      navigate(`/workflows/${workflow.id}`);
    } else {
      onSelect();
    }
  };

  return (
    <div
      className={`group flex items-center gap-2 w-full px-2 py-1.5 rounded-md text-left transition-colors cursor-pointer ${
        selectMode && isChecked
          ? 'bg-accent/10 text-accent border border-accent/20'
          : selected && !selectMode
            ? 'bg-accent/10 text-accent border border-accent/20'
            : 'hover:bg-surface-raised text-text border border-transparent'
      }`}
      onClick={handleClick}
    >
      {selectMode && (
        <span className="shrink-0">
          {isChecked ? (
            <CheckSquare className="w-4 h-4 text-accent" />
          ) : (
            <Square className="w-4 h-4 text-text-tertiary" />
          )}
        </span>
      )}
      <span className="text-base shrink-0">
        <WorkflowIcon icon={workflow.icon} className="w-4 h-4" />
      </span>
      <span className="text-sm truncate flex-1">{workflow.name}</span>
      {!selectMode && !!(workflow.metadata?.autoTodo as Record<string, unknown> | undefined)?.enabled && (
        <Bell className="w-3 h-3 text-accent/50 shrink-0" />
      )}
      {!selectMode && (
        <>
          <ExecutionBadges blocks={workflow.blocks} />
          {workflow.runCount > 0 && (
            <span className="text-[10px] tabular-nums text-text-tertiary bg-surface-raised px-1.5 py-0.5 rounded-full shrink-0">
              {workflow.runCount}
            </span>
          )}
          <button
            onClick={(e) => { e.stopPropagation(); onExecute(); }}
            className="sm:opacity-0 sm:group-hover:opacity-100 p-0.5 rounded hover:bg-accent/20 text-accent transition-all shrink-0"
            title="Ausführen"
          >
            <Play className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={(e) => { e.stopPropagation(); onDelete(); }}
            className="sm:opacity-0 sm:group-hover:opacity-100 p-0.5 rounded hover:bg-danger/20 text-text-tertiary hover:text-danger transition-all shrink-0"
            title="Löschen"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </>
      )}
    </div>
  );
}

function WorkflowDetailPanel({
  workflow,
  t,
  onExecute,
  onEdit,
  onSaveAsTemplate,
  saveAsTemplateLoading,
  navigate,
}: {
  workflow: Workflow;
  t: ReturnType<typeof useT>;
  onExecute: () => void;
  onEdit: () => void;
  onSaveAsTemplate: () => void;
  saveAsTemplateLoading: boolean;
  navigate: ReturnType<typeof useNavigate>;
}) {
  const { data: runsPage, isLoading: runsLoading } = useWorkflowRuns(workflow.id, 0, 5);
  const recentRuns = runsPage?.content ?? [];
  const updateWorkflow = useUpdateWorkflow();
  const autoTodoEnabled = !!(workflow.metadata?.autoTodo as Record<string, unknown> | undefined)?.enabled;

  return (
    <div className="bg-surface border border-border rounded-xl p-6 space-y-6">
      {/* Header */}
      <div className="flex items-start gap-3">
        <span className="text-2xl shrink-0">
          <WorkflowIcon icon={workflow.icon} className="w-7 h-7" />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-xl font-bold text-text truncate">{workflow.name}</h2>
          <p className="text-sm text-text-tertiary">
            {workflow.projectName}
            {workflow.projectSubtitle && (
              <span className="text-text-tertiary/60"> · {workflow.projectSubtitle}</span>
            )}
          </p>
        </div>
      </div>

      {/* Project Description */}
      {workflow.projectDescription && (
        <p className="text-xs text-text-tertiary italic">{workflow.projectDescription}</p>
      )}

      {/* Description */}
      {workflow.description && (
        <p className="text-sm text-text-secondary">{workflow.description}</p>
      )}

      {/* Stats */}
      <div className="flex flex-wrap gap-2">
        <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-surface-raised border border-border text-sm">
          <span className="text-text-tertiary font-medium">
            {workflow.blocks.length}{' '}
            {pluralize(workflow.blocks.length, t.workflows.block, t.workflows.blocks)}
          </span>
        </div>
        <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-surface-raised border border-border text-sm">
          <span className="text-text-tertiary font-medium">
            {workflow.runCount}{' '}
            {pluralize(workflow.runCount, t.common.run, t.common.runs)}
          </span>
        </div>
        <ExecutionBadges blocks={workflow.blocks} />
        {workflow.model && (
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-surface-raised border border-border text-sm">
            <span className="text-text-tertiary font-medium">{workflow.model}</span>
          </div>
        )}
      </div>

      {/* Auto-Todo Toggle */}
      <div className="flex items-center justify-between px-3 py-2 rounded-lg bg-surface-raised border border-border">
        <div>
          <span className="text-sm font-medium text-text">{t.workflows.autoTodo}</span>
          <p className="text-xs text-text-tertiary">{t.workflows.autoTodoDescription}</p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={autoTodoEnabled}
          onClick={() =>
            updateWorkflow.mutate({
              id: workflow.id,
              data: { metadata: { autoTodo: { enabled: !autoTodoEnabled } } },
            })
          }
          className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors ${
            autoTodoEnabled ? 'bg-accent' : 'bg-border'
          }`}
        >
          <span
            className={`pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow transform transition-transform ${
              autoTodoEnabled ? 'translate-x-4' : 'translate-x-0'
            }`}
          />
        </button>
      </div>

      {/* Recent Runs */}
      <div>
        <h3 className="text-sm font-semibold text-text mb-2 flex items-center gap-1.5">
          <Clock className="w-4 h-4 text-text-tertiary" />
          {t.dashboard.recentRuns}
        </h3>
        {runsLoading ? (
          <div className="flex justify-center py-4">
            <Spinner />
          </div>
        ) : recentRuns.length === 0 ? (
          <p className="text-xs text-text-tertiary py-2">{t.workflows.noRuns}</p>
        ) : (
          <div className="space-y-1.5">
            {recentRuns.map((run) => (
              <button
                key={run.id}
                onClick={() => navigate(`/workflows/${workflow.id}/run/${run.id}`)}
                className="flex items-center gap-3 w-full px-3 py-2 rounded-lg hover:bg-surface-raised transition-colors text-sm text-left"
              >
                <StatusBadge status={run.status} />
                <span className="text-text-tertiary text-xs tabular-nums">
                  {formatDateTime(run.startedAt)}
                </span>
                {run.durationMs != null && (
                  <span className="text-text-tertiary/60 text-xs tabular-nums">
                    {formatDuration(run.durationMs)}
                  </span>
                )}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Skills */}
      {workflow.activatedSkillNames.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-text mb-2">{t.workflows.activatedSkills}</h3>
          <div className="flex flex-wrap gap-1.5">
            {workflow.activatedSkillNames.map((name) => (
              <span
                key={name}
                className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-accent/10 text-accent"
              >
                {name}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Actions */}
      <div className="flex gap-2 pt-2 border-t border-border">
        <Button icon={<Play className="w-4 h-4" />} onClick={onExecute}>
          {t.workflows.execute}
        </Button>
        <Button variant="secondary" icon={<Pencil className="w-4 h-4" />} onClick={onEdit}>
          {t.common.edit}
        </Button>
        <Button
          variant="ghost"
          size="sm"
          icon={<Download className="w-4 h-4" />}
          onClick={() => {
            const safeName = workflow.name.replace(/[^a-zA-Z0-9_-]/g, '_');
            exportWorkflow(workflow.id, `${safeName}.json`);
          }}
          aria-label={t.workflows.exportWorkflow}
        />
        <Button
          variant="ghost"
          size="sm"
          icon={<Save className="w-4 h-4" />}
          onClick={onSaveAsTemplate}
          loading={saveAsTemplateLoading}
          aria-label={t.templates.saveAsTemplate}
        />
      </div>
    </div>
  );
}
