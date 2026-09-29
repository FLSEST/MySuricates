import { Link } from 'react-router-dom';
import { ExternalLink } from 'lucide-react';
import { useT, type TranslationKeys } from '../../i18n';
import type { ProjectBundleImportResult, ProjectBundleItemKind } from '../../types';

interface BundleImportResultProps {
  result: ProjectBundleImportResult;
  onOpenProject: () => void;
}

const KIND_ORDER: ProjectBundleItemKind[] = ['workflow', 'chain', 'schedule'];

function kindLabel(kind: ProjectBundleItemKind, t: TranslationKeys): string {
  return kind === 'workflow' ? t.nav.workflows : kind === 'chain' ? t.nav.chains : t.nav.schedules;
}

/** Groups a skipped/renamed list by `kind`, in a fixed workflow/chain/schedule order. */
function groupByKind<T extends { kind: ProjectBundleItemKind }>(items: T[]): Array<[ProjectBundleItemKind, T[]]> {
  return KIND_ORDER
    .map((kind): [ProjectBundleItemKind, T[]] => [kind, items.filter((item) => item.kind === kind)])
    .filter(([, group]) => group.length > 0);
}

/** Success screen: link to the project plus what was created, skipped, renamed or warned about. */
export function BundleImportResult({ result, onOpenProject }: BundleImportResultProps) {
  const t = useT();
  const skippedByKind = groupByKind(result.skipped);
  const renamedByKind = groupByKind(result.renamed);

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

      {skippedByKind.length > 0 && (
        <div className="space-y-1.5">
          <p className="text-xs font-medium text-text-secondary">{t.projects.bundle.resultSkipped}</p>
          {skippedByKind.map(([kind, items]) => (
            <div key={kind}>
              <p className="text-xs text-text-tertiary font-medium">{kindLabel(kind, t)}</p>
              <ul className="text-xs text-text-tertiary space-y-0.5 list-disc list-inside">
                {items.map((item, i) => <li key={i}>{item.key} — {item.reason}</li>)}
              </ul>
            </div>
          ))}
        </div>
      )}

      {renamedByKind.length > 0 && (
        <div className="space-y-1.5">
          <p className="text-xs font-medium text-text-secondary">{t.projects.bundle.resultRenamed}</p>
          {renamedByKind.map(([kind, items]) => (
            <div key={kind}>
              <p className="text-xs text-text-tertiary font-medium">{kindLabel(kind, t)}</p>
              <ul className="text-xs text-text-tertiary space-y-0.5 list-disc list-inside">
                {items.map((item, i) => <li key={i}>{item.from} &rarr; {item.to}</li>)}
              </ul>
            </div>
          ))}
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
