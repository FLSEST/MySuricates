import { Link } from 'react-router-dom';
import { Ban } from 'lucide-react';
import { useT, useI18nStore } from '../../i18n';
import { useSealStatuses } from '../../hooks/useSealStatus';
import { formatRelativeTime } from '../../utils/formatTime';

/**
 * Starts refused by the run admission (broken seal, unattended run of an unsealed workflow) create
 * no run, so the run list alone would never show them. This notice lists them above the list.
 */
export function RefusedStartsNotice() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data } = useSealStatuses();
  const refused = (data ?? []).filter((s) => s.refusedStarts > 0);
  if (refused.length === 0) return null;
  const total = refused.reduce((sum, s) => sum + s.refusedStarts, 0);
  return (
    <div role="status" data-testid="refused-starts-notice"
         className="flex items-start gap-3 p-3 rounded-lg border border-warning/40 bg-warning-subtle">
      <Ban className="w-5 h-5 shrink-0 mt-0.5 text-warning" />
      <div className="flex-1 min-w-0">
        <p className="text-sm font-semibold text-warning">
          {t.workflows.sealView.refusedNotice.replace('{count}', String(total))}
        </p>
        <p className="text-xs text-text-secondary mt-0.5">{t.workflows.sealView.refusedNoticeHint}</p>
        <ul className="mt-1.5 space-y-0.5">
          {refused.map((s) => (
            <li key={s.workflowId} className="text-sm text-text">
              <Link to={`/workflows/${s.workflowId}`} className="font-medium hover:underline">{s.workflowName}</Link>
              <span className="text-text-secondary">
                {' — '}{s.refusedStarts}× {s.lastRefusedAt && `(${formatRelativeTime(s.lastRefusedAt, locale)})`}
                {s.state === 'VIOLATED' && ` · ${t.workflows.sealView.violated}`}
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
