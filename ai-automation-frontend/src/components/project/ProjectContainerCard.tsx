import { Play, Square, Container as ContainerIcon } from 'lucide-react';
import { Card, CardHeader, Button } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { useStartContainer, useStopContainer } from '../../hooks/useProjects';
import { useT } from '../../i18n';
import type { Project } from '../../types';

/** Docker container of the project: image, status and start (stopped or failed) or stop (running). */
export function ProjectContainerCard({ project }: { project: Project }) {
  const t = useT();
  const startContainer = useStartContainer();
  const stopContainer = useStopContainer();

  return (
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
              {project.container?.imageName ?? t.projects.noContainer}
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
  );
}
