import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { FolderKanban, Plus, Play, Square, Loader2 } from 'lucide-react';
import { useT } from '../i18n';
import { useProjects, useCreateProject, useStartContainer, useStopContainer } from '../hooks/useProjects';
import { Spinner, Button, Modal, Select, Input } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { SimplePage, SimplePageHeader, SimpleCard, StatusDot } from '../components/simple/kit';
import { AREA_COLORS } from '../utils/homeTiles';
import type { ProjectType } from '../types';

const C = AREA_COLORS.projects;
const TYPES: ProjectType[] = ['CODE', 'RESEARCH', 'CONTENT', 'BUSINESS', 'PERSONAL'];

export function SimpleProjects() {
  const t = useT();
  const navigate = useNavigate();
  const { data: projects, isLoading, error, refetch } = useProjects();
  const create = useCreateProject();
  const startC = useStartContainer();
  const stopC = useStopContainer();

  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState('');
  const [type, setType] = useState<ProjectType>('CODE');
  const [path, setPath] = useState('');

  const submitCreate = () => {
    if (!name.trim() || !path.trim()) return;
    create.mutate(
      { name: name.trim(), type, path: path.trim() },
      { onSuccess: () => { setCreateOpen(false); setName(''); setPath(''); setType('CODE'); } },
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
          title={t.simple.projects.empty}
          description={t.simple.projects.emptyHint}
        />
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
          {(projects ?? []).map((p) => {
            const running = p.status === 'RUNNING';
            const busy = p.status === 'STARTING' || startC.isPending || stopC.isPending;
            return (
              <SimpleCard key={p.id} color={C}>
                <button onClick={() => navigate(`/projects/${p.id}`)} className="block text-left w-full">
                  <div className="flex items-start justify-between mb-3">
                    <div
                      className="w-10 h-10 rounded-xl flex items-center justify-center"
                      style={{ background: `color-mix(in srgb, ${C} 16%, transparent)`, color: C }}
                    >
                      <FolderKanban className="w-5 h-5" />
                    </div>
                    <StatusDot status={p.status} />
                  </div>
                  <h3 className="text-[15px] font-semibold text-text truncate tracking-tight">{p.name}</h3>
                  <p className="text-xs text-text-secondary mt-0.5 truncate">
                    {p.type} · {p.workflowCount} {t.simple.projects.workflowsLabel}
                  </p>
                </button>
                <button
                  disabled={busy}
                  onClick={() => (running ? stopC.mutate(p.id) : startC.mutate(p.id))}
                  className="mt-3 flex items-center gap-1.5 text-xs font-medium text-text-secondary hover:text-text transition-colors disabled:opacity-50"
                >
                  {p.status === 'STARTING'
                    ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    : running ? <Square className="w-3.5 h-3.5" /> : <Play className="w-3.5 h-3.5" />}
                  {running ? t.simple.projects.stop : t.simple.projects.start}
                </button>
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
            <Button onClick={submitCreate} loading={create.isPending} disabled={!name.trim() || !path.trim()}>{t.common.create}</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Input label={t.common.name} value={name} onChange={(e) => setName(e.target.value)} />
          <Select
            label={t.simple.projects.type}
            value={type}
            onChange={(e) => setType(e.target.value as ProjectType)}
            options={TYPES.map((ty) => ({ value: ty, label: ty }))}
          />
          <Input label={t.simple.projects.path} value={path} onChange={(e) => setPath(e.target.value)} placeholder="/home/…" />
        </div>
      </Modal>
    </SimplePage>
  );
}
