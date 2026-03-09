import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Archive, ArchiveRestore, FolderKanban, Pencil, Plus, Search } from 'lucide-react';
import { Card, Button, Input, Select, Modal, Spinner, DirectoryPicker } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { useProjects, useCreateProject, useUpdateProject, useStartContainer, useStopContainer, useArchivedProjects, useRestoreProject } from '../hooks/useProjects';
import { useT } from '../i18n';
import type { Project, ProjectType, CreateProjectRequest, UpdateProjectRequest } from '../types';

const PROJECT_TYPES: ProjectType[] = ['CODE', 'RESEARCH', 'CONTENT', 'BUSINESS', 'PERSONAL'];

export function Projects() {
  const t = useT();
  const navigate = useNavigate();
  const { data: projects, isLoading, error, refetch } = useProjects();
  const createProject = useCreateProject();
  const updateProject = useUpdateProject();
  const startContainer = useStartContainer();
  const stopContainer = useStopContainer();
  const { data: archivedProjects, refetch: fetchArchived } = useArchivedProjects();
  const restoreProject = useRestoreProject();

  const [search, setSearch] = useState('');
  const [showArchived, setShowArchived] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<CreateProjectRequest>({ name: '', type: 'CODE', path: '' });
  const [editProject, setEditProject] = useState<Project | null>(null);
  const [editForm, setEditForm] = useState<UpdateProjectRequest & { name: string; type: ProjectType; path: string }>({ name: '', type: 'CODE', path: '' });

  const activeFiltered = projects?.filter((p) =>
    p.name.toLowerCase().includes(search.toLowerCase())
  ) ?? [];
  const archivedFiltered = archivedProjects?.filter((p) =>
    p.name.toLowerCase().includes(search.toLowerCase())
  ) ?? [];
  const filtered = showArchived ? archivedFiltered : activeFiltered;

  const toggleArchived = () => {
    const next = !showArchived;
    setShowArchived(next);
    if (next) fetchArchived();
  };

  const openEdit = (project: Project) => {
    setEditForm({
      name: project.name,
      subtitle: project.subtitle ?? '',
      description: project.description ?? '',
      type: project.type,
      path: project.path,
    });
    setEditProject(project);
  };

  const handleUpdate = () => {
    if (!editProject) return;
    updateProject.mutate(
      { id: editProject.id, data: editForm },
      { onSuccess: () => setEditProject(null) }
    );
  };

  const handleCreate = () => {
    createProject.mutate(form, {
      onSuccess: () => {
        setShowCreate(false);
        setForm({ name: '', subtitle: '', description: '', type: 'CODE', path: '' });
      },
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
    <div className="space-y-6 animate-fade-in">
      {/* Page header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.projects.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{t.projects.subtitle}</p>
        </div>
        <Button icon={<Plus className="w-4 h-4" />} onClick={() => setShowCreate(true)}>
          {t.projects.create}
        </Button>
      </div>

      {/* Search + Archive toggle */}
      <div className="flex items-center gap-3">
        <div className="max-w-sm flex-1">
          <Input
            placeholder={t.common.search}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            icon={<Search className="w-4 h-4 text-text-tertiary" />}
          />
        </div>
        <Button
          variant={showArchived ? 'primary' : 'secondary'}
          size="sm"
          icon={showArchived ? <ArchiveRestore className="w-4 h-4" /> : <Archive className="w-4 h-4" />}
          onClick={toggleArchived}
        >
          {showArchived ? t.projects.showActive : t.projects.showArchived}
        </Button>
      </div>

      {/* Project cards */}
      {filtered.length === 0 ? (
        <EmptyState
          icon={showArchived ? <Archive className="w-8 h-8 text-text-tertiary" /> : <FolderKanban className="w-8 h-8 text-text-tertiary" />}
          title={search ? t.common.noResults : showArchived ? t.projects.archived : t.projects.title}
          description={search ? undefined : showArchived ? t.projects.noArchivedProjects : t.projects.noProjects}
          action={
            !search && !showArchived && (
              <Button size="sm" onClick={() => setShowCreate(true)}>
                {t.projects.create}
              </Button>
            )
          }
        />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 animate-stagger">
          {filtered.map((project: Project) => (
            <Card
              key={project.id}
              className={`group/card cursor-pointer hover:border-border-strong transition-colors ${showArchived ? 'opacity-75' : ''}`}
              onClick={() => !showArchived && navigate(`/projects/${project.id}`)}
            >
              <div className="flex items-start justify-between mb-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <h3 className="font-semibold text-text truncate">{project.name}</h3>
                    {project.subtitle && (
                      <span className="text-xs text-text-tertiary shrink-0">{project.subtitle}</span>
                    )}
                    {showArchived && (
                      <span className="text-xs px-1.5 py-0.5 rounded bg-warning/10 text-warning font-medium shrink-0">
                        {t.projects.archived}
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-text-tertiary mt-0.5 truncate font-mono">{project.path}</p>
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                  {!showArchived && (
                    <button
                      onClick={(e) => { e.stopPropagation(); openEdit(project); }}
                      className="sm:opacity-0 sm:group-hover/card:opacity-100 p-1 rounded-md hover:bg-surface-raised text-text-tertiary hover:text-text transition-all"
                      title={t.common.edit}
                    >
                      <Pencil className="w-3.5 h-3.5" />
                    </button>
                  )}
                  {!showArchived && <StatusBadge status={project.status} />}
                </div>
              </div>
              <div className="flex items-center justify-between text-xs text-text-secondary">
                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-sm bg-surface text-text-secondary">
                  {t.projects.types[project.type]}
                </span>
                <div className="flex items-center gap-2">
                  {showArchived ? (
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        restoreProject.mutate(project.id);
                      }}
                      className="text-accent hover:text-accent-hover transition-colors font-medium inline-flex items-center gap-1"
                    >
                      <ArchiveRestore className="w-3.5 h-3.5" />
                      {t.projects.restore}
                    </button>
                  ) : (
                    <>
                      <span>{project.workflowCount} {t.nav.workflows.toLowerCase()}</span>
                      {project.status === 'STOPPED' ? (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            startContainer.mutate(project.id);
                          }}
                          className="text-accent hover:text-accent-hover transition-colors font-medium"
                        >
                          {t.projects.startContainer}
                        </button>
                      ) : project.status === 'RUNNING' ? (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            stopContainer.mutate(project.id);
                          }}
                          className="text-danger hover:text-red-400 transition-colors font-medium"
                        >
                          {t.projects.stopContainer}
                        </button>
                      ) : null}
                    </>
                  )}
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {/* Create modal */}
      <Modal
        open={showCreate}
        onClose={() => setShowCreate(false)}
        title={t.projects.create}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowCreate(false)}>
              {t.common.cancel}
            </Button>
            <Button
              onClick={handleCreate}
              loading={createProject.isPending}
              disabled={!form.name || !form.path}
            >
              {t.common.create}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Input
            label={t.projects.name}
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            placeholder="My Project"
          />
          <Input
            label={t.projects.subtitleField}
            value={form.subtitle ?? ''}
            onChange={(e) => setForm({ ...form, subtitle: e.target.value })}
            placeholder={t.projects.subtitlePlaceholder}
          />
          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">{t.projects.descriptionField}</label>
            <textarea
              className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text placeholder:text-text-tertiary focus:outline-hidden focus:ring-2 focus:ring-accent/50 focus:border-accent transition-colors resize-none"
              rows={3}
              value={form.description ?? ''}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder={t.projects.descriptionPlaceholder}
            />
          </div>
          <Select
            label={t.projects.type}
            value={form.type}
            onChange={(e) => setForm({ ...form, type: e.target.value as ProjectType })}
            options={PROJECT_TYPES.map((type) => ({
              value: type,
              label: t.projects.types[type],
            }))}
          />
          <DirectoryPicker
            label={t.projects.path}
            value={form.path}
            onChange={(path) => setForm({ ...form, path })}
            placeholder="/home/user/projects/my-project"
          />
        </div>
      </Modal>

      {/* Edit modal */}
      <Modal
        open={!!editProject}
        onClose={() => setEditProject(null)}
        title={`${t.common.edit} ${editProject?.name ?? ''}`}
        footer={
          <>
            <Button variant="secondary" onClick={() => setEditProject(null)}>
              {t.common.cancel}
            </Button>
            <Button onClick={handleUpdate} loading={updateProject.isPending}>
              {t.common.save}
            </Button>
          </>
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
            value={editForm.subtitle ?? ''}
            onChange={(e) => setEditForm({ ...editForm, subtitle: e.target.value })}
            placeholder={t.projects.subtitlePlaceholder}
          />
          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">{t.projects.descriptionField}</label>
            <textarea
              className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text placeholder:text-text-tertiary focus:outline-hidden focus:ring-2 focus:ring-accent/50 focus:border-accent transition-colors resize-none"
              rows={3}
              value={editForm.description ?? ''}
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
    </div>
  );
}
