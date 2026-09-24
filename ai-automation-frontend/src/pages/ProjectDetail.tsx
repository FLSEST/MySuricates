import { Hint } from '../components/hints/Hint';
import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Archive, ArrowLeft, Pencil, GitBranch } from 'lucide-react';
import { Card, CardHeader, Button, Modal, ModalFooter, ConfirmModal, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import { useProject, useUpdateProject, useArchiveProject } from '../hooks/useProjects';
import { OnboardingCard } from '../components/project/OnboardingCard';
import { ProjectFormFields } from '../components/project/ProjectFormFields';
import { ProjectAiContextCard } from '../components/project/ProjectAiContextCard';
import { ProjectContainerCard } from '../components/project/ProjectContainerCard';
import { EMPTY_PROJECT_FORM, type ProjectFormValues } from '../components/project/projectForm';
import { useWorkflows } from '../hooks/useWorkflows';
import { useModuleEnabled } from '../hooks/useModules';
import { useI18nStore, useT } from '../i18n';
import { formatDate } from '../utils/localeFormat';

export function ProjectDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  const { data: project, isLoading, error, refetch } = useProject(id!);
  const { data: workflows } = useWorkflows();
  const onboardingModule = useModuleEnabled('onboarding-profiles');
  const updateProject = useUpdateProject();
  const archiveProject = useArchiveProject();

  const [showEdit, setShowEdit] = useState(false);
  const [showArchive, setShowArchive] = useState(false);
  const [editForm, setEditForm] = useState<ProjectFormValues>(EMPTY_PROJECT_FORM);

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

  const handleUpdate = () => {
    updateProject.mutate(
      { id: project.id, data: editForm },
      { onSuccess: () => setShowEdit(false) }
    );
  };

  const handleArchive = () => {
    archiveProject.mutate(project.id, {
      onSuccess: () => navigate('/projects'),
    });
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
          {/* No restore here: GET /projects/{id} answers 404 for archived projects, so this page
              never shows one — they are restored from the archive filter of the project list. */}
          <Button variant="ghost" size="sm" icon={<Pencil className="w-4 h-4" />} onClick={openEdit}>
            {t.common.edit}
          </Button>
          <Button variant="danger" size="sm" icon={<Archive className="w-4 h-4" />} onClick={() => setShowArchive(true)}>
            {t.projects.archive}
          </Button>
        </div>
      </div>

      <Hint id="project-context" />

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
              <span className="text-text">{formatDate(project.createdAt, locale)}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-text-secondary">{t.common.updated}</span>
              <span className="text-text">{formatDate(project.updatedAt, locale)}</span>
            </div>
          </div>
        </Card>

        <ProjectContainerCard project={project} />
      </div>

      <ProjectAiContextCard project={project} />

      {/* MySuricates Integration */}
      {onboardingModule && <OnboardingCard projectId={project.id} />}

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
        <ProjectFormFields value={editForm} onChange={setEditForm} />
      </Modal>

      {/* Archive Confirmation */}
      <ConfirmModal
        open={showArchive}
        onClose={() => setShowArchive(false)}
        onConfirm={handleArchive}
        title={`${t.projects.archive} "${project.name}"?`}
        message={t.projects.archiveConfirm}
        confirmLabel={t.projects.archive}
        variant="danger"
        isPending={archiveProject.isPending}
      />
    </div>
  );
}
