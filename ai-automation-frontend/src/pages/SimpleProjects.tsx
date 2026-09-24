import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { FolderKanban, Plus, Square, Loader2 } from 'lucide-react';
import { useT } from '../i18n';
import { useProjects, useCreateProject, useStopContainer } from '../hooks/useProjects';
import { Spinner, Button, Modal } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { FirstProjectActions } from '../components/project/FirstProjectActions';
import { ProjectFormFields } from '../components/project/ProjectFormFields';
import { COMPACT_PROJECT_FORM, type ProjectFormValues } from '../components/project/projectForm';
import { SimplePage, SimplePageHeader, SimpleCard, StatusDot } from '../components/simple/kit';
import { ProBadge } from '../components/simple/ProHandoff';
import { AREA_COLORS } from '../utils/homeTiles';

const C = AREA_COLORS.projects;

export function SimpleProjects() {
  const t = useT();
  const navigate = useNavigate();
  const { data: projects, isLoading, error, refetch } = useProjects();
  const create = useCreateProject();
  const stopC = useStopContainer();

  // `?new=1` (the Simple home's "create a project") opens the create dialog right away
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedCreate = searchParams.get('new') === '1';
  const [createOpen, setCreateOpen] = useState(requestedCreate);

  useEffect(() => {
    if (!requestedCreate) return;
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('new');
      return next;
    }, { replace: true });
  }, [requestedCreate, setSearchParams]);
  const [newProject, setNewProject] = useState<ProjectFormValues>(COMPACT_PROJECT_FORM);
  const canCreate = newProject.name.trim() !== '' && newProject.path.trim() !== '';

  const submitCreate = () => {
    if (!canCreate) return;
    create.mutate(
      { ...newProject, name: newProject.name.trim(), path: newProject.path.trim() },
      { onSuccess: () => { setCreateOpen(false); setNewProject(COMPACT_PROJECT_FORM); } },
    );
  };

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <SimplePage>
      <SimplePageHeader
        title={t.simple.projects.title}
        subtitle={t.simple.projects.subtitle}
        right={<Button size="sm" icon={<Plus className="w-4 h-4" />} onClick={() => setCreateOpen(true)}>{t.common.create}</Button>}
      />

      {(projects?.length ?? 0) === 0 ? (
        <EmptyState
          icon={<FolderKanban className="w-8 h-8 text-text-tertiary" />}
          title={t.projects.firstProject.title}
          description={t.projects.firstProject.projectsHint}
          action={<FirstProjectActions onCreateOwn={() => setCreateOpen(true)} />}
        />
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
          {(projects ?? []).map((p) => {
            // Containers stay out of sight until one is already there: a newcomer never needs to
            // start one, but a running container can still be stopped from here.
            const running = p.status === 'RUNNING';
            const starting = p.status === 'STARTING';
            return (
              <SimpleCard key={p.id} color={C}>
                {/* Opens the Pro project page but stays in Simple mode — ProPageNotice offers the way back. */}
                <button onClick={() => navigate(`/projects/${p.id}`)} className="block text-left w-full">
                  <div className="flex items-start justify-between mb-3">
                    <div
                      className="w-10 h-10 rounded-xl flex items-center justify-center"
                      style={{ background: `color-mix(in srgb, ${C} 16%, transparent)`, color: C }}
                    >
                      <FolderKanban className="w-5 h-5" />
                    </div>
                    <div className="flex items-center gap-2">
                      <ProBadge />
                      <StatusDot status={p.status} />
                    </div>
                  </div>
                  <h3 className="text-[15px] font-semibold text-text truncate tracking-tight">{p.name}</h3>
                  <p className="text-xs text-text-secondary mt-0.5 truncate">
                    {t.projects.types[p.type] ?? p.type} · {p.workflowCount} {t.simple.projects.workflowsLabel}
                  </p>
                </button>
                {(running || starting) && (
                  <button
                    disabled={starting || stopC.isPending}
                    onClick={() => stopC.mutate(p.id)}
                    className="mt-3 flex items-center gap-1.5 text-xs font-medium text-text-secondary hover:text-text transition-colors disabled:opacity-50"
                  >
                    {starting
                      ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                      : <Square className="w-3.5 h-3.5" />}
                    {starting ? t.simple.projects.containerStarting : t.simple.projects.stopContainer}
                  </button>
                )}
              </SimpleCard>
            );
          })}
        </div>
      )}

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t.simple.projects.createTitle}
        footer={
          <>
            <Button variant="secondary" onClick={() => setCreateOpen(false)}>{t.common.cancel}</Button>
            <Button onClick={submitCreate} loading={create.isPending} disabled={!canCreate}>{t.common.create}</Button>
          </>
        }
      >
        <ProjectFormFields value={newProject} onChange={setNewProject} compact />
      </Modal>
    </SimplePage>
  );
}
