import { Link } from 'react-router-dom';
import { useT } from '../../i18n';
import type { TriggerSource } from '../../types';

/**
 * What started a run, as a chip ("Started by schedule") — in the run header (`RunProvenance`) and
 * on audit reports. Renders nothing while the source is unknown (older runs, provenance off).
 */
export function TriggerSourceChip({ source, to }: {
  source: TriggerSource | null | undefined;
  /** Where the trigger can be seen, e.g. the schedule (3a35ced1); the chip then is a link. */
  to?: string | null;
}) {
  const t = useT();
  if (!source) return null;
  const content = <>
    <span className="text-text-secondary">{t.workflows.triggeredBy}</span>
    <span className={`font-medium ${to ? 'text-accent' : 'text-text'}`}>{t.workflows.triggerSources[source] ?? source}</span>
  </>;
  const chip = 'inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-surface-raised';
  return to
    ? <Link to={to} className={`${chip} hover:underline`} data-testid="trigger-source">{content}</Link>
    : <span className={chip} data-testid="trigger-source">{content}</span>;
}

