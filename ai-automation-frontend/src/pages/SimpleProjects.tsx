import { useNavigate } from 'react-router-dom';
import { FolderKanban } from 'lucide-react';
import { useT } from '../i18n';
import { useProjects } from '../hooks/useProjects';
import { Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { SimplePage, SimplePageHeader, SimpleCard, StatusDot } from '../components/simple/kit';
import { AREA_COLORS } from '../utils/homeTiles';

const C = AREA_COLORS.projects;

export function SimpleProjects() {
  const t = useT();
  const navigate = useNavigate();
  const { data: projects, isLoading, error, refetch } = useProjects();

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <SimplePage>
      <SimplePageHeader title={t.simple.projects.title} subtitle={t.simple.projects.subtitle} />

      {(projects?.length ?? 0) === 0 ? (
        <EmptyState
          icon={<FolderKanban className="w-8 h-8 text-text-tertiary" />}
          title={t.simple.projects.empty}
          description={t.simple.projects.emptyHint}
        />
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
          {(projects ?? []).map((p) => (
            <SimpleCard key={p.id} color={C} onClick={() => navigate(`/projects/${p.id}`)}>
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
            </SimpleCard>
          ))}
        </div>
      )}
    </SimplePage>
  );
}
