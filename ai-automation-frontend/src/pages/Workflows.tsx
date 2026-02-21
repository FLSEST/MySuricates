import { useMemo, useState } from 'react';
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
} from 'lucide-react';
import { Button, Input, Modal, Spinner, Select } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { TemplateSelector } from '../components/workflow/TemplateSelector';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { WorkflowIcon } from '../utils/workflowIcon';
import {
  useWorkflows,
  useCreateWorkflow,
  useExecuteWorkflow,
  useWorkflowRuns,
} from '../hooks/useWorkflows';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import { formatDuration, formatDateTime } from '../utils/formatTime';
import type { CreateWorkflowRequest, Workflow } from '../types';
import type { WorkflowTemplate } from '../data/workflowTemplates';

function pluralize(count: number, singular: string, plural: string) {
  return count === 1 ? singular : plural;
}

type CreateStep = 'closed' | 'template' | 'details';

export function Workflows() {
  const t = useT();
  const navigate = useNavigate();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const { data: projects } = useProjects();
  const createWorkflow = useCreateWorkflow();
  const executeWorkflow = useExecuteWorkflow();

  const [search, setSearch] = useState('');
  const [selectedWorkflowId, setSelectedWorkflowId] = useState<string | null>(null);
  const [collapsedProjects, setCollapsedProjects] = useState<Set<string>>(new Set());
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
    const groups = new Map<string, { projectName: string; workflows: Workflow[] }>();
    for (const wf of filtered) {
      const key = wf.projectId;
      if (!groups.has(key)) {
        groups.set(key, { projectName: wf.projectName || t.common.project, workflows: [] });
      }
      groups.get(key)!.workflows.push(wf);
    }
    return Array.from(groups.values()).sort((a, b) =>
      a.projectName.localeCompare(b.projectName),
    );
  }, [filtered, t.common.project]);

  // Auto-select first workflow if nothing selected
  const selectedWorkflow = useMemo(() => {
    if (!filtered.length) return null;
    const found = selectedWorkflowId ? filtered.find((w) => w.id === selectedWorkflowId) : null;
    return found ?? filtered[0];
  }, [filtered, selectedWorkflowId]);

  const toggleProject = (projectName: string) => {
    setCollapsedProjects((prev) => {
      const next = new Set(prev);
      if (next.has(projectName)) next.delete(projectName);
      else next.add(projectName);
      return next;
    });
  };

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
        name: t.templates[template.nameKey as keyof typeof t.templates] as string,
        description: t.templates[template.descriptionKey as keyof typeof t.templates] as string,
        icon: template.icon,
        blocks: template.blocks,
        instructions: template.instructions,
        model: template.model,
      });
    } else {
      setForm({ projectId: '', name: '' });
    }
    setCreateStep('details');
  };

  const handleCreate = () => {
    createWorkflow.mutate(form, {
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
        <Button icon={<Plus className="w-4 h-4" />} onClick={openCreate}>
          {t.workflows.create}
        </Button>
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
            <div className="mb-3">
              <Input
                placeholder={t.common.search}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                icon={<Search className="w-4 h-4 text-text-tertiary" />}
              />
            </div>
            <div className="flex-1 overflow-y-auto space-y-1 pr-1">
              {groupedByProject.map((group) => (
                <ProjectGroup
                  key={group.projectName}
                  projectName={group.projectName}
                  workflows={group.workflows}
                  collapsed={collapsedProjects.has(group.projectName)}
                  onToggle={() => toggleProject(group.projectName)}
                  selectedId={selectedWorkflow?.id ?? null}
                  onSelect={(id) => setSelectedWorkflowId(id)}
                  navigate={navigate}
                />
              ))}
            </div>
          </div>

          {/* Right: Detail panel (desktop only) */}
          {selectedWorkflow && (
            <div className="hidden lg:block flex-1 min-h-0 overflow-y-auto">
              <WorkflowDetailPanel
                workflow={selectedWorkflow}
                t={t}
                onExecute={() => handleExecute(selectedWorkflow)}
                onEdit={() => navigate(`/workflows/${selectedWorkflow.id}`)}
                navigate={navigate}
              />
            </div>
          )}
        </div>
      )}

      {/* Execute with variables dialog */}
      <ExecuteDialog
        open={executeDialog.open}
        onClose={() => setExecuteDialog({ open: false, workflow: null, variables: [] })}
        onExecute={handleExecuteWithContext}
        variables={executeDialog.variables}
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
                  {t.templates[selectedTemplate.nameKey as keyof typeof t.templates]}
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
  navigate,
}: {
  projectName: string;
  workflows: Workflow[];
  collapsed: boolean;
  onToggle: () => void;
  selectedId: string | null;
  onSelect: (id: string) => void;
  navigate: ReturnType<typeof useNavigate>;
}) {
  return (
    <div>
      <button
        onClick={onToggle}
        className="flex items-center gap-1.5 w-full px-2 py-1.5 text-left rounded-md hover:bg-surface-raised transition-colors group"
      >
        {collapsed ? (
          <ChevronRight className="w-3.5 h-3.5 text-text-tertiary" />
        ) : (
          <ChevronDown className="w-3.5 h-3.5 text-text-tertiary" />
        )}
        <FolderOpen className="w-3.5 h-3.5 text-text-tertiary" />
        <span className="text-sm font-medium text-text-secondary truncate">{projectName}</span>
        <span className="text-xs text-text-tertiary ml-auto">({workflows.length})</span>
      </button>
      {!collapsed && (
        <div className="ml-3 mt-0.5 space-y-0.5">
          {workflows.map((wf) => (
            <WorkflowRow
              key={wf.id}
              workflow={wf}
              selected={wf.id === selectedId}
              onSelect={() => onSelect(wf.id)}
              navigate={navigate}
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
  navigate,
}: {
  workflow: Workflow;
  selected: boolean;
  onSelect: () => void;
  navigate: ReturnType<typeof useNavigate>;
}) {
  const handleClick = () => {
    // On mobile (<lg) navigate directly; on desktop select
    if (window.innerWidth < 1024) {
      navigate(`/workflows/${workflow.id}`);
    } else {
      onSelect();
    }
  };

  return (
    <button
      onClick={handleClick}
      className={`flex items-center gap-2 w-full px-2 py-1.5 rounded-md text-left transition-colors ${
        selected
          ? 'bg-accent/10 text-accent border border-accent/20'
          : 'hover:bg-surface-raised text-text border border-transparent'
      }`}
    >
      <span className="text-base shrink-0">
        <WorkflowIcon icon={workflow.icon} className="w-4 h-4" />
      </span>
      <span className="text-sm truncate flex-1">{workflow.name}</span>
      {workflow.runCount > 0 && (
        <span className="text-[10px] tabular-nums text-text-tertiary bg-surface-raised px-1.5 py-0.5 rounded-full shrink-0">
          {workflow.runCount}
        </span>
      )}
    </button>
  );
}

function WorkflowDetailPanel({
  workflow,
  t,
  onExecute,
  onEdit,
  navigate,
}: {
  workflow: Workflow;
  t: ReturnType<typeof useT>;
  onExecute: () => void;
  onEdit: () => void;
  navigate: ReturnType<typeof useNavigate>;
}) {
  const { data: runs, isLoading: runsLoading } = useWorkflowRuns(workflow.id);
  const recentRuns = runs?.slice(0, 5) ?? [];

  return (
    <div className="bg-surface border border-border rounded-xl p-6 space-y-6">
      {/* Header */}
      <div className="flex items-start gap-3">
        <span className="text-2xl shrink-0">
          <WorkflowIcon icon={workflow.icon} className="w-7 h-7" />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-xl font-bold text-text truncate">{workflow.name}</h2>
          <p className="text-sm text-text-tertiary">{workflow.projectName}</p>
        </div>
      </div>

      {/* Description */}
      {workflow.description && (
        <p className="text-sm text-text-secondary">{workflow.description}</p>
      )}

      {/* Stats */}
      <div className="flex gap-3">
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
        {workflow.model && (
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-surface-raised border border-border text-sm">
            <span className="text-text-tertiary font-medium">{workflow.model}</span>
          </div>
        )}
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
      </div>
    </div>
  );
}
