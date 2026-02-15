import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { FolderKanban, Plus, Search } from 'lucide-react';
import { Card, Button, Input, Select, Modal, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { useProjects, useCreateProject, useStartContainer, useStopContainer } from '../hooks/useProjects';
import { useT } from '../i18n';
import type { Project, ProjectType, CreateProjectRequest } from '../types';

const PROJECT_TYPES: ProjectType[] = ['CODE', 'RESEARCH', 'CONTENT', 'BUSINESS', 'PERSONAL'];

export function Projects() {
  const t = useT();
  const navigate = useNavigate();
  const { data: projects, isLoading, error, refetch } = useProjects();
  const createProject = useCreateProject();
  const startContainer = useStartContainer();
  const stopContainer = useStopContainer();

  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<CreateProjectRequest>({ name: '', type: 'CODE', path: '' });

  const filtered = projects?.filter((p) =>
    p.name.toLowerCase().includes(search.toLowerCase())
  ) ?? [];

  const handleCreate = () => {
    createProject.mutate(form, {
      onSuccess: () => {
        setShowCreate(false);
        setForm({ name: '', type: 'CODE', path: '' });
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

      {/* Search */}
      <div className="max-w-sm">
        <Input
          placeholder={t.common.search}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          icon={<Search className="w-4 h-4 text-text-tertiary" />}
        />
      </div>

      {/* Project cards */}
      {filtered.length === 0 ? (
        <EmptyState
          icon={<FolderKanban className="w-8 h-8 text-text-tertiary" />}
          title={search ? t.common.noResults : t.projects.title}
          description={search ? undefined : t.projects.noProjects}
          action={
            !search && (
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
              className="cursor-pointer hover:border-border-strong transition-colors"
              onClick={() => navigate(`/projects/${project.id}`)}
            >
              <div className="flex items-start justify-between mb-3">
                <div className="min-w-0">
                  <h3 className="font-semibold text-text truncate">{project.name}</h3>
                  <p className="text-xs text-text-tertiary mt-0.5 truncate font-mono">{project.path}</p>
                </div>
                <StatusBadge status={project.status} />
              </div>
              <div className="flex items-center justify-between text-xs text-text-secondary">
                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-surface text-text-secondary">
                  {t.projects.types[project.type]}
                </span>
                <div className="flex items-center gap-2">
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
          <Select
            label={t.projects.type}
            value={form.type}
            onChange={(e) => setForm({ ...form, type: e.target.value as ProjectType })}
            options={PROJECT_TYPES.map((type) => ({
              value: type,
              label: t.projects.types[type],
            }))}
          />
          <Input
            label={t.projects.path}
            value={form.path}
            onChange={(e) => setForm({ ...form, path: e.target.value })}
            placeholder="/home/user/projects/my-project"
          />
        </div>
      </Modal>
    </div>
  );
}
