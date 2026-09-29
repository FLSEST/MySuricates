import { Button, Input } from '../ui';
import { useT } from '../../i18n';
import type { BundlePathToken } from '../../types';

interface BundleImportPathFieldsProps {
  paths: BundlePathToken[];
  pathMappings: Record<string, string>;
  onChange: (token: string, value: string) => void;
  /** True once a unique old project directory could be guessed from the token list. */
  canApplyProjectPath: boolean;
  onApplyProjectPath: () => void;
}

/** One editable field per `@@path:N@@` token found in the bundle, pre-filled with the original path. */
export function BundleImportPathFields({
  paths, pathMappings, onChange, canApplyProjectPath, onApplyProjectPath,
}: BundleImportPathFieldsProps) {
  const t = useT();
  if (paths.length === 0) return null;

  return (
    <div className="space-y-3">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-sm font-medium text-text">{t.projects.bundle.pathsTitle}</p>
          <p className="text-xs text-text-tertiary">{t.projects.bundle.pathsHint}</p>
          <p className="text-xs text-text-tertiary mt-1">{t.projects.bundle.pathsProjectPathHint}</p>
        </div>
        {canApplyProjectPath && (
          <Button type="button" size="sm" variant="secondary" onClick={onApplyProjectPath}>
            {t.projects.bundle.applyProjectPath}
          </Button>
        )}
      </div>
      <div className="space-y-3">
        {paths.map((p) => (
          <div key={p.token} className="space-y-1">
            <div className="flex items-center justify-between text-xs text-text-secondary">
              <span className="font-mono">{p.token}</span>
              <span>{t.projects.bundle.occurrences.replace('{count}', String(p.occurrences))}</span>
            </div>
            <Input
              aria-label={p.token}
              value={pathMappings[p.token] ?? ''}
              onChange={(e) => onChange(p.token, e.target.value)}
              placeholder={p.original}
            />
            <p className="text-xs text-text-tertiary truncate">{p.hint}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
