import { useT } from '../../i18n';
import type { ChainRun } from '../../types';

/** Finished entries of a chain run out of all, red with the number of failed ones. */
export function ChainProgress({ entryRuns }: { entryRuns: ChainRun['entryRuns'] }) {
  const t = useT();
  if (!entryRuns?.length) return <span className="text-text-secondary">-</span>;

  const total = entryRuns.length;
  const completed = entryRuns.filter((e) => e.status === 'COMPLETED').length;
  const failed = entryRuns.filter((e) => e.status === 'FAILED').length;

  const color = failed > 0
    ? 'text-danger'
    : completed === total
      ? 'text-success'
      : 'text-text-secondary';

  return (
    <span className={`font-mono text-xs font-medium ${color}`}>
      {completed}/{total}
      {failed > 0 && (
        <span className="text-danger ml-1">{t.runs.chainFailedCount.replace('{count}', () => String(failed))}</span>
      )}
    </span>
  );
}
