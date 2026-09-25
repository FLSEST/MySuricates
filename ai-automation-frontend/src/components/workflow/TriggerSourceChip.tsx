import { useT } from '../../i18n';
import type { TriggerSource } from '../../types';

/**
 * What started a run, as a chip ("Started by schedule") — in the run header (`RunProvenance`) and
 * on audit reports. Renders nothing while the source is unknown (older runs, provenance off).
 */
export function TriggerSourceChip({ source }: { source: TriggerSource | null | undefined }) {
  const t = useT();
  if (!source) return null;
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-surface-raised" data-testid="trigger-source">
      <span className="text-text-secondary">{t.workflows.triggeredBy}</span>
      <span className="text-text font-medium">{t.workflows.triggerSources[source] ?? source}</span>
    </span>
  );
}
