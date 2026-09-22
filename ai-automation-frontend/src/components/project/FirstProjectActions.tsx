import { useState } from 'react';
import { FolderPlus, Sparkles } from 'lucide-react';
import { Button } from '../ui';
import { useCreateExampleProject } from '../../hooks/useProjects';
import { useT } from '../../i18n';
import type { Project } from '../../types';
import { CreateProjectModal } from './CreateProjectModal';
import { addExampleProjectView } from '../dashboard/exampleProjectView';

/**
 * The two ways out of an installation without any project (TODO d12c7560): the bundled example
 * project with one click, or a project for a directory of one's own. The example project also brings
 * a dashboard view of its own (TODO e284eb3d).
 *
 * Without `onCreateOwn` the own project is created in a dialog of this component; a caller that
 * already has a project form (a dialog cannot open a second dialog on top of itself) passes it.
 */
export function FirstProjectActions({
  onProjectReady,
  onCreateOwn,
}: {
  onProjectReady?: (project: Project) => void;
  onCreateOwn?: () => void;
}) {
  const t = useT();
  const createExample = useCreateExampleProject();
  const [showCreate, setShowCreate] = useState(false);

  return (
    <>
      <div className="flex flex-wrap items-center justify-center gap-2">
        <Button
          size="sm"
          icon={<Sparkles className="w-4 h-4" />}
          loading={createExample.isPending}
          onClick={() => createExample.mutate(undefined, {
            onSuccess: (project) => {
              void addExampleProjectView(project);
              onProjectReady?.(project);
            },
          })}
        >
          {t.projects.firstProject.createExample}
        </Button>
        <Button
          size="sm"
          variant="secondary"
          icon={<FolderPlus className="w-4 h-4" />}
          onClick={() => (onCreateOwn ? onCreateOwn() : setShowCreate(true))}
        >
          {t.projects.firstProject.createOwn}
        </Button>
      </div>
      {!onCreateOwn && (
        <CreateProjectModal
          open={showCreate}
          onClose={() => setShowCreate(false)}
          onCreated={(project) => onProjectReady?.(project)}
        />
      )}
    </>
  );
}
