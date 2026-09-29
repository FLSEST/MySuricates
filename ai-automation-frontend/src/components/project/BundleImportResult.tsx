import { Link } from 'react-router-dom';
import { ExternalLink } from 'lucide-react';
import { useT } from '../../i18n';
import type { ProjectBundleImportResult } from '../../types';

interface BundleImportResultProps {
  result: ProjectBundleImportResult;
  onOpenProject: () => void;
}

/** Success screen: link to the project plus what was created, skipped, renamed or warned about. */
export function BundleImportResult({ result, onOpenProject }: BundleImportResultProps) {
  const t = useT();

  return (
    <div className="space-y-4">
      <p className="text-sm font-medium text-text">{t.projects.bundle.resultTitle}</p>

      <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-text-secondary">
        <span>{t.nav.workflows}: {result.created.workflows}</span>
        <span>{t.nav.chains}: {result.created.chains}</span>
        <span>{t.nav.schedules}: {result.created.schedules}</span>
      </div>

      <Link
        to={`/projects/${result.projectId}`}
        onClick={onOpenProject}
        className="inline-flex items-center gap-1.5 text-sm font-medium text-accent hover:text-accent-hover transition-colors"
      >
        <ExternalLink className="w-3.5 h-3.5" />
        {t.projects.bundle.openProject}
      </Link>

      {result.skipped.length > 0 && (
        <div className="space-y-1">
          <p className="text-xs font-medium text-text-secondary">{t.projects.bundle.resultSkipped}</p>
          <ul className="text-xs text-text-tertiary space-y-0.5 list-disc list-inside">
            {result.skipped.map((item, i) => (
              <li key={i}>{item.key}{item.reason ? ` — ${item.reason}` : ''}</li>
            ))}
          </ul>
        </div>
      )}

      {result.renamed.length > 0 && (
        <div className="space-y-1">
          <p className="text-xs font-medium text-text-secondary">{t.projects.bundle.resultRenamed}</p>
          <ul className="text-xs text-text-tertiary space-y-0.5 list-disc list-inside">
            {result.renamed.map((item, i) => (
              <li key={i}>{item.key}{item.newName ? ` → ${item.newName}` : ''}</li>
            ))}
          </ul>
        </div>
      )}

      {result.warnings.length > 0 && (
        <div className="space-y-1">
          <p className="text-xs font-medium text-warning">{t.projects.bundle.warningsSection}</p>
          <ul className="text-xs text-text-secondary space-y-0.5 list-disc list-inside">
            {result.warnings.map((w, i) => <li key={i}>{w}</li>)}
          </ul>
        </div>
      )}
    </div>
  );
}
