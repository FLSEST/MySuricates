import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Archive, ArchiveRestore, ArrowLeft, Play, Square, Pencil, GitBranch, Container as ContainerIcon,
  Brain, Sparkles,
} from 'lucide-react';
import { ModalFooter } from '../components/ui';
import { Card, CardHeader, Button, Input, Select, Modal, Spinner, DirectoryPicker } from '../components/ui';
import { MonacoEditor } from '../components/shared/MonacoEditor';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import {
  useProject, useUpdateProject, useArchiveProject, useRestoreProject,
  useStartContainer, useStopContainer, useGenerateProjectContext,
} from '../hooks/useProjects';
import { OnboardingCard } from '../components/project/OnboardingCard';
import { useWorkflows } from '../hooks/useWorkflows';
import { useT } from '../i18n';
import type { ProjectType } from '../types';

const PROJECT_TYPES: ProjectType[] = ['CODE', 'RESEARCH', 'CONTENT', 'BUSINESS', 'PERSONAL'];

export function ProjectDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const t = useT();

  const { data: project, isLoading, error, refetch } = useProject(id!);
  const { data: workflows } = useWorkflows();
  const updateProject = useUpdateProject();
  const archiveProject = useArchiveProject();
  const restoreProject = useRestoreProject();
  const startContainer = useStartContainer();
  const stopContainer = useStopContainer();
  const generateContext = useGenerateProjectContext();

  const [showEdit, setShowEdit] = useState(false);
  const [showArchive, setShowArchive] = useState(false);
  const [showAiContext, setShowAiContext] = useState(false);
  const [editForm, setEditForm] = useState({ name: '', subtitle: '', description: '', type: 'CODE' as ProjectType, path: '' });
  const [editAiContext, setEditAiContext] = useState('');

  const projectWorkflows = workflows?.filter((w) => w.projectId === id) ?? [];

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error || !project) {
    return <ErrorState message={error?.message} onRetry={() => refetch()} />;
  }

  const openEdit = () => {
    setEditForm({ name: project.name, subtitle: project.subtitle ?? '', description: project.description ?? '', type: project.type, path: project.path });
    setShowEdit(true);
  };

  const openAiContext = () => {
    setEditAiContext(project.aiContext ?? '');
    setShowAiContext(true);
  };

  const handleUpdate = () => {
    updateProject.mutate(
      { id: project.id, data: editForm },
      { onSuccess: () => setShowEdit(false) }
    );
  };

  const handleSaveAiContext = () => {
    updateProject.mutate(
      { id: project.id, data: { aiContext: editAiContext } },
      { onSuccess: () => setShowAiContext(false) }
    );
  };

  const handleGenerate = () => {
    generateContext.mutate(project.id, {
      onSuccess: (data) => {
        setEditAiContext(data.context);
      },
    });
  };

  const handleArchive = () => {
    archiveProject.mutate(project.id, {
      onSuccess: () => navigate('/projects'),
    });
  };

  const handleRestore = () => {
    restoreProject.mutate(project.id);
  };

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Back + Title */}
      <div className="flex items-center gap-4">
        <button
          onClick={() => navigate('/projects')}
          className="p-2 rounded-lg hover:bg-surface-raised text-text-secondary hover:text-text transition-colors"
          aria-label={t.common.back}
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-text truncate">{project.name}</h1>
            <StatusBadge status={project.status} />
          </div>
          <p className="text-sm text-text-tertiary font-mono mt-0.5">{project.path}</p>
        </div>
        <div className="flex items-center gap-2">
          {project.archivedAt ? (
            <Button
              variant="primary"
              size="sm"
              icon={<ArchiveRestore className="w-4 h-4" />}
              onClick={handleRestore}
              loading={restoreProject.isPending}
            >
              {t.projects.restore}
            </Button>
          ) : (
            <>
              <Button variant="ghost" size="sm" icon={<Pencil className="w-4 h-4" />} onClick={openEdit}>
                {t.common.edit}
              </Button>
              <Button variant="danger" size="sm" icon={<Archive className="w-4 h-4" />} onClick={() => setShowArchive(true)}>
                {t.projects.archive}
              </Button>
            </>
          )}
        </div>
      </div>

      {/* Info cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Project info */}
        <Card>
          <CardHeader title={t.projects.info} />
          <div className="space-y-3 text-sm">
            <div className="flex justify-between">
              <span className="text-text-secondary">{t.projects.type}</span>
              <span className="text-text font-medium">{t.projects.types[project.type]}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-text-secondary">{t.common.created}</span>
              <span className="text-text">{new Date(project.createdAt).toLocaleDateString()}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-text-secondary">{t.common.updated}</span>
              <span className="text-text">{new Date(project.updatedAt).toLocaleDateString()}</span>
            </div>
          </div>
        </Card>

        {/* Container status */}
        <Card>
          <CardHeader
            title={t.projects.status}
            subtitle={project.container?.dockerContainerId?.slice(0, 12) ?? '-'}
          />
          <div className="space-y-4">
            <div className="flex items-center gap-3">
              <div className="p-3 rounded-xl bg-surface">
                <ContainerIcon className="w-5 h-5 text-text-secondary" />
              </div>
              <div>
                <p className="text-sm font-medium text-text">
                  {project.container?.imageName ?? 'No container'}
                </p>
                <StatusBadge status={project.container?.status ?? 'STOPPED'} />
              </div>
            </div>
            <div className="flex gap-2">
              {project.status === 'STOPPED' || project.status === 'ERROR' ? (
                <Button
                  size="sm"
                  icon={<Play className="w-4 h-4" />}
                  onClick={() => startContainer.mutate(project.id)}
                  loading={startContainer.isPending}
                >
                  {t.projects.startContainer}
                </Button>
              ) : project.status === 'RUNNING' ? (
                <Button
                  variant="danger"
                  size="sm"
                  icon={<Square className="w-4 h-4" />}
                  onClick={() => stopContainer.mutate(project.id)}
                  loading={stopContainer.isPending}
                >
                  {t.projects.stopContainer}
                </Button>
              ) : null}
            </div>
          </div>
        </Card>
      </div>

      {/* AI Context */}
      <Card>
        <CardHeader
          title={t.projects.aiContext}
          action={
            <Button
              size="sm"
              variant="secondary"
              icon={<Brain className="w-4 h-4" />}
              onClick={openAiContext}
            >
              {t.projects.editAiContext}
            </Button>
          }
        />
        {project.aiContext ? (
          <pre className="text-sm text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded-lg p-3 max-h-48 overflow-y-auto">
            {project.aiContext}
          </pre>
        ) : (
          <p className="text-sm text-text-tertiary">{t.projects.noAiContext}</p>
        )}
      </Card>

      {/* MySuricates Integration */}
      <OnboardingCard projectId={project.id} />

      {/* Workflows in project */}
      <Card padding={false}>
        <div className="p-5 pb-0">
          <CardHeader
            title={`${t.nav.workflows} (${projectWorkflows.length})`}
            action={
              <Button
                size="sm"
                variant="secondary"
                icon={<GitBranch className="w-4 h-4" />}
                onClick={() => navigate('/workflows')}
              >
                {t.workflows.create}
              </Button>
            }
          />
        </div>
        {projectWorkflows.length === 0 ? (
          <div className="px-5 py-12 text-center text-sm text-text-tertiary">
            {t.workflows.noWorkflows}
          </div>
        ) : (
          <div className="divide-y divide-border">
            {projectWorkflows.map((wf) => (
              <div
                key={wf.id}
                className="flex items-center justify-between px-5 py-3 hover:bg-surface transition-colors cursor-pointer"
                onClick={() => navigate(`/workflows/${wf.id}`)}
              >
                <div className="flex items-center gap-3 min-w-0">
                  <span className="text-lg">{wf.icon || '📋'}</span>
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-text truncate">{wf.name}</p>
                    <p className="text-xs text-text-tertiary">{wf.blocks.length} {t.workflows.blocks.toLowerCase()}</p>
                  </div>
                </div>
                <span className="text-xs text-text-tertiary">{wf.runCount} {t.common.runs}</span>
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* Edit Modal */}
      <Modal
        open={showEdit}
        onClose={() => setShowEdit(false)}
        title={`${t.common.edit} ${project.name}`}
        footer={
          <ModalFooter
            onCancel={() => setShowEdit(false)}
            onAction={handleUpdate}
            cancelLabel={t.common.cancel}
            actionLabel={t.common.save}
            actionLoading={updateProject.isPending}
          />
        }
      >
        <div className="space-y-4">
          <Input
            label={t.projects.name}
            value={editForm.name}
            onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
          />
          <Input
            label={t.projects.subtitleField}
            value={editForm.subtitle}
            onChange={(e) => setEditForm({ ...editForm, subtitle: e.target.value })}
            placeholder={t.projects.subtitlePlaceholder}
          />
          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">{t.projects.descriptionField}</label>
            <textarea
              className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text placeholder:text-text-tertiary focus:outline-hidden focus:ring-2 focus:ring-accent/50 focus:border-accent transition-colors resize-none"
              rows={3}
              value={editForm.description}
              onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
              placeholder={t.projects.descriptionPlaceholder}
            />
          </div>
          <Select
            label={t.projects.type}
            value={editForm.type}
            onChange={(e) => setEditForm({ ...editForm, type: e.target.value as ProjectType })}
            options={PROJECT_TYPES.map((type) => ({
              value: type,
              label: t.projects.types[type],
            }))}
          />
          <DirectoryPicker
            label={t.projects.path}
            value={editForm.path}
            onChange={(path) => setEditForm({ ...editForm, path })}
          />
        </div>
      </Modal>

      {/* AI Context Modal */}
      <Modal
        open={showAiContext}
        onClose={() => setShowAiContext(false)}
        title={t.projects.editAiContext}
        size="lg"
        footer={
          <ModalFooter
            onCancel={() => setShowAiContext(false)}
            onAction={handleSaveAiContext}
            cancelLabel={t.common.cancel}
            actionLabel={t.common.save}
            actionLoading={updateProject.isPending}
          />
        }
      >
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <p className="text-sm text-text-secondary">{t.projects.aiContextHint}</p>
            <Button
              size="sm"
              variant="secondary"
              icon={<Sparkles className="w-3.5 h-3.5" />}
              onClick={handleGenerate}
              loading={generateContext.isPending}
            >
              {generateContext.isPending ? t.projects.generating : t.projects.generateAiContext}
            </Button>
          </div>
          <MonacoEditor
            value={editAiContext}
            onChange={setEditAiContext}
            language="markdown"
            height="400px"
          />
        </div>
      </Modal>

      {/* Archive Confirmation */}
      <Modal
        open={showArchive}
        onClose={() => setShowArchive(false)}
        title={`${t.projects.archive} "${project.name}"?`}
        size="sm"
        footer={
          <ModalFooter
            onCancel={() => setShowArchive(false)}
            onAction={handleArchive}
            cancelLabel={t.common.cancel}
            actionLabel={t.projects.archive}
            actionVariant="danger"
            actionLoading={archiveProject.isPending}
          />
        }
      >
        <p className="text-sm text-text-secondary">
          {t.projects.archiveConfirm}
        </p>
      </Modal>
    </div>
  );
}
