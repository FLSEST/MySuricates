import { Input, Select, DirectoryPicker } from '../ui';
import { useT } from '../../i18n';
import type { ProjectType } from '../../types';
import { PROJECT_TYPES, type ProjectFormValues } from './projectForm';

/** The fields of a project, shared by creating and editing one. */
export function ProjectFormFields<T extends ProjectFormValues>({
  value,
  onChange,
  placeholders = false,
}: {
  value: T;
  onChange: (value: T) => void;
  /** Example values for an empty form; editing an existing project shows none. */
  placeholders?: boolean;
}) {
  const t = useT();
  return (
    <div className="space-y-4">
      <Input
        label={t.projects.name}
        value={value.name}
        onChange={(e) => onChange({ ...value, name: e.target.value })}
        placeholder={placeholders ? 'My Project' : undefined}
      />
      <Input
        label={t.projects.subtitleField}
        value={value.subtitle ?? ''}
        onChange={(e) => onChange({ ...value, subtitle: e.target.value })}
        placeholder={t.projects.subtitlePlaceholder}
      />
      <div>
        <label className="block text-sm font-medium text-text-secondary mb-1.5">{t.projects.descriptionField}</label>
        <textarea
          className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text placeholder:text-text-tertiary focus:outline-hidden focus:ring-2 focus:ring-accent/50 focus:border-accent transition-colors resize-none"
          rows={3}
          value={value.description ?? ''}
          onChange={(e) => onChange({ ...value, description: e.target.value })}
          placeholder={t.projects.descriptionPlaceholder}
        />
      </div>
      <Select
        label={t.projects.type}
        value={value.type}
        onChange={(e) => onChange({ ...value, type: e.target.value as ProjectType })}
        options={PROJECT_TYPES.map((type) => ({
          value: type,
          label: t.projects.types[type],
        }))}
      />
      <DirectoryPicker
        label={t.projects.path}
        value={value.path}
        onChange={(path) => onChange({ ...value, path })}
        placeholder={placeholders ? '/home/user/projects/my-project' : undefined}
      />
    </div>
  );
}
