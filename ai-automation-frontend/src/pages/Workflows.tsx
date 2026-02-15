import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { GitBranch, Plus, Search, Play } from 'lucide-react';
import { Card, Button, Input, Select, Modal, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { useWorkflows, useCreateWorkflow, useExecuteWorkflow } from '../hooks/useWorkflows';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
import type { CreateWorkflowRequest } from '../types';

export function Workflows() {
  const t = useT();
  const navigate = useNavigate();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const { data: projects } = useProjects();
  const createWorkflow = useCreateWorkflow();
  const executeWorkflow = useExecuteWorkflow();

  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<CreateWorkflowRequest>({ projectId: '', name: '' });

  const filtered = workflows?.filter((w) =>
    w.name.toLowerCase().includes(search.toLowerCase()) ||
    w.projectName.toLowerCase().includes(search.toLowerCase())
  ) ?? [];

  const handleCreate = () => {
    createWorkflow.mutate(form, {
      onSuccess: (wf) => {
        setShowCreate(false);
        setForm({ projectId: '', name: '' });
        navigate(`/workflows/${wf.id}`);
      },
    });
  };

  const handleExecute = (e: React.MouseEvent, workflowId: string) => {
    e.stopPropagation();
    executeWorkflow.mutate(
      { id: workflowId },
      {
        onSuccess: (run) => navigate(`/workflows/${workflowId}/run/${run.id}`),
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
        <Button icon={<Plus className="w-4 h-4" />} onClick={() => setShowCreate(true)}>
          {t.workflows.create}
        </Button>
      </div>

      {/* Search */}
      <div className="max-w-sm">
        <Input
          placeholder={t.common.search}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          icon={<Search className="w-4 h-4 text-text-tertiary" />}
        />
      </div>

      {/* Workflow list */}
      {filtered.length === 0 ? (
        <EmptyState
          icon={<GitBranch className="w-8 h-8 text-text-tertiary" />}
          title={search ? t.common.noResults : t.workflows.title}
          description={search ? undefined : t.workflows.noWorkflows}
          action={
            !search && (
              <Button size="sm" onClick={() => setShowCreate(true)}>
                {t.workflows.create}
              </Button>
            )
          }
        />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 animate-stagger">
          {filtered.map((wf) => (
            <Card
              key={wf.id}
              className="cursor-pointer hover:border-border-strong transition-colors"
              onClick={() => navigate(`/workflows/${wf.id}`)}
            >
              <div className="flex items-start justify-between mb-2">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="text-xl">{wf.icon || '📋'}</span>
                  <div className="min-w-0">
                    <h3 className="font-semibold text-text truncate">{wf.name}</h3>
                    <p className="text-xs text-text-tertiary">{wf.projectName}</p>
                  </div>
                </div>
                <button
                  onClick={(e) => handleExecute(e, wf.id)}
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
          ))}
        </div>
      )}

      {/* Create modal */}
      <Modal
        open={showCreate}
        onClose={() => setShowCreate(false)}
        title={t.workflows.create}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowCreate(false)}>
              {t.common.cancel}
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
