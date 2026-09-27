import { Link } from 'react-router-dom';
import { ChevronRight, Clock } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { SHORT_DATE } from './planDisplay';
import { MilestoneDiamond } from './MilestoneDiamond';
import { WorkPackageMark } from './WorkPackageMark';
import type { MilestoneAttentionItem } from '../../types';

/**
 * Ein Termin in „Braucht dich“ (TODO e192df18, c60bf914): ein Meilenstein (Raute), bald fällig oder
 * überfällig, oder ein Arbeitspaket (Balken), dessen Ende vorbei ist – mit seinem Ziel, dem Stand
 * und dem Weg zur Zielseite. Ein älteres Backend schickt kein `kind`; seine Einträge sind Pakete.
 */
export function MilestoneDueRow({ item }: { item: MilestoneAttentionItem }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const milestone = item.kind === 'MILESTONE';
  const words = milestone ? t.goals.milestones : t.goals.workPackages;
  const progress = milestone
    ? t.goals.milestones.packagesProgressLong.replace('{done}', String(item.done)).replace('{total}', String(item.total))
    : t.goals.workPackages.progress.replace('{done}', String(item.done)).replace('{total}', String(item.total));
  const overdue = item.state === 'OVERDUE';
  return (
    <Link to={`/goals/${encodeURIComponent(item.goalId)}`} data-testid="needs-you-milestone" data-kind={milestone ? 'milestone' : 'work-package'}
      className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-surface-raised">
      {milestone ? <MilestoneDiamond state={item.state} /> : <WorkPackageMark state={item.state} />}
      <span className="min-w-0 flex-1 truncate text-sm text-text" title={item.title}>{item.title}</span>
      <span className="hidden shrink-0 rounded-md bg-surface px-2 py-0.5 text-xs text-text-secondary sm:inline">{item.goalTitle}</span>
      <span className="shrink-0 text-xs tabular-nums text-text-tertiary" title={progress}>
        {item.done}/{item.total}
      </span>
      <span className={`flex shrink-0 items-center gap-1 text-xs ${overdue ? 'font-medium text-danger' : 'text-warning'}`}>
        <Clock className="h-3 w-3" aria-hidden="true" />
        {words.states[item.state]} · {formatDate(item.dueDate, locale, SHORT_DATE)}
      </span>
      <ChevronRight className="h-4 w-4 shrink-0 text-text-tertiary" aria-hidden="true" />
    </Link>
  );
}
