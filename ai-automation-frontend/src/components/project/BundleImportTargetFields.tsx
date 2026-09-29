import { Button, Input, Select, DirectoryPicker } from '../ui';
import { useT } from '../../i18n';
import type { Project, ProjectBundleConflictBehavior, ProjectBundleImportTarget } from '../../types';

interface BundleImportTargetFieldsProps {
  target: ProjectBundleImportTarget;
  onTargetChange: (target: ProjectBundleImportTarget) => void;
  onConflict: ProjectBundleConflictBehavior;
  onConflictChange: (value: ProjectBundleConflictBehavior) => void;
  projects: Project[];
  /** Suggested name for a new project — the bundle's own project name. */
  defaultName: string;
  /** Whether `defaultName` already names an active project (preview answer). */
  nameTaken: boolean;
}

/** New project (name + folder) or an existing one (target picker + conflict behavior). */
export function BundleImportTargetFields({
  target, onTargetChange, onConflict, onConflictChange, projects, defaultName, nameTaken,
}: BundleImportTargetFieldsProps) {
  const t = useT();

  return (
    <div className="space-y-3">
      <p className="text-sm font-medium text-text">{t.projects.bundle.targetTitle}</p>
      <div className="flex gap-2">
        <Button
          type="button"
          size="sm"
          variant={target.mode === 'new' ? 'primary' : 'secondary'}
          onClick={() => onTargetChange({ mode: 'new', name: defaultName, path: '' })}
        >
          {t.projects.bundle.targetNew}
        </Button>
        <Button
          type="button"
          size="sm"
          variant={target.mode === 'existing' ? 'primary' : 'secondary'}
          disabled={projects.length === 0}
          onClick={() => onTargetChange({ mode: 'existing', projectId: projects[0]?.id ?? '' })}
        >
          {t.projects.bundle.targetExisting}
        </Button>
      </div>

      {target.mode === 'new' ? (
        <>
          <Input
            label={t.projects.name}
            value={target.name}
            onChange={(e) => onTargetChange({ mode: 'new', name: e.target.value, path: target.path })}
          />
          {nameTaken && <p className="text-xs text-warning">{t.projects.bundle.nameTakenHint}</p>}
          <DirectoryPicker
            label={t.projects.path}
            value={target.path ?? ''}
            onChange={(path) => onTargetChange({ mode: 'new', name: target.name, path })}
          />
        </>
      ) : (
        <>
          <Select
            label={t.projects.bundle.targetProject}
            value={target.projectId}
            onChange={(e) => onTargetChange({ mode: 'existing', projectId: e.target.value })}
            options={projects.map((p) => ({ value: p.id, label: p.name }))}
          />
          <Select
            label={t.projects.bundle.conflictBehavior}
            value={onConflict}
            onChange={(e) => onConflictChange(e.target.value as ProjectBundleConflictBehavior)}
            options={[
              { value: 'skip', label: t.projects.bundle.conflictSkip },
              { value: 'rename', label: t.projects.bundle.conflictRename },
            ]}
          />
        </>
      )}
    </div>
  );
}
