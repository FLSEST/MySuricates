import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { GitBranch, Plus, Search, Play, LayoutGrid, FolderOpen } from 'lucide-react';
import { Card, Button, Input, Select, Modal, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { TemplateSelector } from '../components/workflow/TemplateSelector';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { useWorkflows, useCreateWorkflow, useExecuteWorkflow } from '../hooks/useWorkflows';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import type { CreateWorkflowRequest, Workflow } from '../types';
import type { WorkflowTemplate } from '../data/workflowTemplates';

type ViewMode = 'flat' | 'grouped';

type CreateStep = 'closed' | 'template' | 'details';

export function Workflows() {
  const t = useT();
  const navigate = useNavigate();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const { data: projects } = useProjects();
  const createWorkflow = useCreateWorkflow();
  const executeWorkflow = useExecuteWorkflow();

  const [search, setSearch] = useState('');
  const [projectFilter, setProjectFilter] = useState('');
  const [viewMode, setViewMode] = useState<ViewMode>('flat');
  const [createStep, setCreateStep] = useState<CreateStep>('closed');
  const [selectedTemplate, setSelectedTemplate] = useState<WorkflowTemplate | null>(null);
  const [form, setForm] = useState<CreateWorkflowRequest>({ projectId: '', name: '' });
  const [executeDialog, setExecuteDialog] = useState<{ open: boolean; workflow: Workflow | null; variables: string[] }>({
    open: false,
    workflow: null,
    variables: [],
  });

  const filtered = workflows?.filter((w) => {
    const matchesSearch = !search ||
      w.name.toLowerCase().includes(search.toLowerCase()) ||
      w.projectName.toLowerCase().includes(search.toLowerCase());
    const matchesProject = !projectFilter || w.projectId === projectFilter;
    return matchesSearch && matchesProject;
  }) ?? [];

  const groupedByProject = useMemo(() => {
    if (viewMode !== 'grouped') return [];
    const groups = new Map<string, { projectName: string; workflows: Workflow[] }>();
    for (const wf of filtered) {
      const key = wf.projectId;
      if (!groups.has(key)) {
        groups.set(key, { projectName: wf.projectName || t.common.project, workflows: [] });
      }
      groups.get(key)!.workflows.push(wf);
    }
    return Array.from(groups.values()).sort((a, b) => a.projectName.localeCompare(b.projectName));
  }, [filtered, viewMode, t.common.project]);

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

  const handleExecute = (e: React.MouseEvent, workflow: Workflow) => {
    e.stopPropagation();
    const variables = extractWorkflowVariables(workflow.blocks);
    if (variables.length > 0) {
      setExecuteDialog({ open: true, workflow, variables });
    } else {
      executeWorkflow.mutate(
        { id: workflow.id },
        { onSuccess: (run) => navigate(`/workflows/${workflow.id}/run/${run.id}`) }
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
      }
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
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.workflows.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{t.workflows.subtitle}</p>
        </div>
        <Button icon={<Plus className="w-4 h-4" />} onClick={openCreate}>
          {t.workflows.create}
        </Button>
      </div>

      {/* Search & Filters */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-64">
          <Input
            placeholder={t.common.search}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            icon={<Search className="w-4 h-4 text-text-tertiary" />}
          />
        </div>
        <div className="w-48">
          <Select
            value={projectFilter}
            onChange={(e) => setProjectFilter(e.target.value)}
            options={[
              { value: '', label: t.workflows.allProjects },
              ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
            ]}
          />
        </div>
        <div className="flex rounded-lg border border-border overflow-hidden">
          <button
            onClick={() => setViewMode('flat')}
            className={`p-2 transition-colors ${viewMode === 'flat' ? 'bg-accent/10 text-accent' : 'text-text-tertiary hover:text-text'}`}
            title={t.workflows.viewFlat}
          >
            <LayoutGrid className="w-4 h-4" />
          </button>
          <button
            onClick={() => setViewMode('grouped')}
            className={`p-2 transition-colors ${viewMode === 'grouped' ? 'bg-accent/10 text-accent' : 'text-text-tertiary hover:text-text'}`}
            title={t.workflows.viewByProject}
          >
            <FolderOpen className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Workflow list */}
      {filtered.length === 0 ? (
        <EmptyState
          icon={<GitBranch className="w-8 h-8 text-text-tertiary" />}
          title={search || projectFilter ? t.common.noResults : t.workflows.title}
          description={search || projectFilter ? undefined : t.workflows.noWorkflows}
          action={
            !search && !projectFilter && (
              <Button size="sm" onClick={openCreate}>
                {t.workflows.create}
              </Button>
            )
          }
        />
      ) : viewMode === 'grouped' ? (
        <div className="space-y-6 animate-stagger">
          {groupedByProject.map((group) => (
            <div key={group.projectName}>
              <div className="flex items-center gap-2 mb-3">
                <FolderOpen className="w-4 h-4 text-text-tertiary" />
                <h2 className="text-sm font-semibold text-text-secondary uppercase tracking-wide">
                  {group.projectName}
                </h2>
                <span className="text-xs text-text-tertiary">({group.workflows.length})</span>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
                {group.workflows.map((wf) => (
                  <WorkflowCard key={wf.id} wf={wf} t={t} navigate={navigate} onExecute={handleExecute} />
                ))}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 animate-stagger">
          {filtered.map((wf) => (
            <WorkflowCard key={wf.id} wf={wf} t={t} navigate={navigate} onExecute={handleExecute} />
          ))}
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
      <Modal
        open={createStep === 'template'}
        onClose={handleClose}
        title={t.workflows.create}
        size="lg"
      >
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

function WorkflowCard({ wf, t, navigate, onExecute }: {
  wf: Workflow;
  t: ReturnType<typeof useT>;
  navigate: ReturnType<typeof useNavigate>;
  onExecute: (e: React.MouseEvent, wf: Workflow) => void;
}) {
  return (
    <Card
      className="cursor-pointer hover:border-border-strong transition-colors"
      onClick={() => navigate(`/workflows/${wf.id}`)}
    >
      <div className="flex items-start justify-between mb-2">
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-xl">{wf.icon || '\uD83D\uDCCB'}</span>
          <div className="min-w-0">
            <h3 className="font-semibold text-text truncate">{wf.name}</h3>
            <p className="text-xs text-text-tertiary">{wf.projectName}</p>
          </div>
        </div>
        <button
          onClick={(e) => onExecute(e, wf)}
          className="p-2 rounded-lg text-accent hover:bg-accent/10 transition-colors shrink-0"
          title={t.workflows.execute}
          aria-label={t.workflows.execute}
        >
          <Play className="w-4 h-4" />
        </button>
      </div>
      {wf.description && (
        <p className="text-xs text-text-secondary line-clamp-2 mb-3">{wf.description}</p>
      )}
      <div className="flex items-center justify-between text-xs text-text-tertiary">
        <span>{wf.blocks.length} {t.workflows.blocks.toLowerCase()}</span>
        <span>{wf.runCount} {t.common.runs}</span>
      </div>
      {wf.activatedSkillNames.length > 0 && (
        <div className="flex flex-wrap gap-1 mt-2">
          {wf.activatedSkillNames.map((name) => (
            <span
              key={name}
              className="inline-flex items-center px-2 py-0.5 rounded text-[10px] font-medium bg-accent/10 text-accent"
            >
              {name}
            </span>
          ))}
        </div>
      )}
    </Card>
  );
}
